//
// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.rulestone.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import vn.io.dxa.rulestone.api.DecisionRef;
import vn.io.dxa.rulestone.api.DecisionResult;
import vn.io.dxa.rulestone.api.Facts;
import vn.io.dxa.rulestone.api.Money;
import vn.io.dxa.rulestone.api.RuleStoneEngine;

/**
 * The content hash of a table or flow is part of its rule version, and of every decision record
 * written with it. Adding a feature must not change the hash of any existing file: the hashes
 * below were computed before model nodes were added (hybrid AI step 3), and must never change. The tables and flows added since are pinned too, and tables and flows without
 * parameters hash as they did before parameter sets existed.
 */
class ContentHashStabilityTest {

    private static final Path EXAMPLES = Path.of("..", "examples");

    /** Path below examples/ to the SHA-256 of the canonical form, computed before model nodes existed. */
    private static final Map<String, String> HASHES = new TreeMap<>(Map.ofEntries(
            Map.entry("aml_alert_action.stone.json", "fe8094ca1322d1612ce469d56bb4ba369f1c9148a69b58456ed9f86ebae9c2e8"),
            Map.entry("aml_name_screening.stone.json", "2b96a7658d419be671e03751e7862392fc921b29f1fde7adb956b6f9aec2fa52"),
            Map.entry("aml_transaction_monitoring.stone.json", "a029919563b439effb7ca5c6d1b01f7adde64e92fc856480fa45c93a276f00e6"),
            Map.entry("card_fraud_action.stone.json", "233b70918b53104159135ad1e826aabf3735e641a56e35c6dedc9d2a4fcc0c53"),
            Map.entry("card_fraud_signals.stone.json", "ec29a1c2aeade1d0e2c4ad452ed55e9ad5cfb6301fd3071fc7090ab67e01e011"),
            Map.entry("consumer_loan_conditions.stone.json", "aca0c378f39ebcd88f90b3c25d08d2226cfbedcc0d694c4b472cdc5ecd92f091"),
            Map.entry("consumer_loan_underwriting.stone.json", "c094463aa4f7bbd7722cf39015653faf64bdcad719f0133634e2081bd2a60f6a"),
            // Added with local values (let), before parameter sets existed:
            Map.entry("credit_limit_increase.stone.json", "192cf62ada772414ce5857bc657ca2a61f2587650f45a7ac0007808f32054444"),
            // Added with model nodes, before parameter sets existed:
            Map.entry("flows/card_origination_scored.flow.json", "d2a520ac6b9eb1e2499f4ecae00a06ad847abd85032a8fea73e502044dd3f376"),
            Map.entry("flows/card_pd_grade.stone.json", "d75a53905d69fe66e7dd6614ce492e6c1b5ba7ef89fb756573fe48da455271ee"),
            Map.entry("deposit_rate.stone.json", "7be00bd2fd120c8f5d5a8dfde9dab63d2a912820a77f5384b525b3ead14fed70"),
            Map.entry("flows/card_grade_existing.stone.json", "63cd7b62f4208a03bfd914f30d0ee45e4dcfcce038dccfb0e62964dc1af6fa7b"),
            Map.entry("flows/card_grade_new.stone.json", "59b18f7e770f4ff0fa64395b47eb80eebb4151e657ebb11b36703871f92225da"),
            Map.entry("flows/card_limit.stone.json", "af5200a630dbf793caa4d3d72f7a4547ee675eb5d03e5baaf74d27259408d31a"),
            Map.entry("flows/card_origination.flow.json", "986ffd80e5fedf4282e123e5c45f00da428234a6b2fbffb874571d3c056d924c"),
            Map.entry("flows/card_policy.stone.json", "54e99ef7d8be5c18c3d490cbe6dc2facd60e32bce51f7769a486af9a0854adcb"),
            Map.entry("loan_eligibility.v2.stone.json", "f38aaf48011b20e0fcefdd8518359133b3baa7421e1e4ab1c8fef37d59fef9e6"),
            Map.entry("loan_eligibility.v3.stone.json", "20086bceed39338618c0e6d98dda386bebebeae02f5876b5c43ecf9ea473377a"),
            Map.entry("transfer_fees.stone.json", "3aced83b417126b3c717d617b73b0247338a7c8a80abbeb068766c81beaedb09")));

    @Test
    void existingTablesAndFlowsHashAsBeforeModelNodes() {
        Map<String, String> actual = new TreeMap<>();
        HASHES.keySet().forEach(name -> {
            Path file = EXAMPLES.resolve(name);
            String canonical = name.endsWith(DecisionFlowJson.FILE_SUFFIX)
                    ? DecisionFlowJson.write(DecisionFlowJson.read(file))
                    : DecisionTableJson.write(DecisionTableJson.read(file));
            actual.put(name, CompiledDecision.sha256(canonical));
        });
        assertThat(actual).isEqualTo(HASHES);
    }

    @Test
    void theExampleFlowEvaluatesWithItsFormerRuleVersion() {
        RuleStoneEngine engine = RuleStone.builder().decisions(DecisionFlowTest.tables()).flow(DecisionFlowTest.flow()).build();
        DecisionResult result = engine.evaluate(DecisionRef.of("card_origination").asOf(LocalDate.of(2026, 12, 1)),
                Facts.of("applicant", Map.of("id", "C-1002", "age", 35, "income", Money.of("20000000", "VND"),
                                "existingCustomer", true),
                        "bureau", Map.of("dpd30Count12m", 0, "utilisation", new BigDecimal("0.10"))));
        assertThat(result.ruleVersion())
                .isEqualTo("1@sha256:" + HASHES.get("flows/card_origination.flow.json"));
        assertThat(result.record().path().get(0).version())
                .contains("1@sha256:" + HASHES.get("flows/card_policy.stone.json"));
    }
}
