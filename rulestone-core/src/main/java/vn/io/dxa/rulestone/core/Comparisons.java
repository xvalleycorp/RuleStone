//
// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.rulestone.core;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import vn.io.dxa.rulestone.api.DecisionComparison.Difference;
import vn.io.dxa.rulestone.api.DecisionComparison.Kind;
import vn.io.dxa.rulestone.api.DecisionRecord;
import vn.io.dxa.rulestone.api.DecisionRecord.ModelStep;
import vn.io.dxa.rulestone.api.DecisionRecord.ParamSetVersion;
import vn.io.dxa.rulestone.api.DecisionRecord.PathStep;
import vn.io.dxa.rulestone.api.DecisionRecord.ReasonCode;
import vn.io.dxa.rulestone.api.DecisionRecord.Status;

/**
 * Compares a recorded decision with a new evaluation, for replays and re-scores. Descriptions name
 * outputs, nodes, rules, versions and codes, never input or output values.
 */
final class Comparisons {

    private Comparisons() {}

    /**
     * The differences between {@code recorded} and {@code evaluated}, in the order of the record's
     * fields. The facts hash is compared separately, by {@link #factsHash}.
     */
    static List<Difference> compare(DecisionRecord recorded, DecisionRecord evaluated) {
        List<Difference> differences = new ArrayList<>();
        if (recorded.status() != evaluated.status()) {
            differences.add(new Difference(Kind.STATUS, "status: recorded " + recorded.status() + ", now "
                    + evaluated.status()));
        } else if (!recorded.error().equals(evaluated.error())) {
            differences.add(new Difference(Kind.STATUS, "error: the recorded and the new error differ"));
        }
        if (!recorded.version().equals(evaluated.version())) {
            differences.add(new Difference(Kind.VERSION, "version: recorded " + recorded.version().orElse("none")
                    + ", now " + evaluated.version().orElse("none")));
        }
        if (!recorded.businessDate().equals(evaluated.businessDate())) {
            differences.add(new Difference(Kind.BUSINESS_DATE, "businessDate: recorded " + recorded.businessDate()
                    + ", now " + evaluated.businessDate()));
        }
        if (!recorded.params().equals(evaluated.params())) {
            differences.add(new Difference(Kind.PARAMS, "params: recorded " + params(recorded.params()) + ", now "
                    + params(evaluated.params())));
        }
        if (recorded.status() == Status.OK && evaluated.status() == Status.OK) {
            outputs(recorded.output(), evaluated.output(), differences);
            if (!recorded.reasonCodes().equals(evaluated.reasonCodes())) {
                differences.add(new Difference(Kind.REASON_CODES, "reasonCodes: recorded "
                        + reasons(recorded.reasonCodes()) + ", now " + reasons(evaluated.reasonCodes())));
            }
        }
        path(recorded.path(), evaluated.path(), differences);
        return differences;
    }

    /**
     * The difference between the record's facts hash and the hash of the facts given now, if any.
     *
     * @param evaluated the hash of the facts given now, computed with the recorded version
     */
    static Optional<Difference> factsHash(Optional<String> recorded, Optional<String> evaluated) {
        if (recorded.equals(evaluated)) {
            return Optional.empty();
        }
        if (recorded.isEmpty()) {
            return Optional.of(new Difference(Kind.FACTS,
                    "factsHash: the record has none, because its inputs were not valid"));
        }
        if (evaluated.isEmpty()) {
            return Optional.of(new Difference(Kind.FACTS, "factsHash: the facts are not valid inputs of "
                    + "the recorded version"));
        }
        String was = algorithm(recorded.get());
        String now = algorithm(evaluated.get());
        if (!was.equals(now)) {
            return Optional.of(new Difference(Kind.FACTS, "factsHash: the record's hash is " + was
                    + " but this engine computes " + now + "; verify with the factsHashKey the record was made with"));
        }
        return Optional.of(new Difference(Kind.FACTS,
                "factsHash: the facts are not those the record was made on"
                        + (was.startsWith("hmac") ? ", or the factsHashKey is not the one it was made with" : "")));
    }

    private static String algorithm(String hash) {
        int colon = hash.indexOf(':');
        return colon < 0 ? "unknown" : hash.substring(0, colon);
    }

    private static void outputs(Map<String, Object> recorded, Map<String, Object> evaluated,
                                List<Difference> differences) {
        Set<String> names = new LinkedHashSet<>(recorded.keySet());
        names.addAll(evaluated.keySet());
        for (String name : names) {
            Object was = recorded.get(name);
            Object now = evaluated.get(name);
            if (now == null) {
                differences.add(new Difference(Kind.OUTPUT, "output '" + name + "': recorded, not set now"));
            } else if (was == null) {
                differences.add(new Difference(Kind.OUTPUT, "output '" + name + "': set now, not recorded"));
            } else if (!same(was, now)) {
                differences.add(new Difference(Kind.OUTPUT, "output '" + name + "' differs"));
            }
        }
    }

    /**
     * Whether two values are the same, compared in their JSON forms, so that a value read back from a
     * record's JSON, such as {@code "0.0310"} for a decimal, equals the decimal itself.
     */
    static boolean same(Object a, Object b) {
        return json(FactsJson.writeValue(a)).equals(json(FactsJson.writeValue(b)));
    }

    private static String json(JsonNode node) {
        return node.toString();
    }

    private static void path(List<PathStep> recorded, List<PathStep> evaluated, List<Difference> differences) {
        int common = Math.min(recorded.size(), evaluated.size());
        for (int i = 0; i < common; i++) {
            step(i + 1, recorded.get(i), evaluated.get(i), differences);
        }
        if (recorded.size() != evaluated.size()) {
            differences.add(new Difference(Kind.PATH, "path: recorded " + recorded.size() + " steps ("
                    + nodes(recorded) + "), now " + evaluated.size() + " (" + nodes(evaluated) + ")"));
        }
    }

    private static void step(int index, PathStep was, PathStep now, List<Difference> differences) {
        String at = "path step " + index + " (" + was.node() + ")";
        if (!was.node().equals(now.node()) || !was.type().equals(now.type())) {
            differences.add(new Difference(Kind.PATH, "path step " + index + ": recorded " + was.type() + " node '"
                    + was.node() + "', now " + now.type() + " node '" + now.node() + "'"));
            return;
        }
        if (!was.decision().equals(now.decision()) || !was.version().equals(now.version())) {
            differences.add(new Difference(Kind.PATH, at + ": recorded " + version(was) + ", now " + version(now)));
        }
        if (!was.matchedRules().equals(now.matchedRules())) {
            differences.add(new Difference(Kind.PATH, at + ": recorded matched rules " + was.matchedRules()
                    + ", now " + now.matchedRules()));
        }
        if (!was.taken().equals(now.taken())) {
            differences.add(new Difference(Kind.PATH, at + ": recorded taken '" + was.taken().orElse("")
                    + "', now '" + now.taken().orElse("") + "'"));
        }
        if (was.model().isPresent() && now.model().isPresent()) {
            model(at, was.model().get(), now.model().get(), differences);
        } else if (was.model().isPresent() != now.model().isPresent()) {
            differences.add(new Difference(Kind.PATH, at + ": only one of the steps has a model result"));
        }
    }

    /** Compares what a model returned; the mode, latency and failure detail are how, not what. */
    private static void model(String at, ModelStep was, ModelStep now, List<Difference> differences) {
        if (!was.modelId().equals(now.modelId()) || !was.modelVersion().equals(now.modelVersion())) {
            differences.add(new Difference(Kind.PATH, at + ": recorded model " + was.modelId() + "@"
                    + was.modelVersion() + ", now " + now.modelId() + "@" + now.modelVersion()));
            return;
        }
        if (was.status() != now.status()) {
            differences.add(new Difference(Kind.PATH, at + ": recorded model status " + was.status() + ", now "
                    + now.status()));
            return;
        }
        Set<String> names = new LinkedHashSet<>(was.outputs().keySet());
        names.addAll(now.outputs().keySet());
        for (String name : names) {
            Object a = was.outputs().get(name);
            Object b = now.outputs().get(name);
            if (a == null || b == null || !same(a, b)) {
                differences.add(new Difference(Kind.PATH, at + ": model output '" + name + "' differs"));
            }
        }
        boolean sameConfidence = was.confidence().isPresent() == now.confidence().isPresent()
                && (was.confidence().isEmpty() || was.confidence().get().compareTo(now.confidence().get()) == 0);
        if (!sameConfidence) {
            differences.add(new Difference(Kind.PATH, at + ": model confidence differs"));
        }
        if (!was.attributionMethod().equals(now.attributionMethod()) || !was.attributions().equals(now.attributions())) {
            differences.add(new Difference(Kind.PATH, at + ": model attributions differ"));
        }
    }

    private static String version(PathStep step) {
        return step.decision().map(d -> d + " ").orElse("") + step.version().orElse("no version");
    }

    private static String nodes(List<PathStep> path) {
        return String.join(", ", path.stream().map(PathStep::node).toList());
    }

    private static String params(List<ParamSetVersion> params) {
        return params.isEmpty() ? "none"
                : String.join(", ", params.stream().map(p -> p.set() + " " + p.version()).toList());
    }

    private static String reasons(List<ReasonCode> reasons) {
        return "[" + String.join(", ", reasons.stream().map(r -> r.code() + " from " + r.source()).toList()) + "]";
    }
}
