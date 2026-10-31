// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0
//
// Build-time data for the playground: the example tables of the repository's examples/
// folder, one case of each table's .stone-test.json as sample facts, and the example
// decision flow of docs/hybrid-ai.md. They are read when the site is built, so the playground
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

export interface PlaygroundData {
  examples: PlaygroundExample[];
  /** Every .params.json parameter set of examples/, as written, for the tables that read them. */
  paramSets: string[];
  /** The card_origination flow of docs/hybrid-ai.md, section 3.2. */
  flow: string;
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
    '../../../../docs/hybrid-ai.md',
  ],
  load(files: string[]): PlaygroundData {
    const read = (suffix: string) => files.filter((f) => f.endsWith(suffix));
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

    // The first JSON block of docs/hybrid-ai.md that defines the card_origination flow.
    const doc = readFileSync(read('hybrid-ai.md')[0]!, 'utf8');
    const flow = [...doc.matchAll(/```json\n([\s\S]*?)\n```/g)]
      .map((m) => m[1]!)
      .find((block) => /"id": "card_origination"/.test(block) && /"nodes"/.test(block));
    if (!flow) throw new Error('docs/hybrid-ai.md has no card_origination flow (website playground)');
    JSON.parse(flow);

    const paramSets = read('.params.json').sort().map((f) => readFileSync(f, 'utf8'));

    return { examples, paramSets, flow: flow + '\n' };
  },
};
