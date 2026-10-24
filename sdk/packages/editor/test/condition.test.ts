// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0

import { describe, expect, it } from 'vitest'
import {
  findNode, findParent, indent, insertNode, moveBy, moveNode, newGroup, newLeaf, outdent, parseCondition, printCondition,
  removeNode, updateNode, wrapInGroup, type CondNode, type Group, type Leaf,
} from '../src/model/condition.js'
import { exampleTables, shape } from './helpers.js'

const conditions = exampleTables().flatMap(([file, table]) => table.rules.map((rule) => ({ file, rule: rule.id, when: rule.when })))

function count(node: CondNode, kind: CondNode['kind']): number {
  const own = node.kind === kind ? 1 : 0
  if (node.kind === 'group') return own + node.children.reduce((n, c) => n + count(c, kind), 0)
  if (node.kind === 'quantifier') return own + count(node.body, kind)
  return own
}

describe('condition builder and CEL round trip, over every condition in examples/', () => {
  it.each(conditions)('$file $rule', ({ when }) => {
    const model = parseCondition(when)
    // Unedited, the text comes back exactly.
    expect(printCondition(model)).toBe(when)
    // Printed from the model's parts alone, the text is the same expression and, for these
    // canonically written examples, the same text.
    const canonical = printCondition(model, { preserve: false })
    expect(shape(canonical)).toEqual(shape(when))
    expect(canonical).toBe(when)
  })

  it('shows most example conditions entirely as blocks', () => {
    const visual = conditions.filter(({ when }) => count(parseCondition(when), 'raw') === 0)
    expect(visual.length).toBeGreaterThanOrEqual(conditions.length - 12)
  })

  it('keeps what it cannot show as text, with the reason', () => {
    const model = parseCondition('daysBetween(account.openedOn, businessDate) >= 365 && account.customerIsPep')
    expect(model.children.map((c) => c.kind)).toEqual(['raw', 'leaf'])
    expect(model.children[0]).toMatchObject({ kind: 'raw', reason: 'calculation', text: 'daysBetween(account.openedOn, businessDate) >= 365' })
    const syntax = parseCondition('a >= ')
    expect(syntax.children).toMatchObject([{ kind: 'raw', reason: 'syntax', text: 'a >= ' }])
    expect(printCondition(syntax)).toBe('a >= ')
    expect(parseCondition('x.filter(d, d > 1).size() > 2').children[0]).toMatchObject({ kind: 'raw', reason: 'calculation' })
    expect(parseCondition("a > 1 ? b : c").children[0]).toMatchObject({ kind: 'raw', reason: 'conditional' })
    expect(parseCondition('18 <= applicant.age').children[0]).toMatchObject({ kind: 'raw', reason: 'valueFirst' })
  })

  it('reads typed values, nested groups, negations and blocks over lists', () => {
    const model = parseCondition(
      "applicant.segment in ['RETAIL', 'SME'] && !(loan.purpose in ['A']) && (riskGrade == 'D' || !has(loan.collateralValue)) && customer.accounts.exists(a, a.balance >= money('1.5', 'USD') && a.opened < date('2026-01-01')) && !applicant.blacklisted",
    )
    expect(model.op).toBe('and')
    const [segment, purpose, or, exists, blacklisted] = model.children as [Leaf, Leaf, Group, CondNode, Leaf]
    expect(segment).toMatchObject({ op: 'in', value: { kind: 'list', items: [{ kind: 'string', text: 'RETAIL' }, { kind: 'string', text: 'SME' }] } })
    expect(purpose.op).toBe('notIn')
    expect(or).toMatchObject({ kind: 'group', op: 'or', parens: true, children: [{ op: '==' }, { op: 'notHas', field: 'loan.collateralValue' }] })
    expect(exists).toMatchObject({ kind: 'quantifier', macro: 'exists', variable: 'a', field: 'customer.accounts' })
    const body = (exists as { body: Group }).body
    expect(body.children).toMatchObject([
      { field: 'a.balance', op: '>=', value: { kind: 'money', amount: '1.5', currency: 'USD', form: 'string' } },
      { field: 'a.opened', op: '<', value: { kind: 'date', text: '2026-01-01' } },
    ])
    expect(blacklisted).toMatchObject({ op: 'isFalse', field: 'applicant.blacklisted' })
  })

  it('preserves unedited text, including spacing and comments, and prints edits canonically', () => {
    const text = "applicant.age>=18  &&  applicant.segment == \"VIP\" // adults"
    const model = parseCondition(text)
    expect(printCondition(model)).toBe(text)
    const age = model.children[0] as Leaf
    const edited = updateNode(model, age.id, (n) => ({ ...(n as Leaf), value: { kind: 'int', text: '21' } }))
    expect(printCondition(edited)).toBe('applicant.age >= 21 && applicant.segment == "VIP"')
    expect(shape(printCondition(edited))).toEqual(shape("applicant.age >= 21 && applicant.segment == 'VIP'"))
  })

  it('adds parentheses that precedence needs when groups change', () => {
    const model = parseCondition('a && b')
    const or = newGroup('or', [newLeaf('c', 'isTrue'), newLeaf('d', 'isTrue')])
    const added = insertNode(model, model.id, or)
    expect(printCondition(added)).toBe('a && b && (c || d)')
    const negated = updateNode(added, or.id, (g) => ({ ...(g as Group), negated: true }))
    expect(printCondition(negated)).toBe('a && b && !(c || d)')
    const flipped = updateNode(parseCondition('a || b && c'), parseCondition('x').id, (n) => n)
    expect(printCondition(flipped)).toBe('a || b && c')
    const root = parseCondition('a || b && c')
    const asAnd = updateNode(root, root.id, (g) => ({ ...(g as Group), op: 'and' }))
    expect(printCondition(asAnd)).toBe('a && b && c')
    expect(printCondition(updateNode(root, root.id, (g) => ({ ...(g as Group), children: [] })))).toBe('false')
  })

  it('writes typed values as CEL literals', () => {
    const model = parseCondition('true')
    const leaves: Leaf[] = [
      newLeaf('amount', '>', { kind: 'money', amount: '1500000.50', currency: 'VND', form: 'string' }),
      newLeaf('amount', '<=', { kind: 'money', amount: '2000', currency: 'VND', form: 'int' }),
      newLeaf('rate', '==', { kind: 'decimal', text: '0.05', form: 'string' }),
      newLeaf('day', '>=', { kind: 'date', text: '2026-11-01' }),
      newLeaf('name', 'startsWith', { kind: 'string', text: "O'Brien\\" }),
      newLeaf('code', 'notIn', { kind: 'list', items: [{ kind: 'int', text: '1' }, { kind: 'int', text: '2' }] }),
      newLeaf('tags', 'includes', { kind: 'string', text: 'VIP' }),
      newLeaf('limit', '>', { kind: 'expr', text: 'a ? 1 : 2' }),
      newLeaf('opt.field', 'has'),
    ]
    const root: Group = { ...model, children: leaves }
    const { source: _s, ...edited } = root
    expect(printCondition(edited as Group)).toBe(
      "amount > money('1500000.50', 'VND') && amount <= money(2000, 'VND') && rate == decimal('0.05') && day >= date('2026-11-01') && name.startsWith('O\\'Brien\\\\') && !(code in [1, 2]) && 'VIP' in tags && limit > (a ? 1 : 2) && has(opt.field)",
    )
  })
})

describe('moving condition blocks', () => {
  const ids = (group: Group) => group.children.map((c) => printCondition(c))

  it('moves up and down within a group with the keyboard', () => {
    const model = parseCondition('a && b && c')
    const c = model.children[2]!
    const up = moveBy(model, c.id, -1)
    expect(ids(up)).toEqual(['a', 'c', 'b'])
    expect(printCondition(up)).toBe('a && c && b')
    const top = moveBy(moveBy(up, c.id, -1), c.id, -1)
    expect(ids(top)).toEqual(['c', 'a', 'b'])
    expect(moveBy(model, model.children[0]!.id, -1)).toBe(model)
  })

  it('indents into the group above and outdents back', () => {
    const model = parseCondition('(x || y) && b')
    const b = model.children[1]!
    const inside = indent(model, b.id)
    expect(printCondition(inside)).toBe('(x || y || b)')
    expect(findParent(inside, b.id)!.parent.op).toBe('or')
    const out = outdent(inside, b.id)
    expect(printCondition(out)).toBe('(x || y) && b')
  })

  it('moves blocks between groups and into and out of exists blocks, as drag and drop does', () => {
    const model = parseCondition('list.exists(i, i.a > 1) && b && (c || d)')
    const [block, b, or] = model.children as [CondNode, CondNode, Group]
    const intoBlock = moveNode(model, b.id, (block as { body: Group }).body.id, 1)
    expect(printCondition(intoBlock)).toBe('list.exists(i, i.a > 1 && b) && (c || d)')
    expect(printCondition(outdent(intoBlock, b.id))).toBe('list.exists(i, i.a > 1) && b && (c || d)')
    const intoOr = moveNode(model, b.id, or.id, 0)
    expect(printCondition(intoOr)).toBe('list.exists(i, i.a > 1) && (b || c || d)')
    // A group cannot be moved into itself.
    expect(moveNode(model, or.id, or.id, 0)).toBe(model)
    expect(printCondition(removeNode(model, or.id))).toBe('list.exists(i, i.a > 1) && b')
    const wrapped = wrapInGroup(model, b.id)
    expect(findNode(wrapped, b.id)).toBeDefined()
    expect(findParent(wrapped, b.id)!.parent.op).toBe('or')
  })
})
