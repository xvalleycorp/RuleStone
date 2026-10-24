// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0

// Decision flows (.flow.json), following the design proposal in docs/hybrid-ai.md section 3:
// the node types, their edges, editing operations, the client-side checks, and a layout for
// the canvas. The engine cannot run flows yet; these are for designing them.

import type { TypeSpec } from './table.js'

export const NODE_TYPES = ['table', 'switch', 'derive', 'model', 'split', 'end', 'flow'] as const
export type NodeType = (typeof NODE_TYPES)[number]

export interface Branch {
  when?: string
  to: string
}

export interface FlowNodeJson {
  type: NodeType | string
  /** table: the decision id. */
  decision?: string
  /** flow: the flow id, and optionally its version. */
  flow?: string
  version?: string
  /** table, flow: explicit facts, name to expression. */
  facts?: Record<string, string>
  /** table, model, flow: the shared name the outputs are published under. */
  as?: string
  /** table, derive, model, flow: the next node, or conditional branches with a final one without `when`. */
  next?: string | Branch[]
  /** switch: the first matching case wins; the final case has no `when`. */
  cases?: Branch[]
  /** derive: name to expression, computed once. */
  values?: Record<string, string>
  /** model */
  mode?: 'invoke' | 'supplied' | string
  kind?: string
  model?: { id?: string; version?: string }
  features?: Record<string, string>
  outputs?: Record<string, TypeSpec>
  valid?: string
  minConfidence?: string
  timeoutMs?: number
  reasonCodes?: Record<string, string>
  onFailure?: { to?: string; reasonCode?: string }
  shadow?: unknown[]
  /** split */
  key?: string
  salt?: string
  buckets?: { name: string; percent: number; to: string }[]
  /** end: output name to expression. */
  set?: Record<string, string>
  [key: string]: unknown
}

export interface FlowJson {
  id: string
  version: string
  description?: string
  effectiveFrom?: string
  effectiveTo?: string
  inputs: Record<string, TypeSpec>
  outputs: Record<string, TypeSpec>
  start: string
  nodes: Record<string, FlowNodeJson>
  [key: string]: unknown
}

/** Canvas positions, by node id. Kept apart from the flow file, which has no layout. */
export type FlowLayout = Record<string, { x: number; y: number }>

export type EdgeKind = 'next' | 'branch' | 'case' | 'bucket' | 'failure'

export interface Edge {
  readonly from: string
  readonly to: string
  readonly kind: EdgeKind
  /** Index of the branch, case or bucket. */
  readonly index: number
  /** A short label: the condition, the bucket and its percentage, or 'onFailure'. */
  readonly label: string
}

export function emptyFlow(id = 'new_flow'): FlowJson {
  return {
    id,
    version: '1',
    inputs: {},
    outputs: { decision: 'string' },
    start: 'start',
    nodes: { start: { type: 'end', set: { decision: "'APPROVE'" } } },
  }
}

/** The edges leaving a node, in the order of its fields. */
export function edgesOf(id: string, node: FlowNodeJson): Edge[] {
  const edges: Edge[] = []
  if (typeof node.next === 'string') edges.push({ from: id, to: node.next, kind: 'next', index: 0, label: '' })
  else if (Array.isArray(node.next)) {
    node.next.forEach((b, i) => edges.push({ from: id, to: b.to, kind: 'branch', index: i, label: b.when ?? 'else' }))
  }
  node.cases?.forEach((b, i) => edges.push({ from: id, to: b.to, kind: 'case', index: i, label: b.when ?? 'else' }))
  node.buckets?.forEach((b, i) => edges.push({ from: id, to: b.to, kind: 'bucket', index: i, label: `${b.name} ${b.percent}%` }))
  if (node.onFailure?.to !== undefined) edges.push({ from: id, to: node.onFailure.to, kind: 'failure', index: 0, label: 'onFailure' })
  return edges
}

export function allEdges(flow: FlowJson): Edge[] {
  return Object.entries(flow.nodes).flatMap(([id, node]) => edgesOf(id, node))
}

/** A node id not used in the flow, from a type: table1, switch2, … */
export function newNodeId(flow: FlowJson, type: string): string {
  for (let n = 1; ; n++) if (!(`${type}${n}` in flow.nodes)) return `${type}${n}`
}

/** A new node of a type with the fields its type needs. */
export function newNode(type: NodeType, flow: FlowJson): FlowNodeJson {
  switch (type) {
    case 'table':
      return { type, decision: '' }
    case 'switch':
      return { type, cases: [] }
    case 'derive':
      return { type, values: {} }
    case 'model':
      return { type, mode: 'invoke', model: { id: '', version: '' }, features: {}, outputs: { score: 'decimal' }, onFailure: { reasonCode: 'MODEL_UNAVAILABLE' } }
    case 'split':
      return { type, key: '', salt: '', buckets: [{ name: 'champion', percent: 90, to: '' }, { name: 'challenger', percent: 10, to: '' }] }
    case 'end':
      return { type, set: Object.fromEntries(Object.keys(flow.outputs).map((o) => [o, ''])) }
    case 'flow':
      return { type, flow: '' }
  }
}

export function addNode(flow: FlowJson, type: NodeType, id = newNodeId(flow, type)): { flow: FlowJson; id: string } {
  const nodes = { ...flow.nodes, [id]: newNode(type, flow) }
  return { flow: { ...flow, nodes, start: flow.start === '' ? id : flow.start }, id }
}

function retarget(node: FlowNodeJson, change: (to: string) => string | undefined): FlowNodeJson {
  const out: FlowNodeJson = { ...node }
  if (typeof out.next === 'string') {
    const to = change(out.next)
    if (to === undefined) delete out.next
    else out.next = to
  } else if (Array.isArray(out.next)) {
    out.next = out.next.map((b) => ({ ...b, to: change(b.to) ?? '' }))
  }
  if (out.cases) out.cases = out.cases.map((b) => ({ ...b, to: change(b.to) ?? '' }))
  if (out.buckets) out.buckets = out.buckets.map((b) => ({ ...b, to: change(b.to) ?? '' }))
  if (out.onFailure?.to !== undefined) {
    const to = change(out.onFailure.to)
    out.onFailure = to === undefined ? omit(out.onFailure, 'to') : { ...out.onFailure, to }
  }
  return out
}

function omit<T extends object, K extends keyof T>(value: T, key: K): Omit<T, K> {
  const { [key]: _removed, ...rest } = value
  return rest
}

/** Removes a node and the edges into it. */
export function removeNode(flow: FlowJson, id: string): FlowJson {
  const nodes: Record<string, FlowNodeJson> = {}
  for (const [other, node] of Object.entries(flow.nodes)) {
    if (other !== id) nodes[other] = retarget(node, (to) => (to === id ? undefined : to))
  }
  return { ...flow, nodes, start: flow.start === id ? '' : flow.start }
}

/** Renames a node and every edge into it, keeping its position in the file. */
export function renameNode(flow: FlowJson, id: string, newId: string): FlowJson {
  if (!(id in flow.nodes) || newId in flow.nodes || newId === '') return flow
  const nodes: Record<string, FlowNodeJson> = {}
  for (const [other, node] of Object.entries(flow.nodes)) {
    nodes[other === id ? newId : other] = retarget(node, (to) => (to === id ? newId : to))
  }
  return { ...flow, nodes, start: flow.start === id ? newId : flow.start }
}

export function updateNode(flow: FlowJson, id: string, node: FlowNodeJson): FlowJson {
  return { ...flow, nodes: { ...flow.nodes, [id]: node } }
}

/**
 * Connects two nodes the way the source's type connects: a table, derive, model or flow node
 * gets `next` (or, if it has one, a new conditional branch); a switch gets a case; a split
 * gets a bucket; `failure` sets a model's onFailure.
 */
export function connect(flow: FlowJson, from: string, to: string, failure = false): FlowJson {
  const node = flow.nodes[from]
  if (!node || !(to in flow.nodes) || node.type === 'end') return flow
  const next: FlowNodeJson = { ...node }
  if (failure) next.onFailure = { ...(node.onFailure ?? {}), to }
  else if (node.type === 'switch') {
    const cases = node.cases ?? []
    const last = cases.at(-1)
    next.cases = last && last.when === undefined ? [...cases.slice(0, -1), { when: '', to }, last] : [...cases, { to }]
  }
  else if (node.type === 'split') next.buckets = [...(node.buckets ?? []), { name: `bucket${(node.buckets?.length ?? 0) + 1}`, percent: 0, to }]
  else if (node.next === undefined || node.next === '') next.next = to
  // A second edge makes conditional branches: the new one gets a condition to fill in, and the
  // existing edge stays the final branch, taken when no condition holds.
  else if (typeof node.next === 'string') next.next = [{ when: '', to }, { to: node.next }]
  else next.next = [...node.next.slice(0, -1), { when: '', to }, ...node.next.slice(-1)]
  return updateNode(flow, from, next)
}

/** Removes one edge. */
export function disconnect(flow: FlowJson, edge: Edge): FlowJson {
  const node = flow.nodes[edge.from]
  if (!node) return flow
  const next: FlowNodeJson = { ...node }
  switch (edge.kind) {
    case 'next':
      delete next.next
      break
    case 'branch': {
      const branches = (node.next as Branch[]).filter((_, i) => i !== edge.index)
      if (branches.length === 0) delete next.next
      else if (branches.length === 1 && branches[0]!.when === undefined) next.next = branches[0]!.to
      else next.next = branches
      break
    }
    case 'case':
      next.cases = (node.cases ?? []).filter((_, i) => i !== edge.index)
      break
    case 'bucket':
      next.buckets = (node.buckets ?? []).filter((_, i) => i !== edge.index)
      break
    case 'failure':
      next.onFailure = omit(node.onFailure ?? {}, 'to')
      break
  }
  return updateNode(flow, edge.from, next)
}

// --- Checks ------------------------------------------------------------------------------------

export type FlowProblemCode =
  | 'noNodes' | 'noStart' | 'unknownStart' | 'unknownType' | 'unknownTarget' | 'emptyTarget' | 'cycle' | 'unreachable'
  | 'deadEnd' | 'noEndReachable' | 'modelNoFailure' | 'modelUnpinned' | 'splitPercent' | 'unknownTable'
  | 'tableNoDecision' | 'switchNoDefault' | 'branchNoDefault' | 'emptyCondition' | 'endMissingOutput' | 'endUnknownOutput' | 'flowNoId'

export interface FlowProblem {
  readonly code: FlowProblemCode
  readonly severity: 'error' | 'warning'
  /** The node it is about, if any. */
  readonly node?: string
  /** Values for the message, such as the target id, the total percentage, the output name. */
  readonly params: Readonly<Record<string, string | number>>
}

export interface CheckOptions {
  /** Decision ids that table nodes may refer to; when given, other ids are reported. */
  readonly tables?: readonly string[]
}

/**
 * The checks the editor can make without the engine: the graph's shape (cycles, unreachable
 * nodes, paths that never reach an end), model nodes without onFailure, split percentages,
 * unknown table references and end nodes that do not set every output. The engine's load-time
 * checks of names and types (docs/hybrid-ai.md 3.7) are not made here.
 */
export function checkFlow(flow: FlowJson, options: CheckOptions = {}): FlowProblem[] {
  const problems: FlowProblem[] = []
  const add = (code: FlowProblemCode, node?: string, params: Record<string, string | number> = {}, severity: 'error' | 'warning' = 'error') =>
    problems.push(node === undefined ? { code, severity, params } : { code, severity, node, params })
  const ids = Object.keys(flow.nodes)
  if (ids.length === 0) {
    add('noNodes')
    return problems
  }
  if (!flow.start) add('noStart')
  else if (!(flow.start in flow.nodes)) add('unknownStart', undefined, { target: flow.start })

  const successors = new Map<string, string[]>()
  for (const [id, node] of Object.entries(flow.nodes)) {
    if (!(NODE_TYPES as readonly string[]).includes(node.type)) add('unknownType', id, { type: String(node.type) })
    const edges = edgesOf(id, node)
    successors.set(id, edges.filter((e) => e.to in flow.nodes).map((e) => e.to))
    for (const edge of edges) {
      if (edge.to === '') add('emptyTarget', id, { label: edge.label || edge.kind })
      else if (!(edge.to in flow.nodes)) add('unknownTarget', id, { target: edge.to })
    }
    switch (node.type) {
      case 'table':
        if (!node.decision) add('tableNoDecision', id)
        else if (options.tables && !options.tables.includes(node.decision)) add('unknownTable', id, { decision: node.decision })
        break
      case 'model':
        if (node.onFailure?.to === undefined || node.onFailure.to === '') add('modelNoFailure', id)
        if (!node.model?.id || !node.model.version) add('modelUnpinned', id)
        break
      case 'split': {
        const total = (node.buckets ?? []).reduce((sum, b) => sum + (Number.isFinite(b.percent) ? b.percent : 0), 0)
        if (total !== 100) add('splitPercent', id, { total })
        break
      }
      case 'switch': {
        const cases = node.cases ?? []
        if (cases.length === 0 || cases.at(-1)!.when !== undefined) add('switchNoDefault', id)
        break
      }
      case 'end': {
        const set = node.set ?? {}
        for (const output of Object.keys(flow.outputs)) if (!(output in set) || set[output] === '') add('endMissingOutput', id, { output })
        for (const output of Object.keys(set)) if (!(output in flow.outputs)) add('endUnknownOutput', id, { output })
        break
      }
      case 'flow':
        if (!node.flow) add('flowNoId', id)
        break
    }
    for (const branch of [...(Array.isArray(node.next) ? node.next : []), ...(node.cases ?? [])]) {
      if (branch.when !== undefined && branch.when.trim() === '') add('emptyCondition', id, { target: branch.to })
    }
    if (Array.isArray(node.next) && (node.next.length === 0 || node.next.at(-1)!.when !== undefined)) add('branchNoDefault', id)
    if (node.type !== 'end' && edges.filter((e) => e.kind !== 'failure').length === 0 && (NODE_TYPES as readonly string[]).includes(node.type)) {
      add('deadEnd', id)
    }
  }

  // Cycles: every node in a strongly connected component of more than one node, or with an edge to itself.
  for (const id of nodesOnCycles(ids, successors)) add('cycle', id)

  // Unreachable nodes, from the start.
  if (flow.start in flow.nodes) {
    const reached = new Set<string>([flow.start])
    const queue = [flow.start]
    while (queue.length > 0) {
      for (const next of successors.get(queue.shift()!) ?? []) {
        if (!reached.has(next)) {
          reached.add(next)
          queue.push(next)
        }
      }
    }
    for (const id of ids) if (!reached.has(id)) add('unreachable', id, {}, 'warning')
  }

  // Nodes from which no end node can be reached.
  const canEnd = new Set(ids.filter((id) => flow.nodes[id]!.type === 'end'))
  for (let changed = true; changed; ) {
    changed = false
    for (const id of ids) {
      if (!canEnd.has(id) && (successors.get(id) ?? []).some((n) => canEnd.has(n))) {
        canEnd.add(id)
        changed = true
      }
    }
  }
  for (const id of ids) {
    if (!canEnd.has(id) && !problems.some((p) => p.node === id && p.code === 'deadEnd')) add('noEndReachable', id)
  }
  return problems
}

/** Tarjan's algorithm: the nodes that lie on a cycle, in the order of `ids`. */
function nodesOnCycles(ids: readonly string[], successors: ReadonlyMap<string, readonly string[]>): string[] {
  const index = new Map<string, number>()
  const low = new Map<string, number>()
  const stack: string[] = []
  const onStack = new Set<string>()
  const cyclic = new Set<string>()
  let counter = 0
  const connect = (v: string) => {
    index.set(v, counter)
    low.set(v, counter++)
    stack.push(v)
    onStack.add(v)
    for (const w of successors.get(v) ?? []) {
      if (!index.has(w)) {
        connect(w)
        low.set(v, Math.min(low.get(v)!, low.get(w)!))
      } else if (onStack.has(w)) {
        low.set(v, Math.min(low.get(v)!, index.get(w)!))
      }
    }
    if (low.get(v) === index.get(v)) {
      const component: string[] = []
      let w: string
      do {
        w = stack.pop()!
        onStack.delete(w)
        component.push(w)
      } while (w !== v)
      if (component.length > 1 || (successors.get(v) ?? []).includes(v)) component.forEach((n) => cyclic.add(n))
    }
  }
  for (const id of ids) if (!index.has(id)) connect(id)
  return ids.filter((id) => cyclic.has(id))
}

// --- Layout ------------------------------------------------------------------------------------

export const NODE_WIDTH = 180
export const NODE_HEIGHT = 64

/** Positions in columns by distance from the start, for flows without a saved layout. */
export function autoLayout(flow: FlowJson, existing: FlowLayout = {}): FlowLayout {
  const layout: FlowLayout = {}
  const depth = new Map<string, number>()
  const start = flow.start in flow.nodes ? flow.start : Object.keys(flow.nodes)[0]
  if (start !== undefined) {
    depth.set(start, 0)
    const queue = [start]
    while (queue.length > 0) {
      const id = queue.shift()!
      for (const edge of edgesOf(id, flow.nodes[id]!)) {
        if (edge.to in flow.nodes && !depth.has(edge.to)) {
          depth.set(edge.to, depth.get(id)! + 1)
          queue.push(edge.to)
        }
      }
    }
  }
  const maxDepth = Math.max(0, ...depth.values())
  const rows = new Map<number, number>()
  for (const id of Object.keys(flow.nodes)) {
    if (existing[id]) {
      layout[id] = existing[id]
      continue
    }
    const column = depth.get(id) ?? maxDepth + 1
    const row = rows.get(column) ?? 0
    rows.set(column, row + 1)
    layout[id] = { x: 40 + column * (NODE_WIDTH + 70), y: 40 + row * (NODE_HEIGHT + 40) }
  }
  return layout
}

/** The flow as the pretty-printed JSON text of a .flow.json file. */
export function flowText(flow: FlowJson): string {
  return JSON.stringify(flow, null, 2) + '\n'
}

/** Reads a flow from JSON text; throws SyntaxError or TypeError for something that is not a flow. */
export function parseFlow(text: string): FlowJson {
  const value = JSON.parse(text) as unknown
  if (value === null || typeof value !== 'object' || Array.isArray(value)) throw new TypeError('a flow is a JSON object')
  const flow = value as Partial<FlowJson>
  if (flow.nodes === null || typeof flow.nodes !== 'object' || Array.isArray(flow.nodes)) throw new TypeError('a flow needs a "nodes" object')
  return {
    ...flow,
    id: typeof flow.id === 'string' ? flow.id : '',
    version: typeof flow.version === 'string' ? flow.version : '',
    inputs: flow.inputs && typeof flow.inputs === 'object' ? flow.inputs : {},
    outputs: flow.outputs && typeof flow.outputs === 'object' ? flow.outputs : {},
    start: typeof flow.start === 'string' ? flow.start : '',
    nodes: flow.nodes,
  } as FlowJson
}
