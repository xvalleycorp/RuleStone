//
// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.rulestone.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import vn.io.dxa.rulestone.api.DecisionRef;
import vn.io.dxa.rulestone.api.DecisionResult;
import vn.io.dxa.rulestone.api.Facts;
import vn.io.dxa.rulestone.api.Money;
import vn.io.dxa.rulestone.cel.ValueType;

class FactsJsonTest {

    private static final Map<String, ValueType> INPUTS = inputs();

    private static Map<String, ValueType> inputs() {
        Map<String, ValueType> account = new LinkedHashMap<>();
        account.put("openedOn", ValueType.DATE);
        account.put("balance", ValueType.MONEY);
        account.put("rate", ValueType.DECIMAL);
        account.put("segment", ValueType.STRING);
        Map<String, ValueType> inputs = new LinkedHashMap<>();
        inputs.put("flag", ValueType.BOOL);
        inputs.put("count", ValueType.INT);
        inputs.put("account", ValueType.struct(account));
        inputs.put("deposits", ValueType.listOf(ValueType.MONEY));
        return inputs;
    }

    private static List<String> problems(String json) {
        try {
            FactsJson.readFacts(json, INPUTS);
        } catch (InvalidFactsException e) {
            return e.problems();
        }
        throw new AssertionError("no problem reported");
    }

    @Test
    void readsEveryTypeExactly() {
        Facts facts = FactsJson.readFacts("""
                { "flag": true, "count": 30,
                  "account": { "openedOn": "2026-11-01", "balance": "1500000.50 VND", "rate": 0.0350, "segment": "SME" },
                  "deposits": ["240000000 VND", "-12.5 USD"] }""", INPUTS);

        Map<String, Object> values = facts.asMap();
        assertThat(values.get("flag")).isEqualTo(true);
        assertThat(values.get("count")).isEqualTo(30L);
        assertThat(values.get("account")).isEqualTo(Map.of(
                "openedOn", LocalDate.of(2026, 11, 1),
                "balance", Money.of("1500000.50", "VND"),
                "rate", new BigDecimal("0.0350"),
                "segment", "SME"));
        assertThat(((BigDecimal) ((Map<?, ?>) values.get("account")).get("rate")).scale()).isEqualTo(4);
        assertThat(values.get("deposits")).isEqualTo(List.of(Money.of("240000000", "VND"), Money.of("-12.5", "USD")));
    }

    @Test
    void decimalsMayBeIntegersOrStrings() {
        Map<String, ValueType> types = Map.of("d", ValueType.DECIMAL);

        assertThat(FactsJson.readFacts("{ \"d\": 5 }", types).asMap()).containsEntry("d", new BigDecimal("5"));
        assertThat(FactsJson.readFacts("{ \"d\": \"0.0980\" }", types).asMap()).containsEntry("d", new BigDecimal("0.0980"));
        assertThat(FactsJson.readFacts("{ \"d\": \"-12\" }", types).asMap()).containsEntry("d", new BigDecimal("-12"));
        assertThat(FactsJson.readFacts("{ \"d\": 1.5e3 }", types).asMap()).containsEntry("d", new BigDecimal("1.5e3"));
    }

    @Test
    void absentAndNullStructFieldsAreUnset() {
        Facts facts = FactsJson.readFacts("{ \"account\": { \"segment\": \"SME\", \"rate\": null } }", INPUTS);

        assertThat(facts.asMap()).containsOnlyKeys("account");
        assertThat(facts.asMap().get("account")).isEqualTo(Map.of("segment", "SME"));
    }

    @Test
    void unsetFieldsWorkWithHas() {
        DecisionTable table = DecisionTableJson.read("""
                { "id": "t", "version": "1", "hitPolicy": "FIRST",
                  "inputs": { "account": { "openedOn": "date", "balance": "money" } },
                  "outputs": { "opened": "bool", "large": "bool" },
                  "rules": [ { "id": "R", "when": "true", "then": {
                    "opened": "has(account.openedOn)",
                    "large": "has(account.balance) && account.balance >= money(1000, 'VND')" } } ] }""");
        Facts facts = FactsJson.readFacts(JsonMapper.builder().build().createObjectNode()
                .set("account", JsonNodeFactory.instance.objectNode().put("balance", "1000 VND")), table);

        DecisionResult result = RuleStone.builder().decision(table).build().evaluate(DecisionRef.of("t"), facts);

        assertThat(result.output()).hasToString("{opened=false, large=true}");
    }

    @Test
    void reportsEveryProblemWithItsPathAndNeverTheValue() {
        List<String> problems = problems("""
                { "flag": "yes", "count": 1.5, "extra": 1,
                  "account": { "openedOn": "01/11/2026", "balance": 1500000, "rate": "1,5", "segment": 7, "sgment": "x" },
                  "deposits": ["240000000 vnd", "1000 XYZ", null, "2026-02-30"] }""");

        assertThat(problems).containsExactly(
                "flag: expected true or false",
                "count: expected an integer",
                "extra: not declared (declared: flag, count, account, deposits)",
                "account.openedOn: expected a date as \"yyyy-MM-dd\"",
                "account.balance: expected money as \"<amount> <CCY>\", e.g. \"1500000 VND\"",
                "account.rate: expected a decimal as a number or a string in plain notation, e.g. \"0.0980\"",
                "account.segment: expected a string",
                "account.sgment: unknown field (declared: openedOn, balance, rate, segment)",
                "deposits[0]: expected money as \"<amount> <CCY>\", e.g. \"1500000 VND\"",
                "deposits[1]: expected money as \"<amount> <CCY>\", e.g. \"1500000 VND\", with an ISO 4217 currency code",
                "deposits[2]: null is not a value",
                "deposits[3]: expected money as \"<amount> <CCY>\", e.g. \"1500000 VND\"");
        assertThat(String.join("\n", problems)).doesNotContain("yes", "1.5", "01/11", "1,5", "vnd", "XYZ");
    }

    @Test
    void reportsImpossibleDatesAndOutOfRangeIntegers() {
        assertThat(problems("{ \"account\": { \"openedOn\": \"2026-02-30\" }, \"count\": 99999999999999999999 }"))
                .containsExactly(
                        "account.openedOn: expected an existing date as \"yyyy-MM-dd\"",
                        "count: expected an integer between -9223372036854775808 and 9223372036854775807");
    }

    @Test
    void rejectsNullInputsWrongShapesAndBadJson() {
        assertThat(problems("{ \"flag\": null, \"account\": [], \"deposits\": {} }")).containsExactly(
                "flag: null is not a value; leave it out instead",
                "account: expected an object",
                "deposits: expected an array");
        assertThat(problems("[]")).containsExactly("$: expected an object of names to values");
        assertThat(problems("{ \"flag\": tru }")).singleElement().asString()
                .startsWith("$: invalid JSON at line 1")
                .doesNotContain("tru");
        assertThat(problems("")).containsExactly("$: expected a JSON object");
        assertThatThrownBy(() -> FactsJson.readFacts("{} {}", INPUTS)).isInstanceOf(InvalidFactsException.class);
    }

    @Test
    void neverConvertsFloatingPointNodes() {
        JsonNode doubleNode = JsonNodeFactory.instance.objectNode().put("d", 0.1);

        assertThatThrownBy(() -> FactsJson.readValues(doubleNode, Map.of("d", ValueType.DECIMAL)))
                .isInstanceOf(InvalidFactsException.class)
                .hasMessageContaining("d: floating-point number; read the JSON with USE_BIG_DECIMAL_FOR_FLOATS");
    }

    @Test
    void absentInputsAreLeftToTheEngine() {
        assertThat(FactsJson.readFacts("{}", INPUTS).asMap()).isEmpty();
    }

    @Test
    void exceptionMessageListsProblems() {
        assertThatThrownBy(() -> FactsJson.readFacts("{ \"flag\": 1 }", INPUTS))
                .isInstanceOf(InvalidFactsException.class)
                .hasMessage("invalid JSON values (1 problem):\n  - flag: expected true or false");
        assertThatThrownBy(() -> new InvalidFactsException(List.of())).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void readsSingleValues() {
        JsonNodeFactory nodes = JsonNodeFactory.instance;

        assertThat(FactsJson.readValue(nodes.textNode("2026-11-01"), ValueType.DATE)).isEqualTo(LocalDate.of(2026, 11, 1));
        assertThatThrownBy(() -> FactsJson.readValue(nodes.nullNode(), ValueType.DATE))
                .hasMessageContaining("value: null is not a value");
        assertThatThrownBy(() -> FactsJson.readValue(nodes.numberNode(1), ValueType.MONEY))
                .hasMessageContaining("value: expected money");
    }

    @Test
    void resultTypesAreListsUnderCollect() {
        DecisionTable fees = RuleStoneEngineTest.load("transfer_fees.stone.json");
        DecisionTable rates = RuleStoneEngineTest.load("deposit_rate.stone.json");

        assertThat(FactsJson.resultTypes(fees)).isEqualTo(Map.of(
                "feeCode", ValueType.listOf(ValueType.STRING), "fee", ValueType.listOf(ValueType.DECIMAL)));
        assertThat(FactsJson.resultTypes(rates)).isEqualTo(Map.of("rate", ValueType.DECIMAL));
    }

    @Test
    void writesOutputsInTheSameForms() {
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("fee", Money.of("25000", "VND"));
        output.put("settles", LocalDate.of(2026, 11, 2));
        output.put("rate", new BigDecimal("0.0980"));
        output.put("big", new BigDecimal("1.535E+6"));
        output.put("count", 3L);
        output.put("ok", true);
        output.put("codes", List.of("A", "B"));
        output.put("nested", Map.of("x", 1));

        assertThat(FactsJson.write(output)).isEqualTo("{\"fee\":\"25000 VND\",\"settles\":\"2026-11-02\","
                + "\"rate\":\"0.0980\",\"big\":\"1535000\",\"count\":3,\"ok\":true,\"codes\":[\"A\",\"B\"],"
                + "\"nested\":{\"x\":1}}");
        assertThat(FactsJson.writeValue(HitPolicy.FIRST).textValue()).isEqualTo("FIRST");
        assertThat(FactsJson.writeValue(BigInteger.TEN).bigIntegerValue()).isEqualTo(BigInteger.TEN);
    }

    @Test
    void rejectsValuesThatCannotBeWritten() {
        assertThatThrownBy(() -> FactsJson.writeValue(0.1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("value: floating-point numbers are not supported; use BigDecimal");
        assertThatThrownBy(() -> FactsJson.writeValues(Map.of("a", List.of(new Object()))))
                .hasMessage("a[0]: unsupported type java.lang.Object");
        assertThatThrownBy(() -> FactsJson.writeValue(Map.of(1, "x")))
                .hasMessage("value: map keys must be strings");
        assertThatThrownBy(() -> FactsJson.writeValue(null)).hasMessage("value: null is not a value");
    }

    @Test
    void roundTripsThroughJson() {
        Facts facts = FactsJson.readFacts("""
                { "account": { "openedOn": "2026-11-01", "balance": "1500000.50 VND", "rate": "0.0350" },
                  "deposits": ["1.00 USD"] }""", INPUTS);

        String json = FactsJson.write(facts.asMap());

        assertThat(FactsJson.readFacts(json, INPUTS)).isEqualTo(facts);
    }

    @Test
    void readsResultsOfACollectDecision() {
        DecisionTable fees = RuleStoneEngineTest.load("transfer_fees.stone.json");

        Map<String, Object> expected = FactsJson.readValues(
                JsonNodeFactory.instance.objectNode().set("fee",
                        JsonNodeFactory.instance.arrayNode().add("25000").add(10000)),
                FactsJson.resultTypes(fees));

        assertThat(expected).isEqualTo(Map.of("fee", List.of(new BigDecimal("25000"), new BigDecimal("10000"))));
    }
}
