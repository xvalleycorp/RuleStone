// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0

// Decision flows and the .flow.json format, as DecisionFlow, FlowNode and DecisionFlowJson: a
// strict reader that reports every problem with its JSON path, and the canonical form the content
// hash is computed over.

import { Decimal, javaIsBlank } from './decimal.js'
import type { LocalDate } from './date.js'
import { InvalidDecisionError } from './errors.js'
import { JsonSyntaxError, parseJson, writeJson, type JsonNode, type Writable } from './json.js'
import { TableReader, sha256Hex, sortedMap, typesJson, type ParamsDeclaration } from './table.js'
import type { ValueType } from './types.js'

/** File name suffix of decision flows. */
export const FLOW_FILE_SUFFIX = '.flow.json'

/** One way out of a node: to node `to` when `when` holds; the last alternative has no `when`. */
export interface Branch {
  readonly when?: string
  readonly to: string
}

/** Evaluates a decision table. */
export interface TableFlowNode {
  readonly type: 'table'
  readonly description?: string
  /** The id of a registered decision table. */
  readonly decision: string
  /** A version of the table to always use; absent for the version in force on the business date. */
  readonly version?: string
  /** Table input name to a CEL expression over the flow values, in file order. */
  readonly facts: ReadonlyMap<string, string>
  /** A name to publish the table's outputs under instead of the node id. */
  readonly as?: string
  readonly next: readonly Branch[]
}

/** Chooses a branch; publishes `taken`, the id of the node chosen. */
export interface SwitchFlowNode {
  readonly type: 'switch'
  readonly description?: string
  readonly cases: readonly Branch[]
}

/** Computes named, typed values once. */
export interface DeriveFlowNode {
  readonly type: 'derive'
  readonly description?: string
  readonly outputs: ReadonlyMap<string, ValueType>
  /** Value name to a CEL expression of its type. */
  readonly set: ReadonlyMap<string, string>
  readonly as?: string
  readonly next: readonly Branch[]
}

/** How a model node obtains its result. */
export type ModelMode = 'invoke' | 'supplied'

/** Obtains a machine-learning model's result and checks it against a typed contract. */
export interface ModelFlowNode {
  readonly type: 'model'
  readonly description?: string
  readonly mode: ModelMode
  readonly modelId: string
  /** The one exact model version, e.g. 2.4.0. */
  readonly modelVersion: string
  /** In invoke mode, feature name to a CEL expression: the only values the model receives. */
  readonly features: ReadonlyMap<string, string>
  readonly outputs: ReadonlyMap<string, ValueType>
  /** A CEL condition over the outputs, by name. */
  readonly valid?: string
  readonly minConfidence?: Decimal
  /** In invoke mode, how long the model may take. */
  readonly timeoutMs?: number
  /** Feature name to the reason code given when the feature is among the main reasons. */
  readonly reasonCodes: ReadonlyMap<string, string>
  /** How many reason codes the node gives at most; three when absent. */
  readonly maxReasonCodes?: number
  readonly onFailure: { readonly to: string; readonly reasonCode: string }
  readonly as?: string
  readonly next: readonly Branch[]
}

/** A bucket of a split. */
export interface Bucket {
  readonly name: string
  /** Whole percentage of keys, 1 to 100. */
  readonly percent: number
  readonly to: string
}

/** A deterministic percentage split; publishes `taken`, the name of the bucket chosen. */
export interface SplitFlowNode {
  readonly type: 'split'
  readonly description?: string
  /** A CEL expression producing a string. */
  readonly key: string
  readonly salt: string
  readonly buckets: readonly Bucket[]
}

/** Ends the flow, setting every output. */
export interface EndFlowNode {
  readonly type: 'end'
  readonly description?: string
  /** Flow output name to a CEL expression of its type. */
  readonly set: ReadonlyMap<string, string>
}

/** One node of a decision flow, as FlowNode. */
export type FlowNode = TableFlowNode | SwitchFlowNode | DeriveFlowNode | ModelFlowNode | SplitFlowNode | EndFlowNode

/**
 * A versioned decision flow, as DecisionFlow: typed inputs and outputs plus a directed acyclic graph
 * of nodes, evaluated from `start` along exactly one path to an end node. Structural and type
 * errors are reported when an engine is created.
 */
export interface DecisionFlow {
  readonly id: string
  readonly version: string
  readonly description?: string
  readonly effectiveFrom?: LocalDate
  readonly effectiveTo?: LocalDate
  readonly inputs: ReadonlyMap<string, ValueType>
  readonly outputs: ReadonlyMap<string, ValueType>
  readonly params?: ParamsDeclaration
  readonly start: string
  /** The nodes by id, in file order. */
  readonly nodes: ReadonlyMap<string, FlowNode>
}

/** Model node fields of the design that later steps add; reported as not supported yet. */
const MODEL_FIELDS_PLANNED = ['kind', 'shadow', 'prompt']
const FLOW_FIELDS = ['id', 'version', 'description', 'effectiveFrom', 'effectiveTo', 'inputs', 'outputs', 'params', 'start', 'nodes']
const TABLE_FIELDS = ['type', 'description', 'decision', 'version', 'facts', 'as', 'next']
const SWITCH_FIELDS = ['type', 'description', 'cases']
const DERIVE_FIELDS = ['type', 'description', 'outputs', 'set', 'as', 'next']
const MODEL_FIELDS = [
  'type', 'description', 'mode', 'model', 'features', 'outputs', 'valid', 'minConfidence', 'timeoutMs', 'reasonCodes',
  'maxReasonCodes', 'onFailure', 'as', 'next',
]
const MODEL_REF_FIELDS = ['id', 'version']
const ON_FAILURE_FIELDS = ['to', 'reasonCode']
const SPLIT_FIELDS = ['type', 'description', 'key', 'salt', 'buckets']
const END_FIELDS = ['type', 'description', 'set']
const BRANCH_FIELDS = ['when', 'to']
const BUCKET_FIELDS = ['name', 'percent', 'to']
const NODE_TYPES = 'table, switch, derive, model, split or end'
const INT_MIN = -2147483648
const INT_MAX = 2147483647

type ObjectNode = JsonNode & { type: 'object' }

/**
 * Reads a decision flow from .flow.json text. Unknown and duplicate keys are errors.
 * @throws InvalidDecisionError listing every problem found
 */
export function parseDecisionFlow(text: string): DecisionFlow {
  let root: JsonNode | undefined
  try {
    root = parseJson(text, { failOnTrailingTokens: false })
  } catch (e) {
    if (e instanceof JsonSyntaxError) throw new InvalidDecisionError([`invalid JSON: ${e.message}`])
    throw e
  }
  return new FlowReader().flow(root)
}

class FlowReader {
  private readonly reader = new TableReader()

  flow(root: JsonNode | undefined): DecisionFlow {
    const r = this.reader
    if (!root || root.type !== 'object') throw new InvalidDecisionError(['$: expected a JSON object'])
    r.unknownFields(root, FLOW_FIELDS, '$')
    const id = r.requiredText(root, 'id', '$')
    const version = r.requiredText(root, 'version', '$')
    const description = r.optionalText(root, 'description', '$')
    const effectiveFrom = r.optionalDate(root, 'effectiveFrom')
    const effectiveTo = r.optionalDate(root, 'effectiveTo')
    if (effectiveFrom && effectiveTo && effectiveFrom.compareTo(effectiveTo) >= 0) r.problem('$.effectiveTo', 'must be after effectiveFrom')
    const inputs = r.typeMap(root, 'inputs')
    const outputs = r.typeMap(root, 'outputs')
    const params = r.params(root)
    const start = r.requiredText(root, 'start', '$')
    const nodes = new Map<string, FlowNode>()
    const nodesNode = root.entries.get('nodes')
    if (!nodesNode || nodesNode.type !== 'object' || nodesNode.entries.size === 0) {
      r.problem('$.nodes', 'required non-empty object of node id to node')
    } else {
      for (const [nodeId, value] of nodesNode.entries) {
        const node = this.node(value, `$.nodes.${nodeId}`)
        if (node) nodes.set(nodeId, node)
      }
    }
    if (r.problems.length > 0) throw new InvalidDecisionError(r.problems)
    return {
      id,
      version,
      ...(description !== undefined ? { description } : {}),
      ...(effectiveFrom ? { effectiveFrom } : {}),
      ...(effectiveTo ? { effectiveTo } : {}),
      inputs,
      outputs,
      ...(params ? { params } : {}),
      start,
      nodes,
    }
  }

  private node(node: JsonNode, path: string): FlowNode | undefined {
    const r = this.reader
    if (node.type !== 'object') {
      r.problem(path, 'expected a node object with a "type"')
      return undefined
    }
    const typeNode = node.entries.get('type')
    if (!typeNode || typeNode.type !== 'string') {
      r.problem(`${path}.type`, `required: ${NODE_TYPES}`)
      return undefined
    }
    const description = r.optionalText(node, 'description', path)
    const d = description !== undefined ? { description } : {}
    switch (typeNode.value) {
      case 'table': {
        r.unknownFields(node, TABLE_FIELDS, path)
        const decision = r.requiredText(node, 'decision', path)
        const version = r.optionalText(node, 'version', path)
        const facts = this.expressions(node, 'facts', path, false)
        const as = r.optionalText(node, 'as', path)
        const next = this.next(node, path)
        return {
          type: 'table', ...d, decision, ...(version !== undefined ? { version } : {}), facts,
          ...(as !== undefined ? { as } : {}), next,
        }
      }
      case 'switch':
        r.unknownFields(node, SWITCH_FIELDS, path)
        return { type: 'switch', ...d, cases: this.branches(node.entries.get('cases'), `${path}.cases`) }
      case 'derive': {
        r.unknownFields(node, DERIVE_FIELDS, path)
        const outputs = r.typeMap(node, 'outputs', path)
        const set = this.expressions(node, 'set', path, true)
        const as = r.optionalText(node, 'as', path)
        const next = this.next(node, path)
        return { type: 'derive', ...d, outputs, set, ...(as !== undefined ? { as } : {}), next }
      }
      case 'split': {
        r.unknownFields(node, SPLIT_FIELDS, path)
        const key = r.requiredText(node, 'key', path)
        const salt = r.requiredText(node, 'salt', path)
        return { type: 'split', ...d, key, salt, buckets: this.buckets(node.entries.get('buckets'), `${path}.buckets`) }
      }
      case 'end':
        r.unknownFields(node, END_FIELDS, path)
        return { type: 'end', ...d, set: this.expressions(node, 'set', path, true) }
      case 'model':
        return this.model(node, description, path)
      case 'flow':
        r.problem(`${path}.type`, 'flow nodes (calling another flow) are not supported yet')
        return undefined
      default:
        r.problem(`${path}.type`, `unknown node type '${typeNode.value}' (expected ${NODE_TYPES})`)
        return undefined
    }
  }

  private model(node: ObjectNode, description: string | undefined, path: string): ModelFlowNode {
    const r = this.reader
    r.unknownFields(node, [...MODEL_FIELDS, ...MODEL_FIELDS_PLANNED], path)
    for (const field of [...MODEL_FIELDS_PLANNED].sort()) {
      if (node.entries.has(field)) {
        r.problem(
          `${path}.${field}`,
          'not supported yet: shadow models and LLM nodes are planned for a later release (see Hybrid AI decisioning)',
        )
      }
    }
    const modeText = r.requiredText(node, 'mode', path)
    let mode: ModelMode = 'invoke'
    if (modeText === 'supplied') mode = 'supplied'
    else if (modeText !== 'invoke' && modeText !== '') r.problem(`${path}.mode`, 'expected "invoke" or "supplied"')
    let modelId = ''
    let modelVersion = ''
    const model = node.entries.get('model')
    if (!model || model.type !== 'object') {
      r.problem(`${path}.model`, 'required object { "id", "version" }')
    } else {
      r.unknownFields(model, MODEL_REF_FIELDS, `${path}.model`)
      modelId = r.requiredText(model, 'id', `${path}.model`)
      modelVersion = r.requiredText(model, 'version', `${path}.model`)
    }
    const features = this.expressions(node, 'features', path, false)
    const outputs = r.typeMap(node, 'outputs', path)
    const valid = r.optionalText(node, 'valid', path)
    let minConfidence: Decimal | undefined
    const min = node.entries.get('minConfidence')
    if (min !== undefined) {
      if (min.type === 'number' && min.integral) minConfidence = Decimal.parse(min.text)
      else if (min.type === 'string' && /^-?[0-9]+(\.[0-9]+)?$/.test(min.value)) minConfidence = Decimal.parse(min.value)
      else r.problem(`${path}.minConfidence`, 'expected a decimal from 0 to 1, e.g. "0.80"')
    }
    const timeoutMs = this.optionalInt(node, 'timeoutMs', path)
    const reasonCodes = this.strings(node, 'reasonCodes', path)
    const maxReasonCodes = this.optionalInt(node, 'maxReasonCodes', path)
    let onFailure = { to: '', reasonCode: '' }
    const failure = node.entries.get('onFailure')
    if (!failure || failure.type !== 'object') {
      r.problem(
        `${path}.onFailure`,
        'required object { "to", "reasonCode" }: where to go when the model fails, and the reason code to give',
      )
    } else {
      r.unknownFields(failure, ON_FAILURE_FIELDS, `${path}.onFailure`)
      onFailure = { to: r.requiredText(failure, 'to', `${path}.onFailure`), reasonCode: r.requiredText(failure, 'reasonCode', `${path}.onFailure`) }
    }
    const as = r.optionalText(node, 'as', path)
    const next = this.next(node, path)
    return {
      type: 'model',
      ...(description !== undefined ? { description } : {}),
      mode,
      modelId,
      modelVersion,
      features,
      outputs,
      ...(valid !== undefined ? { valid } : {}),
      ...(minConfidence !== undefined ? { minConfidence } : {}),
      ...(timeoutMs !== undefined ? { timeoutMs } : {}),
      reasonCodes,
      ...(maxReasonCodes !== undefined ? { maxReasonCodes } : {}),
      onFailure,
      ...(as !== undefined ? { as } : {}),
      next,
    }
  }

  private optionalInt(node: ObjectNode, field: string, path: string): number | undefined {
    const value = node.entries.get(field)
    if (value === undefined) return undefined
    if (value.type !== 'number' || !value.integral || BigInt(value.text) < INT_MIN || BigInt(value.text) > INT_MAX) {
      this.reader.problem(`${path}.${field}`, 'expected a whole number')
      return undefined
    }
    return Number(value.text)
  }

  private strings(node: ObjectNode, field: string, path: string): Map<string, string> {
    const strings = new Map<string, string>()
    const object = node.entries.get(field)
    if (object === undefined) return strings
    if (object.type !== 'object') {
      this.reader.problem(`${path}.${field}`, 'expected an object of feature name to reason code')
      return strings
    }
    for (const [name, value] of object.entries) {
      if (value.type === 'string') strings.set(name, value.value)
      else this.reader.problem(`${path}.${field}.${name}`, 'expected a reason code string')
    }
    return strings
  }

  private next(node: ObjectNode, path: string): Branch[] {
    const next = node.entries.get('next')
    if (next && next.type === 'string') {
      if (javaIsBlank(next.value)) {
        this.reader.problem(`${path}.next`, 'required non-empty node id')
      }
      return [{ to: next.value }]
    }
    if (!next || next.type !== 'array') {
      this.reader.problem(`${path}.next`, 'required: a node id, or an array of { "when", "to" } ending with { "to" }')
      return []
    }
    return this.branches(next, `${path}.next`)
  }

  private branches(array: JsonNode | undefined, path: string): Branch[] {
    const r = this.reader
    if (!array || array.type !== 'array' || array.items.length === 0) {
      r.problem(path, 'required non-empty array of { "when", "to" } ending with { "to" }')
      return []
    }
    const branches: Branch[] = []
    array.items.forEach((b, i) => {
      const at = `${path}[${i}]`
      if (b.type !== 'object') {
        r.problem(at, 'expected an object with "to" and, except for the last, "when"')
        return
      }
      r.unknownFields(b, BRANCH_FIELDS, at)
      const when = r.optionalText(b, 'when', at)
      const to = r.requiredText(b, 'to', at)
      const last = i === array.items.length - 1
      if (last && when !== undefined) {
        r.problem(`${at}.when`, 'the last alternative must have no "when", so that one always applies; add { "to": … } after it')
      } else if (!last && when === undefined) {
        r.problem(`${at}.when`, 'required: only the last alternative may leave out "when"')
      }
      branches.push({ ...(when !== undefined ? { when } : {}), to })
    })
    return branches
  }

  private buckets(array: JsonNode | undefined, path: string): Bucket[] {
    const r = this.reader
    if (!array || array.type !== 'array' || array.items.length === 0) {
      r.problem(path, 'required non-empty array of { "name", "percent", "to" }')
      return []
    }
    const buckets: Bucket[] = []
    array.items.forEach((b, i) => {
      const at = `${path}[${i}]`
      if (b.type !== 'object') {
        r.problem(at, 'expected an object with "name", "percent" and "to"')
        return
      }
      r.unknownFields(b, BUCKET_FIELDS, at)
      const name = r.requiredText(b, 'name', at)
      const to = r.requiredText(b, 'to', at)
      const percent = b.entries.get('percent')
      let value = 0
      if (!percent || percent.type !== 'number' || !percent.integral || BigInt(percent.text) < INT_MIN || BigInt(percent.text) > INT_MAX) {
        r.problem(`${at}.percent`, 'required whole number from 1 to 100')
      } else {
        value = Number(percent.text)
      }
      buckets.push({ name, percent: value, to })
    })
    return buckets
  }

  private expressions(node: ObjectNode, field: string, path: string, required: boolean): Map<string, string> {
    const expressions = new Map<string, string>()
    const object = node.entries.get(field)
    if (object === undefined && !required) return expressions
    if (!object || object.type !== 'object') {
      this.reader.problem(`${path}.${field}`, `${required ? 'required ' : 'expected an '}object of name to CEL expression`)
      return expressions
    }
    for (const [name, value] of object.entries) {
      if (value.type === 'string') expressions.set(name, value.value)
      else {
        this.reader.problem(
          `${path}.${field}.${name}`,
          `expected a CEL expression string (string literals need inner quotes, e.g. "'APPROVE'")`,
        )
      }
    }
    return expressions
  }
}

// --- Canonical form and content hash -----------------------------------------------------------

function branchJson(branch: Branch): Writable {
  const json = new Map<string, Writable>()
  if (branch.when !== undefined) json.set('when', branch.when)
  json.set('to', branch.to)
  return sortedMap(json)
}

function nextJson(next: readonly Branch[]): Writable {
  if (next.length === 1 && next[0]!.when === undefined) return next[0]!.to
  return next.map(branchJson)
}

function nodeJson(node: FlowNode): Writable {
  const json = new Map<string, Writable>()
  json.set('type', node.type)
  if (node.description !== undefined) json.set('description', node.description)
  switch (node.type) {
    case 'table':
      json.set('decision', node.decision)
      if (node.version !== undefined) json.set('version', node.version)
      if (node.facts.size > 0) json.set('facts', sortedMap(node.facts))
      if (node.as !== undefined) json.set('as', node.as)
      json.set('next', nextJson(node.next))
      break
    case 'switch':
      json.set('cases', node.cases.map(branchJson))
      break
    case 'derive':
      json.set('outputs', typesJson(node.outputs))
      json.set('set', sortedMap(node.set))
      if (node.as !== undefined) json.set('as', node.as)
      json.set('next', nextJson(node.next))
      break
    case 'model':
      json.set('mode', node.mode)
      json.set('model', sortedMap<Writable>([['id', node.modelId], ['version', node.modelVersion]]))
      if (node.features.size > 0) json.set('features', sortedMap(node.features))
      json.set('outputs', typesJson(node.outputs))
      if (node.valid !== undefined) json.set('valid', node.valid)
      if (node.minConfidence !== undefined) json.set('minConfidence', node.minConfidence.toPlainString())
      if (node.timeoutMs !== undefined) json.set('timeoutMs', BigInt(node.timeoutMs))
      if (node.reasonCodes.size > 0) json.set('reasonCodes', sortedMap(node.reasonCodes))
      if (node.maxReasonCodes !== undefined) json.set('maxReasonCodes', BigInt(node.maxReasonCodes))
      json.set('onFailure', sortedMap<Writable>([['to', node.onFailure.to], ['reasonCode', node.onFailure.reasonCode]]))
      if (node.as !== undefined) json.set('as', node.as)
      json.set('next', nextJson(node.next))
      break
    case 'split':
      json.set('key', node.key)
      json.set('salt', node.salt)
      json.set(
        'buckets',
        node.buckets.map((b) => sortedMap<Writable>([['name', b.name], ['percent', BigInt(b.percent)], ['to', b.to]])),
      )
      break
    case 'end':
      json.set('set', sortedMap(node.set))
      break
  }
  return sortedMap(json)
}

/**
 * The canonical form, as DecisionFlowJson.write: compact JSON with sorted keys, absent optional
 * fields omitted, and a single unconditional next written as the node id. The content hash is
 * computed over it.
 */
export function writeDecisionFlow(flow: DecisionFlow): string {
  const json = new Map<string, Writable>()
  json.set('id', flow.id)
  json.set('version', flow.version)
  if (flow.description !== undefined) json.set('description', flow.description)
  if (flow.effectiveFrom) json.set('effectiveFrom', flow.effectiveFrom.toString())
  if (flow.effectiveTo) json.set('effectiveTo', flow.effectiveTo.toString())
  json.set('inputs', typesJson(flow.inputs))
  json.set('outputs', typesJson(flow.outputs))
  if (flow.params) {
    const params = new Map<string, Writable>()
    params.set('set', flow.params.set)
    if (flow.params.version !== undefined) params.set('version', flow.params.version)
    params.set('values', typesJson(flow.params.values))
    json.set('params', sortedMap(params))
  }
  json.set('start', flow.start)
  json.set('nodes', sortedMap([...flow.nodes].map(([id, node]) => [id, nodeJson(node)] as [string, Writable])))
  return writeJson(sortedMap(json))
}

/** The flow's rule version: <version>@sha256:<hash of the canonical form>. */
export async function flowVersionOf(flow: DecisionFlow): Promise<string> {
  return `${flow.version}@sha256:${await sha256Hex(writeDecisionFlow(flow))}`
}
