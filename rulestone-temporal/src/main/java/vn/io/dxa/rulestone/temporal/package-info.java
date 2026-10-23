//
// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0
//

/**
 * Integration with Temporal (Java SDK).
 *
 * <p>Two styles:
 *
 * <ul>
 *   <li><b>Activity</b>: {@link vn.io.dxa.rulestone.temporal.RuleStoneActivities}, implemented by
 *       {@link vn.io.dxa.rulestone.temporal.RuleStoneActivitiesImpl}. The workflow sends a
 *       {@link vn.io.dxa.rulestone.temporal.DecisionRequest} and receives a
 *       {@link vn.io.dxa.rulestone.temporal.DecisionOutcome}; both are recorded in history.</li>
 *   <li><b>In workflow</b>: {@link vn.io.dxa.rulestone.temporal.RuleStoneWorkflow} evaluates inside
 *       workflow code and records only the business date and exact rule version, which it pins on
 *       replay.</li>
 * </ul>
 *
 * <p>{@link vn.io.dxa.rulestone.temporal.RuleStoneFailures} names the non-retryable failure types,
 * and {@link vn.io.dxa.rulestone.temporal.RuleStoneSearchAttributes} the search attributes for finding
 * workflows by outcome. Payloads carry decimals, money and dates as exact text, never floating point.
 * Nothing in this package logs input values.
 *
 * <p>The Temporal SDK is a {@code provided} dependency: add {@code io.temporal:temporal-sdk} to your
 * application. See {@code docs/temporal.md}.
 */
package vn.io.dxa.rulestone.temporal;
