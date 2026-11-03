// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0

// Releases and requireRelease: every case of java-releases.json (written by rulestone-test's
// SdkFixturesTest) gives the same problems as the Java engine, in the same order; and the
// in-memory repository and the reloader.

import { join } from 'node:path'
import { describe, expect, it } from 'vitest'
import {
  ApprovalVerifier, InMemoryRuleRepository, InvalidDecisionError, MemoryReleaseStore, RepositoryReloader, createEngine,
  parseDecisionFlow, parseDecisionTable, parseParameterSet, parseReasonCodeCatalog, parseRelease, releaseHash,
  writeRelease, type ApprovalAlgorithm, type Release, type RuleStoneEngine,
} from '../src/index.js'
import { ApprovalIssuer, approvalSubjects, releaseEntries } from '../src/testing.js'
import { REPO, fixture, read } from './helpers.js'

interface Case {
  name: string
  loadExtraTable: boolean
  omit: string[]
  lastAccepted?: string
  release?: { name?: string; jws: string }
  approvals: string[]
  problems: string[]
}

interface Fixture {
  target: string
  tables: string[]
  flows: string[]
  paramSets: string[]
  catalog: string
  extraTable: string
  cases: Case[]
}

interface Keys {
  keys: { kid: string; alg: ApprovalAlgorithm; spki: string }[]
}

const java = fixture<Fixture>('java-releases.json')
const keys = fixture<Keys>('java-approvals.json').keys
const tables = java.tables.map((f) => ({ file: f, table: parseDecisionTable(read(join(REPO, f))) }))
const flows = java.flows.map((f) => parseDecisionFlow(read(join(REPO, f))))
const paramSets = java.paramSets.map((f) => ({ file: f, set: parseParameterSet(read(join(REPO, f))) }))
const reasonCodeCatalog = parseReasonCodeCatalog(read(join(REPO, java.catalog)))
const extraTable = parseDecisionTable(java.extraTable)

async function verifier(): Promise<ApprovalVerifier> {
  const trustedKeys = new Map<string, CryptoKey>()
  for (const key of keys) {
    const params = key.alg === 'EdDSA' ? { name: 'Ed25519' } : { name: 'ECDSA', namedCurve: 'P-256' }
    trustedKeys.set(key.kid, await crypto.subtle.importKey('spki', Buffer.from(key.spki, 'base64'), params, false, ['verify']))
  }
  return new ApprovalVerifier({ trustedKeys })
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

describe('java-releases.json', () => {
  for (const testCase of java.cases) {
    it(testCase.name, async () => {
      const v = await verifier()
      const actual = await problems(createEngine({
        paramSets: paramSets.filter((s) => !testCase.omit.includes(s.file)).map((s) => s.set),
        tables: [
          ...tables.filter((t) => !testCase.omit.includes(t.file)).map((t) => t.table),
          ...(testCase.loadExtraTable ? [extraTable] : []),
        ],
        flows,
        reasonCodeCatalog,
        suppliedModelsOnly: true,
        requireApproved: v,
        approvals: testCase.approvals,
        requireRelease: java.target,
        ...(testCase.release ? { release: testCase.release.name !== undefined ? { name: testCase.release.name, jws: testCase.release.jws } : testCase.release.jws } : {}),
        ...(testCase.lastAccepted ? { lastAcceptedRelease: await v.verifyRelease(testCase.lastAccepted) } : {}),
      }))
      expect(actual).toEqual(testCase.problems)
    })
  }
})

function gate(version: string, effectiveFrom: string, when: string): string {
  return JSON.stringify({
    id: 'gate_decision', version, effectiveFrom, hitPolicy: 'FIRST', inputs: { score: 'int' }, outputs: { action: 'string' },
    rules: [
      { id: 'BLOCK', when, then: { action: "'BLOCK'" } },
      { id: 'ALLOW', when: 'true', then: { action: "'ALLOW'" } },
    ],
  })
}

describe('releases', () => {
  const v1Text = gate('1', '2026-01-01', 'score >= 70')
  const v2Text = gate('2', '2027-01-01', 'score >= 60')
  const v1 = parseDecisionTable(v1Text)
  const v2 = parseDecisionTable(v2Text)
  const artifact = (text: string, source: string) => {
    const t = parseDecisionTable(text)
    return { kind: 'table' as const, id: t.id, version: t.version, source, content: text }
  }

  async function setup() {
    const issuer = await ApprovalIssuer.generate('k')
    const approval = await issuer.issue({
      approvalId: 'a-1', changeSet: 'CHG-1', subjects: await approvalSubjects({ tables: [v1, v2] }), makers: ['u:a'],
      approvers: [{ principal: 'u:b', at: '2026-11-20T03:12:00Z' }], emergency: false, issuer: 'ci', issuedAt: '2026-11-20T03:12:01Z',
    })
    const r1: Release = {
      target: 'gate-prod', sequence: 1, artifacts: await releaseEntries({ tables: [v1] }), routing: [], issuedAt: '2026-11-20T07:45:00Z',
    }
    const r2: Release = {
      target: 'gate-prod', sequence: 2, previous: await releaseHash(r1), artifacts: await releaseEntries({ tables: [v1, v2] }),
      routing: [{ decision: 'gate_decision', kind: 'CANARY', version: (await releaseEntries({ tables: [v2] }))[0]!.version, share: 0.05 }],
      issuedAt: '2026-11-20T08:00:00Z',
    }
    return { issuer, approval, r1, r2 }
  }

  it('are written canonically and verified', async () => {
    const { issuer, r2 } = await setup()
    const text = writeRelease(r2)
    expect(text).toContain('"share":0.05')
    expect(parseRelease(text)).toEqual(r2)
    expect(await issuer.verifier().verifyRelease(await issuer.issueRelease(r2))).toEqual(r2)
    await expect(issuer.verifier().verify(await issuer.issueRelease(r2))).rejects.toThrow("header typ must be 'rulestone-approval+jws'")
  })

  it('an engine without requireRelease ignores a release', async () => {
    await expect(createEngine({ tables: [v1], release: 'not-a-jws' })).resolves.toBeDefined()
  })

  it('requireRelease needs requireApproved', async () => {
    await expect(createEngine({ tables: [v1], requireRelease: 'gate-prod' })).rejects.toThrow(TypeError)
  })

  it('an engine loads a snapshot from an in-memory repository', async () => {
    const { issuer, approval, r1 } = await setup()
    const repository = new InMemoryRuleRepository()
    repository.put('gate-prod', {
      release: await issuer.issueRelease(r1), artifacts: [artifact(v1Text, 'gate.v1.stone.json')], approvals: [approval],
    })
    const snapshot = await repository.load('gate-prod')
    expect(snapshot.sequence).toBe(1)
    const engine = await createEngine({ snapshot, requireApproved: issuer.verifier(), requireRelease: 'gate-prod' })
    expect((await engine.evaluate({ id: 'gate_decision', asOf: '2026-11-01' }, { score: 65 })).ruleVersion).toBe(r1.artifacts[0]!.version)
    expect(await repository.find('table', 'gate_decision', r1.artifacts[0]!.version)).toMatchObject({ source: 'gate.v1.stone.json' })
    expect(await repository.find('table', 'gate_decision', `1@sha256:${'0'.repeat(64)}`)).toBeUndefined()
  })

  it('the reloader swaps in newer releases only, and its store refuses a rollback after a restart', async () => {
    const { issuer, approval, r1, r2 } = await setup()
    const repository = new InMemoryRuleRepository()
    const store = new MemoryReleaseStore()
    const failures: string[] = []
    const engines: RuleStoneEngine[] = []
    const reloader = (): RepositoryReloader => new RepositoryReloader({
      repository, target: 'gate-prod', verifier: issuer.verifier(), store,
      onReload: (engine) => engines.push(engine),
      onFailure: (sequence, e) => failures.push(`${sequence}: ${(e as Error).message}`),
    })
    const r1jws = await issuer.issueRelease(r1)
    repository.put('gate-prod', { release: r1jws, artifacts: [artifact(v1Text, 'v1')], approvals: [approval] })
    const first = reloader()
    expect(await first.reloadIfNewer()).toBe(true)
    expect(first.release).toEqual(r1)
    expect(await store.load('gate-prod')).toBe(r1jws)
    expect(await first.reloadIfNewer()).toBe(false)

    const other = await ApprovalIssuer.generate('k')
    const all = [artifact(v1Text, 'v1'), artifact(v2Text, 'v2')]
    repository.put('gate-prod', { release: await other.issueRelease(r2), artifacts: all, approvals: [approval] })
    expect(await first.reloadIfNewer()).toBe(false)
    expect(first.engine).toBe(engines[0])
    expect(failures).toEqual([
      "2: invalid decision definition (1 problem):\n  - release gate-prod.release.jws: signature does not verify with key 'k'",
    ])

    repository.put('gate-prod', { release: await issuer.issueRelease(r2), artifacts: all, approvals: [approval] })
    expect(await first.reloadIfNewer()).toBe(true)
    expect(first.release).toEqual(r2)

    repository.put('gate-prod', { release: r1jws, artifacts: [artifact(v1Text, 'v1')], approvals: [approval] })
    failures.length = 0
    const restarted = reloader()
    expect(await restarted.reloadIfNewer()).toBe(false)
    expect(restarted.engine).toBeUndefined()
    expect(failures).toEqual([
      '1: invalid decision definition (1 problem):\n  - release gate-prod.release.jws: sequence 1 is older than release 2, which this engine already accepted',
    ])

    await store.save('gate-prod', 'tampered')
    await expect(reloader().reloadIfNewer()).rejects.toThrow('release store: not a JWS compact serialization')
  })
})
