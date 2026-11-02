// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0

import { readFileSync, readdirSync } from 'node:fs'
import { join } from 'node:path'
import { describe, expect, it } from 'vitest'
import {
  FIT_ZOOM, LAYOUT_SPACING, NODE_HEIGHT, NODE_WIDTH, addNode, allEdges, autoLayout, checkFlow, checkFlowWithEngine, connect,
  disconnect, fitZoom,
  layoutBounds, layoutFor, parseFlow, removeFlowNode, renameNode, type FlowJson, type FlowLayout,
} from '../src/model/index.js'
import { REPO } from './helpers.js'

const DEMO_FLOW = join(process.cwd(), 'demo', 'card_origination_scored.flow.json')

function origination(): FlowJson {
  return parseFlow(readFileSync(DEMO_FLOW, 'utf8'))
}

const TABLES = ['card_policy', 'card_pd_grade', 'card_limit']
const FLOWS = join(REPO, 'examples', 'flows')

function codes(flow: FlowJson, tables = TABLES) {
  return checkFlow(flow, { tables }).map((p) => [p.code, p.node ?? null, p.params])
}

/** The tables of examples/flows, which the example flows use. */
function definitions() {
  return { tables: readdirSync(FLOWS).filter((f) => f.endsWith('.stone.json')).map((f) => readFileSync(join(FLOWS, f), 'utf8')) }
}

describe('flow checks', () => {
  it('accept the example flow of examples/flows', () => {
    expect(codes(origination())).toEqual([])
  })

  it('find cycles', () => {
    const flow = origination()
    flow.nodes['grade']!.next = [{ when: "grade.riskGrade == 'D'", to: 'referred' }, { to: 'segment' }]
    expect(codes(flow).filter(([c]) => c === 'cycle').map(([, n]) => n)).toEqual(['segment', 'pd_behaviour', 'pd_application', 'grade'])
  })

  it('find unreachable nodes and paths that never end', () => {
    const flow = origination()
    flow.nodes['orphan'] = { type: 'end', set: { decision: "'X'", riskGrade: "'X'", limit: 'money(0, "VND")' } }
    flow.nodes['grade']!.next = 'stuck'
    flow.nodes['stuck'] = { type: 'derive', outputs: { x: 'int' }, set: { x: '1' } }
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
    flow.nodes['grade']!.decision = 'card_pd_grde'
    flow.nodes['limit']!.next = 'strategy'
    flow.nodes['strategy'] = { type: 'split', key: 'applicant.id', salt: 's', buckets: [{ name: 'a', percent: 90, to: 'approved' }, { name: 'b', percent: 20, to: 'approved' }] }
    expect(codes(flow).filter(([c]) => c !== 'engine')).toEqual([
      ['modelNoFailure', 'pd_behaviour', {}],
      ['modelUnpinned', 'pd_application', {}],
      ['unknownTable', 'grade', { decision: 'card_pd_grde' }],
      ['splitPercent', 'strategy', { total: 110 }],
    ])
  })

  it('find unknown targets, a missing start, a switch without a final case and incomplete end nodes', () => {
    const flow = origination()
    flow.start = 'nowhere'
    flow.nodes['segment']!.cases = [{ when: 'applicant.existingCustomer', to: 'pd_behaviour' }]
    flow.nodes['approved']!.set = { decision: "'APPROVE'", bonus: '1' }
    flow.nodes['limit']!.next = 'gone'
    const found = codes(flow)
    expect(found).toEqual(expect.arrayContaining([
      ['unknownStart', null, { target: 'nowhere' }],
      ['engine', 'segment', { message: '$.nodes.segment.cases[0].when: the last alternative must have no "when", so that one always applies; add { "to": … } after it' }],
      ['endMissingOutput', 'approved', { output: 'riskGrade' }],
      ['endMissingOutput', 'approved', { output: 'limit' }],
      ['endUnknownOutput', 'approved', { output: 'bonus' }],
      ['unknownTarget', 'limit', { target: 'gone' }],
    ]))
  })

  it('report what the engine reads strictly, also without the tables', () => {
    const flow = origination()
    flow.nodes['derived'] = { type: 'derive', values: { x: '1' }, next: 'approved' }
    flow.nodes['fraud']!.shadow = [{ id: 'fraud_v2', version: '1' }]
    const engine = checkFlow(flow, { tables: TABLES }).filter((p) => p.code === 'engine').map((p) => [p.node, p.params.message])
    expect(engine).toEqual([
      ['fraud', '$.nodes.fraud.shadow: not supported yet: shadow models and LLM nodes are planned for a later release (see Hybrid AI decisioning)'],
      ['derived', '$.nodes.derived.values: unknown field (allowed: as, description, next, outputs, set, type)'],
      ['derived', '$.nodes.derived.outputs: required object of name to type'],
      ['derived', '$.nodes.derived.set: required object of name to CEL expression'],
    ])
  })

  it('report the problems the engine reports when the tables are known', async () => {
    expect(await checkFlowWithEngine(origination(), definitions())).toEqual([])
    const flow = origination()
    flow.nodes['referred']!.set = { ...flow.nodes['referred']!.set, limit: 'limit.limit' }
    flow.nodes['grade']!.facts = { pd: 'score.pd', extra: '1' }
    expect(await checkFlowWithEngine(flow, definitions())).toEqual([
      { code: 'engine', severity: 'error', node: 'grade', params: { message: "nodes.grade.facts.extra: not an input of decision 'card_pd_grade' (inputs: [pd])" } },
      {
        code: 'engine', severity: 'error', node: 'referred',
        params: { message: "nodes.referred.set.limit: reads 'limit', which is not set on every path that reaches this node; a node may read only values that every path to it sets" },
      },
    ])
  })
})

describe('flow editing', () => {
  it('adds, connects, renames and removes nodes, keeping edges consistent', () => {
    let flow = origination()
    const added = addNode(flow, 'derive')
    flow = connect(added.flow, 'limit', added.id)
    expect(flow.nodes['limit']!.next).toEqual([{ when: '', to: 'derive1' }, { to: 'approved' }])
    expect(codes(flow)).toEqual(expect.arrayContaining([['emptyCondition', 'limit', { target: 'derive1' }], ['deadEnd', 'derive1', {}]]))
    flow = renameNode(flow, 'derive1', 'dti')
    expect(flow.nodes['limit']!.next).toEqual([{ when: '', to: 'dti' }, { to: 'approved' }])
    const edge = allEdges(flow).find((e) => e.from === 'limit' && e.to === 'dti')!
    flow = disconnect(flow, edge)
    expect(flow.nodes['limit']!.next).toBe('approved')
    flow = removeFlowNode(flow, 'approved')
    expect(flow.nodes['limit']!.next).toBeUndefined()
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
    // policy, fraud and segment fill the first row; the models start the second, at the left.
    expect(layout['pd_behaviour']!.x).toBe(layout['policy']!.x)
    expect(layout['pd_behaviour']!.y).toBeGreaterThan(layout['referred']!.y + NODE_HEIGHT)
    expect(layout['approved']!.y).toBeGreaterThan(layout['limit']!.y + NODE_HEIGHT)
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
    expect(Object.keys(layout)).toHaveLength(10)
  })

  it('is a copy of examples/flows/card_origination_scored.flow.json', () => {
    expect(readFileSync(DEMO_FLOW, 'utf8')).toBe(readFileSync(join(FLOWS, 'card_origination_scored.flow.json'), 'utf8'))
  })
})

function noOverlaps(layout: FlowLayout): boolean {
  const boxes = Object.values(layout)
  return boxes.every((a, i) => boxes.every((b, j) => i === j || a.x + NODE_WIDTH <= b.x || b.x + NODE_WIDTH <= a.x || a.y + NODE_HEIGHT <= b.y || b.y + NODE_HEIGHT <= a.y))
}
