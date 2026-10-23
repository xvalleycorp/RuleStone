//
// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.rulestone.api;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Outcome of evaluating one decision.
 *
 * @param decisionId  the evaluated decision
 * @param ruleVersion exact rule version used, including its content hash, e.g. {@code 3@sha256:…}
 * @param output      named output values produced by the decision, in the order the decision declares them
 * @param reasonCodes stable, machine-readable reasons (e.g. {@code DTI_HIGH}) for audit and customer notices
 * @param trace       the rules examined, in evaluation order
 * @param record      the explanation of this evaluation, also given to every {@link AuditSink}
 */
public record DecisionResult(
        String decisionId,
        String ruleVersion,
        Map<String, Object> output,
        List<String> reasonCodes,
        List<TraceStep> trace,
        DecisionRecord record) {

    public DecisionResult {
        Objects.requireNonNull(decisionId, "decisionId");
        Objects.requireNonNull(ruleVersion, "ruleVersion");
        Objects.requireNonNull(record, "record");
        // Keep the decision's output order so serialized results are deterministic.
        output.forEach((name, value) -> {
            Objects.requireNonNull(name, "output name");
            Objects.requireNonNull(value, () -> "value of output '" + name + "'");
        });
        output = Collections.unmodifiableMap(new LinkedHashMap<>(output));
        reasonCodes = List.copyOf(reasonCodes);
        trace = List.copyOf(trace);
    }

    /** Whether at least one rule matched. */
    public boolean matched() {
        return trace.stream().anyMatch(TraceStep::matched);
    }

    /** Ids of the matched rules, in evaluation order. */
    public List<String> matchedRules() {
        return trace.stream().filter(TraceStep::matched).map(TraceStep::ruleId).toList();
    }
}
