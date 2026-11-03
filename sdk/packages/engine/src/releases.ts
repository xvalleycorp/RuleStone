// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0

// Releases, as the Java engine's Release, ReleaseJson and Releases: the signed list of exact versions
// that the engines of one target load, with its canary and shadow versions, and the build-time checks
// of requireRelease, with the same problems in the same order. Verification of the signature is the
// ApprovalVerifier's, with the same keys as approval statements.

import {
  EXACT_VERSION, HASH, KINDS, artifactRef, checkInstant, type ApprovalArtifact, type ApprovalVerifier, type ArtifactKind,
} from './approvals.js'
import { InvalidDecisionError } from './errors.js'
import { JsonSyntaxError, parseJson, writeJson, type JsonNode, type Writable } from './json.js'
import { TableReader, sha256Hex, sortedMap } from './table.js'

/** The type field of a release. */
export const RELEASE_TYPE = 'rulestone.release'
/** The suffix of signed release files, e.g. retail-credit-prod.release.jws. */
export const RELEASE_FILE_SUFFIX = '.release.jws'
/** The largest sequence, the largest integer that both engines represent exactly (2^53 - 1). */
export const MAX_SEQUENCE = Number.MAX_SAFE_INTEGER

/** ACTIVE versions are part of the current release; RETIRED versions stay loaded for back-dated decisions and replays. */
export type ReleaseState = 'ACTIVE' | 'RETIRED'
/** How a routed version takes traffic, as a record's routing names it. */
export type RouteKind = 'CANARY' | 'SHADOW'

/** One listed version. */
export interface ReleaseEntry {
  readonly kind: ArtifactKind
  /** Its id; for a catalog, the name the repository gives it, by default reason-codes. */
  readonly id: string
  /** Its exact version, <label>@sha256:<hex>. */
  readonly version: string
  readonly state: ReleaseState
}

/** A canary or shadow version of a decision table or flow, one of the listed ACTIVE versions. */
export interface ReleaseRoute {
  readonly decision: string
  readonly kind: RouteKind
  /** The exact version routed to. */
  readonly version: string
  /** For a canary, the share of correlation ids routed to it: above 0, at most 1, with at most 6 decimals. */
  readonly share?: number
}

/** The set of approved versions that the engines of one target load, as the Java Release. */
export interface Release {
  readonly target: string
  /** From 1, growing with every release of the target. */
  readonly sequence: number
  /** sha256:<hex> of the canonical form of release sequence - 1; absent exactly in the first release. */
  readonly previous?: string
  readonly artifacts: readonly ReleaseEntry[]
  readonly routing: readonly ReleaseRoute[]
  /** When it was signed, a UTC instant such as 2026-11-20T07:45:00Z. */
  readonly issuedAt: string
}

const RELEASE_FIELDS = ['type', 'target', 'sequence', 'previous', 'artifacts', 'routing', 'issuedAt']
const ARTIFACT_FIELDS = ['kind', 'id', 'version', 'state']
const ROUTE_FIELDS = ['decision', 'kind', 'version', 'share']

type JsonObject = JsonNode & { type: 'object' }

/**
 * Reads a release strictly, as ReleaseJson.read: every problem is reported with its location, all together.
 * @throws InvalidDecisionError listing every problem
 */
export function parseRelease(text: string): Release {
  let root: JsonNode | undefined
  try {
    root = parseJson(text, { failOnTrailingTokens: false })
  } catch (e) {
    if (e instanceof JsonSyntaxError) throw new InvalidDecisionError([`invalid JSON: ${e.message}`])
    throw e
  }
  return readRelease(root)
}

/**
 * Writes the canonical form, as ReleaseJson.write: compact, keys sorted, artifacts and routing in their
 * order, previous omitted in the first release, routing omitted when empty, a share as a plain decimal.
 */
export function writeRelease(release: Release): string {
  const json = new Map<string, Writable>([
    ['type', RELEASE_TYPE],
    ['target', release.target],
    ['sequence', { rawNumber: String(release.sequence) }],
    ['artifacts', release.artifacts.map((a) => sortedMap<Writable>([['kind', a.kind], ['id', a.id], ['version', a.version], ['state', a.state]]))],
    ['issuedAt', release.issuedAt],
  ])
  if (release.previous !== undefined) json.set('previous', release.previous)
  if (release.routing.length > 0) {
    json.set('routing', release.routing.map((r) => sortedMap<Writable>([
      ['decision', r.decision], ['kind', r.kind], ['version', r.version],
      ...(r.share !== undefined ? [['share', { rawNumber: String(r.share) }] as [string, Writable]] : []),
    ])))
  }
  return writeJson(sortedMap(json))
}

/** sha256:<hex> of the canonical form: what the next release names as previous. */
export async function releaseHash(release: Release): Promise<string> {
  return `sha256:${await sha256Hex(writeRelease(release))}`
}

/** The version label, the part of an exact version before @sha256:. */
function label(exactVersion: string): string {
  const at = exactVersion.indexOf('@sha256:')
  return at < 0 ? exactVersion : exactVersion.slice(0, at)
}

function validShare(share: number): boolean {
  return share > 0 && share <= 1 && /^\d+(\.\d{1,6})?$/.test(String(share))
}

/** @internal */
export function readRelease(root: JsonNode | undefined): Release {
  if (!root || root.type !== 'object') throw new InvalidDecisionError(['$: expected a JSON object'])
  const reader = new TableReader()
  reader.unknownFields(root, RELEASE_FIELDS, '$')
  const type = reader.requiredText(root, 'type', '$')
  if (type !== '' && type !== RELEASE_TYPE) reader.problem('$.type', `must be '${RELEASE_TYPE}'`)
  const target = reader.requiredText(root, 'target', '$')
  const sequence = readSequence(root.entries.get('sequence'))
  if (sequence === 0) reader.problem('$.sequence', `required whole number from 1 to ${MAX_SEQUENCE}`)
  const previous = reader.optionalText(root, 'previous', '$')
  if (previous !== undefined && !HASH.test(previous)) reader.problem('$.previous', 'expected sha256:<64 hex digits>')
  else if (previous !== undefined && sequence === 1) reader.problem('$.previous', 'must be absent in the first release (sequence 1)')
  else if (previous === undefined && sequence > 1) {
    reader.problem('$.previous', `required after the first release: the hash of release ${sequence - 1}`)
  }
  const artifacts = readArtifacts(root, reader)
  const routing = readRouting(root, reader)
  const issuedAt = reader.requiredText(root, 'issuedAt', '$')
  if (issuedAt !== '') checkInstant(issuedAt, '$.issuedAt', reader)
  if (reader.problems.length > 0) throw new InvalidDecisionError(reader.problems)
  return { target, sequence, ...(previous !== undefined ? { previous } : {}), artifacts, routing, issuedAt }
}

/** The sequence, or 0 if it is not a whole number from 1 to MAX_SEQUENCE. */
function readSequence(node: JsonNode | undefined): number {
  if (node?.type !== 'number' || !/^-?\d+$/.test(node.text)) return 0
  const value = BigInt(node.text)
  return value > 0n && value <= BigInt(MAX_SEQUENCE) ? Number(value) : 0
}

function readArtifacts(root: JsonObject, reader: TableReader): ReleaseEntry[] {
  const node = root.entries.get('artifacts')
  const artifacts: ReleaseEntry[] = []
  if (!node || node.type !== 'array' || node.items.length === 0) {
    reader.problem('$.artifacts', 'required non-empty array of { kind, id, version, state }')
    return artifacts
  }
  const listed = new Set<string>()
  let catalog = false
  node.items.forEach((a, i) => {
    const path = `$.artifacts[${i}]`
    if (a.type !== 'object') {
      reader.problem(path, 'expected an object')
      return
    }
    reader.unknownFields(a, ARTIFACT_FIELDS, path)
    const kind = reader.requiredText(a, 'kind', path)
    const known = (KINDS as readonly string[]).includes(kind)
    if (kind !== '' && !known) reader.problem(`${path}.kind`, `unknown kind '${kind}' (expected table, flow, params or catalog)`)
    const id = reader.requiredText(a, 'id', path)
    const version = reader.requiredText(a, 'version', path)
    const exact = EXACT_VERSION.test(version)
    if (version !== '' && !exact) reader.problem(`${path}.version`, 'expected an exact version, <label>@sha256:<64 hex digits>')
    const state = reader.requiredText(a, 'state', path)
    const validState = state === 'ACTIVE' || state === 'RETIRED'
    if (state !== '' && !validState) reader.problem(`${path}.state`, 'expected ACTIVE or RETIRED')
    if (known && id !== '' && exact) {
      if (kind === 'catalog') {
        if (catalog) reader.problem(path, 'a release lists at most one reason-code catalog')
        catalog = true
      } else {
        const key = `${kind} ${id}@${label(version)}`
        if (listed.has(key)) reader.problem(path, `${key} is listed more than once`)
        listed.add(key)
      }
    }
    if (known && validState) artifacts.push({ kind: kind as ArtifactKind, id, version, state })
  })
  return artifacts
}

function readRouting(root: JsonObject, reader: TableReader): ReleaseRoute[] {
  const node = root.entries.get('routing')
  const routing: ReleaseRoute[] = []
  if (node === undefined) return routing
  if (node.type !== 'array' || node.items.length === 0) {
    reader.problem('$.routing', 'expected a non-empty array of { decision, kind, version, share }')
    return routing
  }
  const routed = new Set<string>()
  node.items.forEach((r, i) => {
    const path = `$.routing[${i}]`
    if (r.type !== 'object') {
      reader.problem(path, 'expected an object')
      return
    }
    reader.unknownFields(r, ROUTE_FIELDS, path)
    const decision = reader.requiredText(r, 'decision', path)
    const kind = reader.requiredText(r, 'kind', path)
    const known = kind === 'CANARY' || kind === 'SHADOW'
    if (kind !== '' && !known) reader.problem(`${path}.kind`, 'expected CANARY or SHADOW')
    const version = reader.requiredText(r, 'version', path)
    if (version !== '' && !EXACT_VERSION.test(version)) {
      reader.problem(`${path}.version`, 'expected an exact version, <label>@sha256:<64 hex digits>')
    }
    const shareNode = r.entries.get('share')
    let share: number | undefined
    if (kind === 'CANARY') {
      if (shareNode?.type !== 'number' || !validShare(Number(shareNode.text))) {
        reader.problem(`${path}.share`, 'required for a canary: a number above 0 and at most 1, with at most 6 decimals')
      } else {
        share = Number(shareNode.text)
      }
    } else if (known && shareNode !== undefined) {
      reader.problem(`${path}.share`, 'only a canary has a share')
    }
    if (known && decision !== '') {
      const key = `${kind} ${decision}`
      if (routed.has(key)) reader.problem(path, `decision '${decision}' has more than one ${kind.toLowerCase()}`)
      routed.add(key)
    }
    if (known) routing.push({ decision, kind, version, ...(share !== undefined ? { share } : {}) })
  })
  return routing
}

/**
 * The problems of requireRelease, as the Java Releases.check, and the routes to listed, active and
 * loaded versions.
 * @internal
 */
export async function releaseProblems(
  verifier: ApprovalVerifier, target: string, named: { readonly name?: string; readonly jws: string } | undefined,
  accepted: Release | undefined, loaded: readonly ApprovalArtifact[],
): Promise<{ problems: string[]; routes: ReleaseRoute[]; release?: Release }> {
  const problems: string[] = []
  if (!named) {
    problems.push(`release: required for target '${target}', but none is registered`)
    return { problems, routes: [] }
  }
  const at = named.name === undefined ? 'release: ' : `release ${named.name}: `
  const checked = await verifier.checkRelease(named.jws)
  if (!checked.value) {
    for (const p of checked.problems) problems.push(at + p)
    return { problems, routes: [] }
  }
  const release = checked.value
  if (release.target !== target) {
    problems.push(`${at}target is '${release.target}', but this engine loads '${target}'`)
    return { problems, routes: [] }
  }
  if (accepted) {
    const sequence = release.sequence
    const last = accepted.sequence
    const acceptedHash = await releaseHash(accepted)
    if (sequence < last) problems.push(`${at}sequence ${sequence} is older than release ${last}, which this engine already accepted`)
    else if (sequence === last && writeRelease(release) !== writeRelease(accepted)) {
      problems.push(`${at}sequence ${sequence} was already accepted with other content`)
    } else if (sequence === last + 1 && (release.previous ?? '') !== acceptedHash) {
      problems.push(`${at}previous is ${release.previous ?? 'absent'}, but release ${last} accepted before is ${acceptedHash}`)
    }
    if (problems.length > 0) return { problems, routes: [] }
  }
  const sequence = release.sequence
  for (const artifact of loaded) {
    const listed = release.artifacts.some((e) => e.kind === artifact.kind && e.id === artifact.id && e.version === artifact.exactVersion)
    if (!listed) {
      const sameLabel = release.artifacts
        .filter((e) => e.kind === artifact.kind && e.id === artifact.id && label(e.version) === artifact.label)
        .map((e) => e.version)
      problems.push(
        `${artifactRef(artifact)}: ${artifact.exactVersion} is not in release ${sequence}` +
          (sameLabel.length === 0 ? '' : `; the release lists ${sameLabel.join(', ')}`),
      )
    }
  }
  for (const entry of release.artifacts) {
    const entryLabel = label(entry.version)
    if (!loaded.some((a) => a.kind === entry.kind && a.id === entry.id && a.label === entryLabel)) {
      const ref = artifactRef({ kind: entry.kind, id: entry.id, label: entryLabel, exactVersion: entry.version })
      problems.push(`${ref}: release ${sequence} lists ${entry.version}, which is not loaded`)
    }
  }
  const routes: ReleaseRoute[] = []
  for (const route of release.routing) {
    const name = `${route.kind.toLowerCase()} ${route.decision}@${label(route.version)}`
    const entry = release.artifacts.find(
      (e) => (e.kind === 'table' || e.kind === 'flow') && e.id === route.decision && e.version === route.version,
    )
    if (!entry) problems.push(`${at}${name} is not listed: the release lists no table or flow ${route.version}`)
    else if (entry.state !== 'ACTIVE') problems.push(`${at}${name} is RETIRED; only an ACTIVE version can be routed to`)
    else if (loaded.some((a) => a.kind === entry.kind && a.id === route.decision && a.exactVersion === route.version)) routes.push(route)
  }
  return { problems, routes, release }
}
