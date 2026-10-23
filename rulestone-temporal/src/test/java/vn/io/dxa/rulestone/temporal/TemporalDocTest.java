//
// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.rulestone.temporal;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.protobuf.ByteString;
import io.temporal.api.common.v1.Payload;
import io.temporal.common.converter.DataConverter;
import io.temporal.common.converter.DefaultDataConverter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Checks docs/temporal.md against the code: every Java block is taken verbatim from a compiled
 * sample, the JSON payload is accepted, and quoted names and messages match. Update both together.
 */
class TemporalDocTest {

    private static final Path DOC = Path.of("..", "docs", "temporal.md");
    private static final Path SAMPLES = Path.of("src", "test", "java", "vn", "io", "dxa", "rulestone", "temporal", "samples");
    private static final Pattern BLOCK = Pattern.compile("```(\\w*)\\n(.*?)```", Pattern.DOTALL);

    private static List<String> blocks(String language) throws IOException {
        List<String> blocks = new ArrayList<>();
        Matcher m = BLOCK.matcher(Files.readString(DOC));
        while (m.find()) {
            if (m.group(1).equals(language)) {
                blocks.add(m.group(2));
            }
        }
        return blocks;
    }

    /** Collapses whitespace, so that indentation does not matter. */
    private static String normalized(String code) {
        return code.replaceAll("\\s+", " ").trim();
    }

    @Test
    void everyJavaBlockIsTakenFromACompiledSample() throws IOException {
        StringBuilder sources = new StringBuilder();
        try (Stream<Path> files = Files.list(SAMPLES)) {
            for (Path file : files.sorted().toList()) {
                sources.append(normalized(Files.readString(file))).append('\n');
            }
        }
        List<String> java = blocks("java");
        assertThat(java).hasSize(3);
        for (String block : java) {
            assertThat(sources).as("sample containing:%n%s", block).contains(normalized(block));
        }
    }

    @Test
    void theDocumentedPayloadIsAcceptedAndDecided() throws IOException {
        String json = blocks("json").stream().filter(b -> b.contains("\"decisionId\"")).findFirst().orElseThrow();
        DataConverter converter = DefaultDataConverter.newDefaultInstance();
        Payload payload = Payload.newBuilder()
                .putMetadata("encoding", ByteString.copyFromUtf8("json/plain"))
                .setData(ByteString.copyFrom(json, StandardCharsets.UTF_8))
                .build();

        DecisionRequest request = converter.fromPayload(payload, DecisionRequest.class, DecisionRequest.class);
        DecisionOutcome outcome = new RuleStoneActivitiesImpl(TestSupport.engine(TestSupport.allTables()),
                TestSupport.allTables()).evaluate(request);

        assertThat(request.asOf()).isEqualTo(LocalDate.of(2026, 11, 15));
        assertThat(outcome.text("decision")).contains("APPROVE");
        assertThat(outcome.ruleVersion()).startsWith("3@sha256:");
    }

    @Test
    void failureTypesAndSearchAttributesMatchTheCode() throws IOException {
        String doc = Files.readString(DOC);
        assertThat(doc).contains("`" + RuleStoneFailures.DECISION_NOT_FOUND + "`",
                "`" + RuleStoneFailures.DECISION_EVALUATION_FAILED + "`",
                "`" + RuleStoneFailures.INVALID_REQUEST + "`");
        String command = blocks("sh").get(0);
        assertThat(command).contains(
                "--name " + RuleStoneSearchAttributes.DECISION.getName() + " --type Keyword",
                "--name " + RuleStoneSearchAttributes.RULE_VERSION.getName() + " --type Keyword",
                "--name " + RuleStoneSearchAttributes.OUTCOME.getName() + " --type Keyword",
                "--name " + RuleStoneSearchAttributes.REASON_CODES.getName() + " --type KeywordList");
    }

    @Test
    void theQuotedMismatchMessageMatchesTheCode() throws IOException {
        // The hashes are shortened in the page; RuleStoneWorkflowTest checks the full message.
        String quoted = blocks("text").get(0);
        assertThat(quoted)
                .startsWith("RuleStone replay mismatch for decision 'loan_eligibility': workflow history recorded"
                        + " rule version 3@sha256:")
                .contains(", but version '3' now registered in the engine is 3@sha256:")
                .endsWith(". The rule content changed without a new version label. Register the original content"
                        + " of version '3' again (publish changes under a new label); the workflow task is retried"
                        + " until then.\n");
    }
}
