//
// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.rulestone.api;

/**
 * An {@link AuditSink} did not accept a decision record, so the decision was not returned.
 * The sink's exception is the cause.
 */
public class AuditException extends RuleStoneException {

    private static final long serialVersionUID = 1L;

    private final String recordId;

    public AuditException(String recordId, Throwable cause) {
        super("audit sink rejected decision record " + recordId + " ("
                + cause.getClass().getName() + "); the decision was not returned", cause);
        this.recordId = recordId;
    }

    /** The id of the record that was not accepted. */
    public String recordId() {
        return recordId;
    }
}
