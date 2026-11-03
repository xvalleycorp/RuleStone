// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0

// Issues signed approval statements with WebCrypto, for tests of engines created with
// requireApproved, as rulestone-test's ApprovalIssuer in Java.

import {
  APPROVAL_CATALOG_ID, APPROVAL_JWS_TYPE, RELEASE_JWS_TYPE, ApprovalVerifier, base64urlEncode, writeApprovalStatement,
  type ApprovalAlgorithm, type ApprovalStatement, type ApprovalSubject,
} from './approvals.js'
import { reasonCodeCatalogVersionOf, type ReasonCodeCatalog } from './explanation.js'
import { flowVersionOf, type DecisionFlow } from './flow.js'
import { writeJson } from './json.js'
import { parameterSetVersionOf, type ParameterSet } from './params.js'
import { writeRelease, type Release, type ReleaseEntry, type ReleaseState } from './releases.js'
import { ruleVersionOf, type DecisionTable } from './table.js'

/**
 * Signs approval statements: JWS compact serialization of their canonical form. For tests, and for a
 * CI job that approves reviewed content before a governance service exists; in production the
 * private key belongs in the bank's HSM or KMS.
 */
export class ApprovalIssuer {
  private constructor(
    readonly kid: string,
    readonly algorithm: ApprovalAlgorithm,
    private readonly privateKey: CryptoKey,
    private readonly ownPublicKey: CryptoKey | undefined,
  ) {}

  /** An issuer with a new key pair. */
  static async generate(kid: string, algorithm: ApprovalAlgorithm = 'EdDSA'): Promise<ApprovalIssuer> {
    const params = algorithm === 'EdDSA' ? { name: 'Ed25519' } : { name: 'ECDSA', namedCurve: 'P-256' }
    const pair = (await globalThis.crypto.subtle.generateKey(params, false, ['sign', 'verify'])) as CryptoKeyPair
    return new ApprovalIssuer(kid, algorithm, pair.privateKey, pair.publicKey)
  }

  /** An issuer that signs with an Ed25519 or ECDSA P-256 private key imported with the 'sign' usage. */
  static fromPrivateKey(kid: string, privateKey: CryptoKey): ApprovalIssuer {
    const name = privateKey.algorithm.name
    if (privateKey.type !== 'private' || (name !== 'Ed25519' && name !== 'ECDSA')) {
      throw new TypeError('the key must be an Ed25519 or a P-256 ECDSA private key')
    }
    return new ApprovalIssuer(kid, name === 'Ed25519' ? 'EdDSA' : 'ES256', privateKey, undefined)
  }

  /** The public key that verifies this issuer's statements. @throws TypeError if made from a private key alone */
  get publicKey(): CryptoKey {
    if (!this.ownPublicKey) throw new TypeError('the issuer was made from a private key alone')
    return this.ownPublicKey
  }

  /** A verifier that trusts this issuer's key. */
  verifier(minApprovers = 1): ApprovalVerifier {
    return new ApprovalVerifier({ trustedKeys: { [this.kid]: this.publicKey }, minApprovers })
  }

  /** Signs a statement. */
  issue(statement: ApprovalStatement): Promise<string> {
    const header = writeJson({ alg: this.algorithm, kid: this.kid, typ: APPROVAL_JWS_TYPE })
    return this.sign(header, writeApprovalStatement(statement))
  }

  /** Signs a release, with typ 'rulestone-release+jws'. */
  issueRelease(release: Release): Promise<string> {
    const header = writeJson({ alg: this.algorithm, kid: this.kid, typ: RELEASE_JWS_TYPE })
    return this.sign(header, writeRelease(release))
  }

  /** Signs any header and payload text as they are, for tests of malformed statements. */
  async sign(header: string, payload: string): Promise<string> {
    const encoder = new TextEncoder()
    const input = `${base64urlEncode(encoder.encode(header))}.${base64urlEncode(encoder.encode(payload))}`
    const params = this.algorithm === 'EdDSA' ? { name: 'Ed25519' } : { name: 'ECDSA', hash: 'SHA-256' }
    const signature = await globalThis.crypto.subtle.sign(params, this.privateKey, encoder.encode(input))
    return `${input}.${base64urlEncode(new Uint8Array(signature))}`
  }
}

/** The subjects that approve the exact content of every table, flow, parameter set and catalog given, in that order. */
export async function approvalSubjects(artifacts: {
  readonly tables?: Iterable<DecisionTable>
  readonly flows?: Iterable<DecisionFlow>
  readonly paramSets?: Iterable<ParameterSet>
  readonly reasonCodeCatalog?: ReasonCodeCatalog
}): Promise<ApprovalSubject[]> {
  const subjects: ApprovalSubject[] = []
  for (const t of artifacts.tables ?? []) subjects.push({ kind: 'table', id: t.id, version: await ruleVersionOf(t) })
  for (const f of artifacts.flows ?? []) subjects.push({ kind: 'flow', id: f.id, version: await flowVersionOf(f) })
  for (const s of artifacts.paramSets ?? []) subjects.push({ kind: 'params', id: s.id, version: await parameterSetVersionOf(s) })
  if (artifacts.reasonCodeCatalog) {
    subjects.push({ kind: 'catalog', id: APPROVAL_CATALOG_ID, version: await reasonCodeCatalogVersionOf(artifacts.reasonCodeCatalog) })
  }
  return subjects
}

/**
 * The entries that list the exact content of every table, flow, parameter set and catalog given, in that
 * order, in one state: for releases issued in tests, as ApprovalIssuer.ReleaseBuilder in Java.
 */
export async function releaseEntries(
  artifacts: Parameters<typeof approvalSubjects>[0], state: ReleaseState = 'ACTIVE',
): Promise<ReleaseEntry[]> {
  return (await approvalSubjects(artifacts)).map((s) => ({ ...s, state }))
}
