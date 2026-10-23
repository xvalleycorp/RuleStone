//
// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.rulestone.core;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import vn.io.dxa.rulestone.api.DecisionNotFoundException;
import vn.io.dxa.rulestone.api.DecisionRef;
import vn.io.dxa.rulestone.api.DecisionResult;
import vn.io.dxa.rulestone.api.Facts;
import vn.io.dxa.rulestone.api.RuleStoneEngine;

/** Embedded engine over an immutable set of compiled decisions. */
final class DefaultRuleStoneEngine implements RuleStoneEngine {

    private final Map<String, List<CompiledDecision>> decisions;
    private final Clock clock;

    DefaultRuleStoneEngine(Map<String, List<CompiledDecision>> decisions, Clock clock) {
        this.decisions = Map.copyOf(decisions);
        this.clock = clock;
    }

    @Override
    public DecisionResult evaluate(DecisionRef ref, Facts facts) {
        // Read the clock once: the same date selects the version and is the businessDate input.
        LocalDate businessDate = ref.asOf().orElseGet(() -> LocalDate.now(clock));
        return resolve(ref, businessDate).evaluate(facts, businessDate);
    }

    private CompiledDecision resolve(DecisionRef ref, LocalDate date) {
        List<CompiledDecision> versions = decisions.get(ref.id());
        if (versions == null) {
            throw new DecisionNotFoundException("unknown decision '" + ref.id() + "'");
        }
        if (ref.version().isPresent()) {
            String version = ref.version().get();
            return versions.stream()
                    .filter(d -> d.table().version().equals(version))
                    .findFirst()
                    .orElseThrow(() -> new DecisionNotFoundException(
                            "decision '" + ref.id() + "' has no version '" + version + "'"));
        }
        return versions.stream()
                .filter(d -> d.table().isEffectiveOn(date))
                .findFirst()
                .orElseThrow(() -> new DecisionNotFoundException(
                        "no version of decision '" + ref.id() + "' is effective on " + date));
    }
}
