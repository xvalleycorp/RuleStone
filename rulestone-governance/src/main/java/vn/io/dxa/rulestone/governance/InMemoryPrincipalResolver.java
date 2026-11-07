//
// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.rulestone.governance;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A {@link PrincipalResolver} whose caller is set in code, for tests and for embedding the service
 * in a program that has already authenticated its user:
 *
 * <pre>{@code
 * InMemoryPrincipalResolver who = new InMemoryPrincipalResolver();
 * who.actAs(Principal.of("u:nguyen.van.a", "RULE_MAKER"));
 * service.saveDraft("CHG-1", ArtifactKind.TABLE, json);
 * }</pre>
 *
 * <p>The caller is shared by all threads; a web application resolves it per request instead.
 */
public final class InMemoryPrincipalResolver implements PrincipalResolver {

    private final AtomicReference<Principal> current = new AtomicReference<>();
    private final AtomicReference<String> via = new AtomicReference<>();

    /** Makes {@code principal} the caller of the following calls. */
    public InMemoryPrincipalResolver actAs(Principal principal) {
        current.set(Objects.requireNonNull(principal, "principal"));
        return this;
    }

    /** Names the system the following calls came from, or none if null. */
    public InMemoryPrincipalResolver via(String via) {
        this.via.set(via);
        return this;
    }

    /** Signs the caller out. */
    public void clear() {
        current.set(null);
    }

    @Override
    public Principal current() {
        Principal principal = current.get();
        if (principal == null) {
            throw new GovernanceException(GovernanceException.Code.UNAUTHENTICATED, "there is no authenticated caller");
        }
        return principal;
    }

    @Override
    public Optional<String> via() {
        return Optional.ofNullable(via.get());
    }
}
