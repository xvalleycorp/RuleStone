//
// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.rulestone.core;

import java.util.List;
import vn.io.dxa.rulestone.api.RuleStoneException;

/**
 * JSON text is not a decision record as {@link DecisionRecordJson#write} writes it, as reported by
 * {@link DecisionRecordJson#read}.
 *
 * <p>All problems are reported together, each prefixed with its location, e.g.
 * {@code $.path[2].status: expected one of OK, TIMEOUT, ERROR, …}. Messages name fields and the
 * expected forms, never the values found.
 */
public final class InvalidRecordException extends RuleStoneException {

    private static final long serialVersionUID = 1L;

    @SuppressWarnings("serial") // always a List.copyOf, which is serializable
    private final List<String> problems;

    /**
     * @param problems one entry per problem, each starting with its location; must not be empty
     * @throws IllegalArgumentException if {@code problems} is empty
     */
    public InvalidRecordException(List<String> problems) {
        super(format(problems));
        this.problems = List.copyOf(problems);
    }

    /** The problems, in document order. */
    public List<String> problems() {
        return problems;
    }

    private static String format(List<String> problems) {
        if (problems.isEmpty()) {
            throw new IllegalArgumentException("problems must not be empty");
        }
        StringBuilder message = new StringBuilder("invalid decision record (")
                .append(problems.size())
                .append(problems.size() == 1 ? " problem):" : " problems):");
        problems.forEach(p -> message.append("\n  - ").append(p));
        return message.toString();
    }
}
