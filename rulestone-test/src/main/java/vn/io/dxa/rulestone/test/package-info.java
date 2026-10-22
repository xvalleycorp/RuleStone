//
// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0
//

/**
 * Tools for unit-testing decision tables.
 *
 * <ul>
 *   <li>{@link vn.io.dxa.rulestone.test.DecisionCheck}: fluent assertions on one evaluation, for
 *       any test framework.</li>
 *   <li>{@link vn.io.dxa.rulestone.test.StoneTestJson}: reads test cases written as
 *       {@code .stone-test.json} files, so rules can be tested without writing Java.</li>
 *   <li>{@link vn.io.dxa.rulestone.test.StoneTests}: runs those files as JUnit 5 dynamic tests.</li>
 *   <li>{@link vn.io.dxa.rulestone.test.RuleCoverage}: reports, per decision version, the rules
 *       no passing test has matched.</li>
 * </ul>
 *
 * <p>Use this module with test scope. Only {@code StoneTests} needs JUnit, which the module
 * declares as {@code provided}.
 */
package vn.io.dxa.rulestone.test;
