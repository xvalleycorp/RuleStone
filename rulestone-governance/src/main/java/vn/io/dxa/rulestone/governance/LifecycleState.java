//
// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.rulestone.governance;

import java.util.EnumSet;
import java.util.Set;

/**
 * The lifecycle of a governed artifact version and of the change set that carries it:
 *
 * <pre>
 *            submit            approve (policy met)          activate
 *  DRAFT ─────────────▶ SUBMITTED ─────────────▶ APPROVED ─────────────▶ ACTIVE ─────▶ RETIRED
 *    ▲                     │
 *    │ withdraw, edit      │  reject (with a reason)
 *    ├─────────────────────┤
 *    │ revise              ▼
 *    └──────────────── REJECTED
 * </pre>
 *
 * <p>{@code ACTIVE} is not "in force": activation decides which versions engines load, effective
 * dates still decide per request which loaded version applies.
 */
public enum LifecycleState {
    /** A working copy: not a version yet, and no engine with {@code requireApproved} loads it. */
    DRAFT,
    /** Frozen for review at one content hash. An edit returns it to {@code DRAFT} and voids the approvals given so far. */
    SUBMITTED,
    /** A checker refused it, with a reason. Final for that submission; the author revises it as a new draft. */
    REJECTED,
    /** The approval policy is met and a signed approval statement exists. Not yet part of a release. */
    APPROVED,
    /** Part of the current release of a target: engines load it. */
    ACTIVE,
    /** No longer in force on any date from today on. Still kept and still listed in releases, for replays. */
    RETIRED;

    /** The states reachable from this one in a single step. */
    public Set<LifecycleState> next() {
        return switch (this) {
            case DRAFT -> EnumSet.of(SUBMITTED);
            case SUBMITTED -> EnumSet.of(APPROVED, REJECTED, DRAFT);
            case REJECTED -> EnumSet.of(DRAFT);
            case APPROVED -> EnumSet.of(ACTIVE);
            case ACTIVE -> EnumSet.of(RETIRED);
            case RETIRED -> EnumSet.noneOf(LifecycleState.class);
        };
    }

    public boolean canMoveTo(LifecycleState state) {
        return next().contains(state);
    }

    /**
     * @throws GovernanceException with {@link GovernanceException.Code#ILLEGAL_STATE} if {@code state} is not reachable
     */
    public void requireMoveTo(LifecycleState state) {
        if (!canMoveTo(state)) {
            throw new GovernanceException(GovernanceException.Code.ILLEGAL_STATE,
                    "cannot move from " + this + " to " + state);
        }
    }
}
