// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0

// Machine-learning model results for the model nodes of decision flows, as ModelResult,
// SuppliedModel, ModelCall and ModelInvoker, and the JSON form of supplied results, as
// FactsJson.readModels.

import { Decimal, javaIsBlank } from './decimal.js'
import { InvalidFactsError } from './errors.js'
import { JsonSyntaxError, parseJson, toJsonNode, type JsonNode } from './json.js'
import type { Value } from './values.js'

/** How much one feature moved a model's result; positive increases risk. */
export interface Attribution {
  readonly feature: string
  readonly contribution: Decimal
}

/**
 * A model's answer, as ModelResult: the model and version that answered, its outputs by name, and
 * optionally a confidence and attributions. Outputs are read with the types the node declares,
 * through their JSON forms: a decimal can be a Decimal, a bigint or a string such as "0.05"; money a
 * Money or "1500000 VND"; a date a LocalDate or "2026-11-01". A JavaScript number that is not a
 * safe integer breaks the contract, as a double does in Java.
 */
export interface ModelResult {
  readonly modelId: string
  readonly modelVersion: string
  readonly outputs: ReadonlyMap<string, unknown> | { readonly [name: string]: unknown }
  readonly confidence?: Decimal
  readonly attributionMethod?: string
  readonly attributions?: readonly Attribution[]
}

/** A failure the caller reports instead of a result. */
export interface ModelFailure {
  readonly failure: 'TIMEOUT' | 'ERROR'
}

/** What a caller passes for a model node in supplied mode, as SuppliedModel: a result or a failure. */
export type SuppliedModel = ModelResult | ModelFailure

/** Whether a supplied model is a failure. */
export function isModelFailure(model: SuppliedModel): model is ModelFailure {
  return (model as ModelFailure).failure !== undefined
}

/**
 * One call of a model, as ModelCall: the model id and pinned version, the declared features as
 * typed values (exact Decimals, never floating point), the time available and the request's
 * correlation id.
 */
export interface ModelCall {
  readonly modelId: string
  readonly modelVersion: string
  /** Feature name to value, in the node's order. */
  readonly features: ReadonlyMap<string, Value>
  /** The time available, in milliseconds: the node's timeoutMs, or less if the evaluation's timeout leaves less. */
  readonly timeoutMs: number
  readonly correlationId?: string
  /**
   * Aborted when the time available has passed, e.g. for fetch. The engine never interrupts an
   * invoker: it waits for its answer, and treats a late one as a TIMEOUT.
   */
  readonly signal?: AbortSignal
}

/**
 * Calls a model, as ModelInvoker. The engine waits for the answer; one that comes after the time
 * available is a TIMEOUT, and a thrown error or a rejected promise is an ERROR, recorded by the
 * error's name only, never its message. There are no retries.
 */
export type ModelInvoker = (call: ModelCall) => ModelResult | undefined | null | Promise<ModelResult | undefined | null>

const RESULT_FIELDS = ['modelId', 'modelVersion', 'outputs', 'confidence', 'attributionMethod', 'attributions']
const ATTRIBUTION_FIELDS = ['feature', 'contribution']
const DECIMAL_TEXT = /^-?[0-9]+(\.[0-9]+)?$/

/**
 * Reads the model results a caller supplies for the model nodes of a flow, as FactsJson.readModels:
 * a JSON object of node id to a result, { "modelId", "modelVersion", "outputs", "confidence",
 * "attributionMethod", "attributions" }, or to a failure, { "failure": "TIMEOUT" } or "ERROR".
 * @throws InvalidFactsError listing every problem, located by node id, never quoting values
 */
export function readModels(json: string | JsonNode | unknown): Map<string, SuppliedModel> {
  let node: JsonNode
  if (typeof json === 'string') {
    let parsed: JsonNode | undefined
    try {
      parsed = parseJson(json, { failOnTrailingTokens: true })
    } catch (e) {
      if (e instanceof JsonSyntaxError) throw new InvalidFactsError([`$: invalid JSON at line ${e.line}, column ${e.column}`])
      throw e
    }
    if (!parsed) throw new InvalidFactsError(['$: expected an object of model node ids to results'])
    node = parsed
  } else {
    node = toJsonNode(json)
  }
  const problems: string[] = []
  const models = new Map<string, SuppliedModel>()
  if (node.type !== 'object') problems.push('$: expected an object of model node ids to results')
  else {
    for (const [id, value] of node.entries) {
      const model = readModel(value, id, problems)
      if (model) models.set(id, model)
    }
  }
  if (problems.length > 0) throw new InvalidFactsError(problems)
  return models
}

function readModel(node: JsonNode, path: string, problems: string[]): SuppliedModel | undefined {
  const problem = (at: string, message: string): void => {
    problems.push(`${at}: ${message}`)
  }
  if (node.type !== 'object') {
    problem(path, 'expected an object with "modelId", "modelVersion" and "outputs", or with "failure"')
    return undefined
  }
  const before = problems.length
  const failure = node.entries.get('failure')
  if (failure !== undefined) {
    for (const name of node.entries.keys()) if (name !== 'failure') problem(`${path}.${name}`, 'not allowed with "failure"')
    if (failure.type !== 'string' || (failure.value !== 'TIMEOUT' && failure.value !== 'ERROR')) {
      problem(`${path}.failure`, 'expected "TIMEOUT" or "ERROR"')
    }
    return problems.length > before ? undefined : { failure: (failure as { value: 'TIMEOUT' | 'ERROR' }).value }
  }
  for (const name of node.entries.keys()) {
    if (!RESULT_FIELDS.includes(name)) {
      problem(`${path}.${name}`, 'unknown field (allowed: attributionMethod, attributions, confidence, failure, modelId, modelVersion, outputs)')
    }
  }
  const text = (on: JsonNode & { type: 'object' }, field: string, at: string): string | undefined => {
    const value = on.entries.get(field)
    if (!value || value.type !== 'string' || javaIsBlank(value.value)) {
      problem(`${at}.${field}`, 'required non-empty string')
      return undefined
    }
    return value.value
  }
  const modelId = text(node, 'modelId', path)
  const modelVersion = text(node, 'modelVersion', path)
  const outputs = new Map<string, unknown>()
  const outputsNode = node.entries.get('outputs')
  if (!outputsNode || outputsNode.type !== 'object') problem(`${path}.outputs`, 'required object of output names to values')
  else {
    for (const [name, value] of outputsNode.entries) {
      const v = untyped(value, `${path}.outputs.${name}`, problem)
      if (v !== undefined) outputs.set(name, v)
    }
  }
  let confidence: Decimal | undefined
  const confidenceNode = node.entries.get('confidence')
  if (confidenceNode !== undefined) confidence = decimal(confidenceNode, `${path}.confidence`, problem)
  let attributionMethod: string | undefined
  if (node.entries.has('attributionMethod')) attributionMethod = text(node, 'attributionMethod', path)
  const attributions: Attribution[] = []
  const attributionsNode = node.entries.get('attributions')
  if (attributionsNode !== undefined) {
    if (attributionsNode.type !== 'array') problem(`${path}.attributions`, 'expected an array of { "feature", "contribution" }')
    else {
      attributionsNode.items.forEach((attribution, i) => {
        const at = `${path}.attributions[${i}]`
        if (attribution.type !== 'object') {
          problem(at, 'expected an object { "feature", "contribution" }')
          return
        }
        for (const name of attribution.entries.keys()) {
          if (!ATTRIBUTION_FIELDS.includes(name)) problem(`${at}.${name}`, 'unknown field (allowed: contribution, feature)')
        }
        const feature = text(attribution, 'feature', at)
        const contribution = attribution.entries.get('contribution')
        const value = contribution === undefined || contribution.type === 'null' ? undefined : decimal(contribution, `${at}.contribution`, problem)
        if (contribution === undefined || contribution.type === 'null') problem(`${at}.contribution`, 'required decimal')
        if (feature !== undefined && value !== undefined) attributions.push({ feature, contribution: value })
      })
    }
  }
  if (problems.length > before) return undefined
  return {
    modelId: modelId!,
    modelVersion: modelVersion!,
    outputs,
    ...(confidence !== undefined ? { confidence } : {}),
    ...(attributionMethod !== undefined ? { attributionMethod } : {}),
    attributions,
  }
}

/**
 * A JSON value read without a type, exactly: strings and booleans as themselves, numbers as their
 * JsonNode (which keeps the text), arrays as arrays; the node's declared types read them later.
 */
function untyped(node: JsonNode, path: string, problem: (at: string, message: string) => void): unknown {
  switch (node.type) {
    case 'string':
    case 'boolean':
      return node.value
    case 'number':
      if (node.floating) {
        problem(path, 'floating-point number; read the JSON with USE_BIG_DECIMAL_FOR_FLOATS or write the decimal as a string, e.g. "0.0980"')
        return undefined
      }
      return node
    case 'array': {
      const items: unknown[] = []
      node.items.forEach((item, i) => {
        const v = untyped(item, `${path}[${i}]`, problem)
        if (v !== undefined) items.push(v)
      })
      return items
    }
    case 'null':
      problem(path, 'null is not a value')
      return undefined
    case 'object':
      problem(path, 'expected a bool, number, string or array: model outputs have no fields')
      return undefined
  }
}

function decimal(node: JsonNode, path: string, problem: (at: string, message: string) => void): Decimal | undefined {
  if (node.type === 'number' && !node.floating) return Decimal.parse(node.text)
  if (node.type === 'number') {
    problem(path, 'floating-point number; read the JSON with USE_BIG_DECIMAL_FOR_FLOATS or write the decimal as a string, e.g. "0.0980"')
    return undefined
  }
  if (node.type === 'string' && DECIMAL_TEXT.test(node.value)) return Decimal.parse(node.value)
  problem(path, 'expected a decimal as a number or a string in plain notation, e.g. "0.0980"')
  return undefined
}
