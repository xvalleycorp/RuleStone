//
// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.rulestone.governance;

import java.util.Objects;

/**
 * A governance rule refused an operation: the caller may not do it, or the change set is in a state
 * that does not allow it. The {@link #code()} says which rule, for callers that react to it; the
 * message names change sets, versions, principals and roles, never rule content or input values.
 */
public final class GovernanceException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** Why an operation was refused. */
    public enum Code {
        /** There is no authenticated caller. */
        UNAUTHENTICATED,
        /** The change set, artifact or release does not exist. */
        NOT_FOUND,
        /** The change set already exists. */
        ALREADY_EXISTS,
        /** The lifecycle does not allow this operation in the current state. */
        ILLEGAL_STATE,
        /** The caller does not hold a role that the operation needs, or is an AI principal or an administrator where that is not allowed. */
        FORBIDDEN,
        /** Four-eyes: the caller is a maker of the change set. */
        FOUR_EYES,
        /** The caller has already approved this change set. */
        DUPLICATE_APPROVAL,
        /** The role the caller approves in is not one the change set's tiers require. */
        ROLE_NOT_REQUIRED,
        /** The tier needs approvers from different units, and the caller's unit is missing or already represented. */
        UNIT_NOT_DISTINCT,
        /** The version label is bound to other content by an approval. */
        LABEL_BOUND,
        /** The content is not a valid table, flow, parameter set or catalog. */
        INVALID_CONTENT,
        /** The policy is invalid, or assigns no tier to an artifact. */
        INVALID_POLICY,
        /** The request itself is wrong, such as a blank reason. */
        INVALID_REQUEST,
        /** The store changed while the operation ran, or refused an update that would break its invariants. */
        CONFLICT,
        /** The signer could not sign. */
        SIGNING_FAILED
    }

    private final Code code;

    public GovernanceException(Code code, String message) {
        super(message);
        this.code = Objects.requireNonNull(code, "code");
    }

    public GovernanceException(Code code, String message, Throwable cause) {
        super(message, cause);
        this.code = Objects.requireNonNull(code, "code");
    }

    public Code code() {
        return code;
    }
}
