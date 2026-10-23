//
// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.rulestone.camunda8;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

import com.fasterxml.jackson.databind.JsonNode;
import io.camunda.client.CamundaClient;
import io.camunda.client.api.worker.JobHandler;
import io.camunda.client.api.worker.JobWorkerBuilderStep1;
import io.camunda.client.api.worker.JobWorkerBuilderStep1.JobWorkerBuilderStep2;
import io.camunda.client.api.worker.JobWorkerBuilderStep1.JobWorkerBuilderStep3;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;
import org.mockito.ArgumentCaptor;
import vn.io.dxa.rulestone.camunda8.samples.LoanRulesWorker;

/**
 * Checks the Camunda 8 section of docs/camunda.md against the code: Java blocks are taken verbatim
 * from a compiled sample, BPMN blocks from the sample model, and quoted names, defaults and results
 * match. Update both together.
 */
class CamundaDocTest {

    private static final Path DOC = Path.of("..", "docs", "camunda.md");
    private static final Path SAMPLES = Path.of("src", "test", "java", "vn", "io", "dxa", "rulestone", "camunda8", "samples");
    private static final Path BPMN = Path.of("src", "test", "resources", "loan-approval.bpmn");
    private static final Pattern BLOCK = Pattern.compile("```(\\w*)\\n(.*?)```", Pattern.DOTALL);

    private static String section() throws IOException {
        String doc = Files.readString(DOC);
        int start = doc.indexOf("\n## Camunda 8\n");
        int end = doc.indexOf("\n## Camunda 7\n");
        assertThat(start).isPositive();
        assertThat(end).isGreaterThan(start);
        return doc.substring(start, end);
    }

    private static List<String> blocks(String language) throws IOException {
        List<String> blocks = new ArrayList<>();
        Matcher m = BLOCK.matcher(section());
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
        assertThat(java).hasSize(2);
        for (String block : java) {
            assertThat(sources).as("sample containing:%n%s", block).contains(normalized(block));
        }
    }

    @Test
    void everyBpmnBlockIsTakenFromTheSampleModel() throws IOException {
        String model = normalized(Files.readString(BPMN));
        List<String> bpmn = blocks("xml").stream().filter(b -> b.contains("<bpmn:")).toList();
        assertThat(bpmn).hasSize(2);
        for (String block : bpmn) {
            assertThat(model).as("loan-approval.bpmn containing:%n%s", block).contains(normalized(block));
        }
    }

    @Test
    void headersAndDefaultsMatchTheCode() throws IOException {
        String section = section();
        for (String header : List.of(RuleStoneHeaders.DECISION_ID, RuleStoneHeaders.VERSION,
                RuleStoneHeaders.AS_OF_VARIABLE, RuleStoneHeaders.RESULT_VARIABLE, RuleStoneHeaders.FAIL_ON_NO_MATCH,
                RuleStoneHeaders.ERROR_CODE)) {
            assertThat(section).contains("| `" + header + "` |");
        }
        assertThat(section).contains("`" + RuleStoneHeaders.DEFAULT_RESULT_VARIABLE + "`",
                "`" + RuleStoneHeaders.DEFAULT_ERROR_CODE + "`",
                "`" + RuleStoneWorkerOptions.DEFAULT_JOB_TYPE + "`");
        assertThat(Files.readString(DOC)).contains(JobVariables.EXACT_DIGITS + " significant digits");
    }

    @Test
    void theDocumentedResultIsWhatTheWorkerWrites() throws Exception {
        JsonNode documented = JobVariables.MAPPER.readTree(blocks("json").get(0)).get("decision");
        TestSupport.Client client = new TestSupport.Client();
        new RuleStoneJobHandler(TestSupport.engine(), TestSupport.tables(),
                RuleStoneWorkerOptions.builder().clock(TestSupport.CLOCK).build())
                .handle(client.client, TestSupport.job(Map.of(RuleStoneHeaders.DECISION_ID, "loan_eligibility"), """
                        {"applicant": {"age": 35, "monthlyIncome": "25750000", "monthlyDebt": "5000000",
                                       "segment": "MASS", "blacklisted": false},
                         "amount": "300000000"}"""));
        ArgumentCaptor<String> variables = ArgumentCaptor.forClass(String.class);
        verify(client.complete).variables(variables.capture());
        JsonNode written = JobVariables.MAPPER.readTree(variables.getValue()).get("decision");

        assertThat(written.get("output")).isEqualTo(documented.get("output"));
        assertThat(written.get("reasonCodes")).isEqualTo(documented.get("reasonCodes"));
        assertThat(written.get("matchedRules")).isEqualTo(documented.get("matchedRules"));
        assertThat(written.get("matched")).isEqualTo(documented.get("matched"));
        assertThat(written.get("ruleVersion").asText()).startsWith(documented.get("ruleVersion").asText().replace("…", ""));
    }

    @Test
    void theSampleWorkersRegister() throws IOException {
        CamundaClient client = mock(CamundaClient.class);
        JobWorkerBuilderStep1 step1 = mock(JobWorkerBuilderStep1.class);
        JobWorkerBuilderStep2 step2 = mock(JobWorkerBuilderStep2.class);
        JobWorkerBuilderStep3 step3 = mock(JobWorkerBuilderStep3.class, withSettings().defaultAnswer(Answers.RETURNS_SELF));
        when(client.newWorker()).thenReturn(step1);
        when(step1.jobType(anyString())).thenReturn(step2);
        when(step2.handler(any(JobHandler.class))).thenReturn(step3);

        LoanRulesWorker.start(client, Path.of("..", "examples"));
        LoanRulesWorker.startLoanEligibility(client, TestSupport.engine(), TestSupport.tables(), TestSupport.CLOCK);

        verify(step1).jobType(RuleStoneWorkerOptions.DEFAULT_JOB_TYPE);
        verify(step1).jobType("rulestone:loan_eligibility");
        verify(step3).fetchVariables(List.of("applicant", "amount", "applicationDate"));
    }
}
