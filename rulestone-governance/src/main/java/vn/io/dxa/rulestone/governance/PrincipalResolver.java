//
// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.rulestone.governance;

import java.util.Optional;

/**
 * Tells the governance service who is calling. RuleStone has no users: the host provides the
 * authenticated principal of the current request from the bank's SSO (OIDC or SAML claims, Active
 * Directory or LDAP groups mapped to governance roles). Implementations must be thread-safe.
 */
public interface PrincipalResolver {

    /**
     * The authenticated caller of the current request.
     *
     * @throws GovernanceException with {@link GovernanceException.Code#UNAUTHENTICATED} if there is none
     */
    Principal current();

    /**
     * The system the current action came from, such as the editor, a Camunda process instance or the
     * bank's change-management ticket, logged as {@code via}.
     */
    default Optional<String> via() {
        return Optional.empty();
    }
}
