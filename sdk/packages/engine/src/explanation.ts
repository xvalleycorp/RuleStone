// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0

// Reason-code catalogs, as ReasonCodeCatalog and ReasonCodeCatalogJson, and explanations of
// decision records, as Explanation and Explanations: the same explanation, JSON and text, byte for
// byte, for the same record, catalog and locale.

import type { LocalDate } from './date.js'
import { InvalidDecisionError } from './errors.js'
import type { DecisionFlow } from './flow.js'
import { valueToJson, valuesToJson } from './facts.js'
import { JsonSyntaxError, parseJson, writeJson, type JsonNode, type Writable } from './json.js'
import type { Direction, ParamSetVersion, RankedAttribution } from './record.js'
import { TableReader, sha256Hex, sortedMap, tableRef, type DecisionTable } from './table.js'
import type { Value } from './values.js'

/** The conventional file name of a catalog. */
export const REASON_CODES_FILE = 'reason-codes.json'

const CATALOG_FIELDS = ['version', 'description', 'locales', 'codes']
const LOCALE = /^[a-z]{2,3}(-[A-Za-z0-9]{2,8})*$/
const REASON_CODE = /^[A-Z][A-Z0-9_]*$/
const ID = /^[A-Za-z][A-Za-z0-9_.-]*$/
const VERSION_FROM_DIGIT = /^[0-9][A-Za-z0-9_.-]*$/

/** The reviewed messages of reason codes in each locale, as ReasonCodeCatalog: a reason-codes.json file. */
export interface ReasonCodeCatalog {
  readonly version: string
  readonly description?: string
  /** The locales every code has a message in, in file order. */
  readonly locales: readonly string[]
  /** Reason code to locale to message, in file order. */
  readonly codes: ReadonlyMap<string, ReadonlyMap<string, string>>
}

/**
 * Reads a catalog from reason-codes.json text, strictly: unknown and duplicate keys, codes that
 * are not UPPER_SNAKE_CASE, undeclared or missing locales and messages that are blank, padded,
 * multi-line or contain '{' or '}' are errors.
 * @throws InvalidDecisionError listing every problem found
 */
export function parseReasonCodeCatalog(text: string): ReasonCodeCatalog {
  let root: JsonNode | undefined
  try {
    // Like the Java reader, anything after the first JSON value is ignored.
    root = parseJson(text, { failOnTrailingTokens: false })
  } catch (e) {
    if (e instanceof JsonSyntaxError) throw new InvalidDecisionError([`invalid JSON: ${e.message}`])
    throw e
  }
  if (!root || root.type !== 'object') throw new InvalidDecisionError(['$: expected a JSON object'])
  const reader = new TableReader()
  reader.unknownFields(root, CATALOG_FIELDS, '$')
  const version = reader.requiredText(root, 'version', '$')
  if (version !== '' && !ID.test(version) && !VERSION_FROM_DIGIT.test(version)) {
    reader.problem('$.version', "must be letters, digits, '.', '_' or '-'")
  }
  const description = reader.optionalText(root, 'description', '$')
  const locales = readLocales(root.entries.get('locales'), reader)
  const codes = new Map<string, ReadonlyMap<string, string>>()
  const node = root.entries.get('codes')
  if (!node || node.type !== 'object') {
    reader.problem('$.codes', 'required object of reason code to { locale: message }')
  } else {
    for (const [code, entry] of node.entries) {
      const messages = readMessages(code, entry, locales, reader)
      if (messages) codes.set(code, messages)
    }
  }
  if (reader.problems.length > 0) throw new InvalidDecisionError(reader.problems)
  return { version, ...(description !== undefined ? { description } : {}), locales, codes }
}

function readLocales(node: JsonNode | undefined, reader: TableReader): string[] {
  if (!node || node.type !== 'array' || node.items.length === 0) {
    reader.problem('$.locales', 'required non-empty array of language tags, e.g. ["en", "vi"]')
    return []
  }
  const locales: string[] = []
  node.items.forEach((item, i) => {
    const path = `$.locales[${i}]`
    if (item.type !== 'string' || !LOCALE.test(item.value)) reader.problem(path, 'must be a language tag such as en, vi or en-GB')
    else if (locales.includes(item.value)) reader.problem(path, `duplicate locale '${item.value}'`)
    else locales.push(item.value)
  })
  return locales
}

function readMessages(code: string, node: JsonNode, locales: readonly string[], reader: TableReader): Map<string, string> | undefined {
  const path = `$.codes.${code}`
  if (!REASON_CODE.test(code)) reader.problem(path, 'must be UPPER_SNAKE_CASE, e.g. DTI_HIGH')
  if (node.type !== 'object') {
    reader.problem(path, 'expected an object of locale to message')
    return undefined
  }
  const messages = new Map<string, string>()
  for (const [locale, value] of node.entries) {
    const at = `${path}.${locale}`
    if (!locales.includes(locale)) {
      reader.problem(at, 'locale not declared in $.locales')
      continue
    }
    if (value.type !== 'string') {
      reader.problem(at, 'expected a string')
      continue
    }
    const problem = messageProblem(value.value)
    if (problem !== undefined) {
      reader.problem(at, problem)
      continue
    }
    messages.set(locale, value.value)
  }
  for (const locale of locales) {
    if (!node.entries.has(locale)) reader.problem(`${path}.${locale}`, `required: the message in ${locale}`)
  }
  return messages
}

function messageProblem(message: string): string | undefined {
  if ([...message].every((c) => c === ' ')) return 'must not be blank'
  if (message.startsWith(' ') || message.endsWith(' ')) return 'must not start or end with spaces'
  for (let i = 0; i < message.length; i++) {
    const c = message.charCodeAt(i)
    if (c < 0x20 || c === 0x7f) return 'must be one line of text, without control characters'
  }
  if (message.includes('{') || message.includes('}')) return "must not contain '{' or '}', which are reserved for placeholders"
  return undefined
}

/**
 * The canonical form, as ReasonCodeCatalogJson.write: compact JSON with sorted keys, the locales in
 * their declared order. The content hash is computed over it.
 */
export function writeReasonCodeCatalog(catalog: ReasonCodeCatalog): string {
  const json = new Map<string, Writable>()
  json.set('version', catalog.version)
  if (catalog.description !== undefined) json.set('description', catalog.description)
  json.set('locales', [...catalog.locales])
  json.set('codes', sortedMap([...catalog.codes].map(([code, messages]) => [code, sortedMap<Writable>(messages)] as [string, Writable])))
  return writeJson(sortedMap(json))
}

/** The catalog's exact version: <version>@sha256:<hash of the canonical form>. */
export async function reasonCodeCatalogVersionOf(catalog: ReasonCodeCatalog): Promise<string> {
  return `${catalog.version}@sha256:${await sha256Hex(writeReasonCodeCatalog(catalog))}`
}

/** @internal every reason code of the tables without a message in the catalog, as the Java engine reports them */
export function missingReasonCodes(tables: Iterable<DecisionTable>, catalog: ReasonCodeCatalog, flows: Iterable<DecisionFlow> = []): string[] {
  const problems: string[] = []
  const missing = (code: string, at: string): void => {
    if (!catalog.codes.has(code)) problems.push(`${at}: reason code '${code}' is not in the reason-code catalog`)
  }
  for (const table of tables) {
    for (const rule of table.rules) {
      if (rule.reasonCode !== undefined) missing(rule.reasonCode, `${tableRef(table)} rules[${rule.id}].reasonCode`)
    }
  }
  for (const flow of flows) {
    for (const [id, node] of flow.nodes) {
      if (node.type !== 'model') continue
      const at = `${flow.id}@${flow.version} nodes.${id}`
      for (const [feature, code] of node.reasonCodes) missing(code, `${at}.reasonCodes.${feature}`)
      missing(node.onFailure.reasonCode, `${at}.onFailure.reasonCode`)
    }
  }
  return problems
}

/** The problem reported when a catalog is required but none is given. */
export const MISSING_CATALOG = 'reason-code catalog: required, but none is registered'

// --- Explanations ---------------------------------------------------------------------------

export type { Direction, RankedAttribution }

/**
 * A decision record as an explanation reads it: the browser engine's DecisionRecord, or a record of
 * the Java engine read from DecisionRecordJson, whose path may have flow and model steps.
 */
export interface ExplainableRecord {
  readonly recordId: string
  readonly correlationId?: string
  readonly decision: string
  readonly version?: string
  readonly params?: readonly ParamSetVersion[]
  readonly businessDate: LocalDate
  readonly status: 'OK' | 'ERROR' | 'TIMEOUT'
  readonly output: ReadonlyMap<string, Value>
  readonly reasonCodes: readonly { readonly code: string; readonly source: string }[]
  readonly path: readonly {
    readonly node: string
    readonly type: string
    readonly decision?: string
    readonly version?: string
    readonly matchedRules?: readonly string[]
    readonly taken?: string
    readonly model?: { readonly id: string; readonly version: string; readonly status: string; readonly attributions?: readonly RankedAttribution[] }
  }[]
}

/** One reason for the outcome, with the catalog's message, absent only when the catalog has no entry for the code. */
export interface ExplanationReason {
  readonly code: string
  readonly source: string
  readonly message?: string
}

/** One node visited, without values. */
export interface ExplanationStep {
  readonly node: string
  readonly type: string
  readonly decision?: string
  readonly version?: string
  readonly matchedRules: readonly string[]
  readonly taken?: string
  /** For a model node: the model, its status and its attributions by rank; no outputs or contributions. */
  readonly model?: { readonly id: string; readonly version: string; readonly status: string; readonly attributions: readonly RankedAttribution[] }
}

/** A decision record explained in one language, as Explanation. It never contains input values. */
export interface Explanation {
  readonly recordId: string
  readonly correlationId?: string
  readonly decision: string
  readonly version?: string
  readonly businessDate: LocalDate
  readonly status: 'OK' | 'ERROR' | 'TIMEOUT'
  readonly locale: string
  /** The catalog's exact version, e.g. 3@sha256:… */
  readonly catalog: string
  readonly outcome: ReadonlyMap<string, Value>
  readonly reasons: readonly ExplanationReason[]
  readonly steps: readonly ExplanationStep[]
  readonly params: readonly ParamSetVersion[]
}

/**
 * Explains a record in one of the catalog's locales, with the catalog's exact version as computed by
 * reasonCodeCatalogVersionOf. Pure: no clock, no randomness and no runtime locale.
 * @throws RangeError if the catalog has no such locale
 */
export function explainRecord(record: ExplainableRecord, catalog: ReasonCodeCatalog, catalogVersion: string, locale: string): Explanation {
  if (!catalog.locales.includes(locale)) {
    throw new RangeError(`locale '${locale}' is not in the reason-code catalog, which has ${catalog.locales.join(', ')}`)
  }
  return {
    recordId: record.recordId,
    ...(record.correlationId !== undefined ? { correlationId: record.correlationId } : {}),
    decision: record.decision,
    ...(record.version !== undefined ? { version: record.version } : {}),
    businessDate: record.businessDate,
    status: record.status,
    locale,
    catalog: catalogVersion,
    outcome: record.output,
    reasons: record.reasonCodes.map((r) => {
      const message = catalog.codes.get(r.code)?.get(locale)
      return { code: r.code, source: r.source, ...(message !== undefined ? { message } : {}) }
    }),
    steps: record.path.map((s) => ({
      node: s.node,
      type: s.type,
      ...(s.decision !== undefined ? { decision: s.decision } : {}),
      ...(s.version !== undefined ? { version: s.version } : {}),
      matchedRules: s.matchedRules ?? [],
      ...(s.taken !== undefined ? { taken: s.taken } : {}),
      ...(s.model ? { model: { id: s.model.id, version: s.model.version, status: s.model.status, attributions: s.model.attributions ?? [] } } : {}),
    })),
    params: record.params ?? [],
  }
}

/** Explains a record with a catalog, computing the catalog's exact version. @throws RangeError for an unknown locale */
export async function explainDecision(record: ExplainableRecord, catalog: ReasonCodeCatalog, locale: string): Promise<Explanation> {
  return explainRecord(record, catalog, await reasonCodeCatalogVersionOf(catalog), locale)
}

/** The explanation as a JSON object with Explanations.toJson's field order. */
export function explanationToJson(e: Explanation): Map<string, Writable> {
  const json = new Map<string, Writable>()
  json.set('recordId', e.recordId)
  if (e.correlationId !== undefined) json.set('correlationId', e.correlationId)
  json.set('decision', e.decision)
  if (e.version !== undefined) json.set('version', e.version)
  json.set('businessDate', e.businessDate.toString())
  json.set('status', e.status)
  json.set('locale', e.locale)
  json.set('catalog', e.catalog)
  json.set('outcome', valuesToJson(e.outcome))
  json.set(
    'reasons',
    e.reasons.map((r) => {
      const reason = new Map<string, Writable>([['code', r.code], ['source', r.source]])
      if (r.message !== undefined) reason.set('message', r.message)
      return reason
    }),
  )
  json.set(
    'steps',
    e.steps.map((s) => {
      const step = new Map<string, Writable>([['node', s.node], ['type', s.type]])
      if (s.decision !== undefined) step.set('decision', s.decision)
      if (s.version !== undefined) step.set('version', s.version)
      if (s.type === 'table' || s.matchedRules.length > 0) step.set('matchedRules', [...s.matchedRules])
      if (s.taken !== undefined) step.set('taken', s.taken)
      if (s.model) {
        step.set(
          'model',
          new Map<string, Writable>([
            ['id', s.model.id],
            ['version', s.model.version],
            ['status', s.model.status],
            [
              'attributions',
              s.model.attributions.map((a) => {
                const attribution = new Map<string, Writable>([
                  ['feature', a.feature],
                  ['rank', BigInt(a.rank)],
                  ['direction', a.direction],
                ])
                if (a.reasonCode !== undefined) attribution.set('reasonCode', a.reasonCode)
                return attribution
              }),
            ],
          ]),
        )
      }
      return step
    }),
  )
  if (e.params.length > 0) json.set('params', e.params.map((p) => new Map<string, Writable>([['set', p.set], ['version', p.version]])))
  return json
}

/** The explanation as one line of compact JSON, as Explanations.write. */
export function writeExplanation(e: Explanation): string {
  return writeJson(explanationToJson(e))
}

interface Labels {
  readonly decision: string
  readonly businessDate: string
  readonly outcome: string
  readonly noDecision: string
  readonly noOutputs: string
  readonly reasons: string
  readonly none: string
  readonly noMessage: string
  readonly decidedBy: string
  readonly matched: string
  readonly noRuleMatched: string
  readonly took: string
  readonly INCREASES_RISK: string
  readonly DECREASES_RISK: string
  readonly NONE: string
  readonly params: string
  readonly catalog: string
}

const LABELS: Readonly<Record<string, Labels>> = {
  en: {
    decision: 'Decision', businessDate: 'Business date', outcome: 'Outcome', noDecision: 'no decision', noOutputs: 'no outputs',
    reasons: 'Reasons', none: 'none', noMessage: '(no message in the catalog)', decidedBy: 'Decided by', matched: 'matched',
    noRuleMatched: 'no rule matched', took: 'took', INCREASES_RISK: 'increases risk', DECREASES_RISK: 'decreases risk',
    NONE: 'no effect', params: 'Parameter sets', catalog: 'Reason-code catalog',
  },
  vi: {
    decision: 'Quyết định', businessDate: 'Ngày nghiệp vụ', outcome: 'Kết quả', noDecision: 'chưa có quyết định',
    noOutputs: 'không có đầu ra', reasons: 'Lý do', none: 'không có', noMessage: '(danh mục chưa có nội dung cho mã này)',
    decidedBy: 'Căn cứ quyết định', matched: 'khớp', noRuleMatched: 'không có luật nào khớp', took: 'chọn',
    INCREASES_RISK: 'làm tăng rủi ro', DECREASES_RISK: 'làm giảm rủi ro', NONE: 'không ảnh hưởng', params: 'Bộ tham số',
    catalog: 'Danh mục mã lý do',
  },
}

/**
 * The explanation as plain text, as Explanations.text: one item per line, ending with a line break,
 * with English or Vietnamese headings (English for other locales).
 */
export function explanationText(e: Explanation): string {
  const l = LABELS[e.locale.split('-')[0]!] ?? LABELS['en']!
  const lines: string[] = []
  lines.push(`${l.decision}: ${e.decision}${e.version !== undefined ? ` ${e.version}` : ''}`)
  lines.push(`${l.businessDate}: ${e.businessDate.toString()}`)
  let outcome: string
  if (e.status !== 'OK') outcome = `${l.noDecision} (${e.status})`
  else if (e.outcome.size === 0) outcome = l.noOutputs
  else outcome = [...e.outcome].map(([name, value]) => `${name} = ${writeJson(valueToJson(value, name))}`).join(', ')
  lines.push(`${l.outcome}: ${outcome}`)
  if (e.reasons.length === 0) lines.push(`${l.reasons}: ${l.none}`)
  else {
    lines.push(`${l.reasons}:`)
    e.reasons.forEach((r, i) => lines.push(`  ${i + 1}. ${r.message ?? l.noMessage} (${r.code}, ${r.source})`))
  }
  lines.push(`${l.decidedBy}:`)
  for (const s of e.steps) {
    let line = `  - ${s.node}: ${s.type}`
    if (s.decision !== undefined) line += ` ${s.decision}`
    if (s.version !== undefined) line += ` ${s.version}`
    if (s.type === 'table') line += `; ${s.matchedRules.length === 0 ? l.noRuleMatched : `${l.matched} ${s.matchedRules.join(', ')}`}`
    if (s.taken !== undefined) line += `; ${l.took} ${s.taken}`
    if (s.model) line += ` ${s.model.id} ${s.model.version}; ${s.model.status}`
    lines.push(line)
    for (const a of s.model?.attributions ?? []) {
      lines.push(`      ${a.rank}. ${a.feature}: ${l[a.direction]}${a.reasonCode !== undefined ? ` (${a.reasonCode})` : ''}`)
    }
  }
  if (e.params.length > 0) lines.push(`${l.params}: ${e.params.map((p) => `${p.set} ${p.version}`).join(', ')}`)
  lines.push(`${l.catalog}: ${e.catalog}`)
  return lines.join('\n') + '\n'
}
