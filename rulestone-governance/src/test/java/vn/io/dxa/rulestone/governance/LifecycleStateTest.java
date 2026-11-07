//
// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.rulestone.governance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** The lifecycle of section 2.2: DRAFT, SUBMITTED, APPROVED, ACTIVE, RETIRED, plus REJECTED and withdrawal. */
class LifecycleStateTest {

    private static final Map<LifecycleState, Set<LifecycleState>> ALLOWED = Map.of(
            LifecycleState.DRAFT, EnumSet.of(LifecycleState.SUBMITTED),
            LifecycleState.SUBMITTED, EnumSet.of(LifecycleState.APPROVED, LifecycleState.REJECTED, LifecycleState.DRAFT),
            LifecycleState.REJECTED, EnumSet.of(LifecycleState.DRAFT),
            LifecycleState.APPROVED, EnumSet.of(LifecycleState.ACTIVE),
            LifecycleState.ACTIVE, EnumSet.of(LifecycleState.RETIRED),
            LifecycleState.RETIRED, EnumSet.noneOf(LifecycleState.class));

    @ParameterizedTest
    @EnumSource(LifecycleState.class)
    void everyTransitionIsAllowedOrIllegalExactlyAsTheDesignSays(LifecycleState from) {
        for (LifecycleState to : LifecycleState.values()) {
            if (ALLOWED.get(from).contains(to)) {
                assertThat(from.canMoveTo(to)).as("%s -> %s", from, to).isTrue();
                from.requireMoveTo(to);
            } else {
                assertThat(from.canMoveTo(to)).as("%s -> %s", from, to).isFalse();
                assertThatThrownBy(() -> from.requireMoveTo(to)).isInstanceOf(GovernanceException.class)
                        .hasMessage("cannot move from " + from + " to " + to)
                        .extracting("code").isEqualTo(GovernanceException.Code.ILLEGAL_STATE);
            }
        }
    }

    @Test
    void noStateSkipsTheReviewOrComesBack() {
        assertThat(LifecycleState.DRAFT.canMoveTo(LifecycleState.APPROVED)).isFalse();
        assertThat(LifecycleState.DRAFT.canMoveTo(LifecycleState.ACTIVE)).isFalse();
        assertThat(LifecycleState.APPROVED.canMoveTo(LifecycleState.DRAFT)).isFalse();
        assertThat(LifecycleState.ACTIVE.canMoveTo(LifecycleState.APPROVED)).isFalse();
        assertThat(LifecycleState.RETIRED.canMoveTo(LifecycleState.ACTIVE)).isFalse();
    }
}
