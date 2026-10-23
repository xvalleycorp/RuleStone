//
// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.rulestone.camunda7;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.camunda.bpm.engine.ProcessEngine;
import org.camunda.bpm.engine.ProcessEngineConfiguration;
import org.camunda.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.camunda.bpm.engine.runtime.ProcessInstance;
import org.junit.jupiter.api.Test;
import vn.io.dxa.rulestone.camunda7.samples.LoanRulesEngine;

/**
 * Checks the Camunda 7 section of docs/camunda.md against the code: Java blocks are taken verbatim
 * from a compiled sample, BPMN blocks from the sample models, and the sample setup runs. Update both
 * together.
 */
class CamundaDocTest {

    private static final Path DOC = Path.of("..", "docs", "camunda.md");
    private static final Path SAMPLES = Path.of("src", "test", "java", "vn", "io", "dxa", "rulestone", "camunda7", "samples");
    private static final Path MODELS = Path.of("src", "test", "resources");
    private static final Pattern BLOCK = Pattern.compile("```(\\w*)\\n(.*?)```", Pattern.DOTALL);

    private static String section() throws IOException {
        String doc = Files.readString(DOC);
        int start = doc.indexOf("\n## Camunda 7\n");
        int end = doc.indexOf("\n## Decimal precision\n");
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

    private static String concatenated(Path directory, String suffix) throws IOException {
        StringBuilder text = new StringBuilder();
        try (Stream<Path> files = Files.list(directory)) {
            for (Path file : files.filter(f -> f.toString().endsWith(suffix)).sorted().toList()) {
                text.append(normalized(Files.readString(file))).append('\n');
            }
        }
        return text.toString();
    }

    @Test
    void everyJavaBlockIsTakenFromACompiledSample() throws IOException {
        String sources = concatenated(SAMPLES, ".java");
        List<String> java = blocks("java");
        assertThat(java).hasSize(1);
        for (String block : java) {
            assertThat(sources).as("sample containing:%n%s", block).contains(normalized(block));
        }
    }

    @Test
    void everyBpmnBlockIsTakenFromASampleModel() throws IOException {
        String models = concatenated(MODELS, ".bpmn");
        List<String> bpmn = blocks("xml").stream().filter(b -> b.contains("<bpmn:")).toList();
        assertThat(bpmn).hasSize(4);
        for (String block : bpmn) {
            assertThat(models).as("a sample model containing:%n%s", block).contains(normalized(block));
        }
    }

    @Test
    void quotedNamesAndMessagesMatchTheCode() throws IOException {
        String section = section();
        for (String field : List.of("decisionId", "version", "asOfVariable", "resultVariable", "failOnNoMatch", "errorCode")) {
            assertThat(section).contains("`" + field + "`");
        }
        assertThat(section).contains("`" + RuleStoneDecisionService.DEFAULT_RESULT_VARIABLE + "`",
                "`" + RuleStoneDecisionService.DEFAULT_ERROR_CODE + "`",
                "`" + RuleStoneProcessEnginePlugin.DEFAULT_BEAN_NAME + "`",
                // LoanApprovalProcessTest and RuleStoneDecisionServiceTest check these texts.
                "`applicant.monthlyIncome: floating-point numbers are not supported; use BigDecimal`",
                "`\"30000.0100 VND\"`");
    }

    @Test
    void theSampleSetupRunsAProcess() {
        String name = "rulestone-doc-" + UUID.randomUUID();
        ProcessEngineConfigurationImpl configuration = (ProcessEngineConfigurationImpl) ProcessEngineConfiguration
                .createStandaloneInMemProcessEngineConfiguration()
                .setProcessEngineName(name)
                .setJdbcUrl("jdbc:h2:mem:" + name + ";DB_CLOSE_DELAY=-1")
                .setJobExecutorActivate(false)
                .setHistory(ProcessEngineConfiguration.HISTORY_NONE);
        ProcessEngine engine = LoanRulesEngine.build(configuration, TestSupport.tables(), TestSupport.CLOCK);
        try {
            engine.getRepositoryService().createDeployment().addClasspathResource("loan-approval.bpmn").deploy();
            ProcessInstance instance = engine.getRuntimeService().startProcessInstanceByKey("loan-approval",
                    TestSupport.loanVariables(TestSupport.applicant(35, new BigDecimal("25750000"), BigDecimal.ZERO),
                            "2026-11-05"));

            assertThat(engine.getTaskService().createTaskQuery().processInstanceId(instance.getId()).singleResult()
                    .getTaskDefinitionKey()).isEqualTo("Disburse");
        } finally {
            engine.close();
            RuleStoneRegistry.unregister(name);
        }
    }
}
