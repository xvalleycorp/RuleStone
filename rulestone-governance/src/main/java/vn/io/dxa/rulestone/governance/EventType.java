//
// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.rulestone.governance;

/** The kinds of governance event, each one entry of the {@link LogEntry log}. */
public enum EventType {
    /** A change set was created. */
    DRAFT_CREATED,
    /** A maker saved a revision of an item. Makes the saver a maker; voids approvals of a submitted change set. */
    DRAFT_SAVED,
    /** A change set was frozen for review at the hashes of its items. */
    SUBMITTED,
    /** A maker took a submission back to draft before a decision. */
    WITHDRAWN,
    /** A comment on a change set, optionally at a location in an item. */
    COMMENTED,
    /** One checker approved, in a role. */
    APPROVED,
    /** A checker refused a submission, with a reason. */
    REJECTED,
    /** The policy is met: the signed approval statement was issued. Its {@code logEntry} is this entry's hash. */
    APPROVAL_ISSUED,
    /** A change set was declared an emergency. */
    EMERGENCY_DECLARED,
    /** One emergency approver approved an emergency change set. */
    EMERGENCY_APPROVED,
    /** The full policy post-approved an emergency change; a second statement was issued. */
    POST_APPROVED,
    /** The full policy refused an emergency change after the fact: it must be superseded or cancelled. */
    POST_APPROVAL_REJECTED,
    /** The post-approval of an emergency change is overdue. An alert, never a reason to refuse a build. */
    POST_APPROVAL_OVERDUE,
    /** A signed release was issued. */
    RELEASED,
    /** A change set entered a release. */
    ACTIVATED,
    /** A version can no longer be in force, and is listed as retired. */
    RETIRED,
    /** An active version that was never in force was cancelled. Reserved: cancellation is a later step. */
    CANCELLED,
    /** The governance policy changed. */
    POLICY_CHANGED,
    /** A signing key was added. Reserved for key management. */
    KEY_ADDED,
    /** A signing key was retired. Reserved for key management. */
    KEY_RETIRED,
    /** The head of the log was anchored outside the platform team's reach. Reserved for anchoring. */
    ANCHORED
}
