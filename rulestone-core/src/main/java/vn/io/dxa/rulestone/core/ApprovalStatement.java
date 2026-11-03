//
// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.rulestone.core;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import vn.io.dxa.rulestone.api.ArtifactKind;

/**
 * A statement that the exact content of some artifacts was approved: the payload of a signed
 * {@code .approval.jws} file, read with {@link ApprovalStatementJson} and verified with an
 * {@link ApprovalVerifier}. An engine built with {@link RuleStone.Builder#requireApproved} loads
 * only content that such statements cover.
 *
 * <pre>{@code
 * {
 *   "type": "rulestone.approval",
 *   "approvalId": "4a1f9c2e-7b3d-4e8a-b5c6-0d9e8f7a6b5c",
 *   "changeSet": "CHG-2026-0042",
 *   "subjects": [ { "kind": "table", "id": "loan_eligibility", "version": "4@sha256:9f2c…" } ],
 *   "makers": ["u:nguyen.van.a"],
 *   "approvers": [ { "principal": "u:tran.thi.b", "role": "CREDIT_RISK", "at": "2026-11-20T03:12:00Z" } ],
 *   "policy": "credit-policy@3",
 *   "emergency": false,
 *   "issuer": "rulestone-governance@bank.example",
 *   "issuedAt": "2026-11-20T07:41:02Z",
 *   "logEntry": "sha256:5e0b…"
 * }
 * }</pre>
 *
 * <p>Instants are UTC, written as {@code 2026-11-20T07:41:02Z}, optionally with a fraction of a
 * second. The statement holds principal ids, versions and hashes, never rule content or customer data.
 *
 * @param approvalId      the statement's unique id, e.g. a UUID
 * @param changeSet       the change set approved, e.g. {@code CHG-2026-0042}; problems name the statement by it
 * @param subjects        the exact versions approved, at least one
 * @param makers          every principal who wrote or submitted the change, at least one
 * @param approvers       the approvers, at least one, each a distinct principal
 * @param policy          the governance policy applied, e.g. {@code credit-policy@3}
 * @param emergency       whether this is an emergency approval, to be post-approved
 * @param postApprovalDue for an emergency approval, when the full policy must post-approve it
 * @param issuer          who signed the statement, e.g. the governance service or a CI job
 * @param issuedAt        when the statement was signed
 * @param logEntry        {@code sha256:<hex>} of the governance log entry that issued it, if any
 */
public record ApprovalStatement(
        String approvalId,
        String changeSet,
        List<Subject> subjects,
        List<String> makers,
        List<Approver> approvers,
        Optional<String> policy,
        boolean emergency,
        Optional<String> postApprovalDue,
        String issuer,
        String issuedAt,
        Optional<String> logEntry) {

    /** The value of the statement's {@code type} field. */
    public static final String TYPE = "rulestone.approval";

    /**
     * The id under which subjects name an engine's reason-code catalog, which has no id of its own:
     * {@code reason-codes}, after its conventional file name.
     */
    public static final String CATALOG_ID = "reason-codes";

    public ApprovalStatement {
        Objects.requireNonNull(approvalId, "approvalId");
        Objects.requireNonNull(changeSet, "changeSet");
        subjects = List.copyOf(subjects);
        makers = List.copyOf(makers);
        approvers = List.copyOf(approvers);
        Objects.requireNonNull(policy, "policy");
        Objects.requireNonNull(postApprovalDue, "postApprovalDue");
        Objects.requireNonNull(issuer, "issuer");
        Objects.requireNonNull(issuedAt, "issuedAt");
        Objects.requireNonNull(logEntry, "logEntry");
    }

    /**
     * One approved artifact version.
     *
     * @param kind    the kind of artifact
     * @param id      its id; for a catalog, the name the repository gives it, by default {@code reason-codes}
     * @param version its exact version, {@code <label>@sha256:<hex>}, e.g. {@code 4@sha256:9f2c…}
     */
    public record Subject(ArtifactKind kind, String id, String version) {

        public Subject {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(version, "version");
        }
    }

    /**
     * One approver.
     *
     * @param principal the approver's principal id, e.g. {@code u:tran.thi.b}
     * @param role      the role they approved in, e.g. {@code CREDIT_RISK}
     * @param at        when they approved
     */
    public record Approver(String principal, Optional<String> role, String at) {

        public Approver {
            Objects.requireNonNull(principal, "principal");
            Objects.requireNonNull(role, "role");
            Objects.requireNonNull(at, "at");
        }
    }
}
