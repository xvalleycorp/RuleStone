//
// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.rulestone.temporal;

import io.temporal.worker.NonDeterministicException;
import java.util.Optional;

/**
 * Replaying a workflow cannot reproduce a decision recorded in its history: the rule version pinned
 * by {@link RuleStoneWorkflow} is no longer registered, its content hash changed (the rule text was
 * edited without a new version label), or the workflow code now evaluates a different decision at
 * that point.
 *
 * <p>This is a deployment error, not a business outcome, so it is a {@link NonDeterministicException}:
 * by default Temporal fails the workflow <em>task</em> and keeps retrying it, so the workflow stays
 * open, neither completed nor failed with a decision the original rules would not have made, and
 * it resumes once the original rule version is registered again. To fail the workflow execution
 * instead, add {@code NonDeterministicException.class} (or this class) to
 * {@code WorkflowImplementationOptions.setFailWorkflowExceptionTypes}. The message names the decision,
 * versions and hashes involved, never input values.
 */
public final class RuleVersionMismatchException extends NonDeterministicException {

    private static final long serialVersionUID = 1L;

    private final String decisionId;
    private final String recordedRuleVersion;
    private final String registeredRuleVersion;

    RuleVersionMismatchException(String decisionId, String recordedRuleVersion, String registeredRuleVersion,
            String message) {
        super("RuleStone replay mismatch for decision '" + decisionId + "': " + message);
        this.decisionId = decisionId;
        this.recordedRuleVersion = recordedRuleVersion;
        this.registeredRuleVersion = registeredRuleVersion;
    }

    /**
     * The decision whose replay failed.
     *
     * @return the decision id
     */
    public String decisionId() {
        return decisionId;
    }

    /**
     * The rule version recorded in workflow history, {@code <version>@sha256:<hash>}, if the history
     * recorded one.
     *
     * @return the recorded version
     */
    public Optional<String> recordedRuleVersion() {
        return Optional.ofNullable(recordedRuleVersion);
    }

    /**
     * The rule version the engine has now under the recorded label, if it has one.
     *
     * @return the registered version
     */
    public Optional<String> registeredRuleVersion() {
        return Optional.ofNullable(registeredRuleVersion);
    }
}
