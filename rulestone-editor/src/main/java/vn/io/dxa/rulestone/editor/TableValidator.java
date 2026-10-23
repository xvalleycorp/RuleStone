//
// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.rulestone.editor;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import vn.io.dxa.rulestone.core.DecisionTable;
import vn.io.dxa.rulestone.core.DecisionTableJson;
import vn.io.dxa.rulestone.core.InvalidDecisionException;
import vn.io.dxa.rulestone.core.RuleStone;

/**
 * Checks a table exactly as the engine would, then against the other versions of the same decision
 * in the rules directory (duplicate versions, overlapping effective periods).
 */
final class TableValidator {

    private final RulesDirectory directory;

    TableValidator(RulesDirectory directory) {
        this.directory = directory;
    }

    /**
     * The outcome of checking one table.
     *
     * @param tree        the table as JSON, or {@code null} if the text is not JSON
     * @param table       the parsed table, or {@code null} if it has structural problems
     * @param problems    every problem found
     * @param ruleVersion {@code <version>@sha256:<hash>} as the engine reports it, when there are no problems
     *                    in the table itself (version conflicts with other files do not change it)
     */
    record Result(JsonNode tree, DecisionTable table, List<Problem> problems, String ruleVersion) {
        boolean valid() {
            return problems.isEmpty();
        }
    }

    /**
     * Validates table text.
     *
     * @param selfFile  the file this text is (or will be) saved as, excluded from the version checks; may be {@code null}
     * @param overrides tables to use instead of what is on disk for some files, for the version checks
     */
    Result validate(String text, String selfFile, Map<String, DecisionTable> overrides) {
        JsonNode tree;
        try {
            tree = Json.MAPPER.readTree(text);
        } catch (JsonProcessingException e) {
            tree = null;
        }
        DecisionTable table;
        List<String> raw = new ArrayList<>();
        try {
            table = DecisionTableJson.read(text);
        } catch (InvalidDecisionException e) {
            table = null;
            raw.addAll(e.problems());
        }
        if (table != null) {
            raw.addAll(compileProblems(table));
        }
        List<Problem> problems = new ArrayList<>(new ProblemParser(tree).parseAll(raw));
        String ruleVersion = table != null && problems.isEmpty() ? ruleVersion(table) : null;
        if (table != null) {
            problems.addAll(versionProblems(table, selfFile, overrides));
        }
        return new Result(tree, table, List.copyOf(problems), ruleVersion);
    }

    /** Problems the engine reports when building with this table alone. */
    static List<String> compileProblems(DecisionTable table) {
        try {
            RuleStone.builder().decision(table).build();
            return List.of();
        } catch (InvalidDecisionException e) {
            return e.problems();
        }
    }

    /** Duplicate versions and overlapping effective periods between {@code table} and the files on disk. */
    List<Problem> versionProblems(DecisionTable table, String selfFile, Map<String, DecisionTable> overrides) {
        List<Problem> problems = new ArrayList<>();
        others(selfFile, overrides).forEach((file, other) -> {
            if (!other.id().equals(table.id())) {
                return;
            }
            if (other.version().equals(table.version())) {
                problems.add(versionProblem("version", "version " + table.version() + " already exists in " + file, file));
            } else if (overlap(table, other)) {
                boolean otherIsEarlier = start(other).compareTo(start(table)) <= 0;
                problems.add(versionProblem(otherIsEarlier ? "effectiveFrom" : "effectiveTo",
                        "effective period overlaps version " + other.version() + " in " + file
                                + " (" + period(other) + "); " + (otherIsEarlier
                                ? "set effectiveTo of version " + other.version() + " or move effectiveFrom"
                                : "set effectiveTo of this version"),
                        file));
            }
        });
        return problems;
    }

    /** Every parsable table in the directory, by file, with {@code overrides} applied and {@code selfFile} left out. */
    Map<String, DecisionTable> others(String selfFile, Map<String, DecisionTable> overrides) {
        Map<String, DecisionTable> tables = new LinkedHashMap<>();
        for (String file : directory.list()) {
            if (file.equals(selfFile)) {
                continue;
            }
            DecisionTable override = overrides.get(file);
            if (override != null) {
                tables.put(file, override);
                continue;
            }
            readQuietly(file).ifPresent(t -> tables.put(file, t));
        }
        return tables;
    }

    Optional<DecisionTable> readQuietly(String file) {
        try {
            return Optional.of(DecisionTableJson.read(directory.read(file)));
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    static boolean overlap(DecisionTable a, DecisionTable b) {
        return start(a).isBefore(end(b)) && start(b).isBefore(end(a));
    }

    static String ruleVersion(DecisionTable table) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(DecisionTableJson.write(table).getBytes(StandardCharsets.UTF_8));
            return table.version() + "@sha256:" + HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Problem versionProblem(String field, String message, String file) {
        Problem p = Problem.table(field, message);
        return new Problem(p.message(), p.detail(), p.location().withFile(file));
    }

    private static LocalDate start(DecisionTable t) {
        return t.effectiveFrom().orElse(LocalDate.MIN);
    }

    private static LocalDate end(DecisionTable t) {
        return t.effectiveTo().orElse(LocalDate.MAX);
    }

    private static String period(DecisionTable t) {
        return t.effectiveFrom().map(LocalDate::toString).orElse("no start")
                + " to " + t.effectiveTo().map(LocalDate::toString).orElse("no end");
    }
}
