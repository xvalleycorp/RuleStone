//
// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.rulestone.temporal;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import vn.io.dxa.rulestone.api.DecisionEvaluationException;
import vn.io.dxa.rulestone.api.DecisionNotFoundException;
import vn.io.dxa.rulestone.api.DecisionRef;
import vn.io.dxa.rulestone.api.DecisionResult;
import vn.io.dxa.rulestone.api.Facts;
import vn.io.dxa.rulestone.api.RuleStoneEngine;
import vn.io.dxa.rulestone.core.DecisionTable;

/**
 * The {@link RuleStoneActivities} implementation: evaluates requests with an engine.
 *
 * <p>Facts arrive in JSON forms and are read with the input types of the decision version being
 * evaluated, so the activity needs the {@link DecisionTable}s as well as the engine. Pass the same
 * tables you registered with the engine.
 *
 * <p>The business date is the request's {@code asOf}, or else today according to this activity's
 * clock; the date used is always passed to the engine explicitly and recorded in the outcome. Set
 * the clock to your bank's business time zone, like the engine's.
 *
 * <p>This class logs nothing. It is thread-safe: register one instance with the worker.
 */
public final class RuleStoneActivitiesImpl implements RuleStoneActivities {

    private final RuleStoneEngine engine;
    private final Map<String, List<DecisionTable>> tables;
    private final Clock clock;

    /**
     * Creates the activity with the system clock in the JVM's default time zone. Prefer
     * {@link #RuleStoneActivitiesImpl(RuleStoneEngine, Collection, Clock)} with an explicit zone.
     *
     * @param engine the engine
     * @param tables every decision table registered with the engine
     */
    public RuleStoneActivitiesImpl(RuleStoneEngine engine, Collection<DecisionTable> tables) {
        this(engine, tables, Clock.systemDefaultZone());
    }

    /**
     * Creates the activity.
     *
     * @param engine the engine
     * @param tables every decision table registered with the engine
     * @param clock  determines the business date of requests without {@code asOf}, e.g.
     *               {@code Clock.system(ZoneId.of("Asia/Ho_Chi_Minh"))}
     */
    public RuleStoneActivitiesImpl(RuleStoneEngine engine, Collection<DecisionTable> tables, Clock clock) {
        this.engine = Objects.requireNonNull(engine, "engine");
        this.clock = Objects.requireNonNull(clock, "clock");
        Map<String, List<DecisionTable>> byId = new LinkedHashMap<>();
        for (DecisionTable table : tables) {
            byId.computeIfAbsent(table.id(), id -> new ArrayList<>()).add(table);
        }
        byId.replaceAll((id, versions) -> List.copyOf(versions));
        this.tables = Map.copyOf(byId);
    }

    @Override
    public DecisionOutcome evaluate(DecisionRequest request) {
        if (request == null) {
            throw RuleStoneFailures.invalidRequest("request is null");
        }
        LocalDate businessDate = request.asOf() != null ? request.asOf() : LocalDate.now(clock);
        DecisionTable table = table(request, businessDate);
        Facts facts;
        try {
            facts = PayloadJson.readFacts(request.facts(), table.inputs());
        } catch (IllegalArgumentException e) {
            throw RuleStoneFailures.invalidRequest(table.ref() + ": " + e.getMessage());
        }
        DecisionRef ref = DecisionRef.of(request.decisionId()).asOf(businessDate);
        if (request.version() != null) {
            ref = ref.version(request.version());
        }
        DecisionResult result;
        try {
            result = engine.evaluate(ref, facts);
        } catch (DecisionNotFoundException e) {
            throw RuleStoneFailures.decisionNotFound(e);
        } catch (DecisionEvaluationException e) {
            throw RuleStoneFailures.evaluationFailed(e);
        }
        return DecisionOutcome.from(result, businessDate);
    }

    /** The table whose input types describe the facts; selected as the engine selects versions. */
    private DecisionTable table(DecisionRequest request, LocalDate businessDate) {
        String id = request.decisionId();
        List<DecisionTable> versions = tables.get(id);
        if (versions == null) {
            throw RuleStoneFailures.decisionNotFound("unknown decision '" + id + "'");
        }
        String version = request.version();
        if (version != null) {
            return versions.stream()
                    .filter(t -> t.version().equals(version))
                    .findFirst()
                    .orElseThrow(() -> RuleStoneFailures.decisionNotFound(
                            "decision '" + id + "' has no version '" + version + "'"));
        }
        return versions.stream()
                .filter(t -> t.isEffectiveOn(businessDate))
                .findFirst()
                .orElseThrow(() -> RuleStoneFailures.decisionNotFound(
                        "no version of decision '" + id + "' is effective on " + businessDate));
    }
}
