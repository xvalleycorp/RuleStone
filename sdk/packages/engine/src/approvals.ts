// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0

// Approval statements and their verification, as the Java engine's ApprovalStatement,
// ApprovalStatementJson, ApprovalVerifier and Approvals: JWS compact serialization with EdDSA
// (Ed25519) or ES256, keys only from the verifier's configuration, a canonical payload, and the
// build-time checks of requireApproved, with the same problems in the same order. createEngine
// only calls an ApprovalVerifier it is given, so this module stays out of bundles that use none.

import { LocalDate } from './date.js'
import { javaIsBlank } from './decimal.js'
import { InvalidDecisionError } from './errors.js'
import { JsonSyntaxError, parseJson, writeJson, type JsonNode, type Writable } from './json.js'
import { TableReader, sortedMap } from './table.js'

/** The type field of an approval statement. */
export const APPROVAL_STATEMENT_TYPE = 'rulestone.approval'
/** The typ header of a signed approval statement. */
export const APPROVAL_JWS_TYPE = 'rulestone-approval+jws'
/** The suffix of signed approval statement files, e.g. CHG-2026-0042.approval.jws. */
export const APPROVAL_FILE_SUFFIX = '.approval.jws'
/** The id under which subjects name an engine's reason-code catalog, which has no id of its own. */
export const APPROVAL_CATALOG_ID = 'reason-codes'

/** EdDSA with Ed25519, or ECDSA with P-256 and SHA-256. */
export type ApprovalAlgorithm = 'EdDSA' | 'ES256'
/** The kinds of artifact an approval covers. */
export type ArtifactKind = 'table' | 'flow' | 'params' | 'catalog'

/** One approved artifact version. */
export interface ApprovalSubject {
  readonly kind: ArtifactKind
  /** Its id; for a catalog, APPROVAL_CATALOG_ID. */
  readonly id: string
  /** Its exact version, <label>@sha256:<hex>. */
  readonly version: string
}

export interface Approver {
  readonly principal: string
  readonly role?: string
  /** When they approved, a UTC instant such as 2026-11-20T03:12:00Z. */
  readonly at: string
}

/** A statement that the exact content of some artifacts was approved, as ApprovalStatement. */
export interface ApprovalStatement {
  readonly approvalId: string
  readonly changeSet: string
  readonly subjects: readonly ApprovalSubject[]
  readonly makers: readonly string[]
  readonly approvers: readonly Approver[]
  readonly policy?: string
  readonly emergency: boolean
  readonly postApprovalDue?: string
  readonly issuer: string
  readonly issuedAt: string
  readonly logEntry?: string
}

/** A signed statement, and the name problems give it: its file name, for example. */
export interface NamedApproval {
  readonly name: string
  readonly jws: string
}

const KINDS: readonly ArtifactKind[] = ['table', 'flow', 'params', 'catalog']
const SUPPORTED: readonly ApprovalAlgorithm[] = ['EdDSA', 'ES256']
const STATEMENT_FIELDS = [
  'type', 'approvalId', 'changeSet', 'subjects', 'makers', 'approvers', 'policy', 'emergency', 'postApprovalDue', 'issuer',
  'issuedAt', 'logEntry',
]
const SUBJECT_FIELDS = ['kind', 'id', 'version']
const APPROVER_FIELDS = ['principal', 'role', 'at']
const HEADER_FIELDS = ['alg', 'kid', 'typ']
const EXACT_VERSION = /^[^@\s]+@sha256:[0-9a-f]{64}$/
const HASH = /^sha256:[0-9a-f]{64}$/
const INSTANT = /^(\d{4})-(\d{2})-(\d{2})T([01]\d|2[0-3]):[0-5]\d:[0-5]\d(\.\d{1,9})?Z$/
const BASE64URL = /^[A-Za-z0-9_-]*$/
const B64 = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_'

type JsonObject = JsonNode & { type: 'object' }

/**
 * Reads an approval statement strictly, as ApprovalStatementJson.read: every problem is reported
 * with its location, all together.
 * @throws InvalidDecisionError listing every problem
 */
export function parseApprovalStatement(text: string): ApprovalStatement {
  let root: JsonNode | undefined
  try {
    root = parseJson(text, { failOnTrailingTokens: false })
  } catch (e) {
    if (e instanceof JsonSyntaxError) throw new InvalidDecisionError([`invalid JSON: ${e.message}`])
    throw e
  }
  return readStatement(root)
}

/**
 * Writes the canonical form, as ApprovalStatementJson.write: compact, keys sorted, subjects, makers
 * and approvers in their order, absent optionals omitted, emergency always written.
 */
export function writeApprovalStatement(statement: ApprovalStatement): string {
  const json = new Map<string, Writable>([
    ['type', APPROVAL_STATEMENT_TYPE],
    ['approvalId', statement.approvalId],
    ['changeSet', statement.changeSet],
    ['subjects', statement.subjects.map((s) => sortedMap<Writable>([['kind', s.kind], ['id', s.id], ['version', s.version]]))],
    ['makers', [...statement.makers]],
    [
      'approvers',
      statement.approvers.map((a) =>
        sortedMap<Writable>([['principal', a.principal], ...(a.role !== undefined ? [['role', a.role] as [string, Writable]] : []), ['at', a.at]]),
      ),
    ],
    ['emergency', statement.emergency],
    ['issuer', statement.issuer],
    ['issuedAt', statement.issuedAt],
  ])
  if (statement.policy !== undefined) json.set('policy', statement.policy)
  if (statement.postApprovalDue !== undefined) json.set('postApprovalDue', statement.postApprovalDue)
  if (statement.logEntry !== undefined) json.set('logEntry', statement.logEntry)
  return writeJson(sortedMap(json))
}

function readStatement(root: JsonNode | undefined): ApprovalStatement {
  if (!root || root.type !== 'object') throw new InvalidDecisionError(['$: expected a JSON object'])
  const reader = new TableReader()
  reader.unknownFields(root, STATEMENT_FIELDS, '$')
  const type = reader.requiredText(root, 'type', '$')
  if (type !== '' && type !== APPROVAL_STATEMENT_TYPE) reader.problem('$.type', `must be '${APPROVAL_STATEMENT_TYPE}'`)
  const approvalId = reader.requiredText(root, 'approvalId', '$')
  const changeSet = reader.requiredText(root, 'changeSet', '$')
  const subjects = readSubjects(root, reader)
  const makers = readMakers(root, reader)
  const approvers = readApprovers(root, reader)
  const policy = reader.optionalText(root, 'policy', '$')
  const emergency = root.entries.get('emergency')
  if (emergency?.type !== 'boolean') reader.problem('$.emergency', 'required boolean')
  const postApprovalDue = reader.optionalText(root, 'postApprovalDue', '$')
  if (postApprovalDue !== undefined) checkInstant(postApprovalDue, '$.postApprovalDue', reader)
  const issuer = reader.requiredText(root, 'issuer', '$')
  const issuedAt = reader.requiredText(root, 'issuedAt', '$')
  if (issuedAt !== '') checkInstant(issuedAt, '$.issuedAt', reader)
  const logEntry = reader.optionalText(root, 'logEntry', '$')
  if (logEntry !== undefined && !HASH.test(logEntry)) reader.problem('$.logEntry', 'expected sha256:<64 hex digits>')
  if (reader.problems.length > 0) throw new InvalidDecisionError(reader.problems)
  return {
    approvalId,
    changeSet,
    subjects,
    makers,
    approvers,
    ...(policy !== undefined ? { policy } : {}),
    emergency: (emergency as { value: boolean }).value,
    ...(postApprovalDue !== undefined ? { postApprovalDue } : {}),
    issuer,
    issuedAt,
    ...(logEntry !== undefined ? { logEntry } : {}),
  }
}

function readSubjects(root: JsonObject, reader: TableReader): ApprovalSubject[] {
  const node = root.entries.get('subjects')
  const subjects: ApprovalSubject[] = []
  if (!node || node.type !== 'array' || node.items.length === 0) {
    reader.problem('$.subjects', 'required non-empty array of { kind, id, version }')
    return subjects
  }
  node.items.forEach((s, i) => {
    const path = `$.subjects[${i}]`
    if (s.type !== 'object') {
      reader.problem(path, 'expected an object')
      return
    }
    reader.unknownFields(s, SUBJECT_FIELDS, path)
    const kind = reader.requiredText(s, 'kind', path)
    const known = (KINDS as readonly string[]).includes(kind)
    if (kind !== '' && !known) reader.problem(`${path}.kind`, `unknown kind '${kind}' (expected table, flow, params or catalog)`)
    const id = reader.requiredText(s, 'id', path)
    const version = reader.requiredText(s, 'version', path)
    if (version !== '' && !EXACT_VERSION.test(version)) {
      reader.problem(`${path}.version`, 'expected an exact version, <label>@sha256:<64 hex digits>')
    }
    if (known) subjects.push({ kind: kind as ArtifactKind, id, version })
  })
  return subjects
}

function readMakers(root: JsonObject, reader: TableReader): string[] {
  const node = root.entries.get('makers')
  const makers: string[] = []
  if (!node || node.type !== 'array' || node.items.length === 0) {
    reader.problem('$.makers', 'required non-empty array of principal ids')
    return makers
  }
  node.items.forEach((m, i) => {
    if (m.type !== 'string' || javaIsBlank(m.value)) reader.problem(`$.makers[${i}]`, 'required non-empty string')
    else makers.push(m.value)
  })
  return makers
}

function readApprovers(root: JsonObject, reader: TableReader): Approver[] {
  const node = root.entries.get('approvers')
  const approvers: Approver[] = []
  if (!node || node.type !== 'array' || node.items.length === 0) {
    reader.problem('$.approvers', 'required non-empty array of { principal, role, at }')
    return approvers
  }
  const principals = new Set<string>()
  node.items.forEach((a, i) => {
    const path = `$.approvers[${i}]`
    if (a.type !== 'object') {
      reader.problem(path, 'expected an object')
      return
    }
    reader.unknownFields(a, APPROVER_FIELDS, path)
    const principal = reader.requiredText(a, 'principal', path)
    if (principal !== '') {
      if (principals.has(principal)) reader.problem(`${path}.principal`, `approver ${principal} is named more than once`)
      principals.add(principal)
    }
    const role = reader.optionalText(a, 'role', path)
    const at = reader.requiredText(a, 'at', path)
    if (at !== '') checkInstant(at, `${path}.at`, reader)
    approvers.push({ principal, ...(role !== undefined ? { role } : {}), at })
  })
  return approvers
}

function checkInstant(text: string, path: string, reader: TableReader): void {
  const m = INSTANT.exec(text)
  if (!m || !LocalDate.ofValid(Number(m[1]), Number(m[2]), Number(m[3]))) {
    reader.problem(path, 'expected a UTC instant such as 2026-11-20T07:41:02Z')
  }
}

export interface ApprovalVerifierOptions {
  /**
   * The keys statements may be signed with, by the kid their header names: Ed25519 public keys for
   * EdDSA, or ECDSA P-256 public keys for ES256, imported with the 'verify' usage. Keep retired keys
   * configured: a statement stays valid for the content it covers.
   */
  readonly trustedKeys: ReadonlyMap<string, CryptoKey> | { readonly [kid: string]: CryptoKey }
  /** The algorithms accepted; default both, EdDSA and ES256. */
  readonly allowedAlgorithms?: readonly ApprovalAlgorithm[]
  /** The least number of distinct approvers every statement must name; default 1. */
  readonly minApprovers?: number
}

/** A registered artifact at the exact version the engine computed from its content. @internal */
export interface ApprovalArtifact {
  readonly kind: ArtifactKind
  readonly id: string
  readonly label: string
  readonly exactVersion: string
}

interface Checked {
  readonly statement?: ApprovalStatement
  readonly problems: readonly string[]
}

/**
 * Verifies signed approval statements, as the Java ApprovalVerifier: JWS compact serialization whose
 * header is exactly alg, kid and typ 'rulestone-approval+jws', with an allowed algorithm and the kid
 * of a trusted key, and whose payload is a statement in canonical form. Pass it to createEngine as
 * requireApproved.
 */
export class ApprovalVerifier {
  readonly minApprovers: number
  private readonly keys: ReadonlyMap<string, { readonly key: CryptoKey; readonly algorithm: ApprovalAlgorithm }>
  private readonly allowed: readonly ApprovalAlgorithm[]

  /**
   * @throws TypeError if a key is not an Ed25519 or P-256 ECDSA public key usable to verify
   * @throws RangeError if no key is trusted, an algorithm is not supported, or minApprovers is below 1
   */
  constructor(options: ApprovalVerifierOptions) {
    const keys = new Map<string, { key: CryptoKey; algorithm: ApprovalAlgorithm }>()
    const entries = options.trustedKeys instanceof Map ? [...options.trustedKeys] : Object.entries(options.trustedKeys)
    for (const [kid, key] of entries) {
      if (kid === '') throw new RangeError('kid must not be empty')
      const algorithm = algorithmOf(key)
      if (!algorithm) throw new TypeError(`key '${kid}' must be an Ed25519 or a P-256 EC public key`)
      if (!key.usages.includes('verify')) throw new TypeError(`key '${kid}' must be imported with the 'verify' usage`)
      keys.set(kid, { key, algorithm })
    }
    if (keys.size === 0) throw new RangeError('trust at least one key')
    const allowed = options.allowedAlgorithms ?? SUPPORTED
    for (const algorithm of allowed) {
      if (!SUPPORTED.includes(algorithm)) throw new RangeError(`algorithm '${algorithm}' is not supported (supported: EdDSA, ES256)`)
    }
    if (allowed.length === 0) throw new RangeError('allow at least one algorithm')
    const minApprovers = options.minApprovers ?? 1
    if (!Number.isInteger(minApprovers) || minApprovers < 1) throw new RangeError('minApprovers must be at least 1')
    this.keys = keys
    this.allowed = SUPPORTED.filter((a) => allowed.includes(a))
    this.minApprovers = minApprovers
  }

  /**
   * Verifies one signed statement: its header, its signature with a trusted key, and its payload.
   * Four-eyes and minApprovers are checked per artifact by createEngine, not here.
   * @throws InvalidDecisionError listing the problems
   */
  async verify(jws: string): Promise<ApprovalStatement> {
    const checked = await this.check(jws)
    if (!checked.statement) throw new InvalidDecisionError(checked.problems)
    return checked.statement
  }

  /** @internal */
  async check(jws: string): Promise<Checked> {
    const parts = jws.replace(/^[ \t\r\n]+|[ \t\r\n]+$/g, '').split('.')
    if (parts.length !== 3 || parts[0] === '' || parts[1] === '' || !parts.every((p) => BASE64URL.test(p) && p.length % 4 !== 1)) {
      return failed("not a JWS compact serialization (three base64url parts separated by '.')")
    }
    const header = json(utf8(parts[0]!))
    if (header?.type !== 'object') return failed('header is not a JSON object')
    const problems: string[] = []
    for (const name of header.entries.keys()) {
      if (!HEADER_FIELDS.includes(name)) problems.push(`header parameter '${name}' is not allowed`)
    }
    const typ = header.entries.get('typ')
    if (typ?.type !== 'string' || typ.value !== APPROVAL_JWS_TYPE) problems.push(`header typ must be '${APPROVAL_JWS_TYPE}'`)
    const alg = header.entries.get('alg')
    if (alg?.type !== 'string') problems.push('header alg is required')
    else if (!(this.allowed as readonly string[]).includes(alg.value)) {
      problems.push(`algorithm '${alg.value}' is not allowed (allowed: ${this.allowed.join(', ')})`)
    }
    const kid = header.entries.get('kid')
    let key: { key: CryptoKey; algorithm: ApprovalAlgorithm } | undefined
    if (kid?.type !== 'string' || kid.value === '') problems.push('header kid is required')
    else {
      key = this.keys.get(kid.value)
      if (!key) problems.push(`unknown key '${kid.value}'`)
    }
    if (problems.length > 0) return { problems }
    const algorithm = (alg as { value: string }).value
    const kidValue = (kid as { value: string }).value
    if (key!.algorithm !== algorithm) return failed(`key '${kidValue}' is an ${key!.algorithm} key, not ${algorithm}`)
    if (!(await signatureVerifies(key!.key, key!.algorithm, parts))) {
      return failed(`signature does not verify with key '${kidValue}'`)
    }
    const payload = utf8(parts[1]!)
    const root = payload === undefined ? undefined : json(payload)
    if (root?.type !== 'object') return failed('payload is not a JSON object')
    let statement: ApprovalStatement
    try {
      statement = readStatement(root)
    } catch (e) {
      if (e instanceof InvalidDecisionError) return { problems: e.problems }
      throw e
    }
    if (writeApprovalStatement(statement) !== payload) return failed('payload is not in canonical form')
    return { statement, problems: [] }
  }

  /**
   * The problems of requireApproved, as the Java Approvals.problems: first those of statements that
   * do not verify, in the order given, then those of artifacts that no acceptable statement covers.
   * @internal
   */
  async approvalProblems(approvals: readonly NamedApproval[], artifacts: readonly ApprovalArtifact[]): Promise<string[]> {
    const problems: string[] = []
    const verified: ApprovalStatement[] = []
    for (const approval of approvals) {
      const checked = await this.check(approval.jws)
      if (checked.statement) verified.push(checked.statement)
      for (const p of checked.problems) problems.push(`approval ${approval.name}: ${p}`)
    }
    for (const artifact of artifacts) {
      const ref = artifactRef(artifact)
      const covers = (s: ApprovalSubject) => s.kind === artifact.kind && s.id === artifact.id
      const covering = verified.filter((s) => s.subjects.some((o) => covers(o) && o.version === artifact.exactVersion))
      if (covering.length === 0) {
        const approved = new Set<string>()
        for (const s of verified) {
          for (const o of s.subjects) if (covers(o) && o.version.startsWith(`${artifact.label}@sha256:`)) approved.add(o.version)
        }
        problems.push(
          `${ref}: no approval covers ${artifact.exactVersion}` + (approved.size === 0 ? '' : `; the approved content is ${[...approved].join(', ')}`),
        )
        continue
      }
      const rejected: string[] = []
      let accepted = false
      for (const statement of covering) {
        const own = statementProblems(statement, this.minApprovers, ref)
        if (own.length === 0) accepted = true
        rejected.push(...own)
      }
      if (!accepted) problems.push(...rejected)
    }
    return problems
  }
}

function failed(problem: string): Checked {
  return { problems: [problem] }
}

function artifactRef(artifact: ApprovalArtifact): string {
  const ref = `${artifact.id}@${artifact.label}`
  switch (artifact.kind) {
    case 'table':
      return ref
    case 'flow':
      return `${ref} (flow)`
    case 'params':
      return `${ref} (parameter set)`
    case 'catalog':
      return `${ref} (reason-code catalog)`
  }
}

function statementProblems(statement: ApprovalStatement, minApprovers: number, ref: string): string[] {
  const problems: string[] = []
  const at = `${ref}: approval ${statement.changeSet}`
  for (const approver of statement.approvers) {
    if (statement.makers.includes(approver.principal)) problems.push(`${at} names approver ${approver.principal}, who is also a maker`)
  }
  const count = statement.approvers.length
  if (count < minApprovers) {
    problems.push(`${at} names ${count} ${count === 1 ? 'approver' : 'approvers'}; at least ${minApprovers} are required`)
  }
  return problems
}

function algorithmOf(key: CryptoKey): ApprovalAlgorithm | undefined {
  if (key.type !== 'public') return undefined
  if (key.algorithm.name === 'Ed25519') return 'EdDSA'
  if (key.algorithm.name === 'ECDSA' && (key.algorithm as EcKeyAlgorithm).namedCurve === 'P-256') return 'ES256'
  return undefined
}

async function signatureVerifies(key: CryptoKey, algorithm: ApprovalAlgorithm, parts: readonly string[]): Promise<boolean> {
  const signature = base64url(parts[2]!)
  if (signature.length !== 64) return false
  const input = new TextEncoder().encode(`${parts[0]}.${parts[1]}`)
  try {
    return await globalThis.crypto.subtle.verify(
      algorithm === 'EdDSA' ? { name: 'Ed25519' } : { name: 'ECDSA', hash: 'SHA-256' },
      key,
      signature as Uint8Array<ArrayBuffer>,
      input,
    )
  } catch {
    return false
  }
}

/** The JSON value of text, or undefined if it is not JSON; text after the value is ignored, as Jackson's readTree does. */
function json(text: string | undefined): JsonNode | undefined {
  if (text === undefined) return undefined
  try {
    return parseJson(text, { failOnTrailingTokens: false })
  } catch {
    return undefined
  }
}

/** The UTF-8 text of a base64url part, or undefined if it is not UTF-8. */
function utf8(part: string): string | undefined {
  try {
    return new TextDecoder('utf-8', { fatal: true, ignoreBOM: true }).decode(base64url(part))
  } catch {
    return undefined
  }
}

/** Decodes base64url without padding, ignoring leftover bits, as Java's URL decoder does. */
export function base64url(text: string): Uint8Array {
  const out = new Uint8Array(Math.floor((text.length * 3) / 4))
  let value = 0
  let bits = 0
  let j = 0
  for (const c of text) {
    value = ((value << 6) | B64.indexOf(c)) & 0xffffff
    bits += 6
    if (bits >= 8) {
      bits -= 8
      out[j++] = (value >> bits) & 0xff
    }
  }
  return out
}

/** Encodes bytes as base64url without padding. */
export function base64urlEncode(bytes: Uint8Array): string {
  let out = ''
  for (let i = 0; i < bytes.length; i += 3) {
    const n = (bytes[i]! << 16) | ((bytes[i + 1] ?? 0) << 8) | (bytes[i + 2] ?? 0)
    const chars = i + 2 < bytes.length ? 4 : i + 1 < bytes.length ? 3 : 2
    for (let k = 0; k < chars; k++) out += B64[(n >> (18 - 6 * k)) & 63]
  }
  return out
}
