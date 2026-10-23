//
// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.rulestone.test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import vn.io.dxa.rulestone.api.RuleStoneEngine;
import vn.io.dxa.rulestone.core.DecisionTable;
import vn.io.dxa.rulestone.core.DecisionTableJson;
import vn.io.dxa.rulestone.core.RuleStone;

/** Runs every {@code examples/*.stone-test.json} file and requires every rule of every example table to be covered. */
class ExamplesStoneTest {

    static final Path EXAMPLES = Path.of("..", "examples");

    /** Every {@code *.stone.json} table in {@code examples/}. */
    static List<DecisionTable> exampleTables() {
        try (Stream<Path> files = Files.list(EXAMPLES)) {
            return files.filter(f -> f.getFileName().toString().endsWith(".stone.json"))
                    .sorted()
                    .map(DecisionTableJson::read)
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The example tables, with the clock fixed so that cases without {@code asOf} are reproducible. */
    static RuleStoneEngine exampleEngine() {
        return RuleStone.builder()
                .clock(Clock.fixed(Instant.parse("2026-12-01T03:00:00Z"), ZoneId.of("Asia/Ho_Chi_Minh")))
                .decisions(exampleTables())
                .build();
    }

    @TestFactory
    Stream<DynamicTest> examples() {
        return StoneTests.dynamicTests(exampleEngine(), EXAMPLES, RuleCoverage.of(exampleTables()));
    }
}
