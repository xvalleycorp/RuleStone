//
// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.rulestone.governance;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import vn.io.dxa.rulestone.core.ApprovalStatement;

/**
 * One entry of the governance audit log: an append-only, hash-chained record of a governance event.
 *
 * <pre>{@code
 * {
 *   "seq": 1042,
 *   "eventId": "9d2b7a1e-3c4f-4a5b-8e6d-7f0a1b2c3d4e",
 *   "at": "2026-11-20T03:12:00.481Z",
 *   "type": "APPROVED",
 *   "actor": { "principal": "u:tran.thi.b", "via": "camunda8:rule-change/2251799813685321" },
 *   "changeSet": "CHG-2026-0042",
 *   "subjects": [ { "kind": "table", "id": "loan_eligibility", "version": "4@sha256:9f2c…" } ],
 *   "details": { "role": "CREDIT_RISK", "comment": "DTI limit matches the Credit Committee minutes" },
 *   "prev": "sha256:8c1e…",
 *   "hash": "sha256:5e0b…"
 * }
 * }</pre>
 *
 * <p>{@code hash} is the SHA-256 of the entry's canonical form without {@code hash}; {@code prev} is
 * the {@code hash} of entry {@code seq - 1}, and {@link #GENESIS} for the first. The log holds ids,
 * versions, hashes, principals and the free text of comments and reasons: no rule content and no
 * customer data.
 *
 * @param seq       the position in the log, from 1, without gaps
 * @param eventId   a unique id for the event
 * @param at        when, as a UTC instant with milliseconds, such as {@code 2026-11-20T03:12:00.481Z}
 * @param type      what happened
 * @param actor     who did it
 * @param changeSet the change set it concerns, if any
 * @param subjects  the exact versions it concerns
 * @param details   what else the event records: strings, whole numbers, booleans, lists and maps
 * @param prev      the hash of the previous entry
 * @param hash      the hash of this entry
 */
public record LogEntry(
        long seq,
        String eventId,
        String at,
        EventType type,
        Actor actor,
        Optional<String> changeSet,
        List<ApprovalStatement.Subject> subjects,
        Map<String, Object> details,
        String prev,
        String hash) {

    /** The {@code prev} of the first entry: 64 zeros. */
    public static final String GENESIS = "sha256:" + "0".repeat(64);

    @SuppressWarnings("unchecked")
    public LogEntry {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(at, "at");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(changeSet, "changeSet");
        subjects = List.copyOf(subjects);
        details = (Map<String, Object>) Canonical.copy(details, "details");
        Objects.requireNonNull(prev, "prev");
        Objects.requireNonNull(hash, "hash");
    }

    /**
     * Who did it.
     *
     * @param principal the principal id, or {@code svc:<issuer>} for the service itself
     * @param via       the system the action came from, such as the editor or a Camunda process instance
     */
    public record Actor(String principal, Optional<String> via) {

        public Actor {
            Objects.requireNonNull(principal, "principal");
            Objects.requireNonNull(via, "via");
        }
    }

    /** An entry chained to {@code prev}, with its hash computed. */
    static LogEntry create(long seq, String eventId, String at, EventType type, Actor actor, Optional<String> changeSet,
            List<ApprovalStatement.Subject> subjects, Map<String, Object> details, String prev) {
        LogEntry unsealed = new LogEntry(seq, eventId, at, type, actor, changeSet, subjects, details, prev, "");
        return new LogEntry(seq, eventId, at, type, actor, changeSet, subjects, details, prev,
                Canonical.sha256(LogEntryJson.writeUnsealed(unsealed)));
    }

    /** Whether {@link #hash()} is the hash of the rest of the entry. */
    public boolean hashMatches() {
        return Canonical.sha256(LogEntryJson.writeUnsealed(this)).equals(hash);
    }
}
