//
// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.rulestone.camunda7;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.camunda.bpm.engine.ProcessEngine;
import org.camunda.bpm.engine.ProcessEngineConfiguration;
import org.camunda.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import vn.io.dxa.rulestone.core.DecisionTable;
import vn.io.dxa.rulestone.core.DecisionTableJson;
import vn.io.dxa.rulestone.core.RuleStone;

/** Rules and an in-memory Camunda 7 engine shared by the tests. */
final class TestSupport {

    static final ZoneId HCM = ZoneId.of("Asia/Ho_Chi_Minh");

    /** 5 November 2026 in Ho Chi Minh City: version 3 of loan_eligibility is in force. */
    static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-05T03:00:00Z"), HCM);

    /** Values that must never appear in a message. */
    static final String SECRET_INCOME = "25750123";
    static final String SECRET_DEBT = "98765432.17";

    static final String CARD_FEE = """
            {
              "id": "card_fee",
              "version": "1",
              "hitPolicy": "FIRST",
              "inputs": { "customer": { "birthDate": "date" }, "spend": "money", "rate": "decimal" },
              "outputs": { "fee": "money", "nextReview": "date", "tier": "string" },
              "rules": [
                { "id": "STANDARD", "when": "true",
                  "then": { "fee": "spend * rate", "nextReview": "businessDate.plusMonths(6)",
                            "tier": "'STANDARD'" } }
              ]
            }
            """;

    /** A table without a catch-all rule, so that some inputs match nothing. */
    static final String RISK_BAND = """
            {
              "id": "risk_band",
              "version": "1",
              "hitPolicy": "FIRST",
              "inputs": { "score": "int" },
              "outputs": { "band": "string" },
              "rules": [ { "id": "HIGH", "when": "score < 500", "then": { "band": "'HIGH'" } } ]
            }
            """;

    private TestSupport() {
    }

    static List<DecisionTable> tables() {
        return List.of(
                DecisionTableJson.read(Path.of("..", "examples", "loan_eligibility.v2.stone.json")),
                DecisionTableJson.read(Path.of("..", "examples", "loan_eligibility.v3.stone.json")),
                DecisionTableJson.read(CARD_FEE),
                DecisionTableJson.read(RISK_BAND));
    }

    static RuleStoneDecisionService service() {
        List<DecisionTable> tables = tables();
        return new RuleStoneDecisionService(RuleStone.builder().clock(CLOCK).decisions(tables).build(), tables, CLOCK);
    }

    /** An in-memory engine on H2 with the RuleStone plugin, without a job executor. */
    static ProcessEngine processEngine(String name) {
        ProcessEngineConfigurationImpl configuration = (ProcessEngineConfigurationImpl) ProcessEngineConfiguration
                .createStandaloneInMemProcessEngineConfiguration()
                .setProcessEngineName(name)
                .setJdbcUrl("jdbc:h2:mem:" + name + "-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1")
                .setDatabaseSchemaUpdate(ProcessEngineConfiguration.DB_SCHEMA_UPDATE_CREATE_DROP)
                .setJobExecutorActivate(false)
                .setHistory(ProcessEngineConfiguration.HISTORY_NONE);
        configuration.getProcessEnginePlugins().add(new RuleStoneProcessEnginePlugin(service()));
        return configuration.buildProcessEngine();
    }

    static Map<String, Object> applicant(int age, Object income, Object debt) {
        Map<String, Object> applicant = new LinkedHashMap<>();
        applicant.put("age", age);
        applicant.put("monthlyIncome", income);
        applicant.put("monthlyDebt", debt);
        applicant.put("segment", "MASS");
        applicant.put("blacklisted", false);
        return applicant;
    }

    static Map<String, Object> loanVariables(Map<String, Object> applicant, String applicationDate) {
        Map<String, Object> variables = new LinkedHashMap<>();
        variables.put("applicant", applicant);
        variables.put("amount", new BigDecimal("300000000"));
        variables.put("applicationDate", applicationDate);
        variables.put("customerName", "Nguyen Van A");
        variables.put("riskScore", 0.42d);
        return variables;
    }
}
