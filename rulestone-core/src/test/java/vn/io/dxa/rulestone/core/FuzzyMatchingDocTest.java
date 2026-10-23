//
// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.rulestone.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import vn.io.dxa.rulestone.api.DecisionRef;
import vn.io.dxa.rulestone.api.DecisionResult;
import vn.io.dxa.rulestone.api.Facts;
import vn.io.dxa.rulestone.api.RuleStoneEngine;

/** Runs the screening table shown in docs/fuzzy-matching.md. Update both together. */
class FuzzyMatchingDocTest {

    private static final List<String> WATCHLIST = List.of("Van An Nguyen", "Tran Van Binh");

    private static RuleStoneEngine engine() throws IOException {
        String doc = Files.readString(Path.of("../docs/fuzzy-matching.md"), StandardCharsets.UTF_8);
        int start = doc.indexOf("```json\n") + "```json\n".length();
        String json = doc.substring(start, doc.indexOf("```", start));
        return RuleStone.builder().decision(DecisionTableJson.read(json)).build();
    }

    private static DecisionResult screen(RuleStoneEngine engine, Map<String, ?> customer) {
        return engine.evaluate(DecisionRef.of("name_screening"), Facts.of("customer", customer, "watchlist", WATCHLIST));
    }

    @Test
    void flagsCloseNamesAndReturnsTheScore() throws IOException {
        DecisionResult result = screen(engine(), Map.of("fullName", "NGUYỄN Văn An"));

        assertThat(result.matchedRules()).containsExactly("WATCHLIST_HIT");
        assertThat(result.output()).containsEntry("decision", "REVIEW")
                .containsEntry("matchScore", new BigDecimal("1.0000"));
        assertThat(result.reasonCodes()).containsExactly("WATCHLIST_NAME_MATCH");
    }

    @Test
    void doesNotFlagUnrelatedNames() throws IOException {
        DecisionResult result = screen(engine(), Map.of("fullName", "Le Thi Hoa"));

        assertThat(result.matchedRules()).containsExactly("NO_ALERT");
        assertThat(result.output()).containsEntry("decision", "NO_ALERT");
    }

    @Test
    void sendsMissingOrBlankNamesToReview() throws IOException {
        RuleStoneEngine engine = engine();

        assertThat(screen(engine, Map.of()).reasonCodes()).containsExactly("NAME_MISSING");
        assertThat(screen(engine, Map.of("fullName", " - ")).reasonCodes()).containsExactly("NAME_MISSING");
    }
}
