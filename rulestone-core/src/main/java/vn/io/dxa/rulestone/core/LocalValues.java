//
// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.rulestone.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Supplier;
import vn.io.dxa.rulestone.cel.CelExpression;
import vn.io.dxa.rulestone.cel.ExpressionEnvironment;
import vn.io.dxa.rulestone.cel.ExpressionException;

/**
 * Compiles the local values ({@code let}) of a decision table: checks their names, finds which
 * local values each one reads, rejects cycles, and type-checks them in dependency order so that
 * each one's type is inferred before the local values that read it are checked. The order in the
 * file does not matter: names are processed in sorted order, as the canonical form sorts them.
 *
 * <p>The browser engine compiles them the same way, with the same messages.
 */
final class LocalValues {

    /** Reserved for parameter sets, which tables will read as {@code params.x}. */
    static final String PARAMS = "params";

    private final DecisionTable table;
    private final ExpressionEnvironment inputs;
    private final Supplier<ExpressionEnvironment.Builder> base;
    private final String at;
    private final List<String> problems;
    private final Map<String, String> sources;
    private final Map<String, Set<String>> reads = new TreeMap<>();
    private final Map<String, CelExpression> compiled = new LinkedHashMap<>();
    private final Set<String> failed;

    /**
     * @param inputs an environment with the table's inputs and businessDate, used to parse
     * @param base   a builder with the table's inputs, businessDate and engine settings
     */
    LocalValues(DecisionTable table, ExpressionEnvironment inputs, Supplier<ExpressionEnvironment.Builder> base,
                String at, List<String> problems) {
        this.table = table;
        this.inputs = inputs;
        this.base = base;
        this.at = at;
        this.problems = problems;
        this.sources = new TreeMap<>(table.let());
        this.failed = new TreeSet<>();
    }

    /**
     * @param failedLocals receives the names of the local values that could not be compiled,
     *                     including those that read one that could not
     * @return an environment with the inputs, businessDate and every local value compiled
     */
    ExpressionEnvironment compile(Set<String> failedLocals) {
        sources.forEach(this::checkName);
        findCycles();
        sources.keySet().forEach(this::compile);
        failedLocals.addAll(failed);
        return environment();
    }

    private void checkName(String name, String source) {
        boolean valid = true;
        if (!ExpressionEnvironment.isValidName(name)) {
            problem(name, "must be an identifier (a letter or '_', then letters, digits or '_') "
                    + "and not a reserved word");
            valid = false;
        } else if (name.equals(CompiledDecision.BUSINESS_DATE)) {
            problem(name, "reserved name; every decision receives " + CompiledDecision.BUSINESS_DATE + " implicitly");
            valid = false;
        } else if (name.equals(PARAMS)) {
            problem(name, "reserved name for parameter sets");
            valid = false;
        } else if (table.inputs().containsKey(name)) {
            problem(name, "an input has the same name");
            valid = false;
        }
        try {
            Set<String> names = new TreeSet<>(inputs.references(source));
            names.retainAll(sources.keySet());
            reads.put(name, names);
        } catch (ExpressionException e) {
            problem(name, e.getMessage());
            valid = false;
        }
        if (!valid) {
            failed.add(name);
        }
    }

    /** Reports every cycle once, starting from its first name in sorted order, and fails its local values. */
    private void findCycles() {
        Set<String> cycles = new TreeSet<>();
        Set<String> done = new TreeSet<>();
        for (String name : reads.keySet()) {
            visit(name, new ArrayList<>(), done, cycles);
        }
        for (String cycle : cycles) {
            String first = cycle.substring(0, cycle.indexOf(' '));
            problem(first, "cycle: " + cycle);
        }
    }

    private void visit(String name, List<String> path, Set<String> done, Set<String> cycles) {
        int index = path.indexOf(name);
        if (index >= 0) {
            List<String> cycle = new ArrayList<>(path.subList(index, path.size()));
            failed.addAll(cycle);
            // Start from the smallest name so that each cycle is written one way.
            String smallest = new TreeSet<>(cycle).first();
            int start = cycle.indexOf(smallest);
            List<String> rotated = new ArrayList<>(cycle.subList(start, cycle.size()));
            rotated.addAll(cycle.subList(0, start));
            rotated.add(smallest);
            cycles.add(String.join(" -> ", rotated));
            return;
        }
        if (done.contains(name) || !reads.containsKey(name)) {
            return;
        }
        path.add(name);
        for (String next : reads.get(name)) {
            visit(next, path, done, cycles);
        }
        path.remove(path.size() - 1);
        done.add(name);
    }

    /** Compiles a local value after those it reads; true if it compiled. */
    private boolean compile(String name) {
        if (compiled.containsKey(name)) {
            return true;
        }
        if (failed.contains(name)) {
            return false;
        }
        for (String dependency : reads.get(name)) {
            if (!compile(dependency)) {
                failed.add(name); // the dependency's problem is reported
                return false;
            }
        }
        try {
            compiled.put(name, environment().compileLocal(sources.get(name)));
            return true;
        } catch (ExpressionException e) {
            problem(name, e.getMessage());
            failed.add(name);
            return false;
        }
    }

    /** The inputs plus the local values compiled so far. */
    private ExpressionEnvironment environment() {
        ExpressionEnvironment.Builder builder = base.get();
        compiled.forEach(builder::local);
        return builder.build();
    }

    private void problem(String name, String message) {
        problems.add(at + "let." + name + ": " + message);
    }
}
