//
// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.rulestone.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import vn.io.dxa.rulestone.api.DecisionEvaluationException;
import vn.io.dxa.rulestone.api.DecisionNotFoundException;
import vn.io.dxa.rulestone.api.DecisionRecord;
import vn.io.dxa.rulestone.api.DecisionRecord.ParamSetVersion;
import vn.io.dxa.rulestone.api.DecisionRef;
import vn.io.dxa.rulestone.api.DecisionResult;
import vn.io.dxa.rulestone.api.Facts;
import vn.io.dxa.rulestone.api.Money;
import vn.io.dxa.rulestone.api.RuleStoneEngine;
import vn.io.dxa.rulestone.cel.ValueType;

class ParametersTest {

    private static final Path EXAMPLES = Path.of("..", "examples");

    private static ParameterSet set(String version, String from, String maxDti) {
        return ParameterSetJson.read("""
                { "id": "limits", "version": "%s", "effectiveFrom": "%s",
                  "values": { "maxDti": { "type": "decimal", "value": "%s" },
                              "fee": { "type": "money", "value": "25000 VND" },
                              "start": { "type": "date", "value": "2027-01-01" } } }
                """.formatted(version, from, maxDti));
    }

    private static final String TABLE = """
            { "id": "dti", "version": "1", "hitPolicy": "FIRST",
              "inputs": { "dti": "decimal" }, "outputs": { "decision": "string" },
              "params": { "set": "limits", "values": { "maxDti": "decimal" } },
              "rules": [ { "id": "HIGH", "when": "dti > params.maxDti", "then": { "decision": "'REJECT'" } },
                         { "id": "OK", "when": "true", "then": { "decision": "'APPROVE'" } } ] }
            """;

    private static RuleStoneEngine engine(String table, ParameterSet... sets) {
        return RuleStone.builder().decision(DecisionTableJson.read(table)).paramSets(List.of(sets)).build();
    }

    private static Facts dti(String value) {
        return Facts.of("dti", new BigDecimal(value));
    }

    @Test
    void readsAndWritesTheCanonicalForm() {
        ParameterSet set = ParameterSetJson.read("""
                { "values": { "b": { "value": ["X", "Y"], "type": ["string"] },
                              "a": { "type": "decimal", "value": 0.450 } },
                  "version": "7", "id": "s", "description": "d" }
                """);
        assertThat(set.values().get("a").value()).isEqualTo(new BigDecimal("0.450"));
        assertThat(ParameterSetJson.write(set)).isEqualTo("{\"description\":\"d\",\"id\":\"s\",\"values\":{"
                + "\"a\":{\"type\":\"decimal\",\"value\":\"0.450\"},\"b\":{\"type\":[\"string\"],\"value\":[\"X\",\"Y\"]}},"
                + "\"version\":\"7\"}");
        ParameterSet money = set("1", "2027-01-01", "0.5");
        assertThat(money.values().get("fee").value()).isEqualTo(Money.of("25000", "VND"));
        assertThat(money.values().get("start").value()).isEqualTo(LocalDate.of(2027, 1, 1));
    }

    @Test
    void tablesWithoutParametersHashAsBefore() {
        DecisionTable table = DecisionTableJson.read(EXAMPLES.resolve("loan_eligibility.v3.stone.json"));
        assertThat(DecisionTableJson.write(table)).doesNotContain("params");
        DecisionTable v4 = DecisionTableJson.read(EXAMPLES.resolve("loan_eligibility.v4.stone.json"));
        assertThat(DecisionTableJson.write(v4))
                .contains("\"params\":{\"set\":\"retail_credit\",\"values\":{\"blockedSegments\":[\"string\"],");
    }

    @Test
    void theVersionInForceOnTheBusinessDateIsBoundAndRecorded() {
        RuleStoneEngine engine = engine(TABLE, set("1", "2027-01-01", "0.50"), set("2", "2027-07-01", "0.45"));
        DecisionResult before = engine.evaluate(DecisionRef.of("dti").asOf(LocalDate.of(2027, 6, 30)), dti("0.48"));
        DecisionResult after = engine.evaluate(DecisionRef.of("dti").asOf(LocalDate.of(2027, 7, 1)), dti("0.48"));
        assertThat(before.output()).containsEntry("decision", "APPROVE");
        assertThat(after.output()).containsEntry("decision", "REJECT");
        // The rule version does not change, the parameter set version does; the facts hash covers the facts only.
        assertThat(after.ruleVersion()).isEqualTo(before.ruleVersion());
        assertThat(after.record().factsHash()).isEqualTo(before.record().factsHash());
        assertThat(before.record().params()).singleElement().satisfies(p -> {
            assertThat(p.set()).isEqualTo("limits");
            assertThat(p.version()).startsWith("1@sha256:").hasSize("1@sha256:".length() + 64);
        });
        assertThat(after.record().params().get(0).version()).startsWith("2@sha256:");
        assertThat(DecisionRecordJson.write(after.record()))
                .contains("\"version\":\"" + after.ruleVersion() + "\",\"params\":[{\"set\":\"limits\",\"version\":\"2@sha256:")
                .doesNotContain("0.45");
    }

    @Test
    void aVersionCanBeForcedForReplayButNotOverAPin() {
        RuleStoneEngine engine = engine(TABLE, set("1", "2027-01-01", "0.50"), set("2", "2027-07-01", "0.45"));
        DecisionRef ref = DecisionRef.of("dti").asOf(LocalDate.of(2027, 9, 1));
        assertThat(engine.evaluate(ref.params("limits", "1"), dti("0.48")).output()).containsEntry("decision", "APPROVE");
        assertThatThrownBy(() -> engine.evaluate(ref.params("limits", "9"), dti("0.48")))
                .isInstanceOf(DecisionNotFoundException.class)
                .hasMessage("parameter set 'limits' has no version '9'");

        RuleStoneEngine pinned = engine(TABLE.replace("\"set\": \"limits\"", "\"set\": \"limits\", \"version\": \"2\""),
                set("1", "2027-01-01", "0.50"), set("2", "2027-07-01", "0.45"));
        DecisionResult result = pinned.evaluate(DecisionRef.of("dti").asOf(LocalDate.of(2027, 2, 1)).params("limits", "1"),
                dti("0.48"));
        assertThat(result.output()).containsEntry("decision", "REJECT");
        assertThat(result.record().params().get(0).version()).startsWith("2@");
    }

    @Test
    void noVersionInForceFailsTheEvaluationWithARecord() {
        List<DecisionRecord> records = new java.util.ArrayList<>();
        RuleStoneEngine engine = RuleStone.builder().decision(DecisionTableJson.read(TABLE))
                .paramSet(set("1", "2027-01-01", "0.5")).auditSink(records::add).build();
        assertThatThrownBy(() -> engine.evaluate(DecisionRef.of("dti").asOf(LocalDate.of(2026, 12, 31)), dti("0.1")))
                .isInstanceOf(DecisionNotFoundException.class)
                .hasMessage("no version of parameter set 'limits' is effective on 2026-12-31");
        assertThat(records.get(0).status()).isEqualTo(DecisionRecord.Status.ERROR);
        assertThat(records.get(0).version()).isPresent();
        assertThat(records.get(0).params()).isEmpty();
    }

    @Test
    void parametersCannotBePassedAsFacts() {
        RuleStoneEngine engine = engine(TABLE, set("1", "2027-01-01", "0.5"));
        assertThatThrownBy(() -> engine.evaluate(DecisionRef.of("dti").asOf(LocalDate.of(2027, 2, 1)),
                Facts.of("dti", BigDecimal.ONE, "params", Map.of("maxDti", BigDecimal.TEN))))
                .isInstanceOf(DecisionEvaluationException.class)
                .hasMessageContaining("invalid input params: reserved name; the parameters come from parameter set limits");
    }

    @Test
    void setsBuiltInJavaAreCheckedWhenTheEngineIsBuilt() {
        ParameterSet wrong = new ParameterSet("limits", "1", Optional.empty(), Optional.empty(), Optional.empty(),
                Map.of("maxDti", new ParameterSet.Parameter(ValueType.DECIMAL, "0.5")));
        assertThatThrownBy(() -> engine(TABLE, wrong))
                .isInstanceOfSatisfying(InvalidDecisionException.class, e -> assertThat(e.problems()).singleElement()
                        .asString().startsWith("parameter set limits@1 values.maxDti: "));
    }

    private static final String FLOW = """
            { "id": "f", "version": "1", "inputs": { "dti": "decimal" }, "outputs": { "decision": "string", "fee": "money" },
              "params": { "set": "fees", "values": { "fee": "money" } },
              "start": "check",
              "nodes": {
                "check": { "type": "table", "decision": "dti", "next": "end" },
                "end": { "type": "end", "set": { "decision": "check.decision", "fee": "params.fee" } } } }
            """;

    @Test
    void flowsReadTheirOwnParametersAndTheirTablesTheirs() {
        ParameterSet fees = ParameterSetJson.read("""
                { "id": "fees", "version": "3", "values": { "fee": { "type": "money", "value": "10000 VND" } } }
                """);
        RuleStoneEngine engine = RuleStone.builder().decision(DecisionTableJson.read(TABLE))
                .flow(DecisionFlowJson.read(FLOW)).paramSets(List.of(fees, set("1", "2027-01-01", "0.5"))).build();
        DecisionResult result = engine.evaluate(DecisionRef.of("f").asOf(LocalDate.of(2027, 2, 1)), dti("0.6"));
        assertThat(result.output()).containsEntry("decision", "REJECT").containsEntry("fee", Money.of("10000", "VND"));
        assertThat(result.record().params()).extracting(ParamSetVersion::set).containsExactly("fees", "limits");
        assertThat(DecisionFlowJson.write(DecisionFlowJson.read(FLOW))).contains("\"params\":{\"set\":\"fees\"");
    }

    @Test
    void flowChecks() {
        String flow = FLOW.replace("\"inputs\": { \"dti\": \"decimal\" }", "\"inputs\": { \"dti\": \"decimal\", \"params\": \"int\" }")
                .replace("\"fee\": \"params.fee\"", "\"fee\": \"params.other\"");
        assertThatThrownBy(() -> RuleStone.builder().decision(DecisionTableJson.read(TABLE)).flow(DecisionFlowJson.read(flow))
                .paramSet(set("1", "2027-01-01", "0.5")).build())
                .isInstanceOfSatisfying(InvalidDecisionException.class, e -> assertThat(e.problems()).containsExactly(
                        "f@1 inputs.params: reserved name: the parameters declared in \"params\" are read as params.<name>",
                        "f@1 params.set: no parameter set 'fees' is registered"));
        String reads = FLOW.replace("\"fee\": \"params.fee\"", "\"fee\": \"params.other\"");
        ParameterSet fees = ParameterSetJson.read("""
                { "id": "fees", "version": "3", "values": { "fee": { "type": "money", "value": "10000 VND" } } }
                """);
        assertThatThrownBy(() -> RuleStone.builder().decision(DecisionTableJson.read(TABLE)).flow(DecisionFlowJson.read(reads))
                .paramSets(List.of(fees, set("1", "2027-01-01", "0.5"))).build())
                .isInstanceOfSatisfying(InvalidDecisionException.class, e -> assertThat(e.problems()).containsExactly(
                        "f@1 nodes.end.set.fee: reads params.other, which params.values does not declare (declared: [fee])"));
        String named = FLOW.replace("\"check\"", "\"params\"").replace("check.decision", "params.decision");
        assertThatThrownBy(() -> RuleStone.builder().decision(DecisionTableJson.read(TABLE))
                .flow(DecisionFlowJson.read(named)).paramSet(set("1", "2027-01-01", "0.5")).build())
                .isInstanceOfSatisfying(InvalidDecisionException.class, e -> assertThat(e.problems())
                        .contains("f@1 nodes.params: invalid node id: 'params' is reserved for parameter sets, which "
                                + "expressions read as params.<name>"));
    }
}
