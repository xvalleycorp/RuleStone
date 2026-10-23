// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0

import { join } from 'node:path'
import { describe, expect, it } from 'vitest'
import { ISO4217_AMENDMENT, currencyTable, isCurrencyCode, minorUnits } from '../src/index.js'
import { REPO, read } from './helpers.js'

const SOURCE = join(REPO, 'rulestone-api', 'src', 'main', 'resources', 'vn', 'io', 'dxa', 'rulestone', 'api', 'iso4217.txt')

describe('ISO 4217 table', () => {
  it('is the table the Java engine reads (regenerate with npm run generate:iso4217)', () => {
    let amendment: number | undefined
    const expected = new Map<string, number>()
    for (const raw of read(SOURCE).split('\n')) {
      const line = raw.trim()
      if (line === '' || line.startsWith('#')) continue
      const [key, value] = line.split(' ') as [string, string]
      if (key === 'amendment') amendment = Number(value)
      else expected.set(key, value === '-' ? -1 : Number(value))
    }
    expect(ISO4217_AMENDMENT).toBe(amendment)
    expect([...currencyTable()]).toEqual([...expected])
  })

  it('knows current, recently added and withdrawn codes, the same on every JDK', () => {
    expect(minorUnits('VND')).toBe(0)
    expect(minorUnits('USD')).toBe(2)
    expect(minorUnits('KWD')).toBe(3)
    expect(minorUnits('XCG')).toBe(2)
    expect(minorUnits('ZWG')).toBe(2)
    expect(minorUnits('DEM')).toBe(2)
    expect(minorUnits('XAU')).toBeUndefined()
    expect(isCurrencyCode('ZZZ')).toBe(false)
    expect(isCurrencyCode('vnd')).toBe(false)
    expect(() => minorUnits('SECRET')).toThrow('not an ISO 4217 currency code')
  })
})
