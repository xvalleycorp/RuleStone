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
import vn.io.dxa.rulestone.api.DecisionComparison;
import vn.io.dxa.rulestone.api.DecisionComparison.Difference;
import vn.io.dxa.rulestone.api.DecisionEvaluationException;
import vn.io.dxa.rulestone.api.DecisionNotFoundException;
import vn.io.dxa.rulestone.api.DecisionRecord;
import vn.io.dxa.rulestone.api.DecisionRef;
import vn.io.dxa.rulestone.api.DecisionResult;
import vn.io.dxa.rulestone.api.Facts;
import vn.io.dxa.rulestone.api.RuleStoneEngine;
import vn.io.dxa.rulestone.core.DecisionRecordJson;

/**
 * Evaluates RuleStone decisions inside workflow code, deterministically across replays.
 *
 * <p>On first execution of each {@link #evaluate} call, the decision is evaluated once, inside
 * {@link Workflow#sideEffect}, and its decision record, which holds no facts, is recorded in workflow
 * history; the engine's audit sinks receive the record then, and only then. On every later execution
 * of the workflow code, the recorded decision is replayed with {@link RuleStoneEngine#replay}: with
 * exactly the recorded decision version, business date, parameter set versions, table versions and
 * model results, writing nothing to the audit sinks and calling no model. So a version that became
 * effective since, a different time zone, a changed clock or a retrained model cannot change the
 * outcome. A version that is no longer registered with exactly the recorded content hash, or facts
 * that no longer give the recorded outcome, fail with {@link RuleVersionMismatchException}.
 *
 * <p>The outcome carries the id of that record, the same on every replay, so that the decision can
 * be found in the audit store and replayed or re-scored later. Histories written before RuleStone
 * recorded the record hold the exact version and business date only; they are reproduced by
 * evaluating that version on that date, as before, and their outcomes have no record id.
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
    private static final String PIN_FORMAT_V1 = "rulestone-pin/1";
    private static final String PIN_FORMAT = "rulestone-pin/2";

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
     * @return the outcome, with the business date and exact rule version used, and the id of the
     *         decision record made on first execution
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

        if (pin.record != null) {
            DecisionOutcome replayed = replay(pin, facts);
            if (replayed != null) {
                return replayed;
            }
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
        // The record of this evaluation is a new one on every execution: name the recorded one, if any.
        String recordId = pin.record == null ? null : DecisionRecordJson.read(pin.record).recordId();
        return DecisionOutcome.from(result, pin.businessDate, recordId);
    }

    /** Replays the decision whose record the pin holds; null if the engine cannot replay decisions. */
    private DecisionOutcome replay(Pin pin, Facts facts) {
        DecisionRecord recorded = DecisionRecordJson.read(pin.record);
        DecisionComparison replay;
        try {
            // The model results come from the record.
            replay = engine.replay(recorded, Facts.from(facts.asMap()));
        } catch (UnsupportedOperationException e) {
            return null;   // an engine of another kind: evaluate the recorded version on the recorded date
        } catch (DecisionNotFoundException e) {
            throw new RuleVersionMismatchException(pin.decisionId, pin.ruleVersion, null,
                    "workflow history recorded rule version " + pin.ruleVersion + ", which this engine cannot"
                    + " replay: " + e.getMessage() + ". Keep every rule and parameter set version registered,"
                    + " with its original content, while workflows that used it can still replay; publish changes"
                    + " under a new version label. The workflow task is retried until then.");
        }
        if (!replay.matches() || replay.result().isEmpty()) {
            throw new RuleVersionMismatchException(pin.decisionId, pin.ruleVersion, null,
                    "replaying the decision recorded in workflow history gave another outcome: "
                    + String.join("; ", replay.differences().stream().map(Difference::description).toList())
                    + ". Facts must be deterministic workflow data.");
        }
        return DecisionOutcome.from(replay.result().get(), pin.businessDate, recorded.recordId());
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
        final String record;        // the decision record as JSON, or null: a failed evaluation, or a pin/1

        private Pin(String decisionId, LocalDate businessDate, String ruleVersion, String notFound, String record) {
            this.decisionId = decisionId;
            this.businessDate = businessDate;
            this.ruleVersion = ruleVersion;
            this.notFound = notFound;
            this.record = record;
        }

        /**
         * Runs on first execution only. Evaluates the decision, which hands its record to the audit
         * sinks, and keeps the record, which holds no facts; facts never leave this method.
         */
        static Pin resolve(RuleStoneEngine engine, DecisionRef ref, LocalDate date, Facts facts) {
            try {
                DecisionResult result = engine.evaluate(ref.asOf(date), facts);
                return new Pin(ref.id(), date, result.ruleVersion(), null, DecisionRecordJson.write(result.record()));
            } catch (DecisionEvaluationException e) {
                // The version is known; the evaluation fails again, deterministically, after pinning.
                return new Pin(ref.id(), date, e.ruleVersion(), null, null);
            } catch (DecisionNotFoundException e) {
                return new Pin(ref.id(), date, null, e.getMessage(), null);
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
            fields.put("record", record);
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
            String format = fields.get("format");
            if (!PIN_FORMAT.equals(format) && !PIN_FORMAT_V1.equals(format)) {
                throw new IllegalStateException("workflow history holds a RuleStone pin of unknown format");
            }
            return new Pin(fields.get("decisionId"), LocalDate.parse(fields.get("businessDate")),
                    fields.get("ruleVersion"), fields.get("notFound"), fields.get("record"));
        }
    }
}
