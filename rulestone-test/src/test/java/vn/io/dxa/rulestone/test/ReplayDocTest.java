//
// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.rulestone.test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import vn.io.dxa.rulestone.api.DecisionComparison;
import vn.io.dxa.rulestone.api.DecisionRecord;
import vn.io.dxa.rulestone.api.DecisionRef;
import vn.io.dxa.rulestone.api.DecisionResult;
import vn.io.dxa.rulestone.api.Facts;
import vn.io.dxa.rulestone.api.ModelSource;
import vn.io.dxa.rulestone.api.RuleStoneEngine;
import vn.io.dxa.rulestone.core.DecisionRecordJson;
import vn.io.dxa.rulestone.core.DecisionTableJson;
import vn.io.dxa.rulestone.core.ParameterSetJson;
import vn.io.dxa.rulestone.core.RuleStone;

/** Runs the example of docs/replay.md, and checks the differences it shows in both languages. */
class ReplayDocTest {

    private static final Path EXAMPLES = Path.of("..", "examples");
    private static final Pattern BLOCK = Pattern.compile("```(\\w*)\\n(.*?)```", Pattern.DOTALL);
    private static final byte[] FACTS_HASH_KEY = "replay-doc-test-key-0123456789ab".getBytes();

    private final List<String> auditLog = new ArrayList<>();

    private RuleStoneEngine engine() {
        return RuleStone.builder()
                .decision(DecisionTableJson.read(EXAMPLES.resolve("loan_eligibility.v4.stone.json")))
                .paramSet(ParameterSetJson.read(EXAMPLES.resolve("retail_credit.v1.params.json")))
                .paramSet(ParameterSetJson.read(EXAMPLES.resolve("retail_credit.v2.params.json")))
                .factsHashKey(FACTS_HASH_KEY)
                .auditSink(record -> auditLog.add(DecisionRecordJson.write(record)))
                .build();
    }

    private static Facts facts() {
        return Facts.of(
                "applicant", Map.of("age", 35, "monthlyIncome", new BigDecimal("25000000"),
                        "monthlyDebt", new BigDecimal("12000000"), "segment", "MASS", "blacklisted", false),
                "amount", new BigDecimal("200000000"));
    }

    @Test
    void storeReplayAndReScore(@TempDir Path golden) throws IOException {
        RuleStoneEngine engine = engine();
        Facts facts = facts();

        // As in the page.
        DecisionResult result = engine.evaluate(
                DecisionRef.of("loan_eligibility").asOf(LocalDate.of(2027, 6, 30)).correlationId("LOAN-2027-000042"),
                facts);
        assertThat(result.output()).containsEntry("decision", "APPROVE");
        String stored = auditLog.get(0);

        DecisionRecord record = DecisionRecordJson.read(stored);
        DecisionComparison replay = engine.replay(record, facts);
        assertThat(replay.matches()).isTrue();

        DecisionComparison rescore = engine.rescore(record, facts,
                DecisionRef.of("loan_eligibility").asOf(record.businessDate()).params("retail_credit", "2"),
                ModelSource.RECORDED);
        assertThat(rescore.matches()).isFalse();
        assertThat(rescore.evaluated().output()).containsEntry("decision", "REJECT");
        assertThat(auditLog).hasSize(1);

        List<String> shown = rescore.differences().stream()
                .map(d -> d.toString().replaceAll("sha256:[0-9a-f]{64}", "sha256:…")).toList();
        assertThat(textBlock(Path.of("..", "docs", "replay.md"))).isEqualTo(shown);
        assertThat(textBlock(Path.of("..", "docs", "vi", "replay.md"))).isEqualTo(shown);

        // Golden records with ReplayCheck.
        Path file = golden.resolve("loan-2027-000042.record.json");
        Files.writeString(file, stored);
        assertThat(ReplayCheck.assertReplays(engine, file, facts).matches()).isTrue();
        Facts other = Facts.of("applicant", Map.of("age", 35, "monthlyIncome", new BigDecimal("25000000"),
                "monthlyDebt", new BigDecimal("12000001"), "segment", "MASS", "blacklisted", false),
                "amount", new BigDecimal("200000000"));
        assertThatThrownBy(() -> ReplayCheck.assertReplays(engine, record, other))
                .isInstanceOf(AssertionError.class)
                .hasMessage("decision record " + record.recordId() + " of loan_eligibility does not replay exactly:\n"
                        + "  - FACTS: factsHash: the facts are not those the record was made on, or the factsHashKey"
                        + " is not the one it was made with");
    }

    /** The lines of the {@code text} block that lists the differences of the re-score. */
    private static List<String> textBlock(Path doc) throws IOException {
        Matcher m = BLOCK.matcher(Files.readString(doc));
        while (m.find()) {
            if (m.group(1).equals("text") && m.group(2).startsWith("PARAMS:")) {
                return m.group(2).lines().toList();
            }
        }
        throw new AssertionError(doc + " has no text block of differences");
    }
}
