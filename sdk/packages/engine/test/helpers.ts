// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0

import { readFileSync, readdirSync } from 'node:fs'
import { join } from 'node:path'
import { fileURLToPath } from 'node:url'
import {
  createEngine, parseDecisionFlow, parseDecisionTable, parseParameterSet, parseReasonCodeCatalog, type DecisionFlow,
  type DecisionTable, type ParameterSet,
  type ReasonCodeCatalog, type RuleStoneEngine,
} from '../src/index.js'

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

export function exampleParamSets(): ParameterSet[] {
  return files(EXAMPLES, '.params.json').map((f) => parseParameterSet(read(join(EXAMPLES, f))))
}

/** The examples' reason-code catalog, examples/reason-codes.json. */
export function exampleCatalog(): ReasonCodeCatalog {
  return parseReasonCodeCatalog(read(join(EXAMPLES, 'reason-codes.json')))
}

export const FLOWS = join(EXAMPLES, 'flows')

/** The tables of examples/flows/. */
export function flowTables(): DecisionTable[] {
  return files(FLOWS, '.stone.json').map((f) => parseDecisionTable(read(join(FLOWS, f))))
}

/** The flows of examples/flows/. */
export function exampleFlows(): DecisionFlow[] {
  return files(FLOWS, '.flow.json').map((f) => parseDecisionFlow(read(join(FLOWS, f))))
}

/** The engine of the example flow tests, as ExamplesStoneTest builds it in Java. */
export function exampleFlowEngine(): Promise<RuleStoneEngine> {
  return createEngine({
    tables: [...exampleTables(), ...flowTables()],
    flows: exampleFlows(),
    paramSets: exampleParamSets(),
    suppliedModelsOnly: true,
    clock: () => new Date('2026-12-01T03:00:00Z'),
    timeZone: 'Asia/Ho_Chi_Minh',
    recordIds: () => 'fixture',
    reasonCodeCatalog: exampleCatalog(),
    requireReasonCodeCatalog: true,
  })
}

/** The engine of the example tests, with the Java tests' clock: 2026-12-01 in Hanoi, and the examples' catalog required. */
export function exampleEngine(tables = exampleTables()): Promise<RuleStoneEngine> {
  return createEngine({
    tables,
    paramSets: exampleParamSets(),
    clock: () => new Date('2026-12-01T03:00:00Z'),
    timeZone: 'Asia/Ho_Chi_Minh',
    recordIds: () => 'fixture',
    reasonCodeCatalog: exampleCatalog(),
    requireReasonCodeCatalog: true,
  })
}
