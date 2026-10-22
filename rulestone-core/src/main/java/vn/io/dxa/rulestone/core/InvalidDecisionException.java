//
// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.rulestone.core;

import java.util.List;
import vn.io.dxa.rulestone.api.RuleStoneException;

/**
 * A decision definition is malformed or ill-typed and cannot be loaded.
 *
 * <p>All problems found are reported together, each prefixed with its location
 * (e.g. {@code loan_eligibility@3 rules[R2].when: undefined field 'agee'}), so an
 * author — human or AI — can fix them in one pass.
 */
public final class InvalidDecisionException extends RuleStoneException {

    private static final long serialVersionUID = 1L;

    @SuppressWarnings("serial") // always a List.copyOf, which is serializable
    private final List<String> problems;

    public InvalidDecisionException(List<String> problems) {
        super(format(problems));
        this.problems = List.copyOf(problems);
    }

    public List<String> problems() {
        return problems;
    }

    private static String format(List<String> problems) {
        StringBuilder message = new StringBuilder("invalid decision definition (")
                .append(problems.size())
                .append(problems.size() == 1 ? " problem):" : " problems):");
        problems.forEach(p -> message.append("\n  - ").append(p.replace("\n", "\n    ")));
        return message.toString();
    }
}
