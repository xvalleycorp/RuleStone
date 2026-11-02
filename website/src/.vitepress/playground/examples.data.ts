// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0
//
// Build-time data for the playground: the example tables of the repository's examples/
// folder, one case of each table's .stone-test.json as sample facts, the examples' reason-code
// catalog, and the example
// decision flows of examples/flows/ with their tables and a test case each. They are read when the site is built, so the playground
// always shows the same files as the repository, and nothing is copied by hand.

import { readFileSync } from 'node:fs';
import { basename } from 'node:path';

export interface PlaygroundExample {
  /** File name without .stone.json, e.g. loan_eligibility.v3. */
  name: string;
  /** The .stone.json file, as written in the repository. */
  text: string;
  /** Sample facts (JSON text) and business date from one test case. */
  facts?: string;
  asOf?: string;
  /** The test case the facts come from. */
  caseName?: string;
  featured: boolean;
}

export interface PlaygroundFlow {
  /** File name without .flow.json, e.g. card_origination_scored. */
  name: string;
  /** The .flow.json file, as written in the repository. */
  text: string;
  /** The test case the sample facts and model results come from. */
  caseName: string;
  facts: string;
  /** The case's model results, by node id, as JSON text. */
  models?: string;
  asOf?: string;
}

// The example flows, in this order, with a test case each whose path is worth looking at:
// a model result with a reason code, and the champion limit of an existing customer.
const FLOW_CASES = new Map([
  ['card_origination_scored', "an existing customer with a low PD gets grade A, with the model's reason code"],
  ['card_origination', 'an existing customer with low utilisation gets grade A and the champion limit'],
]);

interface FlowCase {
  name: string;
  asOf?: string;
  facts: unknown;
  models?: unknown;
}

export interface PlaygroundData {
  examples: PlaygroundExample[];
  /** Every .params.json parameter set of examples/, as written, for the tables that read them. */
  paramSets: string[];
  /** examples/reason-codes.json, the reason-code catalog that explains the examples' decisions. */
  reasonCodes: string;
  /** The example flows of examples/flows/, as written, with a sample case each. */
  flows: PlaygroundFlow[];
  /** The tables of examples/flows/, which the flows use, as written. */
  flowTables: string[];
}

// The examples shown first, each with a test case whose result is worth looking at: a referral,
// a VIP rate, three fees at once, several fraud signals, a grade C approval with limits, and a
// fuzzy name match without diacritics.
const FEATURED: [string, string][] = [
  ['loan_eligibility.v3', 'amount above 12 monthly incomes is referred for review'],
  ['deposit_rate', '12 months from 5 billion earns the VIP rate'],
  ['transfer_fees', 'express interbank transfer at the counter pays all three fees'],
  ['card_fraud_signals', 'night-time crypto purchase from a new device scores 85'],
  ['consumer_loan_underwriting', 'secured vehicle loan for a self-employed applicant gets grade C'],
  ['aml_name_screening', 'listed name without diacritics is a possible sanctions match'],
];

interface TestCase {
  name: string;
  asOf?: string;
  facts: unknown;
  expect?: { error?: string };
}

export default {
  watch: [
    '../../../../examples/*.stone.json',
    '../../../../examples/*.stone-test.json',
    '../../../../examples/*.params.json',
    '../../../../examples/reason-codes.json',
    '../../../../examples/flows/*.flow.json',
    '../../../../examples/flows/*.stone.json',
    '../../../../examples/flows/*.stone-test.json',
  ],
  load(files: string[]): PlaygroundData {
    const inFlows = (f: string) => f.replace(/\\/g, '/').includes('/examples/flows/');
    const read = (suffix: string) => files.filter((f) => f.endsWith(suffix) && !inFlows(f));
    const readFlows = (suffix: string) => files.filter((f) => f.endsWith(suffix) && inFlows(f)).sort();
    const tests = new Map(
      read('.stone-test.json').map((f) => [basename(f, '.stone-test.json'), JSON.parse(readFileSync(f, 'utf8')).cases as TestCase[]]),
    );
    const featured = new Map(FEATURED);
    const examples = read('.stone.json').map((file): PlaygroundExample => {
      const name = basename(file, '.stone.json');
      const cases = tests.get(name) ?? [];
      const wanted = featured.get(name);
      const chosen = wanted
        ? cases.find((c) => c.name === wanted)
        : (cases.find((c) => !c.expect?.error && c.asOf !== undefined) ?? cases.find((c) => !c.expect?.error));
      if (wanted && !chosen) throw new Error(`examples/${name}.stone-test.json has no case named "${wanted}" (website playground)`);
      return {
        name,
        text: readFileSync(file, 'utf8'),
        featured: featured.has(name),
        ...(chosen ? { facts: JSON.stringify(chosen.facts, null, 2), caseName: chosen.name } : {}),
        ...(chosen?.asOf ? { asOf: chosen.asOf } : {}),
      };
    });
    for (const [name] of FEATURED) {
      if (!examples.some((e) => e.name === name)) throw new Error(`examples/${name}.stone.json is missing (website playground)`);
    }
    const order = (e: PlaygroundExample) => (e.featured ? FEATURED.findIndex(([n]) => n === e.name) : FEATURED.length);
    examples.sort((a, b) => order(a) - order(b) || a.name.localeCompare(b.name));

    // The example flows, the scored one first, each with the case of its test file named in FLOW_CASES.
    const flowTests = new Map(
      readFlows('.stone-test.json').map((f) => [basename(f, '.stone-test.json'), JSON.parse(readFileSync(f, 'utf8')).cases as FlowCase[]]),
    );
    const flows = readFlows('.flow.json').map((file): PlaygroundFlow => {
      const name = basename(file, '.flow.json');
      const wanted = FLOW_CASES.get(name);
      const chosen = (flowTests.get(name) ?? []).find((c) => c.name === wanted);
      if (!chosen) throw new Error(`examples/flows/${name}.stone-test.json has no case named "${wanted}" (website playground)`);
      return {
        name,
        text: readFileSync(file, 'utf8'),
        caseName: chosen.name,
        facts: JSON.stringify(chosen.facts, null, 2),
        ...(chosen.models ? { models: JSON.stringify(chosen.models, null, 2) } : {}),
        ...(chosen.asOf ? { asOf: chosen.asOf } : {}),
      };
    });
    flows.sort((a, b) => [...FLOW_CASES.keys()].indexOf(a.name) - [...FLOW_CASES.keys()].indexOf(b.name));
    const flowTables = readFlows('.stone.json').map((f) => readFileSync(f, 'utf8'));

    const paramSets = read('.params.json').sort().map((f) => readFileSync(f, 'utf8'));

    const catalog = read('reason-codes.json')[0];
    if (!catalog) throw new Error('examples/reason-codes.json is missing (website playground)');
    const reasonCodes = readFileSync(catalog, 'utf8');

    return { examples, paramSets, reasonCodes, flows, flowTables };
  },
};
