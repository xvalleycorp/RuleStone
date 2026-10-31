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
import java.util.TreeSet;
import java.util.HexFormat;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import vn.io.dxa.rulestone.api.DecisionEvaluationException;
import vn.io.dxa.rulestone.api.DecisionRecord.PathStep;
import vn.io.dxa.rulestone.api.DecisionRecord.ReasonCode;
import vn.io.dxa.rulestone.api.DecisionTimeoutException;
import vn.io.dxa.rulestone.api.Facts;
import vn.io.dxa.rulestone.api.TraceStep;
import vn.io.dxa.rulestone.cel.Bindings;
import vn.io.dxa.rulestone.cel.CelExpression;
import vn.io.dxa.rulestone.cel.ExpressionEnvironment;
import vn.io.dxa.rulestone.cel.ExpressionException;
import vn.io.dxa.rulestone.cel.InvalidValueException;
import vn.io.dxa.rulestone.cel.ValueType;

/** A decision table whose expressions are all compiled and type-checked. Immutable and thread-safe. */
final class CompiledDecision implements Executable {

    /**
     * Implicit input of every decision: the date the decision is made for, which is also the
     * date used to select its version. Tables may not declare it, and facts may not supply it.
     */
    static final String BUSINESS_DATE = "businessDate";

    static final Pattern ID = Pattern.compile("[A-Za-z][A-Za-z0-9_.-]*");
    static final Pattern REASON_CODE = Pattern.compile("[A-Z][A-Z0-9_]*");

    private final DecisionTable table;
    private final String ruleVersion;
    private final ExpressionEnvironment environment;
    private final List<CompiledRule> rules;
    /** The parameters the table reads, or null if it declares none. */
    private final Parameters.Binding params;

    private CompiledDecision(DecisionTable table, String ruleVersion, ExpressionEnvironment environment,
                             List<CompiledRule> rules, Parameters.Binding params) {
        this.table = table;
        this.ruleVersion = ruleVersion;
        this.environment = environment;
        this.rules = rules;
        this.params = params;
    }

    DecisionTable table() {
        return table;
    }

    @Override
    public DecisionTable definition() {
        return table;
    }

    @Override
    public String ruleVersion() {
        return ruleVersion;
    }

    @Override
    public Run run(Facts facts, LocalDate businessDate, Optional<String> correlationId, Deadline deadline,
                   Progress progress, FactsHasher hasher) {
        // The step shows the rules matched so far, also when the evaluation fails.
        progress.open(() -> new PathStep(table.id(), "table", ruleVersion, progress.matchedSince(0)));
        Bindings bindings = bind(facts, businessDate, progress);
        progress.factsHash(hasher.hash(bindings, params != null));
        if (!facts.models().isEmpty()) {
            throw new DecisionEvaluationException(table.id(), ruleVersion, "model results supplied for "
                    + facts.models().keySet() + ", but a decision table has no model nodes", null);
        }
        Outcome outcome = evaluate(bindings, progress.trace, deadline);
        progress.close();
        return new Run(outcome.output(), outcome.reasonCodes().stream()
                .map(code -> new ReasonCode(code, table.id())).toList());
    }

    /**
     * Compiles every expression of {@code table}.
     *
     * @param parameterSets every registered parameter set, which the table's {@code params} are checked against
     * @param problems receives every problem found, prefixed with the table and location
     * @return the compiled decision, or empty if any problem was found
     */
    static Optional<CompiledDecision> compile(DecisionTable table, int maxIterations,
                                              Map<String, Integer> currencyScales, Parameters.Registry parameterSets,
                                              List<String> problems) {
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
        Parameters.Binding params = null;
        ValueType paramsType = null;
        if (table.params().isPresent()) {
            params = Parameters.check(at, table.params().get(), inputs, parameterSets, problems).orElse(null);
            paramsType = Parameters.type(table.params().get());
            // Reported, then compiled with the parameters in its place so that other problems are found too.
            inputs.remove(Parameters.PARAMS);
        }
        boolean paramsInput = inputs.containsKey(Parameters.PARAMS);
        ValueType declaredParams = paramsType;

        Supplier<ExpressionEnvironment.Builder> base = () -> {
            ExpressionEnvironment.Builder builder = ExpressionEnvironment.builder()
                    .variables(inputs)
                    .variable(BUSINESS_DATE, ValueType.DATE)
                    .maxIterations(maxIterations);
            if (declaredParams != null) {
                builder.variable(Parameters.PARAMS, declaredParams);
            }
            currencyScales.forEach(builder::currencyScale);
            return builder;
        };
        ExpressionEnvironment environment;
        try {
            environment = base.get().build();
        } catch (IllegalArgumentException e) {
            problems.add(at + "inputs: " + e.getMessage());
            return Optional.empty();
        }
        ExpressionEnvironment parsing = environment;
        Function<String, String> undeclared =
                source -> Parameters.undeclared(parsing, source, table.params(), paramsInput);
        Set<String> failedLocals = new TreeSet<>();
        if (!table.let().isEmpty()) {
            environment = new LocalValues(table, environment, base, at, problems, undeclared).compile(failedLocals);
        }

        List<CompiledRule> rules = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        ExpressionEnvironment rulesEnvironment = environment;
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
            CelExpression when = compile(rulesEnvironment, rule.when(), ValueType.BOOL, ruleAt + ".when", problems,
                    failedLocals, undeclared);
            Map<String, CelExpression> then = new LinkedHashMap<>();
            rule.then().forEach((output, source) -> {
                ValueType type = table.outputs().get(output);
                if (type == null) {
                    problems.add(ruleAt + ".then." + output + ": undeclared output (declared: " + table.outputs().keySet() + ")");
                } else if (!containsStruct(type)) {
                    then.put(output, compile(rulesEnvironment, source, type, ruleAt + ".then." + output, problems,
                            failedLocals, undeclared));
                }
            });
            rules.add(new CompiledRule(rule.id(), when, then, rule.reasonCode()));
        }

        if (problems.size() > before) {
            return Optional.empty();
        }
        String ruleVersion = table.version() + "@sha256:" + sha256(DecisionTableJson.write(table));
        return Optional.of(new CompiledDecision(table, ruleVersion, environment, List.copyOf(rules), params));
    }

    /**
     * Converts and type-checks the facts for this decision, and binds its parameters.
     *
     * @param businessDate the value of the implicit {@code businessDate} input, which also selects
     *                     the parameter set version
     * @param progress     gives the parameter set versions the request forces, and receives those used
     * @throws DecisionEvaluationException if a fact is missing or has the wrong type
     * @throws vn.io.dxa.rulestone.api.DecisionNotFoundException if no version of the parameter set
     *         is in force, or the request forces one that does not exist
     */
    Bindings bind(Facts facts, LocalDate businessDate, Progress progress) {
        if (facts.asMap().containsKey(BUSINESS_DATE)) {
            throw failure("invalid input " + BUSINESS_DATE + ": reserved name; the business date comes from "
                    + "DecisionRef.asOf or the engine clock and cannot be passed as a fact", null);
        }
        if (params != null && facts.asMap().containsKey(Parameters.PARAMS)) {
            throw failure("invalid input " + Parameters.PARAMS + ": reserved name; the parameters come from "
                    + "parameter set " + params.declaration().set() + " and cannot be passed as a fact", null);
        }
        Map<String, Object> inputs = new HashMap<>(facts.asMap());
        inputs.put(BUSINESS_DATE, businessDate);
        if (params != null) {
            inputs.put(Parameters.PARAMS, params.select(businessDate, progress));
        }
        try {
            return environment.bind(inputs);
        } catch (InvalidValueException e) {
            throw failure("invalid input " + e.getMessage(), e);
        }
    }

    /**
     * Applies the rules.
     *
     * @param trace    receives each rule examined as it is examined, so that it shows how far
     *                 evaluation got if it fails
     * @param deadline checked before each rule
     * @throws DecisionTimeoutException    if the deadline passes before every rule needed is examined
     * @throws DecisionEvaluationException if a rule fails or the hit policy is violated
     */
    Outcome evaluate(Bindings bindings, List<TraceStep> trace, Deadline deadline) {
        return switch (table.hitPolicy()) {
            case FIRST -> first(bindings, trace, deadline);
            case UNIQUE -> unique(bindings, trace, deadline);
            case COLLECT -> collect(bindings, trace, deadline);
        };
    }

    /** The outputs and reason codes of one evaluation. */
    record Outcome(Map<String, Object> output, List<String> reasonCodes) {}

    private Outcome first(Bindings bindings, List<TraceStep> trace, Deadline deadline) {
        for (CompiledRule rule : rules) {
            checkTime(trace, deadline);
            boolean matched = matches(rule, bindings);
            trace.add(new TraceStep(rule.id, matched));
            if (matched) {
                return new Outcome(outputs(rule, bindings), reasons(List.of(rule)));
            }
        }
        return new Outcome(Map.of(), List.of());
    }

    private Outcome unique(Bindings bindings, List<TraceStep> trace, Deadline deadline) {
        List<CompiledRule> matched = matchAll(bindings, trace, deadline);
        if (matched.size() > 1) {
            throw failure("UNIQUE hit policy violated: rules "
                    + matched.stream().map(r -> r.id).toList() + " all matched", null);
        }
        if (matched.isEmpty()) {
            return new Outcome(Map.of(), List.of());
        }
        return new Outcome(outputs(matched.get(0), bindings), reasons(matched));
    }

    private Outcome collect(Bindings bindings, List<TraceStep> trace, Deadline deadline) {
        List<CompiledRule> matched = matchAll(bindings, trace, deadline);
        Map<String, List<Object>> collected = new LinkedHashMap<>();
        table.outputs().keySet().forEach(name -> collected.put(name, new ArrayList<>()));
        for (CompiledRule rule : matched) {
            outputs(rule, bindings).forEach((name, value) -> collected.get(name).add(value));
        }
        Map<String, Object> output = new LinkedHashMap<>();
        collected.forEach((name, values) -> output.put(name, List.copyOf(values)));
        return new Outcome(output, reasons(matched));
    }

    private List<CompiledRule> matchAll(Bindings bindings, List<TraceStep> trace, Deadline deadline) {
        List<CompiledRule> matched = new ArrayList<>();
        for (CompiledRule rule : rules) {
            checkTime(trace, deadline);
            boolean match = matches(rule, bindings);
            trace.add(new TraceStep(rule.id, match));
            if (match) {
                matched.add(rule);
            }
        }
        return matched;
    }

    private void checkTime(List<TraceStep> trace, Deadline deadline) {
        if (deadline.passed()) {
            throw new DecisionTimeoutException(table.id(), ruleVersion, deadline.timeout(), trace);
        }
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
                                         String at, List<String> problems, Set<String> failedLocals,
                                         Function<String, String> undeclared) {
        String paramsProblem = undeclared.apply(source);
        if (paramsProblem != null) {
            problems.add(at + ": " + paramsProblem);
            return null;
        }
        if (!failedLocals.isEmpty()) {
            try {
                if (environment.references(source).stream().anyMatch(failedLocals::contains)) {
                    return null; // the local value's own problem is reported
                }
            } catch (ExpressionException e) {
                // reported by compile below
            }
        }
        try {
            return environment.compile(source, type);
        } catch (ExpressionException e) {
            problems.add(at + ": " + e.getMessage());
            return null;
        }
    }

    static boolean containsStruct(ValueType type) {
        return type instanceof ValueType.Struct
                || type instanceof ValueType.ListOf list && containsStruct(list.element());
    }

    static String sha256(String text) {
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
