//
// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.rulestone.governance;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import vn.io.dxa.rulestone.api.ArtifactKind;
import vn.io.dxa.rulestone.core.ApprovalStatement;

/**
 * A group of artifact versions that are submitted, reviewed, approved and activated together: one
 * submission and review of all items, one approval statement covering every item's hash, one
 * activation, so that engines never load half of a change. A change set of one artifact is the
 * common case.
 *
 * <p>It is an immutable snapshot; the service replaces it with each operation, and the
 * {@code revision} grows by one so that a store can refuse a concurrent update.
 *
 * @param id           the change set's id, such as {@code CHG-2026-0042}
 * @param title        what the change is for, short free text
 * @param state        where the change set is in the {@link LifecycleState lifecycle}
 * @param revision     counts the changes of this record, from 1
 * @param submission   how many times it was submitted; each submission is a separate event with its own hashes
 * @param createdBy    the principal who created it
 * @param items        the artifact versions it carries
 * @param makers       every principal who saved a revision of an item, or submitted it, and whoever the AI principals acted for: none of them may approve
 * @param approvals    the approvals of the current submission; an edit, a withdrawal or a rejection voids them
 * @param postApprovals the post-approvals of an emergency change, given after it was activated
 * @param emergency    set if the change set was declared an emergency
 * @param rejection    why the last submission was rejected
 * @param activations  the releases it entered
 */
public record ChangeSet(
        String id,
        String title,
        LifecycleState state,
        long revision,
        int submission,
        String createdBy,
        List<Item> items,
        List<String> makers,
        List<Approval> approvals,
        List<Approval> postApprovals,
        Optional<Emergency> emergency,
        Optional<String> rejection,
        List<Activation> activations) {

    public ChangeSet {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(createdBy, "createdBy");
        items = List.copyOf(items);
        makers = List.copyOf(makers);
        approvals = List.copyOf(approvals);
        postApprovals = List.copyOf(postApprovals);
        Objects.requireNonNull(emergency, "emergency");
        Objects.requireNonNull(rejection, "rejection");
        activations = List.copyOf(activations);
    }

    /** A new, empty change set in {@code DRAFT}. */
    static ChangeSet create(String id, String title, String createdBy) {
        return new ChangeSet(id, title, LifecycleState.DRAFT, 1, 0, createdBy, List.of(), List.of(), List.of(),
                List.of(), Optional.empty(), Optional.empty(), List.of());
    }

    /** The exact versions of all items, as an approval statement names them. */
    public List<ApprovalStatement.Subject> subjects() {
        return items.stream().map(Item::subject).toList();
    }

    ChangeSet withRevision(long revision) {
        return new ChangeSet(id, title, state, revision, submission, createdBy, items, makers, approvals, postApprovals,
                emergency, rejection, activations);
    }

    /** Starts a change to this record; {@link Edit#build()} moves the revision on by one. */
    Edit edit() {
        return new Edit(this);
    }

    /**
     * One artifact version in a change set.
     *
     * @param kind         the kind of artifact
     * @param id           its id; for a catalog the name given to it, by default {@code reason-codes}
     * @param label        its version label, e.g. {@code 4}
     * @param exactVersion {@code <label>@sha256:<hex>}, computed by the service from the content
     * @param content      the JSON text as saved; the content lives in the store, never in the log
     * @param state        the item's own state: its change set's, until it is retired
     */
    public record Item(ArtifactKind kind, String id, String label, String exactVersion, String content,
            LifecycleState state) {

        public Item {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(label, "label");
            Objects.requireNonNull(exactVersion, "exactVersion");
            Objects.requireNonNull(content, "content");
            Objects.requireNonNull(state, "state");
        }

        public ApprovalStatement.Subject subject() {
            return new ApprovalStatement.Subject(kind, id, exactVersion);
        }

        boolean sameLabel(Item other) {
            return kind == other.kind && id.equals(other.id) && label.equals(other.label);
        }

        Item with(LifecycleState state) {
            return new Item(kind, id, label, exactVersion, content, state);
        }
    }

    /**
     * An approval of a checker.
     *
     * @param principal the approver
     * @param role      the role they approved in
     * @param unit      their organisational unit, if the identity provider gave one
     * @param at        when
     * @param comment   what they wrote, if anything
     * @param emergency whether it is an approval in an emergency role
     */
    public record Approval(String principal, String role, Optional<String> unit, Instant at, Optional<String> comment,
            boolean emergency) {

        public Approval {
            Objects.requireNonNull(principal, "principal");
            Objects.requireNonNull(role, "role");
            Objects.requireNonNull(unit, "unit");
            Objects.requireNonNull(at, "at");
            Objects.requireNonNull(comment, "comment");
        }
    }

    /** How far an emergency change is on its way to the full policy's post-approval. */
    public enum EmergencyStatus {
        /** Declared, not yet approved by an emergency approver. */
        DECLARED,
        /** Approved by an emergency approver and usable; the full policy must post-approve by the due instant. */
        AWAITING_POST_APPROVAL,
        /** The full policy post-approved the same content hashes. */
        POST_APPROVED,
        /** The full policy refused: the version must be superseded or cancelled. */
        POST_APPROVAL_REJECTED
    }

    /**
     * The emergency part of a change set.
     *
     * @param reason          why it cannot wait
     * @param declaredBy      who declared it
     * @param status          where it is
     * @param postApprovalDue when the full policy must have post-approved; set at the emergency approval
     * @param overdueAlerted  whether the overdue alert was raised
     */
    public record Emergency(String reason, String declaredBy, EmergencyStatus status, Optional<Instant> postApprovalDue,
            boolean overdueAlerted) {

        public Emergency {
            Objects.requireNonNull(reason, "reason");
            Objects.requireNonNull(declaredBy, "declaredBy");
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(postApprovalDue, "postApprovalDue");
        }
    }

    /**
     * A release the change set entered.
     *
     * @param target   the target
     * @param sequence the release's sequence
     */
    public record Activation(String target, long sequence) {}

    /** A mutable copy of a change set. */
    static final class Edit {

        private final ChangeSet base;
        LifecycleState state;
        int submission;
        String title;
        List<Item> items;
        List<String> makers;
        List<Approval> approvals;
        List<Approval> postApprovals;
        Optional<Emergency> emergency;
        Optional<String> rejection;
        List<Activation> activations;

        private Edit(ChangeSet base) {
            this.base = base;
            this.state = base.state;
            this.submission = base.submission;
            this.title = base.title;
            this.items = new ArrayList<>(base.items);
            this.makers = new ArrayList<>(base.makers);
            this.approvals = new ArrayList<>(base.approvals);
            this.postApprovals = new ArrayList<>(base.postApprovals);
            this.emergency = base.emergency;
            this.rejection = base.rejection;
            this.activations = new ArrayList<>(base.activations);
        }

        /** Moves to {@code next}, which the lifecycle must allow, and moves every item that is not retired with it. */
        Edit moveTo(LifecycleState next) {
            state.requireMoveTo(next);
            state = next;
            items.replaceAll(item -> item.with(next));
            return this;
        }

        Edit maker(String principal) {
            if (!makers.contains(principal)) {
                makers.add(principal);
            }
            return this;
        }

        ChangeSet build() {
            return new ChangeSet(base.id, title, state, base.revision + 1, submission, base.createdBy, items, makers,
                    approvals, postApprovals, emergency, rejection, activations);
        }
    }
}
