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
import vn.io.dxa.rulestone.api.DecisionRecord.ParamSetVersion;
import vn.io.dxa.rulestone.api.DecisionRecord.PathStep;
import vn.io.dxa.rulestone.api.TraceStep;

/** What one evaluation has done so far: enough to record it, also when it fails half-way. Not thread-safe. */
final class Progress {

    /** Every rule examined, in order, across all tables. */
    final List<TraceStep> trace = new ArrayList<>();
    private final List<PathStep> path = new ArrayList<>();
    /** The step of the node being evaluated, computed when asked so that it reflects the trace so far. */
    private Supplier<PathStep> current;
    private Optional<String> factsHash = Optional.empty();
    /** The parameter set versions the request forces, by set id. */
    private final Map<String, String> forcedParams;
    /** Every parameter set version used, by set id and then version. */
    private final Set<ParamSetVersion> params = new TreeSet<>(
            Comparator.comparing(ParamSetVersion::set).thenComparing(ParamSetVersion::version));

    Progress() {
        this(Map.of());
    }

    /** @param forcedParams the parameter set versions the request forces, by set id */
    Progress(Map<String, String> forcedParams) {
        this.forcedParams = Map.copyOf(forcedParams);
    }

    Map<String, String> forcedParams() {
        return forcedParams;
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
