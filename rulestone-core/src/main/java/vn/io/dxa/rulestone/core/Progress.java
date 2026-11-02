//
// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.rulestone.core;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Supplier;
import vn.io.dxa.rulestone.api.DecisionRecord.ModelStep;
import vn.io.dxa.rulestone.api.DecisionRecord.ParamSetVersion;
import vn.io.dxa.rulestone.api.DecisionRecord.PathStep;
import vn.io.dxa.rulestone.api.TraceStep;

/** What one evaluation has done so far: enough to record it, also when it fails half-way. Not thread-safe. */
final class Progress {

    /**
     * What a replay or re-score fixes for one evaluation, apart from the parameter set versions.
     *
     * @param tables         node id to the exact version, e.g. {@code 3@sha256:…}, that a {@code table}
     *                       node of a flow must use
     * @param models         node id to the recorded step whose result a {@code model} node uses
     * @param recordedModels whether model nodes take their results from {@code models} and the facts
     *                       only, calling no model
     */
    record Overrides(Map<String, String> tables, Map<String, ModelStep> models, boolean recordedModels) {

        static final Overrides NONE = new Overrides(Map.of(), Map.of(), false);

        Overrides {
            tables = Map.copyOf(tables);
            models = Map.copyOf(models);
        }
    }

    /** Every rule examined, in order, across all tables. */
    final List<TraceStep> trace = new ArrayList<>();
    private final List<PathStep> path = new ArrayList<>();
    /** The step of the node being evaluated, computed when asked so that it reflects the trace so far. */
    private Supplier<PathStep> current;
    private Optional<String> factsHash = Optional.empty();
    /** The parameter set versions the request forces, by set id. */
    private final Map<String, String> forcedParams;
    private final Overrides overrides;
    /** Every parameter set version used, by set id and then version. */
    private final Set<ParamSetVersion> params = new TreeSet<>(
            Comparator.comparing(ParamSetVersion::set).thenComparing(ParamSetVersion::version));

    Progress() {
        this(Map.of());
    }

    /** @param forcedParams the parameter set versions the request forces, by set id */
    Progress(Map<String, String> forcedParams) {
        this(forcedParams, Overrides.NONE);
    }

    /**
     * @param forcedParams the parameter set versions the request forces, by set id
     * @param overrides    what a replay or re-score fixes besides
     */
    Progress(Map<String, String> forcedParams, Overrides overrides) {
        this.forcedParams = Map.copyOf(forcedParams);
        this.overrides = overrides;
    }

    Map<String, String> forcedParams() {
        return forcedParams;
    }

    Overrides overrides() {
        return overrides;
    }

    /** Records that the evaluation used {@code version} of parameter set {@code set}. */
    void params(String set, String version) {
        params.add(new ParamSetVersion(set, version));
    }

    /** The parameter set versions used, each once, sorted by set id and then version. */
    List<ParamSetVersion> params() {
        return List.copyOf(params);
    }

    /** Starts evaluating a node; until {@link #close()}, {@link #path()} ends with {@code step}. */
    void open(Supplier<PathStep> step) {
        current = step;
    }

    /** Finishes the node opened last. */
    void close() {
        if (current != null) {
            path.add(current.get());
            current = null;
        }
    }

    /** The nodes visited, ending with the node being evaluated, if any. */
    List<PathStep> path() {
        if (current == null) {
            return List.copyOf(path);
        }
        List<PathStep> steps = new ArrayList<>(path);
        steps.add(current.get());
        return steps;
    }

    void factsHash(String hash) {
        factsHash = Optional.of(hash);
    }

    Optional<String> factsHash() {
        return factsHash;
    }

    /** Ids of the matched rules among {@link #trace} from index {@code from}. */
    List<String> matchedSince(int from) {
        return trace.subList(from, trace.size()).stream().filter(TraceStep::matched).map(TraceStep::ruleId).toList();
    }
}
