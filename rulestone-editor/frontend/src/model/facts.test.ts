// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0

import { describe, expect, it } from 'vitest'
import type { TypeSpec } from '../types'
import { emptyForm, factsJson, formFromJson } from './facts'

const INPUTS: Record<string, TypeSpec> = {
  applicant: { age: 'int', monthlyIncome: 'decimal', segment: 'string', blacklisted: 'bool', birthDate: 'date' },
  amount: 'money',
  tags: ['string'],
}

describe('facts form', () => {
  it('writes the shared JSON forms and leaves empty fields out', () => {
    const form = emptyForm(INPUTS)
    const applicant = form.applicant
    if (applicant.kind !== 'struct') throw new Error()
    Object.assign(applicant.fields.age, { text: '9007199254740993' })
    Object.assign(applicant.fields.monthlyIncome, { text: '25750000.50' })
    Object.assign(applicant.fields.blacklisted, { text: 'false' })
    Object.assign(applicant.fields.birthDate, { text: '1990-05-17' })
    Object.assign(form.amount, { text: '300000000  VND' })
    if (form.tags.kind === 'list') form.tags.items.push({ kind: 'scalar', text: 'VIP' })

    expect(factsJson(form, INPUTS)).toBe(
      '{\n  "applicant": {"age": 9007199254740993, "monthlyIncome": "25750000.50", "blacklisted": false, "birthDate": "1990-05-17"},\n'
        + '  "amount": "300000000 VND",\n  "tags": ["VIP"]\n}',
    )
  })

  it('writes {} for an empty form', () => {
    expect(factsJson(emptyForm({ x: 'int' }), { x: 'int' })).toBe('{}')
  })
})

describe('JSON to form', () => {
  it('keeps the digits of numbers when the browser supports JSON source text', () => {
    const form = formFromJson('{"applicant": {"age": 30, "segment": "SME"}, "amount": "1 VND"}', INPUTS)
    const applicant = form.applicant
    expect(applicant.kind === 'struct' && applicant.fields.age).toEqual({ kind: 'scalar', text: '30' })
    expect(applicant.kind === 'struct' && applicant.fields.segment).toEqual({ kind: 'scalar', text: 'SME' })
    expect(form.amount).toEqual({ kind: 'scalar', text: '1 VND' })
  })
})
