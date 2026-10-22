//
// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0
//

/**
 * The RuleStone expression language: <a href="https://cel.dev">CEL</a> with typed inputs and
 * an exact {@code decimal} type.
 *
 * <p>Most applications do not use this package directly; decision tables in
 * {@code vn.io.dxa.rulestone.core} compile their expressions through it. Use it to evaluate
 * stand-alone expressions:
 *
 * <ol>
 *   <li>declare variables and their {@link vn.io.dxa.rulestone.cel.ValueType}s in an
 *       {@link vn.io.dxa.rulestone.cel.ExpressionEnvironment};</li>
 *   <li>{@link vn.io.dxa.rulestone.cel.ExpressionEnvironment#compile compile} each expression
 *       once, which type-checks it;</li>
 *   <li>for each evaluation, {@link vn.io.dxa.rulestone.cel.ExpressionEnvironment#bind bind}
 *       the inputs once and evaluate any number of expressions against the resulting
 *       {@link vn.io.dxa.rulestone.cel.Bindings}.</li>
 * </ol>
 */
package vn.io.dxa.rulestone.cel;
