// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0

import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import { describe, expect, it } from 'vitest'
import {
  FIT_ZOOM, LAYOUT_SPACING, NODE_HEIGHT, NODE_WIDTH, addNode, allEdges, autoLayout, checkFlow, connect, disconnect, fitZoom,
  layoutBounds, layoutFor, parseFlow, removeFlowNode, renameNode, type FlowJson, type FlowLayout,
} from '../src/model/index.js'
import { REPO } from './helpers.js'

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

  it('wraps long chains into rows below each other, like lines of text', () => {
    const layout = autoLayout(origination(), {}, { maxColumns: 3 })
    const columns = new Set(Object.values(layout).map((p) => p.x))
    expect(columns.size).toBe(3)
    // policy, segment and the models fill the first row; grade starts the second, at the left.
    expect(layout['grade']!.x).toBe(layout['policy']!.x)
    expect(layout['grade']!.y).toBeGreaterThan(layout['pd_application']!.y + NODE_HEIGHT)
    expect(layout['approved']!.y).toBeGreaterThan(layout['limit_challenger']!.y + NODE_HEIGHT)
    expect(noOverlaps(layout)).toBe(true)
  })

  it('chooses a layout that shows the whole flow at a readable zoom when it can', () => {
    const flow = origination()
    const wide = layoutFor(flow, 900, 560)
    expect(fitZoom(wide, 900, 560)).toBeGreaterThanOrEqual(FIT_ZOOM.min)
    expect(noOverlaps(wide)).toBe(true)
    // A single row of seven columns would need a zoom of about 0.5 here.
    expect(fitZoom(autoLayout(flow), 900, 560)).toBeLessThan(FIT_ZOOM.min)
  })

  it('on a phone, keeps the readable zoom and fits the width, so panning is vertical only', () => {
    const layout = layoutFor(origination(), 358, 420)
    const b = layoutBounds(layout)!
    expect((b.width + 2 * LAYOUT_SPACING.margin) * FIT_ZOOM.min).toBeLessThanOrEqual(358)
    expect(Object.keys(layout)).toHaveLength(11)
  })

  it('is the same flow as the example in docs/hybrid-ai.md', () => {
    const doc = readFileSync(join(REPO, 'docs', 'hybrid-ai.md'), 'utf8')
    const block = [...doc.matchAll(/```json\n([\s\S]*?)\n```/g)].map((m) => m[1]!).find((b) => b.includes('"id": "card_origination"') && b.includes('"nodes"'))
    expect(JSON.parse(block!)).toEqual(origination())
  })
})

function noOverlaps(layout: FlowLayout): boolean {
  const boxes = Object.values(layout)
  return boxes.every((a, i) => boxes.every((b, j) => i === j || a.x + NODE_WIDTH <= b.x || b.x + NODE_WIDTH <= a.x || a.y + NODE_HEIGHT <= b.y || b.y + NODE_HEIGHT <= a.y))
}
