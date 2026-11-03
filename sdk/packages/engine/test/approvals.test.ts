// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0

// Approval statements and requireApproved: every case of java-approvals.json (written by
// rulestone-test's SdkFixturesTest) gives the same problems as the Java engine, in the same order.

import { join } from 'node:path'
import { describe, expect, it } from 'vitest'
import {
  ApprovalVerifier, InvalidDecisionError, createEngine, parseApprovalStatement, parseDecisionFlow, parseDecisionTable,
  parseParameterSet, parseReasonCodeCatalog, writeApprovalStatement, type ApprovalAlgorithm,
} from '../src/index.js'
import { ApprovalIssuer, approvalSubjects } from '../src/testing.js'
import { REPO, fixture, read } from './helpers.js'

interface Case {
  name: string
  minApprovers: number
  allowedAlgorithms?: ApprovalAlgorithm[]
  approvals: { name?: string; jws: string }[]
  problems: string[]
}

interface Fixture {
  keys: { kid: string; alg: ApprovalAlgorithm; spki: string }[]
  tables: string[]
  flows: string[]
  paramSets: string[]
  catalog: string
  cases: Case[]
}

const java = fixture<Fixture>('java-approvals.json')
const tables = java.tables.map((f) => parseDecisionTable(read(join(REPO, f))))
const flows = java.flows.map((f) => parseDecisionFlow(read(join(REPO, f))))
const paramSets = java.paramSets.map((f) => parseParameterSet(read(join(REPO, f))))
const reasonCodeCatalog = parseReasonCodeCatalog(read(join(REPO, java.catalog)))

async function trustedKeys(): Promise<Map<string, CryptoKey>> {
  const keys = new Map<string, CryptoKey>()
  for (const key of java.keys) {
    const params = key.alg === 'EdDSA' ? { name: 'Ed25519' } : { name: 'ECDSA', namedCurve: 'P-256' }
    keys.set(key.kid, await crypto.subtle.importKey('spki', Buffer.from(key.spki, 'base64'), params, false, ['verify']))
  }
  return keys
}

async function problems(engine: Promise<unknown>): Promise<readonly string[]> {
  try {
    await engine
    return []
  } catch (e) {
    if (e instanceof InvalidDecisionError) return e.problems
    throw e
  }
}

describe('java-approvals.json', () => {
  for (const testCase of java.cases) {
    it(testCase.name, async () => {
      const verifier = new ApprovalVerifier({
        trustedKeys: await trustedKeys(),
        minApprovers: testCase.minApprovers,
        ...(testCase.allowedAlgorithms ? { allowedAlgorithms: testCase.allowedAlgorithms } : {}),
      })
      const approvals = testCase.approvals.map((a) => (a.name !== undefined ? { name: a.name, jws: a.jws } : a.jws))
      const actual = await problems(createEngine({
        tables, flows, paramSets, reasonCodeCatalog, suppliedModelsOnly: true, requireApproved: verifier, approvals,
      }))
      expect(actual).toEqual(testCase.problems)
    })
  }
})

describe('approval statements', () => {
  it('an engine without requireApproved ignores approvals', async () => {
    await expect(createEngine({ tables, flows, paramSets, approvals: ['not-a-jws'] })).resolves.toBeDefined()
  })

  for (const algorithm of ['EdDSA', 'ES256'] as const) {
    it(`issues and verifies ${algorithm} statements`, async () => {
      const issuer = await ApprovalIssuer.generate('test-key', algorithm)
      const statement = {
        approvalId: 'a-1', changeSet: 'CHG-1', subjects: await approvalSubjects({ tables, flows, paramSets, reasonCodeCatalog }),
        makers: ['u:a'], approvers: [{ principal: 'u:b', role: 'RULE_CHECKER', at: '2026-11-20T03:12:00Z' }],
        emergency: false, issuer: 'ci', issuedAt: '2026-11-20T03:12:01Z',
      }
      const jws = await issuer.issue(statement)
      expect(await issuer.verifier().verify(jws)).toEqual(statement)
      expect(parseApprovalStatement(writeApprovalStatement(statement))).toEqual(statement)
      await expect(createEngine({
        tables, flows, paramSets, reasonCodeCatalog, suppliedModelsOnly: true, requireApproved: issuer.verifier(), approvals: [jws],
      })).resolves.toBeDefined()
      await expect(issuer.verifier(2).verify(jws)).resolves.toEqual(statement)
    })
  }

  it('verify lists problems without naming the source', async () => {
    const issuer = await ApprovalIssuer.generate('k')
    const other = await ApprovalIssuer.generate('other')
    const jws = await issuer.sign('{"alg":"EdDSA","kid":"k","typ":"rulestone-approval+jws"}', '{}')
    await expect(other.verifier().verify(jws)).rejects.toThrow("invalid decision definition (1 problem):\n  - unknown key 'k'")
  })

  it('accepts only supported keys and settings', async () => {
    const hmac = await crypto.subtle.generateKey({ name: 'HMAC', hash: 'SHA-256' }, false, ['verify', 'sign'])
    expect(() => new ApprovalVerifier({ trustedKeys: { h: hmac } })).toThrow("key 'h' must be an Ed25519 or a P-256 EC public key")
    const key = (await ApprovalIssuer.generate('k')).publicKey
    expect(() => new ApprovalVerifier({ trustedKeys: {} })).toThrow(RangeError)
    expect(() => new ApprovalVerifier({ trustedKeys: { k: key }, minApprovers: 0 })).toThrow(RangeError)
    expect(() => new ApprovalVerifier({ trustedKeys: { k: key }, allowedAlgorithms: [] })).toThrow(RangeError)
    expect(() => new ApprovalVerifier({ trustedKeys: { k: key }, allowedAlgorithms: ['RS256' as ApprovalAlgorithm] })).toThrow(RangeError)
  })
})
