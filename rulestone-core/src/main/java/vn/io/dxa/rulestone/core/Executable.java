//
// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.rulestone.core;

import java.time.LocalDate;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import vn.io.dxa.rulestone.api.DecisionRecord.ReasonCode;
import vn.io.dxa.rulestone.api.Facts;

/** A compiled decision table or flow, as the engine selects and evaluates it. Immutable and thread-safe. */
sealed interface Executable permits CompiledDecision, CompiledFlow {

    /** The definition, for version selection. */
    VersionedDefinition definition();

    /** The exact version, e.g. {@code 3@sha256:…}. */
    String ruleVersion();

    /**
     * Checks the facts and evaluates.
     *
     * @param progress receives the facts hash, the rules examined and the nodes visited as the
     *                 evaluation goes, so that a failure can still be recorded
     * @throws vn.io.dxa.rulestone.api.DecisionEvaluationException if the evaluation fails or times out
     */
    Run run(Facts facts, LocalDate businessDate, Deadline deadline, Progress progress, FactsHasher hasher);

    /** The outputs, in declared order, and the reason codes of one evaluation. */
    record Run(Map<String, Object> output, List<ReasonCode> reasonCodes) {

        public Run {
            output = Collections.unmodifiableMap(new LinkedHashMap<>(output));
            reasonCodes = List.copyOf(reasonCodes);
        }
    }
}
