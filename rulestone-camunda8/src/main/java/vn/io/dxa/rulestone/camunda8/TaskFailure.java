//
// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.rulestone.camunda8;

/**
 * An expected failure of a RuleStone task, with how to report it. Messages never contain variable
 * values. No stack trace is recorded: the message says everything an operator needs.
 */
final class TaskFailure extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** How the failure is reported to Zeebe. */
    enum Kind {
        /** Fail the job without retries: an incident a person must resolve. */
        INCIDENT,
        /** Throw the task's BPMN error, which a boundary event can catch. */
        BPMN_ERROR
    }

    private final Kind kind;

    private TaskFailure(Kind kind, String message) {
        super(message, null, false, false);
        this.kind = kind;
    }

    static TaskFailure incident(String message) {
        return new TaskFailure(Kind.INCIDENT, message);
    }

    static TaskFailure bpmnError(String message) {
        return new TaskFailure(Kind.BPMN_ERROR, message);
    }

    Kind kind() {
        return kind;
    }
}
