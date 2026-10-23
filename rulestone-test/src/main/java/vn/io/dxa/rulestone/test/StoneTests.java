//
// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.rulestone.test;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import vn.io.dxa.rulestone.api.RuleStoneEngine;
import vn.io.dxa.rulestone.core.DecisionTable;

/**
 * Runs {@code .stone-test.json} files as JUnit 5 dynamic tests, one per case.
 *
 * <pre>{@code
 * @TestFactory
 * Stream<DynamicTest> decisionTables() {
 *     return StoneTests.dynamicTests(engine, Path.of("src/test/rules"));
 * }
 * }</pre>
 *
 * <p>Each test is named {@code <file>: <case name>}; the file is given relative to the scanned
 * directory. A file that cannot be read becomes one failing test that lists its problems, so
 * the other files still run.
 *
 * <p>This class needs {@code org.junit.jupiter:junit-jupiter-api} on the classpath; the rest of
 * this package works with any test framework.
 */
public final class StoneTests {

    private StoneTests() {}

    /**
     * One dynamic test per case in the test files at {@code path}.
     *
     * @param engine the engine to evaluate the cases with
     * @param path   a test file, or a directory searched recursively for {@code *.stone-test.json} files
     * @return the tests, ordered by file path and then by position in the file
     * @throws IllegalArgumentException if {@code path} does not exist or contains no test file
     */
    public static Stream<DynamicTest> dynamicTests(RuleStoneEngine engine, Path path) {
        return tests(engine, path, null, null);
    }

    /**
     * Like {@link #dynamicTests(RuleStoneEngine, Path)}, but reads facts and expected outputs with
     * the types that {@code tables} declare, so that test files can use money, dates and decimals
     * written as strings (see {@link StoneTestJson}).
     *
     * @param engine the engine to evaluate the cases with
     * @param path   a test file, or a directory searched recursively for {@code *.stone-test.json} files
     * @param tables the decision tables under test, usually the ones the engine was built from
     * @return the tests, ordered by file path and then by position in the file
     * @throws IllegalArgumentException if {@code path} does not exist or contains no test file
     */
    public static Stream<DynamicTest> dynamicTests(RuleStoneEngine engine, Path path,
                                                   Collection<DecisionTable> tables) {
        return tests(engine, path, List.copyOf(tables), null);
    }

    /**
     * Like {@link #dynamicTests(RuleStoneEngine, Path, Collection)} with {@code coverage}'s tables,
     * followed by a test named
     * {@code rule coverage} that fails unless every rule of {@code coverage}'s tables is matched by
     * at least one passing case. Cases that expect an error or fail their expectations cover
     * nothing.
     *
     * <p>The coverage test evaluates every case itself, so its verdict does not depend on the order
     * in which tests run, nor on running the other tests at all.
     *
     * @param engine   the engine to evaluate the cases with
     * @param path     a test file, or a directory searched recursively for {@code *.stone-test.json} files
     * @param coverage the tables whose rules must all be covered; results are recorded into it
     * @return the case tests followed by the coverage test
     * @throws IllegalArgumentException if {@code path} does not exist or contains no test file
     */
    public static Stream<DynamicTest> dynamicTests(RuleStoneEngine engine, Path path, RuleCoverage coverage) {
        return tests(engine, path, Objects.requireNonNull(coverage, "coverage").tables(), coverage);
    }

    private static Stream<DynamicTest> tests(RuleStoneEngine engine, Path path, List<DecisionTable> tables,
                                             RuleCoverage coverage) {
        Objects.requireNonNull(engine, "engine");
        List<Path> files = StoneTestJson.find(path);
        if (files.isEmpty()) {
            throw new IllegalArgumentException("no *" + StoneTestJson.FILE_SUFFIX + " files in "
                    + path.toAbsolutePath().normalize());
        }
        List<DynamicTest> tests = new ArrayList<>();
        List<StoneTestCase> cases = new ArrayList<>();
        for (Path file : files) {
            String name = displayName(path, file);
            URI source = file.toUri();
            try {
                for (StoneTestCase c : StoneTestJson.parse(StoneTestJson.readString(file), name, tables)) {
                    cases.add(c);
                    tests.add(DynamicTest.dynamicTest(name + ": " + c.name(), source, () -> c.check(engine)));
                }
            } catch (InvalidStoneTestException e) {
                // One failing test for the file; the other files still run.
                tests.add(DynamicTest.dynamicTest(name, source, () -> {
                    throw new AssertionError(e.getMessage(), e);
                }));
            }
        }
        if (coverage != null) {
            tests.add(DynamicTest.dynamicTest("rule coverage", path.toUri(), () -> {
                for (StoneTestCase c : cases) {
                    try {
                        c.check(engine).ifPresent(coverage::record);
                    } catch (AssertionError | RuntimeException e) {
                        // A failing case covers nothing; its own test reports why.
                    }
                }
                coverage.assertComplete();
            }));
        }
        return tests.stream();
    }

    private static String displayName(Path root, Path file) {
        if (Files.isDirectory(root)) {
            return root.relativize(file).toString().replace(file.getFileSystem().getSeparator(), "/");
        }
        Path fileName = file.getFileName();
        return fileName == null ? file.toString() : fileName.toString();
    }
}
