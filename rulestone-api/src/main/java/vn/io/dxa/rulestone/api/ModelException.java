//
// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.rulestone.api;

/**
 * Thrown by a {@link ModelInvoker} when the model cannot answer, for example because the model
 * server is unavailable. The engine never passes it on: it is a model failure of kind
 * {@link ModelStatus#ERROR}, handled on the node's {@code onFailure} path, and the decision record
 * names only its type. Its message must still not contain feature values, since invokers may log it.
 */
public class ModelException extends RuleStoneException {

    private static final long serialVersionUID = 1L;

    public ModelException(String message) {
        super(message);
    }

    public ModelException(String message, Throwable cause) {
        super(message, cause);
    }
}
