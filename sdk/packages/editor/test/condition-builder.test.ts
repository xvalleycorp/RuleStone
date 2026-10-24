// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0

import { afterEach, describe, expect, it } from 'vitest'
import '../src/elements/condition-builder.js'
import type { RulestoneConditionBuilder } from '../src/elements/condition-builder.js'
import { $, $$, exampleTable, settle } from './helpers.js'

const inputs = exampleTable('consumer_loan_underwriting.stone.json').inputs

async function mount(value: string, locale = 'en'): Promise<RulestoneConditionBuilder> {
  const el = document.createElement('rulestone-condition-builder')
  el.locale = locale
  el.inputs = inputs
  el.value = value
  document.body.append(el)
  await settle(el)
  return el
}

afterEach(() => document.body.replaceChildren())

describe('<rulestone-condition-builder>', () => {
  it('shows a condition as blocks with typed fields, operators and values', async () => {
    const el = await mount("applicant.creditScore >= 750 && (loan.purpose == 'VEHICLE' || !has(loan.collateralValue))")
    const groups = $$(el, '[part="group"]')
    expect(groups).toHaveLength(2)
    expect(groups[0]!.getAttribute('aria-label')).toBe('All of these are true (AND)')
    expect(groups[1]!.getAttribute('aria-label')).toBe('Any of these is true (OR)')
    const fields = $$(el, 'select.field').map((s) => s.querySelector<HTMLOptionElement>('option[selected]')?.value)
    expect(fields).toEqual(['applicant.creditScore', 'loan.purpose', 'loan.collateralValue'])
    const value = $(el, 'input.number') as HTMLInputElement
    expect(value.value).toBe('750')
  })

  it('emits the edited condition as CEL, keeping the unedited parts exactly', async () => {
    const el = await mount("applicant.creditScore>=750 && loan.purpose == 'VEHICLE'")
    const changes: string[] = []
    el.addEventListener('rulestone-change', (e) => changes.push((e as CustomEvent<{ value: string }>).detail.value))
    const value = $(el, 'input.number') as HTMLInputElement
    value.value = '700'
    value.dispatchEvent(new Event('change'))
    await settle(el)
    expect(changes).toEqual(["applicant.creditScore >= 700 && loan.purpose == 'VEHICLE'"])
  })

  it('switches to CEL text and back without changing the text', async () => {
    const text = 'daysBetween(businessDate, businessDate) >= 1 && applicant.hasBadDebtLast5y'
    const el = await mount(text)
    expect($(el, '.block.raw .reason')!.textContent).toContain('calculation')
    const [, cel] = $$(el, '[part="toolbar"] button')
    cel!.click()
    await settle(el)
    const input = $(el, 'rs-cel-input') as HTMLElement & { value: string }
    expect(input.value).toBe(text)
    $$(el, '[part="toolbar"] button')[0]!.click()
    await settle(el)
    expect(el.value).toBe(text)
  })

  it('moves blocks with Alt+Arrow keys and announces where they went', async () => {
    const el = await mount('applicant.age > 20 && applicant.creditScore > 600 && loan.amount > 1')
    const handles = $$(el, 'button.handle')
    handles[2]!.dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowUp', altKey: true, bubbles: true }))
    await settle(el)
    expect(el.value).toBe('applicant.age > 20 && loan.amount > 1 && applicant.creditScore > 600')
    expect($(el, '[role="status"]')!.textContent).toBe('Block moved to position 2 of 3.')
  })

  it('speaks Vietnamese', async () => {
    const el = await mount('applicant.age > 20', 'vi')
    expect($$(el, '[part="group"]')[0]!.getAttribute('aria-label')).toBe('Tất cả đều đúng (VÀ)')
  })
})
