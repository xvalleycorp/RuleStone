//
// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.rulestone.temporal;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.temporal.workflow.Workflow;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import vn.io.dxa.rulestone.api.DecisionEvaluationException;
import vn.io.dxa.rulestone.api.DecisionNotFoundException;
import vn.io.dxa.rulestone.api.DecisionRef;
import vn.io.dxa.rulestone.api.DecisionResult;
import vn.io.dxa.rulestone.api.Facts;
import vn.io.dxa.rulestone.api.RuleStoneEngine;

/**
 * Evaluates RuleStone decisions inside workflow code, deterministically across replays.
 *
 * <p>On first execution of each {@link #evaluate} call, the business date and the exact rule version
 * ({@code <version>@sha256:<hash>}) are resolved and recorded in workflow history with
 * {@link Workflow#sideEffect}. The decision is then evaluated with that exact version and date. On
 * replay, the recorded values are read back instead of being resolved again, so a rule version that
 * became effective since, a different time zone, or a changed clock cannot change the outcome. The
 * content hash of the version now registered is compared with the recorded one; any difference fails
 * with {@link RuleVersionMismatchException}.
 *
 * <p>Only the pin is recorded, not the outcome: evaluation is pure, so the same version, date and
 * facts give the same outcome. History stays small, and the outcome is visible only if the workflow
 * returns, stores or upserts it (see {@link RuleStoneSearchAttributes}).
 *
 * <p>Rules for workflow code:
 *
 * <ul>
 *   <li>Facts must be deterministic workflow data: workflow arguments, signal or update payloads, and
 *       activity results. Never read clocks, random numbers, databases or mutable static state to
 *       build facts.</li>
 *   <li>Keep every rule version registered with the worker's engine while workflows that used it may
 *       still replay: until they close and the namespace retention period has passed. Never change the
 *       content of a version that was used; publish a new version label.</li>
 *   <li>Evaluations are matched to history by their order. Adding, removing or reordering
 *       {@code evaluate} calls in running workflows needs {@link Workflow#getVersion}, like any other
 *       change to workflow code.</li>
 * </ul>
 *
 * <p>Errors: {@link RuleStoneFailures#DECISION_NOT_FOUND} and
 * {@link RuleStoneFailures#DECISION_EVALUATION_FAILED} are thrown as non-retryable
 * {@link io.temporal.failure.ApplicationFailure}s, which fail the workflow unless caught.
 *
 * <p>Instances are immutable and may be kept in a field of the workflow implementation.
 */
public final class RuleStoneWorkflow {

    private static final ObjectMapper PIN_MAPPER = new ObjectMapper();
    private static final String PIN_FORMAT = "rulestone-pin/1";

    private final RuleStoneEngine engine;
    private final ZoneId businessZone;

    private RuleStoneWorkflow(RuleStoneEngine engine, ZoneId businessZone) {
        this.engine = engine;
        this.businessZone = businessZone;
    }

    /**
     * Creates the helper.
     *
     * @param engine       the engine of this worker; it must hold every rule version that running
     *                     workflows recorded
     * @param businessZone the time zone in which workflow time becomes a business date, e.g.
     *                     {@code ZoneId.of("Asia/Ho_Chi_Minh")}
     * @return the helper
     */
    public static RuleStoneWorkflow of(RuleStoneEngine engine, ZoneId businessZone) {
        return new RuleStoneWorkflow(Objects.requireNonNull(engine, "engine"),
                Objects.requireNonNull(businessZone, "businessZone"));
    }

    /**
     * Evaluates a decision from workflow code. Call only from a workflow thread.
     *
     * <p>Without {@code ref.asOf()}, the business date is the date of {@link Workflow#currentTimeMillis()}
     * in the business zone, never the system clock. With {@code ref.version()}, that version is used and
     * its hash is pinned too.
     *
     * @param ref   the decision, and optionally a version or business date
     * @param facts the inputs; deterministic workflow data only
     * @return the outcome, with the business date and exact rule version used
     * @throws io.temporal.failure.ApplicationFailure non-retryable, of type
     *         {@link RuleStoneFailures#DECISION_NOT_FOUND} or {@link RuleStoneFailures#DECISION_EVALUATION_FAILED}
     * @throws RuleVersionMismatchException on replay, if the recorded rule version cannot be reproduced
     */
    public DecisionOutcome evaluate(DecisionRef ref, Facts facts) {
        Objects.requireNonNull(ref, "ref");
        Objects.requireNonNull(facts, "facts");
        LocalDate date = ref.asOf().orElseGet(this::workflowBusinessDate);
        Pin pin = Pin.decode(Workflow.sideEffect(String.class, () -> Pin.resolve(engine, ref, date, facts).encode()));

        if (!pin.decisionId.equals(ref.id())
                || ref.version().isPresent() && pin.ruleVersion != null
                        && !ref.version().get().equals(pin.versionLabel())) {
            throw new RuleVersionMismatchException(ref.id(), pin.ruleVersion, null,
                    "workflow history recorded an evaluation of " + pin.describe() + " at this point, but the"
                    + " workflow code now evaluates " + describe(ref) + ". Guard changes to the order of"
                    + " RuleStone evaluations in running workflows with Workflow.getVersion.");
        }
        if (pin.notFound != null) {
            throw RuleStoneFailures.decisionNotFound(pin.notFound);
        }

        DecisionRef pinned = DecisionRef.of(ref.id()).version(pin.versionLabel()).asOf(pin.businessDate);
        DecisionResult result;
        try {
            result = engine.evaluate(pinned, facts);
        } catch (DecisionNotFoundException e) {
            throw new RuleVersionMismatchException(ref.id(), pin.ruleVersion, null,
                    "workflow history recorded rule version " + pin.ruleVersion + ", but the engine has no"
                    + " version '" + pin.versionLabel() + "'. Keep every rule version registered while"
                    + " workflows that used it can still replay.");
        } catch (DecisionEvaluationException e) {
            verify(pin, e.ruleVersion());
            throw RuleStoneFailures.evaluationFailed(e);
        }
        verify(pin, result.ruleVersion());
        return DecisionOutcome.from(result, pin.businessDate);
    }

    private LocalDate workflowBusinessDate() {
        return Instant.ofEpochMilli(Workflow.currentTimeMillis()).atZone(businessZone).toLocalDate();
    }

    private static void verify(Pin pin, String registered) {
        if (!registered.equals(pin.ruleVersion)) {
            throw new RuleVersionMismatchException(pin.decisionId, pin.ruleVersion, registered,
                    "workflow history recorded rule version " + pin.ruleVersion + ", but version '"
                    + pin.versionLabel() + "' now registered in the engine is " + registered + ". The rule"
                    + " content changed without a new version label. Register the original content of"
                    + " version '" + pin.versionLabel() + "' again (publish changes under a new label); the"
                    + " workflow task is retried until then.");
        }
    }

    private static String describe(DecisionRef ref) {
        return "decision '" + ref.id() + "'" + ref.version().map(v -> " version '" + v + "'").orElse("");
    }

    /**
     * What one {@code evaluate} call records in history. Stored as JSON text, which every payload
     * converter supports and the Temporal UI shows readably.
     */
    private static final class Pin {

        final String decisionId;
        final LocalDate businessDate;
        final String ruleVersion;   // null when the decision was not found
        final String notFound;      // the not-found message, or null

        private Pin(String decisionId, LocalDate businessDate, String ruleVersion, String notFound) {
            this.decisionId = decisionId;
            this.businessDate = businessDate;
            this.ruleVersion = ruleVersion;
            this.notFound = notFound;
        }

        /** Runs on first execution only. Resolves the version by evaluating; facts never leave this method. */
        static Pin resolve(RuleStoneEngine engine, DecisionRef ref, LocalDate date, Facts facts) {
            try {
                return new Pin(ref.id(), date, engine.evaluate(ref.asOf(date), facts).ruleVersion(), null);
            } catch (DecisionEvaluationException e) {
                // The version is known; the evaluation fails again, deterministically, after pinning.
                return new Pin(ref.id(), date, e.ruleVersion(), null);
            } catch (DecisionNotFoundException e) {
                return new Pin(ref.id(), date, null, e.getMessage());
            }
        }

        String versionLabel() {
            return ruleVersion.substring(0, ruleVersion.indexOf('@'));
        }

        String describe() {
            return "decision '" + decisionId + "'" + (ruleVersion == null ? "" : " version '" + versionLabel() + "'");
        }

        String encode() {
            Map<String, String> fields = new LinkedHashMap<>();
            fields.put("format", PIN_FORMAT);
            fields.put("decisionId", decisionId);
            fields.put("businessDate", businessDate.toString());
            fields.put("ruleVersion", ruleVersion);
            fields.put("notFound", notFound);
            try {
                return PIN_MAPPER.writeValueAsString(fields);
            } catch (JsonProcessingException e) {
                throw new IllegalStateException("cannot encode RuleStone pin", e);
            }
        }

        static Pin decode(String text) {
            Map<String, String> fields;
            try {
                fields = PIN_MAPPER.readValue(text, new TypeReference<Map<String, String>>() { });
            } catch (JsonProcessingException e) {
                throw new IllegalStateException("workflow history holds an unreadable RuleStone pin", e);
            }
            if (!PIN_FORMAT.equals(fields.get("format"))) {
                throw new IllegalStateException("workflow history holds a RuleStone pin of unknown format");
            }
            return new Pin(fields.get("decisionId"), LocalDate.parse(fields.get("businessDate")),
                    fields.get("ruleVersion"), fields.get("notFound"));
        }
    }
}
