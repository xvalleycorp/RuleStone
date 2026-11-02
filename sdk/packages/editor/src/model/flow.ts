// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0

// Decision flows (.flow.json), in the format the engine reads (docs/flows.md):
// the node types, their edges, editing operations, the checks (the engine's own when the tables
// are known, the editor's design checks otherwise), and a layout for the canvas.

import {
  InvalidDecisionError, createEngine, parseDecisionFlow, parseDecisionTable, parseParameterSet, parseReasonCodeCatalog,
  type DecisionTable, type ParameterSet, type ReasonCodeCatalog,
} from '@dxa/rulestone-engine'
import type { TypeSpec } from './table.js'

export const NODE_TYPES = ['table', 'switch', 'derive', 'model', 'split', 'end'] as const
export type NodeType = (typeof NODE_TYPES)[number]

export interface Branch {
  when?: string
  to: string
}

/**
 * A node as the engine reads it (docs/flows.md, "Nodes"). Fields the engine rejects, such as a
 * model node's shadow, kind or prompt (planned) or a derive node's former values, are kept when a
 * file is opened, so that the engine's checks can report them.
 */
export interface FlowNodeJson {
  type: NodeType | string
  description?: string
  /** table: the decision id. */
  decision?: string
  /** table: a version of the table to always use; absent for the version in force. */
  version?: string
  /** table: table input name to expression; inputs not listed receive the flow value of the same name. */
  facts?: Record<string, string>
  /** table, derive, model: the name the values are published under instead of the node id. */
  as?: string
  /** table, derive, model: the next node, or conditional branches with a final one without `when`. */
  next?: string | Branch[]
  /** switch: the first matching case wins; the final case has no `when`. */
  cases?: Branch[]
  /** derive, model: value name to type. */
  outputs?: Record<string, TypeSpec>
  /** derive: value name to expression; end: flow output name to expression. */
  set?: Record<string, string>
  /** model: invoke (the engine calls the model) or supplied (the caller passes its result). */
  mode?: 'invoke' | 'supplied' | string
  /** model: the model and its one exact version. */
  model?: { id?: string; version?: string }
  /** model, invoke mode: feature name to expression. */
  features?: Record<string, string>
  /** model: a condition over the outputs, by name. */
  valid?: string
  /** model: a decimal from 0 to 1, e.g. "0.70". */
  minConfidence?: string | number
  /** model, invoke mode: how long the model may take. */
  timeoutMs?: number
  /** model: feature name to reason code. */
  reasonCodes?: Record<string, string>
  /** model: how many reason codes the node gives at most, 1 to 10; 3 when absent. */
  maxReasonCodes?: number
  onFailure?: { to?: string; reasonCode?: string }
  /** split */
  key?: string
  salt?: string
  buckets?: { name: string; percent: number; to: string }[]
  [key: string]: unknown
}

/** The parameter set a flow's expressions read, as for a table. */
export interface FlowParamsJson {
  set: string
  version?: string
  values: Record<string, TypeSpec>
}

export interface FlowJson {
  id: string
  version: string
  description?: string
  effectiveFrom?: string
  effectiveTo?: string
  inputs: Record<string, TypeSpec>
  outputs: Record<string, TypeSpec>
  params?: FlowParamsJson
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
      return { type, outputs: { value: 'decimal' }, set: { value: '' } }
    case 'model':
      return {
        type, mode: 'supplied', model: { id: '', version: '' }, outputs: { score: 'decimal' },
        onFailure: { reasonCode: 'MODEL_UNAVAILABLE' },
      }
    case 'split':
      return { type, key: '', salt: '', buckets: [{ name: 'champion', percent: 90, to: '' }, { name: 'challenger', percent: 10, to: '' }] }
    case 'end':
      return { type, set: Object.fromEntries(Object.keys(flow.outputs).map((o) => [o, ''])) }
  }
}

/** A derive node's set, following its outputs: kept expressions, and an empty one for each new value. */
export function deriveSet(outputs: Record<string, TypeSpec>, set: Record<string, string> = {}): Record<string, string> {
  return Object.fromEntries(Object.keys(outputs).map((name) => [name, set[name] ?? '']))
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
  | 'tableNoDecision' | 'switchNoDefault' | 'branchNoDefault' | 'emptyCondition' | 'endMissingOutput' | 'endUnknownOutput'
  /** A problem the engine reports, in its own words, in `params.message`. */
  | 'engine'

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

/** The definitions a flow is checked and run against: the tables it uses, parameter sets and a reason-code catalog. */
export interface FlowDefinitions {
  /** Every version of every table the flow uses, as .stone.json text, parsed JSON or a parsed table. */
  readonly tables: readonly (string | object)[]
  /** Parameter sets the flow and its tables read, as .params.json text or parsed JSON. */
  readonly paramSets?: readonly (string | object)[]
  /** A reason-code catalog, as reason-codes.json text, parsed JSON or a parsed catalog. */
  readonly reasonCodes?: string | object
}

/** The node an engine problem is about: `$.nodes.<id>…` when reading, `<flow>@<version> nodes.<id>…` when checking. */
function problemNode(flow: FlowJson, problem: string): string | undefined {
  const m = /^(?:\$\.|\S+ )nodes\.([^.[:\s]+)/.exec(problem)
  return m && m[1]! in flow.nodes ? m[1]! : undefined
}

/** Engine problems as flow problems, without the location prefix that names the flow. */
export function engineFlowProblems(flow: FlowJson, problems: readonly string[]): FlowProblem[] {
  const ref = `${flow.id}@${flow.version} `
  return problems.map((p) => {
    const node = problemNode(flow, p)
    const message = p.startsWith(ref) ? p.slice(ref.length) : p
    return node === undefined
      ? { code: 'engine' as const, severity: 'error' as const, params: { message } }
      : { code: 'engine' as const, severity: 'error' as const, node, params: { message } }
  })
}

/** The problems of the engine's strict .flow.json reader, or none if it reads the flow. */
export function readerProblems(flow: FlowJson): FlowProblem[] {
  try {
    parseDecisionFlow(JSON.stringify(flow))
    return []
  } catch (e) {
    if (e instanceof InvalidDecisionError) return engineFlowProblems(flow, e.problems)
    throw e
  }
}

/**
 * Every problem the engine reports for the flow, as when an engine is created with it and the
 * definitions: the strict reader's, then the load-time checks of the graph, names, types and the
 * values each node may read. Model nodes are checked as for tests (suppliedModelsOnly), so that
 * invoke mode needs no invoker. Problems of the tables themselves are included too.
 */
export async function checkFlowWithEngine(flow: FlowJson, definitions: FlowDefinitions): Promise<FlowProblem[]> {
  const read = readerProblems(flow)
  if (read.length > 0) return read
  const problems: string[] = []
  const tables = []
  for (const t of definitions.tables) {
    try {
      tables.push(typeof t === 'object' && (t as { rules?: unknown }).rules instanceof Array && (t as { inputs?: unknown }).inputs instanceof Map
        ? (t as DecisionTable)
        : parseDecisionTable(typeof t === 'string' ? t : JSON.stringify(t)))
    } catch (e) {
      if (!(e instanceof InvalidDecisionError)) throw e
      problems.push(...e.problems)
    }
  }
  let paramSets: ParameterSet[] = []
  let reasonCodeCatalog: ReasonCodeCatalog | undefined
  try {
    paramSets = (definitions.paramSets ?? []).map((p) => parseParameterSet(typeof p === 'string' ? p : JSON.stringify(p)))
    const catalog = definitions.reasonCodes
    if (catalog !== undefined) {
      reasonCodeCatalog = typeof catalog === 'object' && (catalog as { codes?: unknown }).codes instanceof Map
        ? (catalog as ReasonCodeCatalog)
        : parseReasonCodeCatalog(typeof catalog === 'string' ? catalog : JSON.stringify(catalog))
    }
  } catch (e) {
    if (!(e instanceof InvalidDecisionError)) throw e
    problems.push(...e.problems)
  }
  if (problems.length === 0) {
    try {
      await createEngine({
        tables,
        flows: [parseDecisionFlow(JSON.stringify(flow))],
        paramSets,
        suppliedModelsOnly: true,
        ...(reasonCodeCatalog ? { reasonCodeCatalog } : {}),
      })
    } catch (e) {
      if (!(e instanceof InvalidDecisionError)) throw e
      problems.push(...e.problems)
    }
  }
  return engineFlowProblems(flow, problems)
}

/**
 * The checks the editor can make without the tables a flow uses: the engine's strict reader
 * (unknown, missing and misplaced fields, branch structure), and the graph's shape (cycles,
 * unreachable nodes, paths that never reach an end), model nodes without onFailure, split
 * percentages, unknown table references and end nodes that do not set every output. With the
 * tables, checkFlowWithEngine reports what the engine itself reports.
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
      case 'end': {
        const set = node.set ?? {}
        for (const output of Object.keys(flow.outputs)) if (!(output in set) || set[output] === '') add('endMissingOutput', id, { output })
        for (const output of Object.keys(set)) if (!(output in flow.outputs)) add('endUnknownOutput', id, { output })
        break
      }
    }
    for (const branch of [...(Array.isArray(node.next) ? node.next : []), ...(node.cases ?? [])]) {
      if (branch.when !== undefined && branch.when.trim() === '') add('emptyCondition', id, { target: branch.to })
    }
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
  // The strict reader: unknown, missing and misplaced fields, and branches without a final default.
  problems.push(...readerProblems(flow))
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

export const NODE_WIDTH = 200
export const NODE_HEIGHT = 68

/** Spacing of the automatic layout: around the graph, between columns, rows and bands. */
export const LAYOUT_SPACING = { margin: 40, column: 56, row: 36, band: 72 } as const

/** Zoom limits for fitting a flow: below the minimum, node text is too small to read. */
export const FIT_ZOOM = { min: 0.8, max: 1.25 } as const

export interface LayoutOptions {
  /**
   * Columns per band. Columns are the distance from the start node; a flow longer than this
   * wraps into bands below each other, like lines of text. Default: no wrapping.
   */
  readonly maxColumns?: number
}

/**
 * Positions in columns by distance from the start, for flows without a saved layout. Each
 * column is centred in its band; positions in `existing` are kept.
 */
export function autoLayout(flow: FlowJson, existing: FlowLayout = {}, options: LayoutOptions = {}): FlowLayout {
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
  // Nodes the start cannot reach go in one more column; the BFS order keeps siblings together.
  const maxDepth = Math.max(0, ...depth.values())
  const columns: string[][] = []
  const order = [...[...depth.keys()], ...Object.keys(flow.nodes).filter((id) => !depth.has(id))]
  for (const id of order) {
    if (existing[id]) continue
    const column = depth.get(id) ?? maxDepth + 1
    ;(columns[column] ??= []).push(id)
  }
  const perBand = Math.max(1, Math.floor(options.maxColumns ?? Number.POSITIVE_INFINITY))
  const { margin, column: columnGap, row: rowGap, band: bandGap } = LAYOUT_SPACING
  const rowHeight = NODE_HEIGHT + rowGap
  const layout: FlowLayout = {}
  let bandTop = margin
  for (let first = 0; first < columns.length; first += perBand) {
    const band = columns.slice(first, first + perBand)
    const rows = Math.max(1, ...band.map((c) => c?.length ?? 0))
    band.forEach((ids, i) => {
      const offset = ((rows - (ids?.length ?? 0)) * rowHeight) / 2
      ids?.forEach((id, row) => {
        layout[id] = { x: margin + i * (NODE_WIDTH + columnGap), y: Math.round(bandTop + offset + row * rowHeight) }
      })
    })
    bandTop += rows * rowHeight - rowGap + bandGap
  }
  for (const id of Object.keys(flow.nodes)) if (existing[id]) layout[id] = existing[id]
  return layout
}

/** The rectangle around every node of a layout. */
export function layoutBounds(layout: FlowLayout): { x: number; y: number; width: number; height: number } | undefined {
  const points = Object.values(layout)
  if (points.length === 0) return undefined
  const x = Math.min(...points.map((p) => p.x))
  const y = Math.min(...points.map((p) => p.y))
  return {
    x,
    y,
    width: Math.max(...points.map((p) => p.x)) + NODE_WIDTH - x,
    height: Math.max(...points.map((p) => p.y)) + NODE_HEIGHT - y,
  }
}

/** The zoom at which a layout fits a viewport of the given size, with a margin. */
export function fitZoom(layout: FlowLayout, width: number, height: number): number {
  const b = layoutBounds(layout)
  if (!b) return 1
  const m = LAYOUT_SPACING.margin
  return Math.min(width / (b.width + 2 * m), height / (b.height + 2 * m))
}

/**
 * The automatic layout that best suits a viewport: the number of columns per band that shows
 * the whole flow at the largest zoom. When no layout fits at a readable zoom (FIT_ZOOM.min),
 * it takes the widest one that fits the viewport's width at that zoom, so the rest of the
 * flow is reached by panning in one direction only.
 */
export function layoutFor(flow: FlowJson, width: number, height: number): FlowLayout {
  const columns = Math.max(1, new Set(Object.values(autoLayout(flow)).map((p) => p.x)).size)
  let best: { layout: FlowLayout; zoom: number } | undefined
  let widest: FlowLayout | undefined
  for (let c = columns; c >= 1; c--) {
    const layout = autoLayout(flow, {}, { maxColumns: c })
    const zoom = Math.min(FIT_ZOOM.max, fitZoom(layout, width, height))
    if (!best || zoom > best.zoom + 1e-9) best = { layout, zoom }
    const b = layoutBounds(layout)
    if (!widest && b && (b.width + 2 * LAYOUT_SPACING.margin) * FIT_ZOOM.min <= width) widest = layout
  }
  if (!best) return {}
  return best.zoom >= FIT_ZOOM.min ? best.layout : widest ?? autoLayout(flow, {}, { maxColumns: 1 })
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
