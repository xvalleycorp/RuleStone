//
// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.rulestone.api;

/**
 * Calls a machine-learning model for the {@code model} nodes of decision flows in {@code invoke}
 * mode. Register one per model id with {@code RuleStone.builder().modelInvoker(modelId, invoker)}.
 *
 * <p>The engine calls the invoker on the evaluating thread, with the node's declared features
 * only, and checks what it returns against the node's contract: the model id and pinned version,
 * the declared outputs and their types, the {@code valid} condition and {@code minConfidence}.
 *
 * <p>Implementations must be thread-safe and must return within {@link ModelCall#timeout()}: the
 * engine does not interrupt them, and treats a result that arrives later as a timeout. They should
 * not retry: a failed call is a model failure, which the flow handles on the node's
 * {@code onFailure} path.
 *
 * <p>Exceptions never reach the caller of {@code evaluate}. A {@link ModelException}, or any other
 * exception, is a model failure of kind {@link ModelStatus#ERROR}; the decision record names the
 * exception type only, never its message, which might contain feature values.
 */
@FunctionalInterface
public interface ModelInvoker {

    /**
     * Calls the model.
     *
     * @param call the model id and pinned version, the features and the time available
     * @return the model's result
     * @throws ModelException if the model cannot answer
     */
    ModelResult invoke(ModelCall call) throws ModelException;
}
