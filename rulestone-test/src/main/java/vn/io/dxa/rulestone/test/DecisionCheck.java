//
// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.rulestone.test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import vn.io.dxa.rulestone.api.DecisionRef;
import vn.io.dxa.rulestone.api.DecisionResult;
import vn.io.dxa.rulestone.api.Facts;
import vn.io.dxa.rulestone.api.ModelResult;
import vn.io.dxa.rulestone.api.ModelStatus;
import vn.io.dxa.rulestone.api.RuleStoneEngine;
import vn.io.dxa.rulestone.api.RuleStoneException;

/**
 * Fluent assertions on one evaluation of a decision. Works with any test framework: a failed
 * expectation throws {@link AssertionError}.
 *
 * <pre>{@code
 * DecisionCheck.of(engine)
 *     .decision("loan_eligibility").asOf(LocalDate.of(2026, 12, 1))
 *     .given("applicant", applicant).given("amount", new BigDecimal("300000000"))
 *     .expectOutput("decision", "APPROVE")
 *     .expectOutput("limit", new BigDecimal("309000000"))
 *     .expectReasonCodes()
 *     .expectMatchedRules("R4");
 * }</pre>
 *
 * <p>The decision is evaluated once, by the first {@code expect…} call or {@link #result()};
 * after that the request can no longer be changed. Numbers are compared by value, so
 * {@code 309000000} equals {@code 309000000.00}; floating-point expected values are rejected.
 * Failure messages show expected and actual outputs, never the input facts.
 *
 * <p>Instances are not thread-safe; use one per evaluation.
 */
public final class DecisionCheck {

    private final RuleStoneEngine engine;
    private String decision;
    private String version;
    private LocalDate asOf;
    private Facts facts = Facts.empty();

    private boolean evaluated;
    private DecisionResult result;
    private RuntimeException failure;

    private DecisionCheck(RuleStoneEngine engine) {
        this.engine = Objects.requireNonNull(engine, "engine");
    }

    /**
     * Starts a check against {@code engine}.
     *
     * @param engine the engine that evaluates the decision
     * @return a new check; name the decision with {@link #decision(String)}
     */
    public static DecisionCheck of(RuleStoneEngine engine) {
        return new DecisionCheck(engine);
    }

    // --- Request ---------------------------------------------------------------------------------

    /**
     * Names the decision to evaluate. Required.
     *
     * @param id the decision id, e.g. {@code "loan_eligibility"}
     * @return this check
     */
    public DecisionCheck decision(String id) {
        requireNotEvaluated();
        this.decision = Objects.requireNonNull(id, "id");
        return this;
    }

    /**
     * Evaluates exactly this version, regardless of effective dates.
     *
     * @param version the version label, e.g. {@code "2"}
     * @return this check
     */
    public DecisionCheck version(String version) {
        requireNotEvaluated();
        this.version = Objects.requireNonNull(version, "version");
        return this;
    }

    /**
     * Evaluates the version effective on {@code date}. Recommended for decisions with effective
     * dates: without it the engine uses its clock, and the test depends on when it runs.
     *
     * @param date the business date
     * @return this check
     */
    public DecisionCheck asOf(LocalDate date) {
        requireNotEvaluated();
        this.asOf = Objects.requireNonNull(date, "date");
        return this;
    }

    /**
     * Adds one input fact.
     *
     * @param name  the fact name
     * @param value the value, as for {@link Facts#with(String, Object)}
     * @return this check
     * @throws IllegalArgumentException if a fact with that name was already given
     */
    public DecisionCheck given(String name, Object value) {
        requireNotEvaluated();
        facts = facts.with(name, value);
        return this;
    }

    /**
     * Adds every fact of {@code more}, and the model results it carries.
     *
     * @param more facts to add
     * @return this check
     * @throws IllegalArgumentException if a fact with one of those names, or a result for one of
     *                                  those model nodes, was already given
     */
    public DecisionCheck given(Facts more) {
        requireNotEvaluated();
        more.asMap().forEach((name, value) -> facts = facts.with(name, value));
        facts = facts.withModels(more.models());
        return this;
    }

    /**
     * Gives the result of the model of a {@code model} node, as {@link Facts#withModelResult} does.
     * Build the engine with {@code suppliedModelsOnly()} to give results for nodes in
     * {@code invoke} mode too.
     *
     * @param node   the id of the model node
     * @param result what the model returned
     * @return this check
     * @throws IllegalArgumentException if a result for {@code node} was already given
     */
    public DecisionCheck givenModelResult(String node, ModelResult result) {
        requireNotEvaluated();
        facts = facts.withModelResult(node, result);
        return this;
    }

    /**
     * Reports that the model of a {@code model} node failed, as {@link Facts#withModelFailure} does.
     *
     * @param node    the id of the model node
     * @param failure {@link ModelStatus#TIMEOUT} or {@link ModelStatus#ERROR}
     * @return this check
     * @throws IllegalArgumentException if a result for {@code node} was already given
     */
    public DecisionCheck givenModelFailure(String node, ModelStatus failure) {
        requireNotEvaluated();
        facts = facts.withModelFailure(node, failure);
        return this;
    }

    // --- Expectations on a result ----------------------------------------------------------------

    /**
     * Expects output {@code name} to equal {@code expected}. Other outputs are not checked.
     *
     * @param name     the output name
     * @param expected a {@code String}, {@code Boolean}, integer, {@code BigDecimal} or {@code List} of these;
     *                 numbers are compared by value
     * @return this check
     * @throws IllegalArgumentException if {@code expected} is {@code null} or contains a {@code Double} or
     *                                  {@code Float}; use {@link #expectNoOutput(String)} for an absent output
     */
    public DecisionCheck expectOutput(String name, Object expected) {
        Objects.requireNonNull(name, "name");
        if (expected == null) {
            throw new IllegalArgumentException("expected value of output \"" + name
                    + "\" is null; use expectNoOutput(name) to expect an absent output");
        }
        Expectations.requireExact(expected, "expected value of output \"" + name + "\"");
        Expectations.output(result(), name, expected).ifPresent(this::fail);
        return this;
    }

    /**
     * Expects the decision not to produce output {@code name}, as when a {@code FIRST} rule
     * sets only some outputs.
     *
     * @param name the output name
     * @return this check
     */
    public DecisionCheck expectNoOutput(String name) {
        Objects.requireNonNull(name, "name");
        Expectations.output(result(), name, null).ifPresent(this::fail);
        return this;
    }

    /**
     * Expects exactly these outputs: every listed output equal, and no other output present.
     *
     * @param expected output names and values, compared as in {@link #expectOutput(String, Object)}
     * @return this check
     */
    public DecisionCheck expectExactOutput(Map<String, ?> expected) {
        expected.forEach((name, value) ->
                Expectations.requireExact(value, "expected value of output \"" + name + "\""));
        DecisionResult actual = result();
        expected.forEach((name, value) -> Expectations.output(actual, name, value).ifPresent(this::fail));
        Expectations.unexpectedOutputs(actual, expected).stream().findFirst().ifPresent(this::fail);
        return this;
    }

    /**
     * Expects exactly these reason codes, in this order. With no arguments, expects none.
     *
     * @param codes the expected reason codes
     * @return this check
     */
    public DecisionCheck expectReasonCodes(String... codes) {
        Expectations.reasonCodes(result(), List.of(codes)).ifPresent(this::fail);
        return this;
    }

    /**
     * Expects exactly these rules to have matched, in evaluation order. With no arguments,
     * expects that no rule matched.
     *
     * @param ruleIds the expected rule ids
     * @return this check
     */
    public DecisionCheck expectMatchedRules(String... ruleIds) {
        Expectations.matchedRules(result(), List.of(ruleIds)).ifPresent(this::fail);
        return this;
    }

    /**
     * Expects the evaluation to visit exactly these nodes of a decision flow, in order, from the
     * start node to the end node. For a decision table the path is the decision id alone.
     *
     * @param nodes the expected node ids, in order
     * @return this check
     * @throws AssertionError if the path differs, or the evaluation failed
     */
    public DecisionCheck expectPath(String... nodes) {
        Expectations.path(result(), List.of(nodes)).ifPresent(this::fail);
        return this;
    }

    /**
     * Expects the evaluation to have used this rule version: {@code "3"} accepts any content of
     * version 3, {@code "3@sha256:…"} only that exact content.
     *
     * @param version a version label, or a full rule version with content hash
     * @return this check
     */
    public DecisionCheck expectRuleVersion(String version) {
        Objects.requireNonNull(version, "version");
        Expectations.ruleVersion(result(), version).ifPresent(this::fail);
        return this;
    }

    /**
     * Evaluates the decision if not done yet and returns the result, for checks this class does
     * not offer.
     *
     * @return the result of the evaluation
     * @throws AssertionError if the evaluation failed
     */
    public DecisionResult result() {
        evaluate();
        if (failure != null) {
            throw new AssertionError(subject() + ": evaluation failed: " + failure.getMessage(), failure);
        }
        return result;
    }

    // --- Expectations on a failure ---------------------------------------------------------------

    /**
     * Expects the evaluation to fail with an exception of {@code type}, for example
     * {@code DecisionEvaluationException} or {@code DecisionNotFoundException}.
     *
     * @param type the expected exception type (or a supertype of it)
     * @return this check
     */
    public DecisionCheck expectFailure(Class<? extends RuntimeException> type) {
        Objects.requireNonNull(type, "type");
        evaluate();
        if (failure == null) {
            fail("expected " + type.getSimpleName() + " but the evaluation succeeded with "
                    + Expectations.describe(result));
        } else if (!type.isInstance(failure)) {
            throw new AssertionError(subject() + ": expected " + type.getSimpleName() + " but the evaluation threw "
                    + failure.getClass().getSimpleName() + ": " + failure.getMessage(), failure);
        }
        return this;
    }

    /**
     * Expects the evaluation to fail with a {@link RuleStoneException} whose message contains
     * {@code fragment}, such as {@code "division by zero"} or {@code "balance: missing input"}.
     *
     * @param fragment text the exception message must contain
     * @return this check
     */
    public DecisionCheck expectFailureMessage(String fragment) {
        Objects.requireNonNull(fragment, "fragment");
        evaluate();
        Optional<String> problem = failureProblem(failure, result, fragment);
        if (problem.isPresent()) {
            throw new AssertionError(subject() + ": " + problem.get(), failure);
        }
        return this;
    }

    /** Shared with {@link StoneTestCase}: describes why an expected failure did not happen as expected. */
    static Optional<String> failureProblem(RuntimeException failure, DecisionResult result, String fragment) {
        if (failure == null) {
            return Optional.of("expected the evaluation to fail with a message containing "
                    + Expectations.quote(fragment) + " but it succeeded with " + Expectations.describe(result));
        }
        if (!(failure instanceof RuleStoneException)) {
            return Optional.of("expected a RuleStoneException but the evaluation threw "
                    + failure.getClass().getName() + ": " + failure.getMessage());
        }
        String message = String.valueOf(failure.getMessage());
        return message.contains(fragment) ? Optional.empty()
                : Optional.of("expected a failure message containing " + Expectations.quote(fragment)
                        + " but the message was: " + message);
    }

    // ---------------------------------------------------------------------------------------------

    private void evaluate() {
        if (evaluated) {
            return;
        }
        if (decision == null) {
            throw new IllegalStateException("name the decision with decision(id) before any expectation");
        }
        evaluated = true;
        DecisionRef ref = DecisionRef.of(decision);
        if (version != null) {
            ref = ref.version(version);
        }
        if (asOf != null) {
            ref = ref.asOf(asOf);
        }
        try {
            result = engine.evaluate(ref, facts);
        } catch (RuntimeException e) {
            failure = e;
        }
    }

    private void requireNotEvaluated() {
        if (evaluated) {
            throw new IllegalStateException("the decision was already evaluated; start a new DecisionCheck");
        }
    }

    /** {@code loan_eligibility@3 (asOf 2026-12-01)}: the decision, the version used if known, and the request. */
    private String subject() {
        StringBuilder text = new StringBuilder(decision);
        if (result != null) {
            text.append('@').append(Expectations.versionLabel(result.ruleVersion()));
        }
        String request = (version == null ? "" : "version " + version)
                + (version != null && asOf != null ? ", " : "")
                + (asOf == null ? "" : "asOf " + asOf);
        if (!request.isEmpty()) {
            text.append(" (").append(request).append(')');
        }
        return text.toString();
    }

    private void fail(String problem) {
        throw new AssertionError(subject() + ": " + problem);
    }
}
