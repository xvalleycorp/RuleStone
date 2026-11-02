// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0

// Compiled decision flows, as CompiledFlow: the graph checks (cycles, reachability, the rule that a
// node reads only values every path to it sets), type-checked expressions, and evaluation of every
// node type, model nodes included, with the same records and messages as the Java engine.

import { Bindings, CompiledExpression, ExpressionEnvironment } from './cel/environment.js'
import { LocalDate } from './date.js'
import { Decimal, javaIsBlank, javaStrip } from './decimal.js'
import {
  DecisionEvaluationError, DecisionNotFoundError, DecisionTimeoutError, ExpressionError, RuleStoneError,
} from './errors.js'
import { readValueAt, resultTypes } from './facts.js'
import {
  BUSINESS_DATE, CompiledTable, ID, REASON_CODE, VERSION_FROM_DIGIT, type Executable,
  type MonotonicClock, type Progress, type Run, type RunContext,
} from './executable.js'
import { flowVersionOf, type Branch, type DecisionFlow, type FlowNode, type ModelFlowNode } from './flow.js'
import { javaUtf8, toJsonNode, type JsonNode } from './json.js'
import { isModelFailure, type ModelInvoker, type ModelResult } from './models.js'
import { Money } from './money.js'
import { PARAMS, checkParams, paramsType, selectParams, undeclaredParams, type ParamsBinding, type Registry } from './params.js'
import type { ModelStatus, ModelStep, ModelStepMode, PathStep, RankedAttribution, ReasonCode } from './record.js'
import type { DecisionTable } from './table.js'
import { containsStruct, isPrimitive, structOf, typeToString, typesEqual, type ValueType } from './types.js'
import type { Value } from './values.js'
import { selectVersion } from './versions.js'

/** The value that switch and split nodes publish. */
export const TAKEN = 'taken'

const NAME = /^[A-Za-z_][A-Za-z0-9_]*$/
const RESERVED = new Set([
  'true', 'false', 'null', 'in', 'as', 'break', 'const', 'continue', 'else', 'for', 'function', 'if', 'import', 'let',
  'loop', 'package', 'namespace', 'return', 'var', 'void', 'while',
])
/** Model versions must be exact: no range, wildcard or latest. */
const MODEL_VERSION = /^[A-Za-z0-9][A-Za-z0-9_.+-]*$/
const MAX_REASON_CODES = 10
const DEFAULT_MAX_REASON_CODES = 3

/** How the engine obtains model results, as CompiledFlow.Models. */
export interface Models {
  /** Model id to the invoker that calls it. */
  readonly invokers: ReadonlyMap<string, ModelInvoker>
  /** Whether every model node runs in supplied mode, for tests and replay. */
  readonly suppliedOnly: boolean
  /** The monotonic clock model latencies and timeouts are measured with. */
  readonly now: MonotonicClock
}

/** What a flow is compiled against. */
export interface FlowContext {
  /** Every compiled table version, by id. */
  readonly tables: ReadonlyMap<string, readonly CompiledTable[]>
  /** Every registered table version, by id, including those that failed to compile. */
  readonly registered: ReadonlyMap<string, readonly DecisionTable[]>
  readonly flowIds: ReadonlySet<string>
  readonly maxIterations: number
  readonly currencyScales: ReadonlyMap<string, number>
  readonly models: Models
  readonly registry: Registry
}

function flowRef(flow: DecisionFlow): string {
  return `${flow.id}@${flow.version}`
}

function asName(node: FlowNode): string | undefined {
  return node.type === 'table' || node.type === 'derive' || node.type === 'model' ? node.as : undefined
}

/** Whether a value of type `from` can be passed where `to` is declared: a struct may have more fields. */
export function assignable(from: ValueType, to: ValueType): boolean {
  if (!isPrimitive(from) && !isPrimitive(to)) {
    if (from.kind === 'list' && to.kind === 'list') return assignable(from.element, to.element)
    if (from.kind === 'struct' && to.kind === 'struct') {
      for (const [name, type] of to.fields) {
        const field = from.fields.get(name)
        if (field === undefined || !assignable(field, type)) return false
      }
      return true
    }
  }
  return typesEqual(from, to)
}

function describe(type: ValueType): string {
  if (!isPrimitive(type) && type.kind === 'struct') return `{${[...type.fields].map(([n, t]) => `${n}: ${describe(t)}`).join(', ')}}`
  return typeToString(type)
}

/** A cache key for a set of typed variables that ignores their order, as Java's Map equality does. */
function typeKey(type: ValueType): string {
  if (isPrimitive(type)) return type
  if (type.kind === 'list') return `[${typeKey(type.element)}]`
  return `{${[...type.fields].sort((a, b) => (a[0] < b[0] ? -1 : 1)).map(([n, t]) => `${n}:${typeKey(t)}`).join(',')}}`
}

function variablesKey(variables: ReadonlyMap<string, ValueType>): string {
  return [...variables].sort((a, b) => (a[0] < b[0] ? -1 : 1)).map(([n, t]) => `${n}:${typeKey(t)}`).join(';')
}

/** The iteration order of a java.util.HashMap with these keys, inserted in this order. */
export function javaHashMapOrder(keys: readonly string[]): string[] {
  let capacity = 16
  let size = 0
  for (let i = 0; i < keys.length; i++) {
    if (++size > capacity * 0.75) capacity *= 2
  }
  const index = (key: string): number => {
    let h = 0
    for (let i = 0; i < key.length; i++) h = (Math.imul(31, h) + key.charCodeAt(i)) | 0
    return (h ^ (h >>> 16)) & (capacity - 1)
  }
  return keys.map((key, i) => ({ key, i, bucket: index(key) })).sort((a, b) => a.bucket - b.bucket || a.i - b.i).map((e) => e.key)
}

function escapeRegExp(text: string): string {
  return text.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
}

// --- Compiled nodes ----------------------------------------------------------------------------

interface Route {
  readonly when: CompiledExpression | undefined
  readonly to: string
}

/** Where to go after a node; no environment when there is a single unconditional route. */
interface Router {
  readonly environment: ExpressionEnvironment | undefined
  readonly routes: readonly Route[]
}

interface TableNode {
  readonly kind: 'table'
  readonly decision: string
  readonly pinned: string | undefined
  readonly versions: readonly CompiledTable[]
  readonly environment: ExpressionEnvironment
  readonly mapped: ReadonlyMap<string, CompiledExpression>
  readonly byName: ReadonlyMap<string, string>
  readonly publish: string | undefined
  readonly next: Router
}

interface SwitchNode {
  readonly kind: 'switch'
  readonly environment: ExpressionEnvironment
  readonly cases: readonly Route[]
}

interface DeriveNode {
  readonly kind: 'derive'
  readonly environment: ExpressionEnvironment
  readonly set: ReadonlyMap<string, CompiledExpression>
  readonly publish: string
  readonly next: Router
}

interface ModelNode {
  readonly kind: 'model'
  readonly definition: ModelFlowNode
  readonly environment: ExpressionEnvironment
  readonly features: ReadonlyMap<string, CompiledExpression>
  /** The environment of the valid condition; absent when there is none. */
  readonly outputs: ExpressionEnvironment | undefined
  readonly valid: CompiledExpression | undefined
  /** Absent when the node runs in supplied mode. */
  readonly invoker: ModelInvoker | undefined
  readonly timeoutMs: number
  readonly publish: string
  readonly next: Router
}

/** A bucket that takes the keys whose point, from 0 to 100, is below `upper`. */
interface Slice {
  readonly name: string
  readonly upper: number
  readonly to: string
}

interface SplitNode {
  readonly kind: 'split'
  readonly environment: ExpressionEnvironment
  readonly key: CompiledExpression
  readonly salt: string
  readonly slices: readonly Slice[]
}

interface EndNode {
  readonly kind: 'end'
  readonly environment: ExpressionEnvironment
  readonly set: ReadonlyMap<string, CompiledExpression>
}

type Node = TableNode | SwitchNode | DeriveNode | ModelNode | SplitNode | EndNode

// --- Compilation -------------------------------------------------------------------------------

/**
 * Checks the graph and compiles every node of a flow, adding every problem to `problems`.
 * @returns the compiled flow, or undefined if any problem was found
 */
export async function compileFlow(flow: DecisionFlow, context: FlowContext, problems: string[]): Promise<CompiledFlow | undefined> {
  return new Compiler(flow, context, problems).compile()
}

class Compiler {
  private readonly at: string
  private readonly environments = new Map<string, ExpressionEnvironment>()
  /** Node id to the ids of the nodes it can go to next, in order, without duplicates. */
  private readonly successors = new Map<string, string[]>()
  /** Node id to the name it publishes its values under, and their type, in the order they were found. */
  private readonly publishedAs = new Map<string, string>()
  private readonly publishedType = new Map<string, ValueType>()
  /** Table node id to the versions of its decision it may evaluate. */
  private readonly versions = new Map<string, readonly CompiledTable[]>()
  /** Whether a table node's decision has a version that failed to compile, which is reported already. */
  private failedTable = false

  constructor(
    private readonly flow: DecisionFlow,
    private readonly context: FlowContext,
    private readonly problems: string[],
  ) {
    this.at = flowRef(flow) + ' '
  }

  async compile(): Promise<CompiledFlow | undefined> {
    const flow = this.flow
    const before = this.problems.length
    this.header()
    const inputTypes = new Map(flow.inputs)
    if (inputTypes.delete(BUSINESS_DATE)) {
      this.problem(
        `inputs.${BUSINESS_DATE}`,
        `reserved name; every flow receives ${BUSINESS_DATE} implicitly (DecisionRef.asOf, else the engine clock)`,
      )
    }
    inputTypes.set(BUSINESS_DATE, 'date')
    const inputEnvironment = this.environment(inputTypes, 'inputs')
    if (!inputEnvironment) return undefined
    // Every node may read the parameters, which are not inputs: they are not part of the facts hash.
    let params: ParamsBinding | undefined
    let startTypes: Map<string, ValueType> = inputTypes
    if (flow.params) {
      params = checkParams(this.at, flow.params, flow.inputs, this.context.registry, this.problems)
      startTypes = new Map(inputTypes)
      startTypes.delete(PARAMS)
      const type = paramsType(flow.params)
      if (type !== undefined) startTypes.set(PARAMS, type)
    }
    this.names()
    this.graph()
    this.structure()
    if (this.problems.length > before) return undefined // the typed checks below would only repeat these problems
    const order = this.topologicalOrder()
    if (!order) return undefined // cycles, reported
    this.unreachable(order)
    this.publications(order)
    // A table the flow uses failed to compile: that is reported, and its outputs are unknown.
    if (this.problems.length > before || this.failedTable) return undefined
    this.sharedNames()
    if (this.problems.length > before) return undefined
    const scopes = this.scopes(order, startTypes)
    const compiled = new Map<string, Node>()
    for (const id of order) {
      const node = this.node(id, flow.nodes.get(id)!, scopes.get(id)!)
      if (node) compiled.set(id, node)
    }
    if (this.problems.length > before) return undefined
    return new CompiledFlow(flow, await flowVersionOf(flow), inputEnvironment, compiled, this.context.models, params)
  }

  private header(): void {
    const flow = this.flow
    if (!ID.test(flow.id)) this.problem('id', 'must match [A-Za-z][A-Za-z0-9_.-]*')
    if (!ID.test(flow.version) && !VERSION_FROM_DIGIT.test(flow.version)) this.problem('version', "must be letters, digits, '.', '_' or '-'")
    if (flow.effectiveFrom && flow.effectiveTo && flow.effectiveFrom.compareTo(flow.effectiveTo) >= 0) {
      this.problem('effectiveTo', 'must be after effectiveFrom')
    }
    if (this.context.registered.has(flow.id)) {
      this.problem('id', 'a decision table has the same id; tables and flows share one id namespace')
    }
    for (const [name, type] of flow.outputs) {
      if (containsStruct(type)) this.problem(`outputs.${name}`, 'outputs must be bool, int, string, decimal, money, date or lists of them')
    }
    if (flow.nodes.size === 0) this.problem('nodes', 'a flow needs at least one node')
  }

  /** Node ids and as names become CEL variables next to the inputs. */
  private names(): void {
    for (const [id, node] of this.flow.nodes) {
      const why = this.invalidName(id)
      if (why !== undefined) this.problem(`nodes.${id}`, `invalid node id: ${why}`)
      const as = asName(node)
      if (as !== undefined) {
        const invalid = this.invalidName(as)
        if (invalid !== undefined) this.problem(`nodes.${id}.as`, `invalid name: ${invalid}`)
        else if (this.flow.nodes.has(as)) this.problem(`nodes.${id}.as`, `'${as}' is a node id; use a name that is not`)
      }
    }
  }

  private invalidName(name: string): string | undefined {
    if (!NAME.test(name) || RESERVED.has(name)) {
      return "it names the node's values in expressions, so it must be an identifier (letters, digits and '_', not starting with a digit) and not a CEL keyword"
    }
    if (name === BUSINESS_DATE || this.flow.inputs.has(name)) return `'${name}' is already the name of an input`
    if (name === PARAMS) return `'${name}' is reserved for parameter sets, which expressions read as params.<name>`
    return undefined
  }

  /** Checks that every target exists and records the edges. */
  private graph(): void {
    const flow = this.flow
    if (flow.start !== '' && !flow.nodes.has(flow.start)) this.problem('start', `no node '${flow.start}'`)
    for (const [id, node] of flow.nodes) {
      const next: string[] = []
      for (const [path, target] of this.targets(id, node)) {
        if (flow.nodes.has(target)) {
          if (!next.includes(target)) next.push(target)
        } else if (target !== '') {
          this.problem(path, `no node '${target}'`)
        }
      }
      this.successors.set(id, next)
    }
  }

  /** Location of each target in the file to the target node id. */
  private targets(id: string, node: FlowNode): Map<string, string> {
    const targets = new Map<string, string>()
    const path = `nodes.${id}`
    const branchTargets = (branches: readonly Branch[], at: string): void => {
      if (branches.length === 1 && branches[0]!.when === undefined) {
        targets.set(at, branches[0]!.to)
        return
      }
      branches.forEach((b, i) => targets.set(`${at}[${i}].to`, b.to))
    }
    switch (node.type) {
      case 'table':
      case 'derive':
        branchTargets(node.next, `${path}.next`)
        break
      case 'model':
        branchTargets(node.next, `${path}.next`)
        targets.set(`${path}.onFailure.to`, node.onFailure.to)
        break
      case 'switch':
        branchTargets(node.cases, `${path}.cases`)
        break
      case 'split':
        node.buckets.forEach((b, i) => targets.set(`${path}.buckets[${i}].to`, b.to))
        break
      case 'end':
        break
    }
    return targets
  }

  /** Checks that need no types: branch structure, split percentages, and the names end and derive nodes set. */
  private structure(): void {
    for (const [id, node] of this.flow.nodes) {
      const path = `nodes.${id}`
      switch (node.type) {
        case 'table':
          this.branches(node.next, `${path}.next`)
          if (node.version !== undefined && javaIsBlank(node.version)) this.problem(`${path}.version`, 'must not be empty')
          break
        case 'derive':
          this.branches(node.next, `${path}.next`)
          if (node.outputs.size === 0) this.problem(`${path}.outputs`, 'a derive node needs at least one value')
          for (const [name, type] of node.outputs) {
            if (containsStruct(type)) this.problem(`${path}.outputs.${name}`, 'values must be bool, int, string, decimal, money, date or lists of them')
          }
          this.sameNames(node.set, node.outputs, path, 'value')
          break
        case 'model':
          this.branches(node.next, `${path}.next`)
          this.model(node, path)
          break
        case 'switch':
          this.branches(node.cases, `${path}.cases`)
          break
        case 'split':
          this.buckets(node.salt, node.buckets, path)
          break
        case 'end':
          this.sameNames(node.set, this.flow.outputs, path, 'output')
          break
      }
    }
  }

  private branches(branches: readonly Branch[], path: string): void {
    if (branches.length === 0) {
      this.problem(path, 'required: at least one alternative')
      return
    }
    branches.forEach((branch, i) => {
      const last = i === branches.length - 1
      const at = branches.length === 1 ? path : `${path}[${i}]`
      if (last && branch.when !== undefined) {
        this.problem(`${at}.when`, 'the last alternative must have no condition, so that one always applies')
      } else if (!last && branch.when === undefined) {
        this.problem(`${at}.when`, 'required: only the last alternative may have no condition')
      }
    })
  }

  private buckets(salt: string, buckets: readonly { name: string; percent: number }[], path: string): void {
    if (javaIsBlank(salt)) this.problem(`${path}.salt`, 'required non-empty string')
    if (buckets.length === 0) {
      this.problem(`${path}.buckets`, 'required: at least one bucket')
      return
    }
    const names = new Set<string>()
    let total = 0
    buckets.forEach((bucket, i) => {
      const at = `${path}.buckets[${i}]`
      if (javaIsBlank(bucket.name)) this.problem(`${at}.name`, 'required non-empty string')
      else if (names.has(bucket.name)) this.problem(`${at}.name`, `duplicate bucket name '${bucket.name}'`)
      else names.add(bucket.name)
      if (bucket.percent < 1 || bucket.percent > 100) this.problem(`${at}.percent`, 'must be a whole number from 1 to 100')
      total = (total + bucket.percent) | 0
    })
    if (total !== 100) this.problem(`${path}.buckets`, `percentages add up to ${total}, not 100`)
  }

  /** The contract of a model node, apart from its expressions. */
  private model(model: ModelFlowNode, path: string): void {
    const models = this.context.models
    if (!ID.test(model.modelId)) this.problem(`${path}.model.id`, 'must match [A-Za-z][A-Za-z0-9_.-]*')
    const version = model.modelVersion
    if (!MODEL_VERSION.test(version) || version.toLowerCase() === 'latest' || version.split('.').some((part) => part.toLowerCase() === 'x')) {
      this.problem(`${path}.model.version`, 'must pin one exact model version, e.g. "2.4.0"; ranges, wildcards and "latest" are not allowed')
    }
    if (model.outputs.size === 0) this.problem(`${path}.outputs`, 'a model node needs at least one output')
    for (const [name, type] of model.outputs) {
      if (!NAME.test(name) || RESERVED.has(name)) {
        this.problem(
          `${path}.outputs.${name}`,
          "invalid output name: the valid condition reads outputs by name, so it must be an identifier (letters, digits and '_', not starting with a digit) and not a CEL keyword",
        )
      }
      if (containsStruct(type)) this.problem(`${path}.outputs.${name}`, 'outputs must be bool, int, string, decimal, money, date or lists of them')
    }
    if (model.mode === 'invoke') {
      if (model.features.size === 0) {
        this.problem(`${path}.features`, 'required in invoke mode: the values sent to the model, as feature name to expression')
      }
      if (model.timeoutMs === undefined) {
        this.problem(`${path}.timeoutMs`, 'required in invoke mode: how long the model may take, in milliseconds')
      }
      if (!models.suppliedOnly && ID.test(model.modelId) && !models.invokers.has(model.modelId)) {
        this.problem(
          `${path}.model.id`,
          `no model invoker registered for model '${model.modelId}'; register one with modelInvokers: { '${model.modelId}': invoker }, or create the engine with suppliedModelsOnly for tests`,
        )
      }
    } else {
      if (model.features.size > 0) {
        this.problem(`${path}.features`, 'not allowed in supplied mode: the caller runs the model, so the engine sends it nothing')
      }
      if (model.timeoutMs !== undefined) this.problem(`${path}.timeoutMs`, 'not allowed in supplied mode: the engine calls no model')
    }
    if (model.timeoutMs !== undefined && model.timeoutMs < 1) this.problem(`${path}.timeoutMs`, 'must be at least 1')
    if (model.minConfidence !== undefined && (model.minConfidence.signum() < 0 || model.minConfidence.compareTo(Decimal.fromBigInt(1n)) > 0)) {
      this.problem(`${path}.minConfidence`, 'must be from 0 to 1')
    }
    for (const [feature, code] of model.reasonCodes) {
      if (!REASON_CODE.test(code)) this.problem(`${path}.reasonCodes.${feature}`, 'must be UPPER_SNAKE_CASE, e.g. HIGH_UTILISATION')
    }
    if (model.maxReasonCodes !== undefined && (model.maxReasonCodes < 1 || model.maxReasonCodes > MAX_REASON_CODES)) {
      this.problem(`${path}.maxReasonCodes`, `must be from 1 to ${MAX_REASON_CODES}`)
    }
    if (!REASON_CODE.test(model.onFailure.reasonCode)) {
      this.problem(`${path}.onFailure.reasonCode`, 'must be UPPER_SNAKE_CASE, e.g. MODEL_UNAVAILABLE')
    }
  }

  /** `set` must assign exactly the declared names. */
  private sameNames(set: ReadonlyMap<string, string>, declared: ReadonlyMap<string, ValueType>, path: string, what: string): void {
    for (const name of declared.keys()) if (!set.has(name)) this.problem(`${path}.set`, `does not set ${what} '${name}'`)
    for (const name of set.keys()) {
      if (!declared.has(name)) this.problem(`${path}.set.${name}`, `undeclared ${what} (declared: [${[...declared.keys()].join(', ')}])`)
    }
  }

  /** The nodes reachable from the start, each before every node it can go to; undefined if there is a cycle, which is reported. */
  private topologicalOrder(): string[] | undefined {
    // Depth-first search from every node, so that cycles among unreachable nodes are found too.
    const state = new Map<string, number>() // absent: new, 1: on the stack, 2: done
    const postOrder: string[] = []
    const cycles = new Set<string>()
    const roots = [this.flow.start, ...this.flow.nodes.keys()]
    for (const root of roots) {
      if (state.has(root)) continue
      const stack: string[] = [root] // top last
      const index: number[] = [0]
      state.set(root, 1)
      while (stack.length > 0) {
        const node = stack[stack.length - 1]!
        const i = index.pop()!
        const next = this.successors.get(node) ?? []
        if (i < next.length) {
          index.push(i + 1)
          const target = next[i]!
          const s = state.get(target)
          if (s === undefined) {
            state.set(target, 1)
            stack.push(target)
            index.push(0)
          } else if (s === 1) {
            const topFirst = [...stack].reverse()
            const cycle = topFirst.slice(0, topFirst.indexOf(target) + 1).reverse()
            cycle.push(target)
            const key = [...new Set(cycle)].sort().join('\u0000')
            if (!cycles.has(key)) {
              cycles.add(key)
              this.problem(`nodes.${target}`, `cycle ${cycle.join(' -> ')}; a flow must be acyclic`)
            }
          }
        } else {
          stack.pop()
          state.set(node, 2)
          postOrder.push(node)
        }
      }
    }
    if (cycles.size > 0) return undefined
    // Reverse post-order of the search from the start: every node before its successors.
    const reachable = this.reachableFrom(this.flow.start)
    const order: string[] = []
    for (let i = postOrder.length - 1; i >= 0; i--) if (reachable.has(postOrder[i]!)) order.push(postOrder[i]!)
    return order
  }

  private reachableFrom(start: string): Set<string> {
    const seen = new Set<string>()
    const todo = [start]
    while (todo.length > 0) {
      const node = todo.pop()!
      if (!seen.has(node)) {
        seen.add(node)
        for (const next of this.successors.get(node) ?? []) todo.push(next)
      }
    }
    return seen
  }

  private unreachable(order: readonly string[]): void {
    const reachable = new Set(order)
    for (const id of this.flow.nodes.keys()) {
      if (!reachable.has(id)) this.problem(`nodes.${id}`, `not reachable from start node '${this.flow.start}'`)
    }
  }

  /** What each node publishes, resolving the decision tables of table nodes. */
  private publications(order: readonly string[]): void {
    for (const id of order) {
      const node = this.flow.nodes.get(id)!
      const name = asName(node) ?? id
      const fields = new Map<string, ValueType>()
      if (node.type === 'table') {
        const candidates = this.tableVersions(id, node.decision, node.version)
        if (!candidates) continue
        this.versions.set(id, candidates)
        // Only outputs that every version declares alike can be read.
        for (const [output, type] of resultTypes(candidates[0]!.table)) {
          if (candidates.every((d) => {
            const other = resultTypes(d.table).get(output)
            return other !== undefined && typesEqual(type, other)
          })) {
            fields.set(output, type)
          }
        }
      } else if (node.type === 'derive' || node.type === 'model') {
        for (const [n, t] of node.outputs) fields.set(n, t)
      } else if (node.type === 'switch' || node.type === 'split') {
        fields.set(TAKEN, 'string')
      }
      if (fields.size > 0) {
        this.publishedAs.set(id, name)
        this.publishedType.set(id, structOf(fields))
      }
    }
  }

  /** The versions a table node may evaluate, or undefined after reporting why there are none. */
  private tableVersions(id: string, decision: string, version: string | undefined): readonly CompiledTable[] | undefined {
    const registered = this.context.registered.get(decision)
    if (!registered) {
      this.problem(
        `nodes.${id}.decision`,
        this.context.flowIds.has(decision)
          ? `'${decision}' is a decision flow; calling another flow is not supported yet`
          : `unknown decision '${decision}'`,
      )
      return undefined
    }
    const all = this.context.tables.get(decision) ?? []
    if (all.length < registered.length) {
      this.failedTable = true
      return undefined // a version failed to compile, which is already reported
    }
    if (version !== undefined) {
      const pinned = all.filter((d) => d.table.version === version)
      if (pinned.length === 0) {
        this.problem(`nodes.${id}.version`, `decision '${decision}' has no version '${version}'`)
        return undefined
      }
      return pinned
    }
    return all
  }

  /** Nodes that publish under one as name must publish the same values, on alternative paths. */
  private sharedNames(): void {
    const byName = new Map<string, string[]>()
    for (const [id, node] of this.flow.nodes) {
      if (asName(node) !== undefined && this.publishedAs.has(id)) {
        const name = this.publishedAs.get(id)!
        if (!byName.has(name)) byName.set(name, [])
        byName.get(name)!.push(id)
      }
    }
    for (const [name, ids] of byName) {
      for (let i = 0; i < ids.length; i++) {
        for (let j = i + 1; j < ids.length; j++) {
          const a = ids[i]!
          const b = ids[j]!
          const ta = this.publishedType.get(a)!
          const tb = this.publishedType.get(b)!
          if (!typesEqual(ta, tb)) {
            this.problem(`nodes.${b}.as`, `nodes '${a}' and '${b}' both publish '${name}' but with different values: ${describe(ta)} and ${describe(tb)}`)
          }
          if (this.reachableFrom(a).has(b) || this.reachableFrom(b).has(a)) {
            this.problem(
              `nodes.${b}.as`,
              `nodes '${a}' and '${b}' both publish '${name}' and can both run on one path; only nodes on alternative branches may share a name`,
            )
          }
        }
      }
    }
  }

  /** The names each node may read: those that every path from the start to it sets. */
  private scopes(order: readonly string[], inputTypes: ReadonlyMap<string, ValueType>): Map<string, Map<string, ValueType>> {
    const scopes = new Map<string, Map<string, ValueType>>()
    scopes.set(this.flow.start, new Map(inputTypes))
    for (const id of order) {
      const scope = scopes.get(id)!
      const after = this.after(id, scope)
      // A model node that fails publishes nothing: its onFailure node sees only what the model node itself saw.
      const node = this.flow.nodes.get(id)!
      const failureTarget = node.type === 'model' ? node.onFailure.to : undefined
      for (const next of this.successors.get(id)!) {
        const reaching = next === failureTarget ? scope : after
        const existing = scopes.get(next)
        if (!existing) scopes.set(next, new Map(reaching))
        else {
          const both = new Map<string, ValueType>()
          for (const [name, type] of existing) {
            const other = reaching.get(name)
            if (other !== undefined && typesEqual(type, other)) both.set(name, type)
          }
          scopes.set(next, both)
        }
      }
    }
    return scopes
  }

  /** The names readable after node `id`: its own scope plus what it publishes. */
  private after(id: string, scope: ReadonlyMap<string, ValueType>): Map<string, ValueType> {
    const after = new Map(scope)
    const name = this.publishedAs.get(id)
    if (name !== undefined) after.set(name, this.publishedType.get(id)!)
    return after
  }

  private node(id: string, node: FlowNode, scope: Map<string, ValueType>): Node | undefined {
    const path = `nodes.${id}`
    const environment = this.environment(scope, path)
    if (!environment) return undefined
    switch (node.type) {
      case 'table':
        return this.table(id, node.decision, node.version, node.facts, node.next, scope, environment)
      case 'switch':
        return { kind: 'switch', environment, cases: this.routes(node.cases, environment, scope, `${path}.cases`) }
      case 'derive': {
        const set = new Map<string, CompiledExpression>()
        for (const [name, type] of node.outputs) {
          const expression = this.expression(environment, scope, node.set.get(name)!, type, `${path}.set.${name}`)
          if (expression) set.set(name, expression)
        }
        return { kind: 'derive', environment, set, publish: this.publishedAs.get(id)!, next: this.router(id, node.next, scope)! }
      }
      case 'model':
        return this.modelNode(id, node, scope, environment)
      case 'split': {
        const key = this.expression(environment, scope, node.key, 'string', `${path}.key`)
        const slices: Slice[] = []
        let upper = 0
        for (const bucket of node.buckets) {
          upper += bucket.percent
          slices.push({ name: bucket.name, upper, to: bucket.to })
        }
        return key ? { kind: 'split', environment, key, salt: node.salt, slices } : undefined
      }
      case 'end': {
        const set = new Map<string, CompiledExpression>()
        for (const [name, type] of this.flow.outputs) {
          const expression = this.expression(environment, scope, node.set.get(name)!, type, `${path}.set.${name}`)
          if (expression) set.set(name, expression)
        }
        return { kind: 'end', environment, set }
      }
    }
  }

  private table(
    id: string,
    decision: string,
    version: string | undefined,
    facts: ReadonlyMap<string, string>,
    next: readonly Branch[],
    scope: Map<string, ValueType>,
    environment: ExpressionEnvironment,
  ): TableNode | undefined {
    const path = `nodes.${id}`
    const candidates = this.versions.get(id)!
    // The facts must suit every version the node may evaluate.
    const needed = new Map<string, ValueType>()
    for (const candidate of candidates) {
      for (const [name, type] of candidate.table.inputs) {
        const other = needed.get(name)
        if (other === undefined) needed.set(name, type)
        else if (!typesEqual(other, type)) {
          this.problem(path, `versions of decision '${decision}' declare input '${name}' with different types; pin one with "version"`)
        }
      }
    }
    for (const name of facts.keys()) {
      if (!needed.has(name)) {
        this.problem(`${path}.facts.${name}`, `not an input of decision '${decision}' (inputs: [${[...needed.keys()].join(', ')}])`)
      }
    }
    const mapped = new Map<string, CompiledExpression>()
    const byName = new Map<string, string>()
    for (const [input, type] of needed) {
      const source = facts.get(input)
      const at = `${path}.facts.${input}`
      if (source !== undefined && !containsStruct(type)) {
        const expression = this.expression(environment, scope, source, type, at)
        if (expression) mapped.set(input, expression)
        continue
      }
      if (source !== undefined && !NAME.test(javaStrip(source))) {
        this.problem(at, `input '${input}' is ${describe(type)}; a value with fields can only be passed by name, e.g. "applicant"`)
        continue
      }
      const name = source === undefined ? input : javaStrip(source)
      const actual = scope.get(name)
      const suffix = source === undefined ? '; map it in "facts"' : ''
      if (actual === undefined) {
        this.problem(source === undefined ? path : at, `${source === undefined ? `input '${input}' of decision '${decision}': ` : ''}${this.unset(name)}${suffix}`)
      } else if (!assignable(actual, type)) {
        this.problem(
          source === undefined ? path : at,
          `input '${input}' of decision '${decision}' is ${describe(type)} but flow value '${name}' is ${describe(actual)}${suffix}`,
        )
      } else {
        byName.set(input, name)
      }
    }
    return {
      kind: 'table', decision, pinned: version, versions: candidates, environment, mapped, byName,
      publish: this.publishedAs.get(id), next: this.router(id, next, scope)!,
    }
  }

  private modelNode(id: string, model: ModelFlowNode, scope: Map<string, ValueType>, environment: ExpressionEnvironment): ModelNode {
    const path = `nodes.${id}`
    const features = new Map<string, CompiledExpression>()
    for (const [name, source] of model.features) {
      const feature = this.expression(environment, scope, source, undefined, `${path}.features.${name}`)
      if (feature) features.set(name, feature)
    }
    const outputs = this.environment(model.outputs, `${path}.outputs`)
    const valid =
      model.valid === undefined || !outputs ? undefined : this.expression(outputs, model.outputs, model.valid, 'bool', `${path}.valid`)
    const invoke = model.mode === 'invoke' && !this.context.models.suppliedOnly
    return {
      kind: 'model',
      definition: model,
      environment,
      features,
      outputs,
      valid,
      invoker: invoke ? this.context.models.invokers.get(model.modelId) : undefined,
      timeoutMs: model.timeoutMs ?? 0,
      publish: this.publishedAs.get(id)!,
      next: this.router(id, model.next, scope)!,
    }
  }

  /** Why `name` cannot be read here. */
  private unset(name: string): string {
    const exists = this.flow.inputs.has(name) || [...this.publishedAs.values()].includes(name)
    return exists ? `'${name}' is not set on every path that reaches this node` : `no input or node value named '${name}'`
  }

  private router(id: string, next: readonly Branch[], scope: ReadonlyMap<string, ValueType>): Router | undefined {
    if (next.length === 1 && next[0]!.when === undefined) return { environment: undefined, routes: [{ when: undefined, to: next[0]!.to }] }
    const after = this.after(id, scope)
    const environment = this.environment(after, `nodes.${id}.next`)
    if (!environment) return undefined
    return { environment, routes: this.routes(next, environment, after, `nodes.${id}.next`) }
  }

  private routes(branches: readonly Branch[], environment: ExpressionEnvironment, scope: ReadonlyMap<string, ValueType>, path: string): Route[] {
    return branches.map((branch, i) => ({
      when: branch.when === undefined ? undefined : this.expression(environment, scope, branch.when, 'bool', `${path}[${i}].when`),
      to: branch.to,
    }))
  }

  /**
   * Compiles source, of type `type`, or of the type it produces when that is undefined. When it fails
   * because it reads a name that exists in the flow but is not set on every path to the node, says so.
   */
  private expression(
    environment: ExpressionEnvironment,
    scope: ReadonlyMap<string, ValueType>,
    source: string,
    type: ValueType | undefined,
    path: string,
  ): CompiledExpression | undefined {
    const paramsProblem = undeclaredParams(environment, source, this.flow.params, this.flow.inputs.has(PARAMS))
    if (paramsProblem !== undefined) {
      this.problem(path, paramsProblem)
      return undefined
    }
    try {
      return type === undefined ? environment.compileInferred(source) : environment.compile(source, type)
    } catch (e) {
      if (!(e instanceof ExpressionError)) throw e
      const known = new Set<string>(this.flow.inputs.keys())
      for (const id of javaHashMapOrder([...this.publishedAs.keys()])) known.add(this.publishedAs.get(id)!)
      const unset = [...known].filter((name) => !scope.has(name) && new RegExp(`(?<![A-Za-z0-9_.])${escapeRegExp(name)}(?![A-Za-z0-9_])`).test(source))
      if (unset.length === 0) this.problem(path, e.message)
      else {
        this.problem(
          path,
          `reads ${unset.map((n) => `'${n}'`).join(', ')}, which ${unset.length === 1 ? 'is' : 'are'} not set on every path that reaches this node; a node may read only values that every path to it sets`,
        )
      }
      return undefined
    }
  }

  private environment(variables: ReadonlyMap<string, ValueType>, path: string): ExpressionEnvironment | undefined {
    const key = variablesKey(variables)
    const cached = this.environments.get(key)
    if (cached) return cached
    try {
      const environment = new ExpressionEnvironment({
        variables,
        maxIterations: this.context.maxIterations,
        currencyScales: this.context.currencyScales,
      })
      this.environments.set(key, environment)
      return environment
    } catch (e) {
      if (!(e instanceof RangeError)) throw e
      this.problem(path, e.message)
      return undefined
    }
  }

  private problem(path: string, message: string): void {
    this.problems.push(`${this.at}${path}: ${message}`)
  }
}

// --- Evaluation --------------------------------------------------------------------------------

/** What a model node found: the status, and on success what it publishes and records. */
interface ModelOutcome {
  readonly status: ModelStatus
  readonly detail?: string
  readonly outputs: ReadonlyMap<string, Value>
  readonly confidence?: Decimal
  readonly attributionMethod?: string
  readonly attributions: readonly RankedAttribution[]
  readonly reasonCodes: readonly string[]
}

function failed(status: ModelStatus, detail: string): ModelOutcome {
  return { status, detail, outputs: new Map(), attributions: [], reasonCodes: [] }
}

/**
 * Where a key falls among a split's buckets, in [0, 1): the first 64 bits of the SHA-256 of
 * salt + "\n" + key, as a fraction, as DefaultRuleStoneEngine.bucket.
 */
export async function bucketOf(salt: string, key: string): Promise<number> {
  const digest = new Uint8Array(await globalThis.crypto.subtle.digest('SHA-256', javaUtf8(`${salt}\n${key}`) as Uint8Array<ArrayBuffer>))
  let bits = 0n
  for (let i = 0; i < 8; i++) bits = (bits << 8n) | BigInt(digest[i]!)
  return Number(bits >> 11n) * 2 ** -53
}

/** The JSON form of a model output, which the node's declared type reads, as FactsJson.writeValue. */
function outputNode(value: unknown): JsonNode {
  if (value instanceof Decimal) return { type: 'string', value: value.toPlainString() }
  if (value instanceof Money || value instanceof LocalDate) return { type: 'string', value: value.toString() }
  if (Array.isArray(value)) return { type: 'array', items: value.map(outputNode) }
  return toJsonNode(value)
}

function outputEntries(outputs: ModelResult['outputs']): [string, unknown][] {
  return outputs instanceof Map ? [...outputs] : Object.entries(outputs)
}

/** A compiled decision flow, as CompiledFlow. Immutable. */
export class CompiledFlow implements Executable {
  readonly kind = 'flow'

  constructor(
    readonly flow: DecisionFlow,
    readonly ruleVersion: string,
    private readonly inputs: ExpressionEnvironment,
    private readonly nodes: ReadonlyMap<string, Node>,
    private readonly models: Models,
    /** The parameters the flow's own expressions read, if it declares any. */
    readonly params: ParamsBinding | undefined,
  ) {}

  get definition(): DecisionFlow {
    return this.flow
  }

  async run(context: RunContext): Promise<Run> {
    const { facts, progress, deadline, businessDate } = context
    if (facts.has(BUSINESS_DATE)) {
      throw this.failure(
        `invalid input ${BUSINESS_DATE}: reserved name; the business date comes from DecisionRef.asOf or the engine clock and cannot be passed as a fact`,
      )
    }
    if (this.params && facts.has(PARAMS)) {
      throw this.failure(
        `invalid input ${PARAMS}: reserved name; the parameters come from parameter set ${this.params.declaration.set} and cannot be passed as a fact`,
      )
    }
    // The flow's values by name: the facts as given, then each node's values as it runs.
    const values = new Map<string, unknown>(facts)
    values.set(BUSINESS_DATE, businessDate)
    let bindings: Bindings
    try {
      bindings = this.inputs.bind(values)
    } catch (e) {
      if (e instanceof ExpressionError) throw this.failure(`invalid input ${e.message}`, e)
      throw e
    }
    progress.factsHash = await context.hasher.hash(bindings)
    if (this.params) {
      values.set(PARAMS, selectParams(this.params, businessDate, progress.forcedParams, (set, version) => progress.useParams(set, version)))
    }
    for (const node of context.models.keys()) {
      const model = this.nodes.get(node)
      if (!model || model.kind !== 'model') {
        throw this.failure(`model result supplied for '${node}', which is not a model node of this flow`)
      }
      if (model.invoker && !progress.overrides.recordedModels) {
        throw this.failure(
          `model result supplied for node '${node}', which calls its model (mode invoke); supply results only for nodes in supplied mode, or create the engine with suppliedModelsOnly for tests`,
        )
      }
    }
    const reasons: ReasonCode[] = []
    let id = this.flow.start
    for (;;) {
      if (deadline.passed()) throw new DecisionTimeoutError(this.flow.id, this.ruleVersion, deadline.timeoutMs, progress.trace)
      const node = this.nodes.get(id)!
      try {
        if (node.kind === 'end') {
          const endId = id
          progress.open(() => ({ node: endId, type: 'end', matchedRules: [] }))
          return this.finish(node, values, reasons, progress)
        }
        id = await this.step(id, node, values, context, reasons)
      } catch (e) {
        if (e instanceof DecisionTimeoutError) throw new DecisionTimeoutError(this.flow.id, this.ruleVersion, e.timeoutMs, progress.trace)
        if (e instanceof RuleStoneError || e instanceof ExpressionError) throw this.failure(`node ${id}: ${e.message}`, e)
        throw e
      }
    }
  }

  private finish(end: EndNode, values: ReadonlyMap<string, unknown>, reasons: readonly ReasonCode[], progress: Progress): Run {
    const bindings = end.environment.bind(values)
    const output = new Map<string, Value>()
    for (const [name, expression] of end.set) output.set(name, expression.evaluate(bindings))
    progress.close()
    // Reasons in order, each code once, with the node that first gave it.
    const seen = new Set<string>()
    return { output, reasonCodes: reasons.filter((r) => (seen.has(r.code) ? false : (seen.add(r.code), true))) }
  }

  /** Evaluates node `id` and returns the id of the next node. */
  private async step(id: string, node: Node, values: Map<string, unknown>, context: RunContext, reasons: ReasonCode[]): Promise<string> {
    const { progress, businessDate, deadline } = context
    switch (node.kind) {
      case 'table': {
        progress.open(() => ({ node: id, type: 'table', decision: node.decision, matchedRules: [] }))
        const decision = this.select(node, businessDate, progress.overrides.tables.get(id))
        const from = progress.trace.length
        progress.open(() => ({
          node: id, type: 'table', decision: node.decision, version: decision.ruleVersion, matchedRules: progress.matchedSince(from),
        }))
        const facts = new Map<string, unknown>()
        if (node.mapped.size > 0) {
          const bindings = node.environment.bind(values)
          for (const [input, expression] of node.mapped) facts.set(input, expression.evaluate(bindings))
        }
        for (const [input, name] of node.byName) facts.set(input, values.get(name))
        const outcome = decision.evaluate(decision.bind(facts, businessDate, progress), progress.trace, deadline)
        for (const code of outcome.reasonCodes) reasons.push({ code, source: id })
        if (node.publish !== undefined) values.set(node.publish, outcome.output)
        progress.close()
        return route(node.next, values)
      }
      case 'switch': {
        progress.open(() => ({ node: id, type: 'switch', matchedRules: [] }))
        const to = choose(node.cases, node.environment.bind(values))
        progress.open(() => ({ node: id, type: 'switch', matchedRules: [], taken: to }))
        progress.close()
        values.set(id, new Map([[TAKEN, to]]))
        return to
      }
      case 'derive': {
        progress.open(() => ({ node: id, type: 'derive', matchedRules: [] }))
        const bindings = node.environment.bind(values)
        const computed = new Map<string, Value>()
        for (const [name, expression] of node.set) computed.set(name, expression.evaluate(bindings))
        values.set(node.publish, computed)
        progress.close()
        return route(node.next, values)
      }
      case 'model':
        return this.model(id, node, values, context, reasons)
      case 'split': {
        progress.open(() => ({ node: id, type: 'split', matchedRules: [] }))
        const key = node.key.evaluate(node.environment.bind(values)) as string
        const point = (await bucketOf(node.salt, key)) * 100
        const slice = node.slices.find((s) => point < s.upper) ?? node.slices[node.slices.length - 1]!
        progress.open(() => ({ node: id, type: 'split', matchedRules: [], taken: slice.name }))
        progress.close()
        values.set(id, new Map([[TAKEN, slice.name]]))
        return slice.to
      }
      case 'end':
        throw new Error('unreachable')
    }
  }

  /** The version a replay names, else the pinned version, else the version in force on date. */
  private select(node: TableNode, date: LocalDate, exact: string | undefined): CompiledTable {
    if (exact !== undefined) {
      const found = node.versions.find((d) => d.ruleVersion === exact)
      if (!found) throw new DecisionNotFoundError(`decision '${node.decision}' has no version ${exact} that this node can use`)
      return found
    }
    const table = selectVersion(node.decision, node.versions.map((d) => d.table), node.pinned, date)
    return node.versions.find((d) => d.table === table)!
  }

  /** Obtains, checks and records the result of a model node; returns next, or onFailure when the model fails. */
  private async model(id: string, node: ModelNode, values: Map<string, unknown>, context: RunContext, reasons: ReasonCode[]): Promise<string> {
    const { progress, deadline } = context
    const model = node.definition
    const recorded = progress.overrides.recordedModels
    const mode: ModelStepMode = !node.invoker || recorded ? 'supplied' : 'invoke'
    progress.open(() => ({ node: id, type: 'model', matchedRules: [] }))
    // Features are computed in every mode, so that tests and replays meet the same expression errors.
    const features = new Map<string, Value>()
    if (node.features.size > 0) {
      const bindings = node.environment.bind(values)
      for (const [name, expression] of node.features) features.set(name, expression.evaluate(bindings))
    }
    let outcome: ModelOutcome
    let latency: number | undefined
    if (mode === 'supplied') {
      const supplied = context.models.get(id)
      const step = recorded ? progress.overrides.models.get(id) : undefined
      if (!supplied && step && step.id === model.modelId && step.version === model.modelVersion) {
        outcome = step.status !== 'OK' ? failed(step.status, 'as recorded') : this.check(node, recordedResult(step))
      } else if (!supplied) {
        outcome = failed('MISSING', 'no result was supplied for this node')
      } else if (isModelFailure(supplied)) {
        outcome = failed(supplied.failure, 'reported by the caller')
      } else {
        outcome = this.check(node, supplied)
      }
    } else {
      const timeoutMs = deadline.remaining(node.timeoutMs)
      const start = this.models.now()
      let result: ModelResult | undefined | null
      let error: string | undefined
      try {
        const signal = typeof AbortSignal !== 'undefined' && typeof AbortSignal.timeout === 'function'
          ? AbortSignal.timeout(Math.max(0, Math.ceil(timeoutMs)))
          : undefined
        result = await node.invoker!({
          modelId: model.modelId,
          modelVersion: model.modelVersion,
          features,
          timeoutMs,
          ...(context.correlationId !== undefined ? { correlationId: context.correlationId } : {}),
          ...(signal ? { signal } : {}),
        })
      } catch (e) {
        // The message may contain feature values: record the type only.
        error = `the invoker threw ${e instanceof Error ? e.name : typeof e}`
      }
      const elapsed = this.models.now() - start
      latency = Math.max(0, Math.floor(elapsed))
      if (deadline.passed()) {
        const took = latency
        progress.open(() => ({
          node: id, type: 'model', matchedRules: [],
          model: this.stepOf(model, mode, failed('TIMEOUT', "the evaluation's timeout passed while the model was called"), took),
        }))
        throw new DecisionTimeoutError(this.flow.id, this.ruleVersion, deadline.timeoutMs, progress.trace)
      }
      if (elapsed > node.timeoutMs) outcome = failed('TIMEOUT', `no result within ${model.timeoutMs!} ms`)
      else if (error !== undefined) outcome = failed('ERROR', error)
      else if (result === undefined || result === null) outcome = failed('ERROR', 'the invoker returned no result')
      else outcome = this.check(node, result)
    }
    const done = outcome
    const took = latency
    if (outcome.status !== 'OK') {
      reasons.push({ code: model.onFailure.reasonCode, source: id })
      progress.open(() => ({ node: id, type: 'model', matchedRules: [], model: this.stepOf(model, mode, done, took) }))
      progress.close()
      return model.onFailure.to
    }
    for (const code of outcome.reasonCodes) reasons.push({ code, source: id })
    values.set(node.publish, outcome.outputs)
    progress.open(() => ({ node: id, type: 'model', matchedRules: [], model: this.stepOf(model, mode, done, took) }))
    progress.close()
    return route(node.next, values)
  }

  private stepOf(model: ModelFlowNode, mode: ModelStepMode, outcome: ModelOutcome, latency: number | undefined): ModelStep {
    return {
      mode,
      id: model.modelId,
      version: model.modelVersion,
      status: outcome.status,
      ...(outcome.detail !== undefined ? { detail: outcome.detail } : {}),
      ...(latency !== undefined ? { latencyMs: latency } : {}),
      outputs: outcome.outputs,
      ...(outcome.confidence !== undefined ? { confidence: outcome.confidence } : {}),
      ...(outcome.attributionMethod !== undefined ? { attributionMethod: outcome.attributionMethod } : {}),
      attributions: outcome.attributions,
    }
  }

  /** Checks a result against the node's contract, and ranks its attributions. */
  private check(node: ModelNode, result: ModelResult): ModelOutcome {
    const model = node.definition
    if (result.modelId !== model.modelId || result.modelVersion !== model.modelVersion) {
      return failed(
        'CONTRACT_VIOLATION',
        `answered by ${result.modelId}@${result.modelVersion}, not the pinned ${model.modelId}@${model.modelVersion}`,
      )
    }
    const given = new Map(outputEntries(result.outputs))
    const outputs = new Map<string, Value>()
    for (const [name, type] of model.outputs) {
      const value = given.get(name)
      if (value === undefined || value === null) return failed('CONTRACT_VIOLATION', `output '${name}' is missing`)
      let converted: Value | undefined
      try {
        const problems: string[] = []
        // Through the JSON forms: exact decimals, money and dates, also from their text forms.
        converted = readValueAt(outputNode(value), type, name, problems)
        if (problems.length > 0) converted = undefined
      } catch {
        converted = undefined
      }
      if (converted === undefined) return failed('CONTRACT_VIOLATION', `output '${name}' is not ${describe(type)}`)
      outputs.set(name, converted)
    }
    for (const name of given.keys()) {
      if (!model.outputs.has(name)) return failed('CONTRACT_VIOLATION', `undeclared output '${name}'`)
    }
    if (model.minConfidence !== undefined && result.confidence === undefined) {
      return failed('CONTRACT_VIOLATION', 'no confidence reported, and the node sets minConfidence')
    }
    if (node.valid && node.outputs) {
      let valid: Value
      try {
        valid = node.valid.evaluate(node.outputs.bind(outputs))
      } catch (e) {
        if (!(e instanceof ExpressionError)) throw e
        return failed('INVALID_OUTPUT', 'the valid condition could not be evaluated')
      }
      if (valid !== true) return failed('INVALID_OUTPUT', 'the valid condition is false')
    }
    if (model.minConfidence !== undefined && result.confidence!.compareTo(model.minConfidence) < 0) {
      return failed('LOW_CONFIDENCE', 'confidence below minConfidence')
    }
    // Rank by the size of the contribution, in either direction; ties keep the model's order.
    const ranked = [...(result.attributions ?? [])].sort((a, b) => b.contribution.abs().compareTo(a.contribution.abs()))
    const max = model.maxReasonCodes ?? DEFAULT_MAX_REASON_CODES
    const codes: string[] = []
    const attributions: RankedAttribution[] = ranked.map((attribution, i) => {
      const sign = attribution.contribution.signum()
      const direction = sign > 0 ? 'INCREASES_RISK' : sign < 0 ? 'DECREASES_RISK' : 'NONE'
      const code = model.reasonCodes.get(attribution.feature)
      let emitted: string | undefined
      if (direction === 'INCREASES_RISK' && code !== undefined && codes.length < max) {
        if (!codes.includes(code)) codes.push(code)
        emitted = code
      }
      return { feature: attribution.feature, rank: i + 1, direction, ...(emitted !== undefined ? { reasonCode: emitted } : {}) }
    })
    return {
      status: 'OK',
      outputs,
      ...(result.confidence !== undefined ? { confidence: result.confidence } : {}),
      ...(result.attributionMethod !== undefined ? { attributionMethod: result.attributionMethod } : {}),
      attributions,
      reasonCodes: codes,
    }
  }

  private failure(message: string, cause?: unknown): DecisionEvaluationError {
    return new DecisionEvaluationError(this.flow.id, this.ruleVersion, message, cause === undefined ? undefined : { cause })
  }
}

/**
 * The result a recorded step shows, for a replay: its outputs, confidence and attribution method,
 * and attributions with contributions made up from their ranks and directions, which give the same
 * ranks, directions and reason codes again.
 */
export function recordedResult(step: ModelStep): ModelResult {
  const ranked = [...step.attributions].sort((a, b) => a.rank - b.rank)
  const size = ranked.length
  return {
    modelId: step.id,
    modelVersion: step.version,
    outputs: step.outputs,
    ...(step.confidence !== undefined ? { confidence: step.confidence } : {}),
    ...(step.attributionMethod !== undefined ? { attributionMethod: step.attributionMethod } : {}),
    attributions: ranked.map((a, i) => {
      const magnitude = Decimal.fromBigInt(BigInt(size - i))
      const contribution = a.direction === 'INCREASES_RISK' ? magnitude : a.direction === 'DECREASES_RISK' ? magnitude.negate() : Decimal.fromBigInt(0n)
      return { feature: a.feature, contribution }
    }),
  }
}

function choose(routes: readonly Route[], bindings: Bindings): string {
  for (const r of routes) if (!r.when || r.when.evaluate(bindings) === true) return r.to
  throw new Error('the last alternative has no condition')
}

function route(router: Router, values: ReadonlyMap<string, unknown>): string {
  if (!router.environment) return router.routes[0]!.to
  return choose(router.routes, router.environment.bind(values))
}

export type { PathStep }
