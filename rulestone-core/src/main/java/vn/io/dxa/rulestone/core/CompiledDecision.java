//
// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.rulestone.core;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.HexFormat;
import java.util.regex.Pattern;
import vn.io.dxa.rulestone.api.DecisionEvaluationException;
import vn.io.dxa.rulestone.api.Facts;
import vn.io.dxa.rulestone.api.TraceStep;
import vn.io.dxa.rulestone.cel.Bindings;
import vn.io.dxa.rulestone.cel.CelExpression;
import vn.io.dxa.rulestone.cel.ExpressionEnvironment;
import vn.io.dxa.rulestone.cel.ExpressionException;
import vn.io.dxa.rulestone.cel.InvalidValueException;
import vn.io.dxa.rulestone.cel.ValueType;

/** A decision table whose expressions are all compiled and type-checked. Immutable and thread-safe. */
final class CompiledDecision {

    /**
     * Implicit input of every decision: the date the decision is made for, which is also the
     * date used to select its version. Tables may not declare it, and facts may not supply it.
     */
    static final String BUSINESS_DATE = "businessDate";

    private static final Pattern ID = Pattern.compile("[A-Za-z][A-Za-z0-9_.-]*");
    private static final Pattern REASON_CODE = Pattern.compile("[A-Z][A-Z0-9_]*");

    private final DecisionTable table;
    private final String ruleVersion;
    private final ExpressionEnvironment environment;
    private final List<CompiledRule> rules;

    private CompiledDecision(DecisionTable table, String ruleVersion, ExpressionEnvironment environment,
                             List<CompiledRule> rules) {
        this.table = table;
        this.ruleVersion = ruleVersion;
        this.environment = environment;
        this.rules = rules;
    }

    DecisionTable table() {
        return table;
    }

    String ruleVersion() {
        return ruleVersion;
    }

    /**
     * Compiles every expression of {@code table}.
     *
     * @param problems receives every problem found, prefixed with the table and location
     * @return the compiled decision, or empty if any problem was found
     */
    static Optional<CompiledDecision> compile(DecisionTable table, int maxIterations,
                                              Map<String, Integer> currencyScales, List<String> problems) {
        String at = table.ref() + " ";
        int before = problems.size();

        if (!ID.matcher(table.id()).matches()) {
            problems.add(at + "id: must match " + ID.pattern());
        }
        if (!ID.matcher(table.version()).matches() && !table.version().matches("[0-9][A-Za-z0-9_.-]*")) {
            problems.add(at + "version: must be letters, digits, '.', '_' or '-'");
        }
        if (table.effectiveFrom().isPresent() && table.effectiveTo().isPresent()
                && !table.effectiveFrom().get().isBefore(table.effectiveTo().get())) {
            problems.add(at + "effectiveTo: must be after effectiveFrom");
        }
        table.outputs().forEach((name, type) -> {
            if (containsStruct(type)) {
                problems.add(at + "outputs." + name
                        + ": outputs must be bool, int, string, decimal, money, date or lists of them");
            }
        });
        if (table.rules().isEmpty()) {
            problems.add(at + "rules: a decision table needs at least one rule");
        }

        Map<String, ValueType> inputs = new LinkedHashMap<>(table.inputs());
        if (inputs.remove(BUSINESS_DATE) != null) {
            // Reported, then compiled against the implicit input so that other problems are found too.
            problems.add(at + "inputs." + BUSINESS_DATE + ": reserved name; every decision receives "
                    + BUSINESS_DATE + " implicitly (DecisionRef.asOf, else the engine clock)");
        }

        ExpressionEnvironment environment;
        try {
            ExpressionEnvironment.Builder builder = ExpressionEnvironment.builder()
                    .variables(inputs)
                    .variable(BUSINESS_DATE, ValueType.DATE)
                    .maxIterations(maxIterations);
            currencyScales.forEach(builder::currencyScale);
            environment = builder.build();
        } catch (IllegalArgumentException e) {
            problems.add(at + "inputs: " + e.getMessage());
            return Optional.empty();
        }

        List<CompiledRule> rules = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (Rule rule : table.rules()) {
            String ruleAt = at + "rules[" + rule.id() + "]";
            if (!ids.add(rule.id())) {
                problems.add(ruleAt + ": duplicate rule id");
            }
            rule.reasonCode().ifPresent(code -> {
                if (!REASON_CODE.matcher(code).matches()) {
                    problems.add(ruleAt + ".reasonCode: must be UPPER_SNAKE_CASE, e.g. DTI_HIGH");
                }
            });
            CelExpression when = compile(environment, rule.when(), ValueType.BOOL, ruleAt + ".when", problems);
            Map<String, CelExpression> then = new LinkedHashMap<>();
            rule.then().forEach((output, source) -> {
                ValueType type = table.outputs().get(output);
                if (type == null) {
                    problems.add(ruleAt + ".then." + output + ": undeclared output (declared: " + table.outputs().keySet() + ")");
                } else if (!containsStruct(type)) {
                    then.put(output, compile(environment, source, type, ruleAt + ".then." + output, problems));
                }
            });
            rules.add(new CompiledRule(rule.id(), when, then, rule.reasonCode()));
        }

        if (problems.size() > before) {
            return Optional.empty();
        }
        String ruleVersion = table.version() + "@sha256:" + sha256(DecisionTableJson.write(table));
        return Optional.of(new CompiledDecision(table, ruleVersion, environment, List.copyOf(rules)));
    }

    /**
     * Converts and type-checks the facts for this decision.
     *
     * @param businessDate the value of the implicit {@code businessDate} input
     * @throws DecisionEvaluationException if a fact is missing or has the wrong type
     */
    Bindings bind(Facts facts, LocalDate businessDate) {
        if (facts.asMap().containsKey(BUSINESS_DATE)) {
            throw failure("invalid input " + BUSINESS_DATE + ": reserved name; the business date comes from "
                    + "DecisionRef.asOf or the engine clock and cannot be passed as a fact", null);
        }
        Map<String, Object> inputs = new HashMap<>(facts.asMap());
        inputs.put(BUSINESS_DATE, businessDate);
        try {
            return environment.bind(inputs);
        } catch (InvalidValueException e) {
            throw failure("invalid input " + e.getMessage(), e);
        }
    }

    /**
     * Applies the rules.
     *
     * @param trace receives each rule examined as it is examined, so that it shows how far
     *              evaluation got if it fails
     * @throws DecisionEvaluationException if a rule fails or the hit policy is violated
     */
    Outcome evaluate(Bindings bindings, List<TraceStep> trace) {
        return switch (table.hitPolicy()) {
            case FIRST -> first(bindings, trace);
            case UNIQUE -> unique(bindings, trace);
            case COLLECT -> collect(bindings, trace);
        };
    }

    /** The outputs and reason codes of one evaluation. */
    record Outcome(Map<String, Object> output, List<String> reasonCodes) {}

    private Outcome first(Bindings bindings, List<TraceStep> trace) {
        for (CompiledRule rule : rules) {
            boolean matched = matches(rule, bindings);
            trace.add(new TraceStep(rule.id, matched));
            if (matched) {
                return new Outcome(outputs(rule, bindings), reasons(List.of(rule)));
            }
        }
        return new Outcome(Map.of(), List.of());
    }

    private Outcome unique(Bindings bindings, List<TraceStep> trace) {
        List<CompiledRule> matched = matchAll(bindings, trace);
        if (matched.size() > 1) {
            throw failure("UNIQUE hit policy violated: rules "
                    + matched.stream().map(r -> r.id).toList() + " all matched", null);
        }
        if (matched.isEmpty()) {
            return new Outcome(Map.of(), List.of());
        }
        return new Outcome(outputs(matched.get(0), bindings), reasons(matched));
    }

    private Outcome collect(Bindings bindings, List<TraceStep> trace) {
        List<CompiledRule> matched = matchAll(bindings, trace);
        Map<String, List<Object>> collected = new LinkedHashMap<>();
        table.outputs().keySet().forEach(name -> collected.put(name, new ArrayList<>()));
        for (CompiledRule rule : matched) {
            outputs(rule, bindings).forEach((name, value) -> collected.get(name).add(value));
        }
        Map<String, Object> output = new LinkedHashMap<>();
        collected.forEach((name, values) -> output.put(name, List.copyOf(values)));
        return new Outcome(output, reasons(matched));
    }

    private List<CompiledRule> matchAll(Bindings bindings, List<TraceStep> trace) {
        List<CompiledRule> matched = new ArrayList<>();
        for (CompiledRule rule : rules) {
            boolean match = matches(rule, bindings);
            trace.add(new TraceStep(rule.id, match));
            if (match) {
                matched.add(rule);
            }
        }
        return matched;
    }

    private boolean matches(CompiledRule rule, Bindings bindings) {
        try {
            return (Boolean) rule.when.evaluate(bindings);
        } catch (ExpressionException e) {
            throw failure("rule " + rule.id + " condition failed: " + e.getMessage(), e);
        }
    }

    private Map<String, Object> outputs(CompiledRule rule, Bindings bindings) {
        Map<String, Object> output = new LinkedHashMap<>();
        rule.then.forEach((name, expression) -> {
            try {
                output.put(name, expression.evaluate(bindings));
            } catch (ExpressionException e) {
                throw failure("rule " + rule.id + " output '" + name + "' failed: " + e.getMessage(), e);
            }
        });
        return output;
    }

    private static List<String> reasons(List<CompiledRule> matched) {
        return matched.stream().flatMap(r -> r.reasonCode.stream()).toList();
    }

    private DecisionEvaluationException failure(String message, Throwable cause) {
        return new DecisionEvaluationException(table.id(), ruleVersion, message, cause);
    }

    private static CelExpression compile(ExpressionEnvironment environment, String source, ValueType type,
                                         String at, List<String> problems) {
        try {
            return environment.compile(source, type);
        } catch (ExpressionException e) {
            problems.add(at + ": " + e.getMessage());
            return null;
        }
    }

    private static boolean containsStruct(ValueType type) {
        return type instanceof ValueType.Struct
                || type instanceof ValueType.ListOf list && containsStruct(list.element());
    }

    private static String sha256(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by every Java platform", e);
        }
    }

    private record CompiledRule(String id, CelExpression when, Map<String, CelExpression> then,
                                Optional<String> reasonCode) {}
}
