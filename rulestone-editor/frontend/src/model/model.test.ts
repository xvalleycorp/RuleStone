// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0

import { describe, expect, it } from 'vitest'
import { en } from '../i18n/en'
import { keysOf, setLocale, t } from '../i18n'
import { vi } from '../i18n/vi'
import { diffLines, withContext } from './diff'
import { fieldsAt, inputPaths, nameProblem, recordOf, fieldsOf } from './schema'
import { cleanTable, groupById, inForce, moveItem, nextRuleId, nextVersion, problemKey } from './table'

describe('table helpers', () => {
  it('suggests the next version', () => {
    expect(nextVersion('3')).toBe('4')
    expect(nextVersion('2026.11')).toBe('2026.12')
    expect(nextVersion('v09')).toBe('v10')
    expect(nextVersion('beta')).toBe('beta.1')
  })

  it('suggests unused rule ids', () => {
    expect(nextRuleId([{ id: 'R1', when: 'true' }, { id: 'R7', when: 'true' }, { id: 'X', when: 'true' }])).toBe('R8')
    expect(nextRuleId([])).toBe('R1')
  })

  it('cleans empty optional fields in file key order', () => {
    const clean = cleanTable({
      hitPolicy: 'FIRST', rules: [{ reasonCode: '', then: { a: '', b: "'x'" }, when: 'true', id: 'R1', description: '' }],
      outputs: {}, inputs: {}, description: '', version: '1', id: 't', effectiveFrom: '', effectiveTo: '2027-01-01',
    })
    expect(JSON.stringify(clean)).toBe(
      '{"id":"t","version":"1","effectiveTo":"2027-01-01","hitPolicy":"FIRST","inputs":{},"outputs":{},'
        + '"rules":[{"id":"R1","when":"true","then":{"b":"\'x\'"}}]}',
    )
  })

  it('moves rules', () => {
    const items = ['a', 'b', 'c']
    moveItem(items, 0, 2)
    expect(items).toEqual(['b', 'c', 'a'])
    moveItem(items, 0, 5)
    expect(items).toEqual(['b', 'c', 'a'])
  })

  it('knows which version is in force (end exclusive)', () => {
    const v = { effectiveFrom: '2026-01-01', effectiveTo: '2026-11-01' }
    expect(inForce(v, '2026-01-01')).toBe(true)
    expect(inForce(v, '2026-10-31')).toBe(true)
    expect(inForce(v, '2026-11-01')).toBe(false)
    expect(inForce({}, '1999-01-01')).toBe(true)
  })

  it('groups versions by decision', () => {
    const groups = groupById([
      { file: 'b.stone.json', id: 'b', version: '1', problems: [] },
      { file: 'a3.stone.json', id: 'a', version: '3', effectiveFrom: '2026-11-01', problems: [] },
      { file: 'a2.stone.json', id: 'a', version: '2', effectiveFrom: '2026-01-01', problems: [] },
      { file: 'broken.stone.json', problems: [] },
    ])
    expect(groups.map((g) => g.id)).toEqual(['a', 'b', 'broken.stone.json'])
    expect(groups[0].versions.map((v) => v.version)).toEqual(['2', '3'])
  })

  it('maps problems to cells', () => {
    const p = (location: object) => problemKey({ message: '', detail: '', location })
    expect(p({ ruleIndex: 2, field: 'when' })).toBe('rule:2:when')
    expect(p({ ruleIndex: 0, field: 'then', output: 'limit' })).toBe('rule:0:then:limit')
    expect(p({ ruleIndex: 1 })).toBe('rule:1:row')
    expect(p({ field: 'inputs', input: 'applicant.age' })).toBe('input:applicant.age')
    expect(p({ field: 'effectiveFrom' })).toBe('table:effectiveFrom')
  })
})

describe('schema', () => {
  const inputs = { applicant: { age: 'int', address: { city: 'string' } }, accounts: [{ balance: 'money' }] }

  it('lists readable paths, struct fields included', () => {
    expect(inputPaths(inputs, [{ name: 'businessDate', type: 'date' }]).map((p) => `${p.path}:${p.topLevel}`)).toEqual([
      'applicant:true', 'applicant.age:false', 'applicant.address:false', 'applicant.address.city:false',
      'accounts:true', 'businessDate:true',
    ])
    expect(fieldsAt(inputs, ['applicant']).map(([n]) => n)).toEqual(['age', 'address'])
    expect(fieldsAt(inputs, ['accounts'])).toEqual([])
  })

  it('round-trips types through the editable tree, keeping field order', () => {
    expect(JSON.stringify(recordOf(fieldsOf(inputs)))).toBe(JSON.stringify(inputs))
  })

  it('checks names', () => {
    expect(nameProblem('monthlyIncome')).toBeNull()
    expect(nameProblem('_x1')).toBeNull()
    expect(nameProblem('1x')).toBe('identifier')
    expect(nameProblem('in')).toBe('reserved')
  })
})

describe('diff', () => {
  it('shows added and removed lines', () => {
    const lines = diffLines('a\nb\nc\n', 'a\nB\nc\nd\n')
    expect(lines.map((l) => `${l.kind[0]}${l.text}`)).toEqual(['sa', 'rb', 'aB', 'sc', 'ad'])
    expect(withContext(diffLines('1\n2\n3\n4\n5\n6\n7\n8\n9\n', '1\n2\n3\n4\nX\n6\n7\n8\n9\n'), 1).map((l) => l?.text ?? '…'))
      .toEqual(['…', '4', '5', 'X', '6', '…'])
  })
})

describe('i18n', () => {
  it('has a Vietnamese text for every English text', () => {
    expect(keysOf(vi).sort()).toEqual(keysOf(en).sort())
  })

  it('fills placeholders and switches language', () => {
    setLocale('en')
    expect(t('workspace.problemCount', { n: 3 })).toBe('3 problem(s)')
    expect(t('cond.errors.invalid.decimal')).toBe('Enter a number such as 1234.50')
    setLocale('vi')
    expect(t('grid.reasonCode')).toBe('Mã lý do')
    expect(t('cond.ops.not in')).toBe('không thuộc')
    expect(t('no.such.key')).toBe('no.such.key')
    setLocale('en')
  })
})
