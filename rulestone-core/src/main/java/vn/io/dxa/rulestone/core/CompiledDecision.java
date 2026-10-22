//
// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.rulestone.core;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.HexFormat;
import java.util.regex.Pattern;
import vn.io.dxa.rulestone.api.DecisionEvaluationException;
import vn.io.dxa.rulestone.api.DecisionResult;
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
    static Optional<CompiledDecision> compile(DecisionTable table, int maxIterations, List<String> problems) {
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
                problems.add(at + "outputs." + name + ": outputs must be bool, int, string, decimal or lists of them");
            }
        });
        if (table.rules().isEmpty()) {
            problems.add(at + "rules: a decision table needs at least one rule");
        }

        ExpressionEnvironment environment;
        try {
            environment = ExpressionEnvironment.builder()
                    .variables(table.inputs())
                    .maxIterations(maxIterations)
                    .build();
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

    DecisionResult evaluate(Facts facts) {
        Bindings bindings;
        try {
            bindings = environment.bind(facts.asMap());
        } catch (InvalidValueException e) {
            throw failure("invalid input " + e.getMessage(), e);
        }
        return switch (table.hitPolicy()) {
            case FIRST -> first(bindings);
            case UNIQUE -> unique(bindings);
            case COLLECT -> collect(bindings);
        };
    }

    private DecisionResult first(Bindings bindings) {
        List<TraceStep> trace = new ArrayList<>();
        for (CompiledRule rule : rules) {
            boolean matched = matches(rule, bindings);
            trace.add(new TraceStep(rule.id, matched));
            if (matched) {
                return result(outputs(rule, bindings), reasons(List.of(rule)), trace);
            }
        }
        return result(Map.of(), List.of(), trace);
    }

    private DecisionResult unique(Bindings bindings) {
        List<TraceStep> trace = new ArrayList<>();
        List<CompiledRule> matched = matchAll(bindings, trace);
        if (matched.size() > 1) {
            throw failure("UNIQUE hit policy violated: rules "
                    + matched.stream().map(r -> r.id).toList() + " all matched", null);
        }
        if (matched.isEmpty()) {
            return result(Map.of(), List.of(), trace);
        }
        return result(outputs(matched.get(0), bindings), reasons(matched), trace);
    }

    private DecisionResult collect(Bindings bindings) {
        List<TraceStep> trace = new ArrayList<>();
        List<CompiledRule> matched = matchAll(bindings, trace);
        Map<String, List<Object>> collected = new LinkedHashMap<>();
        table.outputs().keySet().forEach(name -> collected.put(name, new ArrayList<>()));
        for (CompiledRule rule : matched) {
            outputs(rule, bindings).forEach((name, value) -> collected.get(name).add(value));
        }
        Map<String, Object> output = new LinkedHashMap<>();
        collected.forEach((name, values) -> output.put(name, List.copyOf(values)));
        return result(output, reasons(matched), trace);
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

    private DecisionResult result(Map<String, Object> output, List<String> reasonCodes, List<TraceStep> trace) {
        return new DecisionResult(table.id(), ruleVersion, output, reasonCodes, trace);
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
