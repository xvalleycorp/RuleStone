//
// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0
//

/**
 * Integration with Camunda 8 (Zeebe): a job worker that evaluates RuleStone decisions for BPMN
 * service tasks.
 *
 * <p>{@link vn.io.dxa.rulestone.camunda8.RuleStoneJobWorker#register} opens a worker for the job
 * type {@code rulestone:decision}; {@link vn.io.dxa.rulestone.camunda8.RuleStoneJobHandler} is the
 * handler, for applications that build their own worker. Each service task names its decision in
 * custom task headers ({@link vn.io.dxa.rulestone.camunda8.RuleStoneHeaders}), which the Camunda
 * Modeler element template {@code element-templates/rulestone-decision.json} edits.
 *
 * <p>Process variables are read in RuleStone's JSON forms ({@code vn.io.dxa.rulestone.core.FactsJson}),
 * guided by the input types the decision declares; only declared inputs are read. Results are
 * written in the same forms: decimals, money and dates as strings, so they never pass through
 * floating point. Error messages name decisions, inputs and types, never variable values, and
 * nothing in this package logs.
 *
 * <p>The Camunda client is a {@code provided} dependency: add {@code io.camunda:camunda-client-java}
 * to your application. See {@code docs/camunda.md}.
 */
package vn.io.dxa.rulestone.camunda8;
