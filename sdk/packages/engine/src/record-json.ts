// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0

// Reading decision records back from their JSON form, as DecisionRecordJson.read, to replay them.

import { LocalDate } from './date.js'
import { Decimal } from './decimal.js'
import { RuleStoneError } from './errors.js'
import { JsonSyntaxError, parseJson, toJsonNode, type JsonNode } from './json.js'
import type {
  DecisionRecord, Direction, ModelStatus, ModelStep, ModelStepMode, ParamSetVersion, PathStep, RankedAttribution, ReasonCode,
  Routing,
} from './record.js'
import { TableReader } from './table.js'
import type { Value } from './values.js'

/** A decision record's JSON is malformed, as InvalidRecordException. Lists every problem. */
export class InvalidRecordError extends RuleStoneError {
  readonly problems: readonly string[]

  constructor(problems: readonly string[]) {
    super(
      `invalid decision record (${problems.length} ${problems.length === 1 ? 'problem' : 'problems'}):` +
        problems.map((p) => '\n  - ' + p).join(''),
    )
    this.name = 'InvalidRecordError'
    this.problems = [...problems]
  }
}

const RECORD_FIELDS = [
  'recordId', 'correlationId', 'decision', 'version', 'params', 'businessDate', 'evaluatedAt', 'engine', 'status', 'factsHash',
  'output', 'reasonCodes', 'path', 'error', 'routing',
]
const STEP_FIELDS = [
  'node', 'type', 'decision', 'version', 'matchedRules', 'taken', 'mode', 'model', 'status', 'detail', 'latencyMs', 'outputs',
  'confidence', 'attributionMethod', 'attributions',
]
const MODEL_FIELDS = ['mode', 'model', 'status', 'detail', 'latencyMs', 'outputs', 'confidence', 'attributionMethod', 'attributions']
const STATUSES = ['OK', 'ERROR', 'TIMEOUT'] as const
const MODEL_STATUSES: readonly ModelStatus[] = ['OK', 'TIMEOUT', 'ERROR', 'CONTRACT_VIOLATION', 'INVALID_OUTPUT', 'LOW_CONFIDENCE', 'MISSING']
const DIRECTIONS: readonly Direction[] = ['INCREASES_RISK', 'DECREASES_RISK', 'NONE']
const KINDS = ['CANARY', 'SHADOW'] as const
const INSTANT = /^(-?\d{4,})-(\d{2})-(\d{2})T(\d{2}):(\d{2})(?::(\d{2})(\.\d{1,9})?)?Z$/

type ObjectNode = JsonNode & { type: 'object' }

/**
 * Reads a record that writeDecisionRecord, or the Java engine's DecisionRecordJson.write, wrote. A
 * record carries no types, so values read back are the forms of their JSON: a string for a decimal,
 * money or date such as "0.0980", a bigint for an integer, a Decimal for another number, a boolean,
 * an array or a Map. Replays compare outputs in these forms.
 * @throws InvalidRecordError listing every problem found
 */
export function readDecisionRecord(json: string | JsonNode | unknown): DecisionRecord {
  let root: JsonNode | undefined
  if (typeof json === 'string') {
    try {
      root = parseJson(json, { failOnTrailingTokens: false })
    } catch (e) {
      // The parser's message may quote the text, which holds output values: give the location only.
      if (e instanceof JsonSyntaxError) throw new InvalidRecordError([`$: invalid JSON at line ${e.line}, column ${e.column}`])
      throw e
    }
  } else {
    root = toJsonNode(json)
  }
  return new RecordReader().record(root)
}

class RecordReader {
  private readonly text = new TableReader()

  record(root: JsonNode | undefined): DecisionRecord {
    const t = this.text
    if (!root || root.type !== 'object') throw new InvalidRecordError(['$: expected a JSON object'])
    t.unknownFields(root, RECORD_FIELDS, '$')
    const recordId = t.requiredText(root, 'recordId', '$')
    const correlationId = t.optionalText(root, 'correlationId', '$')
    const decision = t.requiredText(root, 'decision', '$')
    const version = t.optionalText(root, 'version', '$')
    const params = this.params(root.entries.get('params'))
    const businessDate = this.date(root, 'businessDate', '$')
    const evaluatedAt = this.instant(root, 'evaluatedAt', '$')
    const engine = t.requiredText(root, 'engine', '$')
    const status = this.enumValue(root, 'status', '$', STATUSES)
    const factsHash = t.optionalText(root, 'factsHash', '$')
    const output = this.values(root.entries.get('output'), '$.output', true)
    const reasonCodes = this.reasonCodes(root.entries.get('reasonCodes'))
    const path = this.path(root.entries.get('path'))
    const error = t.optionalText(root, 'error', '$')
    const routing = this.routing(root.entries.get('routing'))
    if (t.problems.length > 0) throw new InvalidRecordError(t.problems)
    if ((error !== undefined) !== (status !== 'OK')) {
      throw new InvalidRecordError(['$: error must be present exactly when the status is not OK'])
    }
    if (status !== 'OK' && output.size > 0) throw new InvalidRecordError(['$: a failed evaluation has no output'])
    return {
      recordId,
      ...(correlationId !== undefined ? { correlationId } : {}),
      decision,
      ...(version !== undefined ? { version } : {}),
      ...(params.length > 0 ? { params } : {}),
      businessDate: businessDate!,
      evaluatedAt: evaluatedAt!,
      engine,
      status: status!,
      ...(factsHash !== undefined ? { factsHash } : {}),
      output,
      reasonCodes,
      path,
      ...(error !== undefined ? { error } : {}),
      ...(routing ? { routing } : {}),
    }
  }

  private params(node: JsonNode | undefined): ParamSetVersion[] {
    const params: ParamSetVersion[] = []
    if (node === undefined) return params
    if (node.type !== 'array') {
      this.text.problem('$.params', 'expected an array of { "set", "version" }')
      return params
    }
    node.items.forEach((item, i) => {
      const at = `$.params[${i}]`
      if (item.type !== 'object') {
        this.text.problem(at, 'expected an object')
        return
      }
      this.text.unknownFields(item, ['set', 'version'], at)
      params.push({ set: this.text.requiredText(item, 'set', at), version: this.text.requiredText(item, 'version', at) })
    })
    return params
  }

  private reasonCodes(node: JsonNode | undefined): ReasonCode[] {
    const codes: ReasonCode[] = []
    if (!node || node.type !== 'array') {
      this.text.problem('$.reasonCodes', 'required array of { "code", "source" }')
      return codes
    }
    node.items.forEach((item, i) => {
      const at = `$.reasonCodes[${i}]`
      if (item.type !== 'object') {
        this.text.problem(at, 'expected an object')
        return
      }
      this.text.unknownFields(item, ['code', 'source'], at)
      codes.push({ code: this.text.requiredText(item, 'code', at), source: this.text.requiredText(item, 'source', at) })
    })
    return codes
  }

  private path(node: JsonNode | undefined): PathStep[] {
    const path: PathStep[] = []
    if (!node || node.type !== 'array') {
      this.text.problem('$.path', 'required array of steps')
      return path
    }
    node.items.forEach((item, i) => {
      const at = `$.path[${i}]`
      if (item.type !== 'object') {
        this.text.problem(at, 'expected an object')
        return
      }
      const step = this.step(item, at)
      if (step) path.push(step)
    })
    return path
  }

  private step(node: ObjectNode, at: string): PathStep | undefined {
    const t = this.text
    t.unknownFields(node, STEP_FIELDS, at)
    const id = t.requiredText(node, 'node', at)
    const type = t.requiredText(node, 'type', at)
    const decision = t.optionalText(node, 'decision', at)
    const version = t.optionalText(node, 'version', at)
    const matched: string[] = []
    const rules = node.entries.get('matchedRules')
    if (rules !== undefined) {
      if (rules.type !== 'array') t.problem(`${at}.matchedRules`, 'expected an array of rule ids')
      else {
        rules.items.forEach((rule, i) => {
          if (rule.type === 'string') matched.push(rule.value)
          else t.problem(`${at}.matchedRules[${i}]`, 'expected a rule id')
        })
      }
    }
    const taken = t.optionalText(node, 'taken', at)
    let model: ModelStep | undefined
    if (type === 'model') model = this.model(node, at)
    else {
      for (const field of MODEL_FIELDS) if (node.entries.has(field)) t.problem(`${at}.${field}`, 'only a model step has this field')
    }
    return {
      node: id,
      type,
      ...(decision !== undefined ? { decision } : {}),
      ...(version !== undefined ? { version } : {}),
      matchedRules: matched,
      ...(taken !== undefined ? { taken } : {}),
      ...(model ? { model } : {}),
    }
  }

  private model(node: ObjectNode, at: string): ModelStep | undefined {
    const t = this.text
    let mode: ModelStepMode | undefined
    const modeText = t.optionalText(node, 'mode', at)
    if (modeText === undefined) t.problem(`${at}.mode`, 'required: invoke or supplied')
    else if (modeText === 'invoke' || modeText === 'supplied') mode = modeText
    else t.problem(`${at}.mode`, 'expected invoke or supplied')
    const pinned = node.entries.get('model')
    let id = ''
    let version = ''
    if (!pinned || pinned.type !== 'object') t.problem(`${at}.model`, 'required object of { "id", "version" }')
    else {
      t.unknownFields(pinned, ['id', 'version'], `${at}.model`)
      id = t.requiredText(pinned, 'id', `${at}.model`)
      version = t.requiredText(pinned, 'version', `${at}.model`)
    }
    const status = this.enumValue(node, 'status', at, MODEL_STATUSES)
    const detail = t.optionalText(node, 'detail', at)
    let latencyMs: number | undefined
    const latency = node.entries.get('latencyMs')
    if (latency !== undefined) {
      if (latency.type === 'number' && latency.integral && BigInt(latency.text) >= 0n && BigInt(latency.text) <= 2n ** 63n - 1n) {
        latencyMs = Number(latency.text)
      } else {
        t.problem(`${at}.latencyMs`, 'expected a whole number of milliseconds')
      }
    }
    const outputs = node.entries.has('outputs') ? this.values(node.entries.get('outputs'), `${at}.outputs`, true) : new Map<string, Value>()
    let confidence: Decimal | undefined
    const confidenceText = t.optionalText(node, 'confidence', at)
    if (confidenceText !== undefined) {
      confidence = Decimal.parse(confidenceText)
      if (confidence === undefined) t.problem(`${at}.confidence`, 'expected a decimal as a string')
    }
    const attributionMethod = t.optionalText(node, 'attributionMethod', at)
    const attributions = this.attributions(node.entries.get('attributions'), `${at}.attributions`)
    if (mode === undefined || status === undefined) return undefined
    if (status !== 'OK' && (outputs.size > 0 || attributions.length > 0 || confidence !== undefined)) {
      t.problem(at, 'a failed model step records no outputs, confidence or attributions')
      return undefined
    }
    return {
      mode,
      id,
      version,
      status,
      ...(detail !== undefined ? { detail } : {}),
      ...(latencyMs !== undefined ? { latencyMs } : {}),
      outputs,
      ...(confidence !== undefined ? { confidence } : {}),
      ...(attributionMethod !== undefined ? { attributionMethod } : {}),
      attributions,
    }
  }

  private attributions(node: JsonNode | undefined, path: string): RankedAttribution[] {
    const t = this.text
    const attributions: RankedAttribution[] = []
    if (node === undefined) return attributions
    if (node.type !== 'array') {
      t.problem(path, 'expected an array of attributions')
      return attributions
    }
    node.items.forEach((item, i) => {
      const at = `${path}[${i}]`
      if (item.type !== 'object') {
        t.problem(at, 'expected an object')
        return
      }
      t.unknownFields(item, ['feature', 'rank', 'direction', 'reasonCode'], at)
      const feature = t.requiredText(item, 'feature', at)
      const rank = item.entries.get('rank')
      if (!rank || rank.type !== 'number' || !rank.integral || BigInt(rank.text) < 1n || BigInt(rank.text) > 2147483647n) {
        t.problem(`${at}.rank`, 'required whole number, 1 or more')
        return
      }
      const direction = this.enumValue(item, 'direction', at, DIRECTIONS)
      const reasonCode = t.optionalText(item, 'reasonCode', at)
      if (direction !== undefined) {
        attributions.push({ feature, rank: Number(rank.text), direction, ...(reasonCode !== undefined ? { reasonCode } : {}) })
      }
    })
    return attributions
  }

  private routing(node: JsonNode | undefined): Routing | undefined {
    const t = this.text
    if (node === undefined) return undefined
    const at = '$.routing'
    if (node.type !== 'object') {
      t.problem(at, 'expected an object')
      return undefined
    }
    t.unknownFields(node, ['kind', 'champion', 'share', 'shadowOf'], at)
    const kind = this.enumValue(node, 'kind', at, KINDS)
    const champion = t.requiredText(node, 'champion', at)
    let share: number | undefined
    const shareNode = node.entries.get('share')
    if (shareNode !== undefined) {
      if (shareNode.type === 'number') share = Number(shareNode.text)
      else t.problem(`${at}.share`, 'expected a number')
    }
    const shadowOf = t.optionalText(node, 'shadowOf', at)
    if (kind === undefined) return undefined
    const invalid =
      (share !== undefined) !== (kind === 'CANARY')
        ? 'share must be present exactly for a canary'
        : share !== undefined && !(share > 0 && share <= 1)
          ? 'share must be above 0 and at most 1'
          : (shadowOf !== undefined) !== (kind === 'SHADOW')
            ? 'shadowOf must be present exactly for a shadow'
            : undefined
    if (invalid !== undefined) {
      t.problem(at, invalid)
      return undefined
    }
    return { kind, champion, ...(share !== undefined ? { share } : {}), ...(shadowOf !== undefined ? { shadowOf } : {}) }
  }

  /** An object of named values, in the forms of their JSON. */
  private values(node: JsonNode | undefined, path: string, required: boolean): Map<string, Value> {
    const values = new Map<string, Value>()
    if (!node || node.type !== 'object') {
      if (required || node !== undefined) this.text.problem(path, 'required object of named values')
      return values
    }
    for (const [name, value] of node.entries) {
      const v = this.value(value, `${path}.${name}`)
      if (v !== undefined) values.set(name, v)
    }
    return values
  }

  private value(node: JsonNode, path: string): Value | undefined {
    switch (node.type) {
      case 'string':
      case 'boolean':
        return node.value
      case 'number':
        return node.integral ? BigInt(node.text) : Decimal.parse(node.text)!
      case 'array': {
        const items: Value[] = []
        node.items.forEach((item, i) => {
          const v = this.value(item, `${path}[${i}]`)
          if (v !== undefined) items.push(v)
        })
        return items
      }
      case 'object': {
        const fields = new Map<string, Value>()
        for (const [k, v] of node.entries) {
          const field = this.value(v, `${path}.${k}`)
          if (field !== undefined) fields.set(k, field)
        }
        return fields
      }
      case 'null':
        this.text.problem(path, 'expected a value; null is not one')
        return undefined
    }
  }

  private date(node: ObjectNode, field: string, path: string): LocalDate | undefined {
    const value = this.text.requiredText(node, field, path)
    if (value === '') return undefined
    const date = /^-?\d{4,}-\d{2}-\d{2}$/.test(value) ? LocalDate.parse(value) : undefined
    if (!date) this.text.problem(`${path}.${field}`, 'expected a date as "yyyy-MM-dd"')
    return date
  }

  private instant(node: ObjectNode, field: string, path: string): Date | undefined {
    const value = this.text.requiredText(node, field, path)
    if (value === '') return undefined
    const m = INSTANT.exec(value)
    const date = m ? new Date(value) : undefined
    if (!date || Number.isNaN(date.getTime())) {
      this.text.problem(`${path}.${field}`, 'expected an instant such as "2026-11-01T02:15:04.218Z"')
      return undefined
    }
    return date
  }

  private enumValue<E extends string>(node: ObjectNode, field: string, path: string, values: readonly E[]): E | undefined {
    const value = this.text.requiredText(node, field, path)
    if (value === '') return undefined
    if ((values as readonly string[]).includes(value)) return value as E
    this.text.problem(`${path}.${field}`, `expected one of ${values.join(', ')}`)
    return undefined
  }
}
