//
// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.rulestone.core;

import java.util.List;
import vn.io.dxa.rulestone.api.RuleStoneException;

/**
 * JSON facts or values do not match the types a decision declares, as reported by {@link FactsJson}.
 *
 * <p>All problems are reported together, each prefixed with its location, e.g.
 * {@code applicant.income: expected money as "<amount> <CCY>", e.g. "1500000 VND"}. Messages
 * name inputs, fields and types, never the values, so they can be logged without exposing
 * customer data.
 */
public final class InvalidFactsException extends RuleStoneException {

    private static final long serialVersionUID = 1L;

    @SuppressWarnings("serial") // always a List.copyOf, which is serializable
    private final List<String> problems;

    /**
     * Creates an exception listing {@code problems}.
     *
     * @param problems one entry per problem, each starting with its location; must not be empty
     * @throws IllegalArgumentException if {@code problems} is empty
     */
    public InvalidFactsException(List<String> problems) {
        super(format(problems));
        this.problems = List.copyOf(problems);
    }

    /**
     * The problems, in document order.
     *
     * @return one entry per problem, e.g. {@code applicant.birthDate: expected a date as "yyyy-MM-dd"}
     */
    public List<String> problems() {
        return problems;
    }

    private static String format(List<String> problems) {
        if (problems.isEmpty()) {
            throw new IllegalArgumentException("problems must not be empty");
        }
        StringBuilder message = new StringBuilder("invalid JSON values (")
                .append(problems.size())
                .append(problems.size() == 1 ? " problem):" : " problems):");
        problems.forEach(p -> message.append("\n  - ").append(p));
        return message.toString();
    }
}
