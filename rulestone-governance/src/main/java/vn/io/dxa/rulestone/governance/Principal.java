//
// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.rulestone.governance;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Who calls the governance service, as the bank's identity provider says: a stable id stored as
 * given, such as {@code u:tran.thi.b}, and the roles and organisational unit the bank's systems
 * grant. RuleStone stores no passwords and manages no users.
 *
 * <p>An AI authoring assistant is a principal whose id starts with {@code ai:}. It can be a maker,
 * never a checker. Content it drafted at somebody's request has two makers: the assistant and the
 * person named in {@code onBehalfOf}.
 *
 * @param id         the principal id, without white space
 * @param name       a display name, if the identity provider gives one
 * @param roles      the governance roles the caller holds, such as {@code RULE_CHECKER}
 * @param unit       the organisational unit, for tiers that need approvers from different units
 * @param onBehalfOf for an AI principal, the person who asked for the work
 */
public record Principal(String id, Optional<String> name, Set<String> roles, Optional<String> unit,
        Optional<String> onBehalfOf) {

    /** The prefix of the id of an AI principal. */
    public static final String AI_PREFIX = "ai:";

    public Principal {
        Objects.requireNonNull(id, "id");
        if (id.isBlank() || id.chars().anyMatch(Character::isWhitespace)) {
            throw new IllegalArgumentException("a principal id is not empty and has no white space");
        }
        Objects.requireNonNull(name, "name");
        roles = Set.copyOf(roles);
        Objects.requireNonNull(unit, "unit");
        Objects.requireNonNull(onBehalfOf, "onBehalfOf");
    }

    /** A principal with these roles, no unit and no display name. */
    public static Principal of(String id, String... roles) {
        return new Principal(id, Optional.empty(), Set.of(roles), Optional.empty(), Optional.empty());
    }

    public Principal withUnit(String unit) {
        return new Principal(id, name, roles, Optional.of(unit), onBehalfOf);
    }

    public Principal withName(String name) {
        return new Principal(id, Optional.of(name), roles, unit, onBehalfOf);
    }

    /** The same principal acting for {@code person}, who becomes a maker of what it saves. */
    public Principal actingFor(String person) {
        return new Principal(id, name, roles, unit, Optional.of(person));
    }

    public boolean hasRole(String role) {
        return roles.contains(role);
    }

    /** Whether this is an AI principal, which can make changes but never check them. */
    public boolean isAi() {
        return id.startsWith(AI_PREFIX);
    }
}
