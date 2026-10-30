//
// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.rulestone.test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import vn.io.dxa.rulestone.api.RuleStoneEngine;
import vn.io.dxa.rulestone.core.DecisionFlow;
import vn.io.dxa.rulestone.core.DecisionFlowJson;
import vn.io.dxa.rulestone.core.DecisionTable;
import vn.io.dxa.rulestone.core.DecisionTableJson;
import vn.io.dxa.rulestone.core.RuleStone;

/**
 * Runs every {@code .stone-test.json} file under {@code examples/} and requires every rule of every
 * example table, and every node and branch of every example flow, to be covered.
 */
class ExamplesStoneTest {

    static final Path EXAMPLES = Path.of("..", "examples");
    /** The decision flow example and the tables it uses; the browser engine, which runs no flows, skips them. */
    static final Path FLOWS = EXAMPLES.resolve("flows");

    /** Every {@code *.stone.json} table in {@code examples/}, without the flow example's tables. */
    static List<DecisionTable> exampleTables() {
        return files(EXAMPLES, ".stone.json").stream().map(DecisionTableJson::read).toList();
    }

    /** Every example table, including those that the example flows use. */
    static List<DecisionTable> allExampleTables() {
        List<DecisionTable> tables = new ArrayList<>(exampleTables());
        files(FLOWS, ".stone.json").stream().map(DecisionTableJson::read).forEach(tables::add);
        return tables;
    }

    /** Every {@code *.flow.json} flow in {@code examples/flows/}. */
    static List<DecisionFlow> exampleFlows() {
        return files(FLOWS, DecisionFlowJson.FILE_SUFFIX).stream().map(DecisionFlowJson::read).toList();
    }

    private static List<Path> files(Path dir, String suffix) {
        try (Stream<Path> files = Files.list(dir)) {
            return files.filter(f -> f.getFileName().toString().endsWith(suffix)).sorted().toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * The example tables and flows, with the clock fixed so that cases without {@code asOf} are
     * reproducible, and every model node reading its result from the case.
     */
    static RuleStoneEngine exampleEngine() {
        return RuleStone.builder()
                .clock(Clock.fixed(Instant.parse("2026-12-01T03:00:00Z"), ZoneId.of("Asia/Ho_Chi_Minh")))
                .decisions(allExampleTables())
                .flows(exampleFlows())
                .suppliedModelsOnly()   // the model results come from the test cases
                .build();
    }

    @TestFactory
    Stream<DynamicTest> examples() {
        return StoneTests.dynamicTests(exampleEngine(), EXAMPLES, RuleCoverage.of(allExampleTables(), exampleFlows()));
    }
}
