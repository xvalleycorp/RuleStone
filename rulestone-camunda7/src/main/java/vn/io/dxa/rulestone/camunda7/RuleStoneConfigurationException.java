//
// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.rulestone.camunda7;

import org.camunda.bpm.engine.ProcessEngineException;

/**
 * A RuleStone task is misconfigured: an unknown decision or version, no version effective on the
 * business date, a missing or invalid field, or no decision service registered with the process
 * engine. Retrying does not help; someone must fix the model or the deployment.
 *
 * <p>It is not a {@code BpmnError}, so no boundary event catches it: a synchronous call fails and
 * rolls back, and an asynchronous job fails, and becomes an incident once its retries are used up.
 * Messages never contain variable values.
 */
public class RuleStoneConfigurationException extends ProcessEngineException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates the exception.
     *
     * @param message what is wrong and how to fix it
     */
    public RuleStoneConfigurationException(String message) {
        super(message);
    }
}
