//
// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.rulestone.core;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import vn.io.dxa.rulestone.cel.ExpressionEnvironment;
import vn.io.dxa.rulestone.cel.ExpressionException;
import vn.io.dxa.rulestone.cel.InvalidValueException;
import vn.io.dxa.rulestone.cel.ValueType;

/**
 * Parameter sets in the engine: the checks of every registered {@link ParameterSet}, the checks of
 * a table's or flow's {@link ParamsDeclaration} against every version of its set, and, during
 * evaluation, the selection of the version whose values are bound as {@code params}.
 *
 * <p>The browser engine checks tables the same way, with the same messages.
 */
final class Parameters {

    /** The variable that tables and flows read their parameters from. */
    static final String PARAMS = "params";

    /** What {@code params} names in messages and in {@link DecisionVersions#select}. */
    static final String KIND = "parameter set";

    static final String NO_STRUCTS = "parameters must be bool, int, string, decimal, money, date or lists of them";

    private Parameters() {}

    /** A parameter set version that passed its checks, with its exact version, e.g. {@code 7@sha256:…}. */
    record CompiledSet(ParameterSet set, String version) {}

    /**
     * Every registered parameter set.
     *
     * @param registered every registered version, by set id, in registration order
     * @param compiled   the versions that passed their checks, by set id, in registration order
     */
    record Registry(Map<String, List<ParameterSet>> registered, Map<String, List<CompiledSet>> compiled) {

        static final Registry EMPTY = new Registry(Map.of(), Map.of());

        /** Checks every set and its versions, adding problems prefixed with {@code parameter set <id>@<version>}. */
        static Registry compile(List<ParameterSet> sets, List<String> problems) {
            Map<String, List<ParameterSet>> registered = new LinkedHashMap<>();
            Map<String, List<CompiledSet>> compiled = new LinkedHashMap<>();
            for (ParameterSet set : sets) {
                registered.computeIfAbsent(set.id(), id -> new ArrayList<>()).add(set);
                Parameters.compile(set, problems).ifPresent(c ->
                        compiled.computeIfAbsent(set.id(), id -> new ArrayList<>()).add(c));
            }
            registered.values().forEach(versions -> DecisionVersions.problems(versions)
                    .forEach(p -> problems.add(KIND + " " + p)));
            return new Registry(registered, compiled);
        }
    }

    private static Optional<CompiledSet> compile(ParameterSet set, List<String> problems) {
        String at = KIND + " " + set.ref() + " ";
        int before = problems.size();
        if (!CompiledDecision.ID.matcher(set.id()).matches()) {
            problems.add(at + "id: must match " + CompiledDecision.ID.pattern());
        }
        if (!CompiledDecision.ID.matcher(set.version()).matches() && !set.version().matches("[0-9][A-Za-z0-9_.-]*")) {
            problems.add(at + "version: must be letters, digits, '.', '_' or '-'");
        }
        if (set.effectiveFrom().isPresent() && set.effectiveTo().isPresent()
                && !set.effectiveFrom().get().isBefore(set.effectiveTo().get())) {
            problems.add(at + "effectiveTo: must be after effectiveFrom");
        }
        set.values().forEach((name, parameter) -> {
            String path = "values." + name;
            if (!ExpressionEnvironment.isValidName(name)) {
                problems.add(at + path + ": " + INVALID_NAME);
            }
            if (CompiledDecision.containsStruct(parameter.type())) {
                problems.add(at + path + ": " + NO_STRUCTS);
                return;
            }
            try {
                ExpressionEnvironment.checkValue(parameter.value(), parameter.type(), path);
            } catch (InvalidValueException e) {
                problems.add(at + e.getMessage());
            }
        });
        if (problems.size() > before) {
            return Optional.empty();
        }
        String version = set.version() + "@sha256:" + CompiledDecision.sha256(ParameterSetJson.write(set));
        return Optional.of(new CompiledSet(set, version));
    }

    static final String INVALID_NAME =
            "must be an identifier (a letter or '_', then letters, digits or '_') and not a reserved word";

    /**
     * The type of {@code params}: a struct of the declared parameters that have a valid name and
     * type, or null if there are none.
     */
    static ValueType type(ParamsDeclaration params) {
        Map<String, ValueType> fields = new LinkedHashMap<>();
        params.values().forEach((name, type) -> {
            if (ExpressionEnvironment.isValidName(name) && !CompiledDecision.containsStruct(type)) {
                fields.put(name, type);
            }
        });
        return fields.isEmpty() ? null : ValueType.struct(fields);
    }

    /**
     * Checks a declaration against every version of its set that it may use: all of them, or the
     * pinned one.
     *
     * @param at       the prefix of every problem, e.g. {@code loan_eligibility@4 }
     * @param inputs   the inputs of the table or flow
     * @param problems receives the problems found
     * @return the binding to evaluate with, or empty if a problem was found
     */
    static Optional<Binding> check(String at, ParamsDeclaration params, Map<String, ValueType> inputs,
                                   Registry registry, List<String> problems) {
        int before = problems.size();
        if (inputs.containsKey(PARAMS)) {
            problems.add(at + "inputs." + PARAMS + ": reserved name: the parameters declared in \"params\" are read "
                    + "as params.<name>");
        }
        if (params.values().isEmpty()) {
            problems.add(at + "params.values: declare at least one parameter");
        }
        Set<String> invalid = new TreeSet<>();
        params.values().forEach((name, type) -> {
            if (!ExpressionEnvironment.isValidName(name)) {
                problems.add(at + "params.values." + name + ": " + INVALID_NAME);
                invalid.add(name);
            } else if (CompiledDecision.containsStruct(type)) {
                problems.add(at + "params.values." + name + ": " + NO_STRUCTS);
                invalid.add(name);
            }
        });
        List<ParameterSet> registered = registry.registered().get(params.set());
        if (registered == null) {
            problems.add(at + "params.set: no parameter set '" + params.set() + "' is registered");
            return Optional.empty();
        }
        List<ParameterSet> candidates = registered;
        if (params.version().isPresent()) {
            candidates = registered.stream().filter(s -> s.version().equals(params.version().get())).toList();
            if (candidates.isEmpty()) {
                problems.add(at + "params.version: parameter set '" + params.set() + "' has no version '"
                        + params.version().get() + "'");
                return Optional.empty();
            }
        }
        for (ParameterSet set : candidates) {
            params.values().forEach((name, type) -> {
                if (invalid.contains(name)) {
                    return;
                }
                ParameterSet.Parameter parameter = set.values().get(name);
                if (parameter == null) {
                    problems.add(at + "params.values." + name + ": parameter set " + set.ref()
                            + " has no parameter '" + name + "'");
                } else if (!parameter.type().equals(type)) {
                    problems.add(at + "params.values." + name + ": declared " + type + " but parameter set "
                            + set.ref() + " has it as " + parameter.type());
                }
            });
        }
        if (problems.size() > before) {
            return Optional.empty();
        }
        List<CompiledSet> compiled = registry.compiled().getOrDefault(params.set(), List.of());
        if (compiled.size() < registered.size()) {
            return Optional.empty();   // a version failed its checks, which is already reported
        }
        Set<ParameterSet> allowed = Collections.newSetFromMap(new IdentityHashMap<>());
        allowed.addAll(candidates);
        return Optional.of(new Binding(params, compiled.stream().filter(c -> allowed.contains(c.set())).toList()));
    }

    /**
     * Why {@code source} cannot read what it reads from {@code params}, or null if it can: it reads
     * a parameter that is not declared, or reads {@code params} although none are declared.
     *
     * @param params      the declaration, if any
     * @param paramsInput whether an input is named {@code params}, which then is an ordinary input
     */
    static String undeclared(ExpressionEnvironment environment, String source, Optional<ParamsDeclaration> params,
                             boolean paramsInput) {
        if (paramsInput && params.isEmpty()) {
            return null;
        }
        Set<String> fields;
        try {
            if (params.isEmpty() && !environment.references(source).contains(PARAMS)) {
                return null;
            }
            fields = environment.selectedFields(source, PARAMS);
        } catch (ExpressionException e) {
            return null;   // reported when the expression is compiled
        }
        if (params.isEmpty()) {
            String read = fields.isEmpty() ? PARAMS : String.join(", ", fields.stream().map(f -> PARAMS + "." + f).toList());
            return "reads " + read + ", but no parameters are declared; declare them in \"params\"";
        }
        List<String> missing = fields.stream().filter(f -> !params.get().values().containsKey(f)).toList();
        if (missing.isEmpty()) {
            return null;
        }
        return "reads " + String.join(", ", missing.stream().map(f -> PARAMS + "." + f).toList())
                + ", which params.values does not declare (declared: " + params.get().values().keySet() + ")";
    }

    /**
     * A checked declaration, ready for evaluation.
     *
     * @param declaration what the table or flow reads
     * @param versions    the versions it may use: every version of the set, or the pinned one
     */
    record Binding(ParamsDeclaration declaration, List<CompiledSet> versions) {

        /**
         * The declared values of the version used on {@code date}: the pinned version, else the
         * version the request forces, else the version in force. Records the version in {@code progress}.
         *
         * @throws vn.io.dxa.rulestone.api.DecisionNotFoundException if no version is in force on
         *         {@code date}, or the request forces a version that does not exist
         */
        Map<String, Object> select(LocalDate date, Progress progress) {
            String set = declaration.set();
            Optional<String> version = declaration.version()
                    .or(() -> Optional.ofNullable(progress.forcedParams().get(set)));
            ParameterSet chosen = DecisionVersions.select(KIND, set, versions.stream().map(CompiledSet::set).toList(),
                    version, date);
            CompiledSet compiled = versions.stream().filter(c -> c.set() == chosen).findFirst().orElseThrow();
            progress.params(set, compiled.version());
            Map<String, Object> values = new LinkedHashMap<>();
            declaration.values().keySet().forEach(name -> values.put(name, chosen.values().get(name).value()));
            return values;
        }
    }
}
