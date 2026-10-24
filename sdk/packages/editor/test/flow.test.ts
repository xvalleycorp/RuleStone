// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0

import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import { describe, expect, it } from 'vitest'
import {
  addNode, allEdges, autoLayout, checkFlow, connect, disconnect, parseFlow, removeFlowNode, renameNode, type FlowJson,
} from '../src/model/index.js'

const DEMO_FLOW = join(process.cwd(), 'demo', 'card_origination.flow.json')

function origination(): FlowJson {
  return parseFlow(readFileSync(DEMO_FLOW, 'utf8'))
}

const TABLES = ['card_policy', 'pd_to_grade', 'card_limit_matrix', 'card_limit_matrix_b']

function codes(flow: FlowJson, tables = TABLES) {
  return checkFlow(flow, { tables }).map((p) => [p.code, p.node ?? null, p.params])
}

describe('flow checks', () => {
  it('accept the example flow of docs/hybrid-ai.md', () => {
    expect(codes(origination())).toEqual([])
  })

  it('find cycles', () => {
    const flow = origination()
    flow.nodes['grade']!.next = 'segment'
    expect(codes(flow).filter(([c]) => c === 'cycle').map(([, n]) => n)).toEqual(['segment', 'pd_behaviour', 'pd_application', 'grade'])
  })

  it('find unreachable nodes and paths that never end', () => {
    const flow = origination()
    flow.nodes['orphan'] = { type: 'end', set: { decision: "'X'", riskGrade: "'X'", limit: 'money(0, "VND")' } }
    flow.nodes['grade']!.next = 'stuck'
    flow.nodes['stuck'] = { type: 'derive', values: { x: '1' } }
    expect(codes(flow)).toEqual(expect.arrayContaining([
      ['unreachable', 'orphan', {}],
      ['deadEnd', 'stuck', {}],
      ['noEndReachable', 'grade', {}],
    ]))
  })

  it('require onFailure on model nodes, pinned versions, splits of 100% and known tables', () => {
    const flow = origination()
    delete flow.nodes['pd_behaviour']!.onFailure
    flow.nodes['pd_application']!.model = { id: 'retail_pd_application' }
    flow.nodes['limit_strategy']!.buckets![1]!.percent = 20
    flow.nodes['grade']!.decision = 'pd_to_grde'
    expect(codes(flow)).toEqual([
      ['modelNoFailure', 'pd_behaviour', {}],
      ['modelUnpinned', 'pd_application', {}],
      ['unknownTable', 'grade', { decision: 'pd_to_grde' }],
      ['splitPercent', 'limit_strategy', { total: 110 }],
    ])
  })

  it('find unknown targets, a missing start, a switch without a final case and incomplete end nodes', () => {
    const flow = origination()
    flow.start = 'nowhere'
    flow.nodes['segment']!.cases = [{ when: 'applicant.existingCustomer', to: 'pd_behaviour' }]
    flow.nodes['approved']!.set = { decision: "'APPROVE'", bonus: '1' }
    flow.nodes['grade']!.next = 'gone'
    const found = codes(flow)
    expect(found).toEqual(expect.arrayContaining([
      ['unknownStart', null, { target: 'nowhere' }],
      ['switchNoDefault', 'segment', {}],
      ['endMissingOutput', 'approved', { output: 'riskGrade' }],
      ['endMissingOutput', 'approved', { output: 'limit' }],
      ['endUnknownOutput', 'approved', { output: 'bonus' }],
      ['unknownTarget', 'grade', { target: 'gone' }],
    ]))
  })
})

describe('flow editing', () => {
  it('adds, connects, renames and removes nodes, keeping edges consistent', () => {
    let flow = origination()
    const added = addNode(flow, 'derive')
    flow = connect(added.flow, 'grade', added.id)
    expect(flow.nodes['grade']!.next).toEqual([{ when: '', to: 'derive1' }, { to: 'limit_strategy' }])
    expect(codes(flow)).toEqual(expect.arrayContaining([['emptyCondition', 'grade', { target: 'derive1' }], ['deadEnd', 'derive1', {}]]))
    flow = renameNode(flow, 'derive1', 'dti')
    expect(flow.nodes['grade']!.next).toEqual([{ when: '', to: 'dti' }, { to: 'limit_strategy' }])
    const edge = allEdges(flow).find((e) => e.from === 'grade' && e.to === 'dti')!
    flow = disconnect(flow, edge)
    expect(flow.nodes['grade']!.next).toBe('limit_strategy')
    flow = removeFlowNode(flow, 'limit_strategy')
    expect(flow.nodes['grade']!.next).toBeUndefined()
    flow = renameNode(flow, 'policy', 'knockouts')
    expect(flow.start).toBe('knockouts')
    flow = connect(flow, 'pd_behaviour', 'rejected', true)
    expect(flow.nodes['pd_behaviour']!.onFailure).toEqual({ to: 'rejected', reasonCode: 'MODEL_UNAVAILABLE' })
  })

  it('lays out nodes in columns from the start', () => {
    const layout = autoLayout(origination())
    expect(layout['policy']!.x).toBeLessThan(layout['segment']!.x)
    expect(layout['segment']!.x).toBeLessThan(layout['grade']!.x)
    expect(Object.keys(layout)).toHaveLength(Object.keys(origination().nodes).length)
  })
})
