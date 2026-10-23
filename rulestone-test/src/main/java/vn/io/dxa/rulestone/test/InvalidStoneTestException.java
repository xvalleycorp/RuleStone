//
// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.rulestone.test;

import java.util.List;
import vn.io.dxa.rulestone.api.RuleStoneException;

/**
 * A {@code .stone-test.json} file is malformed. All problems found are reported together,
 * each prefixed with its JSON path (e.g. {@code $.cases[2].expect.outptu: unknown field}).
 */
public final class InvalidStoneTestException extends RuleStoneException {

    private static final long serialVersionUID = 1L;

    private final String source;

    @SuppressWarnings("serial") // always a List.copyOf, which is serializable
    private final List<String> problems;

    /**
     * Creates the exception.
     *
     * @param source   the file the problems were found in
     * @param problems every problem found, each prefixed with its JSON path
     */
    public InvalidStoneTestException(String source, List<String> problems) {
        super(format(source, problems));
        this.source = source;
        this.problems = List.copyOf(problems);
    }

    /**
     * The file the problems were found in.
     *
     * @return the source name given when reading
     */
    public String source() {
        return source;
    }

    /**
     * Every problem found.
     *
     * @return the problems, each prefixed with its JSON path
     */
    public List<String> problems() {
        return problems;
    }

    private static String format(String source, List<String> problems) {
        StringBuilder message = new StringBuilder("invalid test file ").append(source).append(" (")
                .append(problems.size())
                .append(problems.size() == 1 ? " problem):" : " problems):");
        problems.forEach(p -> message.append("\n  - ").append(p.replace("\n", "\n    ")));
        return message.toString();
    }
}
