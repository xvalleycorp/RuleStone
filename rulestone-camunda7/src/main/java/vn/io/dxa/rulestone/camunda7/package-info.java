//
// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0
//

/**
 * Integration with the Camunda 7 process engine: evaluate RuleStone decisions from BPMN service
 * tasks and expressions.
 *
 * <ul>
 *   <li>{@link vn.io.dxa.rulestone.camunda7.RuleStoneDecisionService} holds the engine and decision
 *       tables, reads process variables and evaluates. It is also the expression bean:
 *       {@code ${rulestone.evaluate('loan_eligibility', execution).output.decision}}.</li>
 *   <li>{@link vn.io.dxa.rulestone.camunda7.RuleStoneDelegate} is the {@code JavaDelegate} for
 *       service tasks, configured by field injection.</li>
 *   <li>{@link vn.io.dxa.rulestone.camunda7.RuleStoneProcessEnginePlugin} registers the service with a
 *       process engine, so that delegates and expressions find it.</li>
 * </ul>
 *
 * <p>Variables are read with the input types the decision declares, through
 * {@code vn.io.dxa.rulestone.core.FactsJson}; only declared inputs are read, and floating-point
 * values are rejected. Errors in the data or the rules become a {@code BpmnError}; configuration
 * errors, such as an unknown decision, a {@link vn.io.dxa.rulestone.camunda7.RuleStoneConfigurationException}.
 * Messages never contain variable values, and nothing in this package logs.
 *
 * <p>Compiled against Camunda 7.24 ({@code org.camunda.bpm:camunda-engine}, a {@code provided}
 * dependency). See {@code docs/camunda.md}.
 */
package vn.io.dxa.rulestone.camunda7;
