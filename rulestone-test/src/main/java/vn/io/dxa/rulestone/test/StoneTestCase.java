//
// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.rulestone.test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import vn.io.dxa.rulestone.api.DecisionRef;
import vn.io.dxa.rulestone.api.DecisionResult;
import vn.io.dxa.rulestone.api.Facts;
import vn.io.dxa.rulestone.api.RuleStoneEngine;
import vn.io.dxa.rulestone.api.RuleStoneException;

/**
 * One test case read from a {@code .stone-test.json} file by {@link StoneTestJson}: a request
 * (decision, optional version and date, facts) and the expected outcome.
 *
 * <p>Instances are immutable and thread-safe.
 */
public final class StoneTestCase {

    private final String source;
    private final String decision;
    private final String name;
    private final Optional<String> description;
    private final Optional<String> version;
    private final Optional<LocalDate> asOf;
    private final Map<String, String> params;
    private final Facts facts;

    // Expectations; an absent Optional or an empty map means "not checked".
    private final Map<String, Object> output;   // a null value means "this output must be absent"
    private final boolean exactOutput;
    private final Optional<List<String>> reasonCodes;
    private final Optional<List<String>> matchedRules;
    private final Optional<List<String>> path;
    private final Optional<String> ruleVersion;
    private final Optional<String> error;

    StoneTestCase(String source, String decision, String name, Optional<String> description,
                  Optional<String> version, Optional<LocalDate> asOf, Map<String, String> params, Facts facts,
                  Map<String, Object> output, boolean exactOutput, Optional<List<String>> reasonCodes,
                  Optional<List<String>> matchedRules, Optional<List<String>> path, Optional<String> ruleVersion,
                  Optional<String> error) {
        this.source = Objects.requireNonNull(source, "source");
        this.decision = Objects.requireNonNull(decision, "decision");
        this.name = Objects.requireNonNull(name, "name");
        this.description = description;
        this.version = version;
        this.asOf = asOf;
        this.params = Collections.unmodifiableMap(new LinkedHashMap<>(params));
        this.facts = facts;
        this.output = Collections.unmodifiableMap(new LinkedHashMap<>(output));
        this.exactOutput = exactOutput;
        this.reasonCodes = reasonCodes.map(List::copyOf);
        this.matchedRules = matchedRules.map(List::copyOf);
        this.path = path.map(List::copyOf);
        this.ruleVersion = ruleVersion;
        this.error = error;
    }

    /**
     * Where the case was read from, as used in test names and messages.
     *
     * @return the file name (or path relative to the scanned directory) of the test file
     */
    public String source() {
        return source;
    }

    /**
     * The decision under test, from the file's {@code "decision"} field.
     *
     * @return the decision id
     */
    public String decision() {
        return decision;
    }

    /**
     * The case name, unique within its file.
     *
     * @return the {@code "name"} field
     */
    public String name() {
        return name;
    }

    /**
     * Free text explaining the case, e.g. the policy clause it checks.
     *
     * @return the {@code "description"} field, if present
     */
    public Optional<String> description() {
        return description;
    }

    /**
     * The version to evaluate, if the case pins one.
     *
     * @return the {@code "version"} field, if present
     */
    public Optional<String> version() {
        return version;
    }

    /**
     * The business date that selects the version, if given.
     *
     * @return the {@code "asOf"} field, if present
     */
    public Optional<LocalDate> asOf() {
        return asOf;
    }

    /**
     * The parameter set versions the case forces, by parameter set id.
     *
     * @return the {@code "params"} field; empty if absent
     */
    public Map<String, String> params() {
        return params;
    }

    /**
     * The input facts. Read with the tables' types, they are as {@code FactsJson} produces them:
     * {@code Money}, {@code LocalDate}, {@code BigDecimal}, {@code Long} and so on. Read without
     * types, numbers are {@code Long} (or {@code BigInteger} beyond 64 bits) and
     * {@code BigDecimal}, objects {@code Map} and arrays {@code List}.
     *
     * @return the {@code "facts"} field
     */
    public Facts facts() {
        return facts;
    }

    /**
     * Whether the case expects the evaluation to fail.
     *
     * @return {@code true} if the case has {@code "expect": { "error": … }}
     */
    public boolean expectsError() {
        return error.isPresent();
    }

    /**
     * The reference this case evaluates.
     *
     * @return the decision with the case's version, date and parameter set versions, if any
     */
    public DecisionRef ref() {
        DecisionRef ref = DecisionRef.of(decision);
        for (Map.Entry<String, String> forced : params.entrySet()) {
            ref = ref.params(forced.getKey(), forced.getValue());
        }
        if (version.isPresent()) {
            ref = ref.version(version.get());
        }
        if (asOf.isPresent()) {
            ref = ref.asOf(asOf.get());
        }
        return ref;
    }

    /**
     * Evaluates the case and checks every expectation, reporting all mismatches together.
     *
     * @param engine the engine to evaluate with
     * @return the result if the case expects success, or empty if it expects (and got) an error
     * @throws AssertionError listing every expectation that was not met
     */
    public Optional<DecisionResult> check(RuleStoneEngine engine) {
        DecisionResult result = null;
        RuntimeException failure = null;
        try {
            result = engine.evaluate(ref(), facts);
        } catch (RuleStoneException e) {
            failure = e;
        }
        if (error.isPresent()) {
            Optional<String> problem = DecisionCheck.failureProblem(failure, result, error.get());
            if (problem.isPresent()) {
                throw failed(null, List.of(problem.get()), failure);
            }
            return Optional.empty();
        }
        if (failure != null) {
            throw failed(null, List.of("evaluation failed: " + failure.getMessage()), failure);
        }
        List<String> problems = new ArrayList<>();
        for (Map.Entry<String, Object> e : output.entrySet()) {
            Expectations.output(result, e.getKey(), e.getValue()).ifPresent(problems::add);
        }
        if (exactOutput) {
            problems.addAll(Expectations.unexpectedOutputs(result, output));
        }
        DecisionResult actual = result;
        reasonCodes.flatMap(codes -> Expectations.reasonCodes(actual, codes)).ifPresent(problems::add);
        matchedRules.flatMap(rules -> Expectations.matchedRules(actual, rules)).ifPresent(problems::add);
        path.flatMap(nodes -> Expectations.path(actual, nodes)).ifPresent(problems::add);
        ruleVersion.flatMap(v -> Expectations.ruleVersion(actual, v)).ifPresent(problems::add);
        if (!problems.isEmpty()) {
            throw failed(result, problems, null);
        }
        return Optional.of(result);
    }

    private AssertionError failed(DecisionResult result, List<String> problems, Throwable cause) {
        StringBuilder message = new StringBuilder(source).append(", case ").append(Expectations.quote(name));
        if (result != null) {
            message.append(", evaluated with ").append(decision).append('@')
                    .append(Expectations.versionLabel(result.ruleVersion()));
        }
        message.append(':');
        problems.forEach(p -> message.append("\n  - ").append(p));
        return new AssertionError(message.toString(), cause);
    }

    @Override
    public String toString() {
        return source + ": " + name;
    }
}
