// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0

import { readFileSync, readdirSync } from 'node:fs'
import { join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { createEngine, parseDecisionTable, type DecisionTable, type RuleStoneEngine } from '../src/index.js'

/** The repository root. */
export const REPO = fileURLToPath(new URL('../../../../', import.meta.url))
export const EXAMPLES = join(REPO, 'examples')
export const FIXTURES = fileURLToPath(new URL('./fixtures/', import.meta.url))

export function read(path: string): string {
  return readFileSync(path, 'utf8')
}

export function fixture<T = unknown>(name: string): T {
  return JSON.parse(read(join(FIXTURES, name))) as T
}

/** Files in a directory with a suffix, sorted as Java sorts paths. */
export function files(dir: string, suffix: string): string[] {
  return readdirSync(dir)
    .filter((f) => f.endsWith(suffix))
    .sort()
}

export function exampleTables(): DecisionTable[] {
  return files(EXAMPLES, '.stone.json').map((f) => parseDecisionTable(read(join(EXAMPLES, f))))
}

/** The engine of the example tests, with the Java tests' clock: 2026-12-01 in Hanoi. */
export function exampleEngine(tables = exampleTables()): Promise<RuleStoneEngine> {
  return createEngine({
    tables,
    clock: () => new Date('2026-12-01T03:00:00Z'),
    timeZone: 'Asia/Ho_Chi_Minh',
    recordIds: () => 'fixture',
  })
}
