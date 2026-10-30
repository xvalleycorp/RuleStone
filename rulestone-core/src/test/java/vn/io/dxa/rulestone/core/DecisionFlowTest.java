//
// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.rulestone.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import vn.io.dxa.rulestone.api.DecisionEvaluationException;
import vn.io.dxa.rulestone.api.DecisionRecord;
import vn.io.dxa.rulestone.api.DecisionRecord.PathStep;
import vn.io.dxa.rulestone.api.DecisionRecord.ReasonCode;
import vn.io.dxa.rulestone.api.DecisionRecord.Routing;
import vn.io.dxa.rulestone.api.DecisionRecord.Status;
import vn.io.dxa.rulestone.api.DecisionRef;
import vn.io.dxa.rulestone.api.DecisionResult;
import vn.io.dxa.rulestone.api.DecisionTimeoutException;
import vn.io.dxa.rulestone.api.Facts;
import vn.io.dxa.rulestone.api.Money;
import vn.io.dxa.rulestone.api.RuleStoneEngine;

class DecisionFlowTest {

    static final Path FLOWS = Path.of("..", "examples", "flows");
    private static final LocalDate DATE = LocalDate.of(2026, 11, 15);
    private static final DecisionRef REF = DecisionRef.of("card_origination").asOf(DATE);

    static List<DecisionTable> tables() {
        return List.of("card_policy", "card_grade_existing", "card_grade_new", "card_limit").stream()
                .map(name -> DecisionTableJson.read(FLOWS.resolve(name + ".stone.json")))
                .toList();
    }

    static DecisionFlow flow() {
        return DecisionFlowJson.read(FLOWS.resolve("card_origination.flow.json"));
    }

    private final List<DecisionRecord> audited = new ArrayList<>();

    private RuleStoneEngine engine() {
        return RuleStone.builder().decisions(tables()).flow(flow()).auditSink(audited::add).build();
    }

    private static Facts facts(String id, int age, boolean existing, int dpd, String utilisation) {
        return Facts.of(
                "applicant", Map.of("id", id, "age", age, "income", Money.of("20000000", "VND"),
                        "existingCustomer", existing),
                "bureau", Map.of("dpd30Count12m", dpd, "utilisation", new BigDecimal(utilisation)));
    }

    /** An applicant id whose split bucket is {@code bucket}. */
    private static String idIn(String bucket) {
        for (int i = 0; ; i++) {
            double point = DefaultRuleStoneEngine.bucket("card-limit-2026Q4", "C-" + i) * 100;
            if ((point < 90) == bucket.equals("champion")) {
                return "C-" + i;
            }
        }
    }

    // --- Evaluation -----------------------------------------------------------------------------

    @Test
    void aPolicyRejectionEndsTheFlowBeforeAnyGrading() {
        DecisionResult result = engine().evaluate(REF, facts("C-1", 17, true, 0, "0.1"));

        assertThat(result.decisionId()).isEqualTo("card_origination");
        assertThat(result.ruleVersion()).startsWith("1@sha256:");
        assertThat(result.output()).containsExactly(
                Map.entry("decision", "REJECT"), Map.entry("limit", Money.of("0", "VND")), Map.entry("riskGrade", "N/A"));
        assertThat(result.reasonCodes()).containsExactly("UNDERAGE");
        assertThat(result.matchedRules()).containsExactly("UNDERAGE");

        DecisionRecord record = result.record();
        assertThat(record.decision()).isEqualTo("card_origination");
        assertThat(record.version()).contains(result.ruleVersion());
        assertThat(record.reasonCodes()).containsExactly(new ReasonCode("UNDERAGE", "policy"));
        assertThat(record.path()).extracting(PathStep::node).containsExactly("policy", "rejected");
        PathStep policy = record.path().get(0);
        assertThat(policy.type()).isEqualTo("table");
        assertThat(policy.decision()).contains("card_policy");
        assertThat(policy.version()).hasValueSatisfying(v -> assertThat(v).startsWith("1@sha256:"));
        assertThat(policy.matchedRules()).containsExactly("UNDERAGE");
        assertThat(record.path().get(1)).isEqualTo(PathStep.visit("rejected", "end"));
        assertThat(audited).containsExactly(record);
    }

    @Test
    void anExistingCustomerFollowsTheBehaviouralBranchAndGetsTheChampionLimit() {
        String id = idIn("champion");
        DecisionResult result = engine().evaluate(REF, facts(id, 35, true, 0, "0.1"));

        assertThat(result.output()).containsEntry("decision", "APPROVE").containsEntry("riskGrade", "A")
                .containsEntry("limit", Money.of("120000000", "VND"));   // 20m * 12 * 0.5
        assertThat(result.record().path()).extracting(PathStep::node).containsExactly(
                "policy", "affordability", "segment", "grade_existing", "limit_strategy", "limit", "approved");
        assertThat(result.record().path().get(2)).isEqualTo(PathStep.branch("segment", "switch", "grade_existing"));
        assertThat(result.record().path().get(4)).isEqualTo(PathStep.branch("limit_strategy", "split", "champion"));
        assertThat(result.record().path().get(5).matchedRules()).containsExactly("A");
        assertThat(result.matchedRules()).containsExactly("PASS", "A", "A");
    }

    @Test
    void theChallengerBucketIsChosenByApplicantIdAndPassedToTheLimitTable() {
        String id = idIn("challenger");
        RuleStoneEngine engine = engine();
        DecisionResult result = engine.evaluate(REF, facts(id, 35, true, 0, "0.1"));

        assertThat(result.output()).containsEntry("limit", Money.of("144000000", "VND"));   // 240m * 0.6
        assertThat(result.record().path().get(4)).isEqualTo(PathStep.branch("limit_strategy", "split", "challenger"));
        assertThat(result.record().path().get(5).matchedRules()).containsExactly("A_CHALLENGER");
        // The same key always lands in the same bucket.
        assertThat(engine.evaluate(REF, facts(id, 40, true, 0, "0.2")).record().path().get(4).taken())
                .contains("challenger");
    }

    @Test
    void theSplitSendsAboutItsPercentageOfKeysToEachBucket() {
        int challenger = 0;
        for (int i = 0; i < 10_000; i++) {
            if (DefaultRuleStoneEngine.bucket("card-limit-2026Q4", "C-" + i) * 100 >= 90) {
                challenger++;
            }
        }
        assertThat(challenger).isBetween(900, 1100);
    }

    @Test
    void aNewCustomerWithAThinFileIsReferredWithTheGradeOfTheSharedName() {
        DecisionResult result = engine().evaluate(REF, facts("C-2", 19, false, 1, "0.1"));

        assertThat(result.output()).containsEntry("decision", "REFER").containsEntry("riskGrade", "D");
        assertThat(result.record().path()).extracting(PathStep::node)
                .containsExactly("policy", "affordability", "segment", "grade_new", "referred");
        assertThat(result.record().reasonCodes()).containsExactly(new ReasonCode("THIN_CREDIT_FILE", "grade_new"));
    }

    @Test
    void theRecordJsonShowsBranchesAndTableVersionsButNoInputValues() {
        DecisionResult result = engine().evaluate(REF.correlationId("app-1"), facts("C-SECRET-ID", 35, true, 0, "0.1"));

        String json = DecisionRecordJson.write(result.record());
        assertThat(json).contains("\"node\":\"segment\",\"type\":\"switch\",\"taken\":\"grade_existing\"}")
                .contains("\"node\":\"policy\",\"type\":\"table\",\"decision\":\"card_policy\",\"version\":\"1@sha256:")
                .contains("\"node\":\"approved\",\"type\":\"end\"}")
                .doesNotContain("C-SECRET-ID")
                .doesNotContain("\"20000000");
    }

    @Test
    void reasonCodesAreDeduplicatedKeepingTheNodeThatFirstGaveThem() {
        String table = """
                { "id": "signals", "version": "1", "hitPolicy": "COLLECT",
                  "inputs": { "x": "int" }, "outputs": { "flag": "string" },
                  "rules": [ { "id": "R1", "when": "x > 0", "then": { "flag": "'A'" }, "reasonCode": "SAME" },
                             { "id": "R2", "when": "x > 1", "then": { "flag": "'B'" }, "reasonCode": "SAME" } ] }
                """;
        String flow = """
                { "id": "twice", "version": "1", "inputs": { "x": "int" }, "outputs": { "n": "int" }, "start": "a",
                  "nodes": { "a": { "type": "table", "decision": "signals", "next": "b" },
                             "b": { "type": "table", "decision": "signals", "next": "done" },
                             "done": { "type": "end", "set": { "n": "size(a.flag) + size(b.flag)" } } } }
                """;
        DecisionResult result = RuleStone.builder().decision(DecisionTableJson.read(table))
                .flow(DecisionFlowJson.read(flow)).build().evaluate(DecisionRef.of("twice"), Facts.of("x", 2));

        assertThat(result.output()).containsEntry("n", 4L);
        assertThat(result.reasonCodes()).containsExactly("SAME");
        assertThat(result.record().reasonCodes()).containsExactly(new ReasonCode("SAME", "a"));
    }

    @Test
    void derivedValuesAndSwitchesCanBeReadByLaterNodes() {
        String flow = """
                { "id": "pricing", "version": "1",
                  "inputs": { "amount": "decimal", "segment": "string" },
                  "outputs": { "fee": "decimal", "route": "string" }, "start": "calc",
                  "nodes": {
                    "calc": { "type": "derive", "outputs": { "fee": "decimal", "big": "bool" },
                              "set": { "fee": "amount * decimal('0.01')", "big": "amount >= decimal(1000)" },
                              "next": [ { "when": "calc.big", "to": "route" }, { "to": "small" } ] },
                    "route": { "type": "switch", "cases": [ { "when": "segment == 'VIP'", "to": "vip" }, { "to": "std" } ] },
                    "vip": { "type": "end", "set": { "fee": "decimal(0)", "route": "route.taken" } },
                    "std": { "type": "end", "set": { "fee": "calc.fee", "route": "route.taken" } },
                    "small": { "type": "end", "set": { "fee": "calc.fee", "route": "'small'" } } } }
                """;
        RuleStoneEngine engine = RuleStone.builder().flow(DecisionFlowJson.read(flow)).build();

        assertThat(engine.evaluate(DecisionRef.of("pricing"), Facts.of("amount", new BigDecimal("5000"), "segment", "STD"))
                .output()).containsEntry("fee", new BigDecimal("50.00")).containsEntry("route", "std");
        assertThat(engine.evaluate(DecisionRef.of("pricing"), Facts.of("amount", new BigDecimal("5000"), "segment", "VIP"))
                .output()).containsEntry("route", "vip");
        DecisionResult small = engine.evaluate(DecisionRef.of("pricing"), Facts.of("amount", 10, "segment", "VIP"));
        assertThat(small.output()).containsEntry("route", "small");
        assertThat(small.record().path()).containsExactly(PathStep.visit("calc", "derive"), PathStep.visit("small", "end"));
        assertThat(small.trace()).isEmpty();
    }

    // --- Failures -------------------------------------------------------------------------------

    @Test
    void invalidFactsFailTheFlowAndAreRecordedWithoutAPath() {
        RuleStoneEngine engine = engine();

        assertThatThrownBy(() -> engine.evaluate(REF, Facts.of("applicant", Map.of("id", "x"))))
                .isInstanceOf(DecisionEvaluationException.class)
                .hasMessageContaining("card_origination@1@sha256:")
                .hasMessageContaining("invalid input");
        DecisionRecord record = audited.get(0);
        assertThat(record.status()).isEqualTo(Status.ERROR);
        assertThat(record.factsHash()).isEmpty();
        assertThat(record.path()).isEmpty();
    }

    @Test
    void aFailureInsideANodeIsRecordedWithThatNodeLast() {
        String flow = """
                { "id": "divide", "version": "1", "inputs": { "a": "int", "b": "int" }, "outputs": { "q": "int" },
                  "start": "first", "nodes": {
                    "first": { "type": "switch", "cases": [ { "when": "a > 0", "to": "calc" }, { "to": "calc" } ] },
                    "calc": { "type": "derive", "outputs": { "q": "int" }, "set": { "q": "a / b" }, "next": "done" },
                    "done": { "type": "end", "set": { "q": "calc.q" } } } }
                """;
        RuleStoneEngine engine = RuleStone.builder().flow(DecisionFlowJson.read(flow)).auditSink(audited::add).build();

        assertThatThrownBy(() -> engine.evaluate(DecisionRef.of("divide"), Facts.of("a", 987654, "b", 0)))
                .isInstanceOf(DecisionEvaluationException.class)
                .hasMessageContaining("node calc:")
                .hasMessageNotContaining("987654");
        DecisionRecord record = audited.get(0);
        assertThat(record.status()).isEqualTo(Status.ERROR);
        assertThat(record.factsHash()).isPresent();
        assertThat(record.path()).containsExactly(PathStep.branch("first", "switch", "calc"), PathStep.visit("calc", "derive"));
        assertThat(record.error()).hasValueSatisfying(e -> assertThat(e).doesNotContain("987654"));
    }

    @Test
    void aFailingTableNodeIsRecordedWithTheRulesMatchedSoFar() {
        String table = """
                { "id": "strict", "version": "1", "hitPolicy": "UNIQUE", "inputs": { "x": "int" },
                  "outputs": { "y": "int" },
                  "rules": [ { "id": "R1", "when": "x > 0", "then": { "y": "1" } },
                             { "id": "R2", "when": "x > 1", "then": { "y": "2" } } ] }
                """;
        String flow = """
                { "id": "uses_strict", "version": "1", "inputs": { "x": "int" }, "outputs": { "y": "int" },
                  "start": "s", "nodes": { "s": { "type": "table", "decision": "strict", "next": "done" },
                                          "done": { "type": "end", "set": { "y": "s.y" } } } }
                """;
        RuleStoneEngine engine = RuleStone.builder().decision(DecisionTableJson.read(table))
                .flow(DecisionFlowJson.read(flow)).auditSink(audited::add).build();

        assertThatThrownBy(() -> engine.evaluate(DecisionRef.of("uses_strict"), Facts.of("x", 5)))
                .isInstanceOf(DecisionEvaluationException.class)
                .satisfies(e -> assertThat(((DecisionEvaluationException) e).decisionId()).isEqualTo("uses_strict"))
                .hasMessageContaining("node s: strict@1@sha256:")
                .hasMessageContaining("UNIQUE hit policy violated");
        PathStep step = audited.get(0).path().get(0);
        assertThat(step.node()).isEqualTo("s");
        assertThat(step.decision()).contains("strict");
        assertThat(step.matchedRules()).containsExactly("R1", "R2");
    }

    @Test
    void readingAnOutputThatNoRuleSetFailsTheEvaluationInsteadOfGuessing() {
        String table = """
                { "id": "maybe", "version": "1", "hitPolicy": "FIRST", "inputs": { "x": "int" },
                  "outputs": { "y": "int" }, "rules": [ { "id": "R1", "when": "x > 0", "then": { "y": "1" } } ] }
                """;
        String flow = """
                { "id": "reads_maybe", "version": "1", "inputs": { "x": "int" }, "outputs": { "y": "int" },
                  "start": "m", "nodes": {
                    "m": { "type": "table", "decision": "maybe",
                           "next": [ { "when": "has(m.y)", "to": "set" }, { "to": "unset" } ] },
                    "set": { "type": "end", "set": { "y": "m.y" } },
                    "unset": { "type": "end", "set": { "y": "m.y" } } } }
                """;
        RuleStoneEngine engine = RuleStone.builder().decision(DecisionTableJson.read(table))
                .flow(DecisionFlowJson.read(flow)).build();

        assertThat(engine.evaluate(DecisionRef.of("reads_maybe"), Facts.of("x", 1)).output()).containsEntry("y", 1L);
        assertThatThrownBy(() -> engine.evaluate(DecisionRef.of("reads_maybe"), Facts.of("x", 0)))
                .isInstanceOf(DecisionEvaluationException.class)
                .hasMessageContaining("node unset:");
    }

    @Test
    void aFlowThatOverrunsItsTimeoutIsCutShortBetweenNodesOrRules() {
        AtomicLong nanos = new AtomicLong();
        RuleStoneEngine engine = RuleStone.builder().decisions(tables()).flow(flow()).auditSink(audited::add)
                .nanoTime(() -> nanos.getAndAdd(1_000_000)).build();

        DecisionTimeoutException e = catchThrowableOfType(DecisionTimeoutException.class,
                () -> engine.evaluate(REF.timeout(Duration.ofMillis(3)), facts("C-1", 35, true, 0, "0.1")));

        assertThat(e.decisionId()).isEqualTo("card_origination");
        assertThat(e.ruleVersion()).startsWith("1@sha256:");
        DecisionRecord record = audited.get(0);
        assertThat(record.status()).isEqualTo(Status.TIMEOUT);
        assertThat(record.path()).isNotEmpty();
    }

    // --- Versions, canaries and shadows ---------------------------------------------------------

    @Test
    void flowsAreEffectiveDatedLikeTables() {
        RuleStoneEngine engine = engine();
        assertThatThrownBy(() -> engine.evaluate(DecisionRef.of("card_origination").asOf(LocalDate.of(2026, 10, 31)),
                facts("C-1", 35, true, 0, "0.1")))
                .hasMessage("no version of decision 'card_origination' is effective on 2026-10-31");
        assertThat(engine.evaluate(DecisionRef.of("card_origination").version("1").asOf(LocalDate.of(2026, 10, 31)),
                facts("C-1", 17, true, 0, "0.1")).output()).containsEntry("decision", "REJECT");
    }

    @Test
    void aTableNodeUsesTheTableVersionInForceUnlessItPinsOne() {
        String v1 = """
                { "id": "rate", "version": "1", "hitPolicy": "FIRST", "inputs": {}, "outputs": { "r": "int" },
                  "rules": [ { "id": "R", "when": "true", "then": { "r": "1" } } ] }
                """;
        String v2 = v1.replace("\"version\": \"1\"", "\"version\": \"2\", \"effectiveFrom\": \"2027-01-01\"")
                .replace("\"1\" }", "\"2\" }");
        String flow = """
                { "id": "rates", "version": "1", "inputs": {}, "outputs": { "inForce": "int", "pinned": "int" },
                  "start": "a", "nodes": {
                    "a": { "type": "table", "decision": "rate", "next": "b" },
                    "b": { "type": "table", "decision": "rate", "version": "1", "next": "done" },
                    "done": { "type": "end", "set": { "inForce": "a.r", "pinned": "b.r" } } } }
                """;
        RuleStoneEngine engine = RuleStone.builder().decision(DecisionTableJson.read(v1))
                .decision(DecisionTableJson.read(v2)).flow(DecisionFlowJson.read(flow)).build();

        DecisionResult result = engine.evaluate(DecisionRef.of("rates").asOf(LocalDate.of(2027, 2, 1)), Facts.empty());
        assertThat(result.output()).containsEntry("inForce", 2L).containsEntry("pinned", 1L);
        assertThat(result.record().path().get(0).version()).hasValueSatisfying(v -> assertThat(v).startsWith("2@"));
        assertThat(result.record().path().get(1).version()).hasValueSatisfying(v -> assertThat(v).startsWith("1@"));
    }

    @Test
    void flowsCanHaveCanaryAndShadowVersions() {
        DecisionFlow v1 = flow();
        DecisionFlow v2 = DecisionFlowJson.read(DecisionFlowJson.write(v1).replace("\"version\":\"1\"", "\"version\":\"2\"")
                .replace("\"effectiveFrom\":\"2026-11-01\"", "\"effectiveFrom\":\"2027-01-01\""));
        RuleStoneEngine engine = RuleStone.builder().decisions(tables()).flow(v1).flow(v2)
                .canary("card_origination", "2", 1.0).auditSink(audited::add).build();

        DecisionResult result = engine.evaluate(REF.correlationId("app-7"), facts("C-1", 17, true, 0, "0.1"));
        assertThat(result.ruleVersion()).startsWith("2@");
        assertThat(result.record().routing()).map(Routing::kind).contains(Routing.Kind.CANARY);

        audited.clear();
        RuleStoneEngine shadowed = RuleStone.builder().decisions(tables()).flow(v1).flow(v2)
                .shadow("card_origination", "2").auditSink(audited::add).build();
        shadowed.evaluate(REF, facts("C-1", 17, true, 0, "0.1"));
        assertThat(audited).hasSize(2);
        assertThat(audited.get(1).version()).hasValueSatisfying(v -> assertThat(v).startsWith("2@"));
        assertThat(audited.get(1).routing()).map(Routing::kind).contains(Routing.Kind.SHADOW);
    }

    // --- Content hash ---------------------------------------------------------------------------

    @Test
    void theVersionHashCoversTheCanonicalFlowOnly() {
        DecisionFlow flow = flow();
        String canonical = DecisionFlowJson.write(flow);
        assertThat(DecisionFlowJson.write(DecisionFlowJson.read(canonical))).isEqualTo(canonical);

        String ruleVersion = engine().evaluate(REF, facts("C-1", 17, true, 0, "0.1")).ruleVersion();
        assertThat(ruleVersion).isEqualTo("1@sha256:" + CompiledDecision.sha256(canonical));

        // A single unconditional next written as an array is the same content.
        String array = canonical.replace("\"next\":\"segment\"", "\"next\":[{\"to\":\"segment\"}]");
        assertThat(array).isNotEqualTo(canonical);
        assertThat(DecisionFlowJson.write(DecisionFlowJson.read(array))).isEqualTo(canonical);
    }

    // --- Load-time checks -----------------------------------------------------------------------

    private static final String TABLE = """
            { "id": "grade", "version": "1", "hitPolicy": "FIRST",
              "inputs": { "score": "int", "customer": { "age": "int" } },
              "outputs": { "grade": "string" },
              "rules": [ { "id": "R", "when": "true", "then": { "grade": "'A'" } } ] }
            """;

    private static List<String> problems(String flowJson) {
        try {
            RuleStone.builder().decision(DecisionTableJson.read(TABLE)).flow(DecisionFlowJson.read(flowJson)).build();
        } catch (InvalidDecisionException e) {
            return e.problems();
        }
        return List.of();
    }

    private static String flowWith(String nodes) {
        return """
                { "id": "f", "version": "1",
                  "inputs": { "score": "int", "customer": { "age": "int", "name": "string" }, "flag": "bool" },
                  "outputs": { "out": "string" }, "start": "a", "nodes": %s }
                """.formatted(nodes);
    }

    @Test
    void aValidFlowHasNoProblems() {
        assertThat(problems(flowWith("""
                { "a": { "type": "table", "decision": "grade", "next": "e" },
                  "e": { "type": "end", "set": { "out": "a.grade" } } }
                """))).isEmpty();
    }

    @Test
    void cyclesAreRejected() {
        assertThat(problems(flowWith("""
                { "a": { "type": "switch", "cases": [ { "when": "flag", "to": "b" }, { "to": "e" } ] },
                  "b": { "type": "switch", "cases": [ { "when": "flag", "to": "a" }, { "to": "e" } ] },
                  "e": { "type": "end", "set": { "out": "'x'" } } }
                """))).containsExactly("f@1 nodes.a: cycle a -> b -> a; a flow must be acyclic");
    }

    @Test
    void unreachableNodesUnknownTargetsAndAMissingStartAreRejected() {
        assertThat(problems(flowWith("""
                { "a": { "type": "switch", "cases": [ { "when": "flag", "to": "nowhere" }, { "to": "e" } ] },
                  "e": { "type": "end", "set": { "out": "'x'" } },
                  "orphan": { "type": "end", "set": { "out": "'y'" } } }
                """))).containsExactly("f@1 nodes.a.cases[0].to: no node 'nowhere'");
        assertThat(problems(flowWith("""
                { "a": { "type": "end", "set": { "out": "'x'" } },
                  "orphan": { "type": "end", "set": { "out": "'y'" } } }
                """))).containsExactly("f@1 nodes.orphan: not reachable from start node 'a'");
        assertThat(problems(flowWith("""
                { "b": { "type": "end", "set": { "out": "'x'" } } }
                """))).containsExactly("f@1 start: no node 'a'");
    }

    @Test
    void aNameThatSomePathDoesNotSetCannotBeRead() {
        assertThat(problems(flowWith("""
                { "a": { "type": "switch", "cases": [ { "when": "flag", "to": "g" }, { "to": "e" } ] },
                  "g": { "type": "table", "decision": "grade", "next": "e" },
                  "e": { "type": "end", "set": { "out": "g.grade" } } }
                """))).containsExactly("f@1 nodes.e.set.out: reads 'g', which is not set on every path that "
                + "reaches this node; a node may read only values that every path to it sets");
    }

    @Test
    void nodesSharingANameMustPublishTheSameValuesOnAlternativeBranches() {
        String sameValues = """
                { "a": { "type": "switch", "cases": [ { "when": "flag", "to": "g1" }, { "to": "g2" } ] },
                  "g1": { "type": "table", "decision": "grade", "as": "g", "next": "e" },
                  "g2": { "type": "derive", "as": "g", "outputs": { "grade": "string" }, "set": { "grade": "'B'" }, "next": "e" },
                  "e": { "type": "end", "set": { "out": "g.grade" } } }
                """;
        assertThat(problems(flowWith(sameValues))).isEmpty();
        assertThat(problems(flowWith(sameValues.replace("{ \"grade\": \"string\" }, \"set\": { \"grade\": \"'B'\" }",
                "{ \"grade\": \"int\" }, \"set\": { \"grade\": \"1\" }"))))
                .contains("f@1 nodes.g2.as: nodes 'g1' and 'g2' both publish 'g' but with different values: "
                        + "{grade: string} and {grade: int}");
        assertThat(problems(flowWith("""
                { "a": { "type": "table", "decision": "grade", "as": "g", "next": "b" },
                  "b": { "type": "derive", "as": "g", "outputs": { "grade": "string" }, "set": { "grade": "'B'" }, "next": "e" },
                  "e": { "type": "end", "set": { "out": "g.grade" } } }
                """))).contains("f@1 nodes.b.as: nodes 'a' and 'b' both publish 'g' and can both run on one path; "
                + "only nodes on alternative branches may share a name");
    }

    @Test
    void tableNodesMustNameARegisteredTableAndSatisfyItsInputs() {
        assertThat(problems(flowWith("""
                { "a": { "type": "table", "decision": "nope", "next": "e" },
                  "e": { "type": "end", "set": { "out": "'x'" } } }
                """))).containsExactly("f@1 nodes.a.decision: unknown decision 'nope'");
        assertThat(problems(flowWith("""
                { "a": { "type": "table", "decision": "grade", "version": "9", "next": "e" },
                  "e": { "type": "end", "set": { "out": "'x'" } } }
                """))).containsExactly("f@1 nodes.a.version: decision 'grade' has no version '9'");
        assertThat(problems(flowWith("""
                { "a": { "type": "table", "decision": "grade",
                         "facts": { "score": "'high'", "customer": "customer.name", "other": "1" }, "next": "e" },
                  "e": { "type": "end", "set": { "out": "'x'" } } }
                """))).containsExactlyInAnyOrder(
                "f@1 nodes.a.facts.other: not an input of decision 'grade' (inputs: [score, customer])",
                "f@1 nodes.a.facts.score: expression must produce int but produces string: 'high'",
                "f@1 nodes.a.facts.customer: input 'customer' is {age: int}; a value with fields can only be "
                        + "passed by name, e.g. \"applicant\"");
    }

    @Test
    void tableInputsAreMatchedByNameAndType() {
        String flow = """
                { "id": "f", "version": "1", "inputs": { "score": "string", "flag": "bool" },
                  "outputs": { "out": "string" }, "start": "a",
                  "nodes": { "a": { "type": "table", "decision": "grade", "next": "e" },
                             "e": { "type": "end", "set": { "out": "a.grade" } } } }
                """;
        assertThat(problems(flow)).containsExactly(
                "f@1 nodes.a: input 'score' of decision 'grade' is int but flow value 'score' is string; map it in \"facts\"",
                "f@1 nodes.a: input 'customer' of decision 'grade': no input or node value named 'customer'; "
                        + "map it in \"facts\"");
    }

    @Test
    void endNodesMustSetEveryOutputAndOnlyDeclaredOnes() {
        assertThat(problems(flowWith("""
                { "a": { "type": "end", "set": { "other": "'x'" } } }
                """))).containsExactly("f@1 nodes.a.set: does not set output 'out'",
                "f@1 nodes.a.set.other: undeclared output (declared: [out])");
        assertThat(problems(flowWith("""
                { "a": { "type": "end", "set": { "out": "score" } } }
                """))).containsExactly("f@1 nodes.a.set.out: expression must produce string but produces int: score");
    }

    @Test
    void splitsMustAddUpTo100() {
        assertThat(problems(flowWith("""
                { "a": { "type": "split", "key": "customer.name", "salt": "s",
                         "buckets": [ { "name": "x", "percent": 50, "to": "e" }, { "name": "x", "percent": 40, "to": "e" } ] },
                  "e": { "type": "end", "set": { "out": "a.taken" } } }
                """))).containsExactly("f@1 nodes.a.buckets[1].name: duplicate bucket name 'x'",
                "f@1 nodes.a.buckets: percentages add up to 90, not 100");
        assertThat(problems(flowWith("""
                { "a": { "type": "split", "key": "score", "salt": "s", "buckets": [ { "name": "x", "percent": 100, "to": "e" } ] },
                  "e": { "type": "end", "set": { "out": "a.taken" } } }
                """))).containsExactly("f@1 nodes.a.key: expression must produce string but produces int: score");
    }

    @Test
    void namesMustBeIdentifiersAndUnique() {
        assertThat(problems(flowWith("""
                { "a": { "type": "derive", "as": "score", "outputs": { "x": "int" }, "set": { "x": "1" }, "next": "flag" },
                  "flag": { "type": "end", "set": { "out": "'x'" } } }
                """))).containsExactly(
                "f@1 nodes.a.as: invalid name: 'score' is already the name of an input",
                "f@1 nodes.flag: invalid node id: 'flag' is already the name of an input");
        assertThat(problems(flowWith("""
                { "a": { "type": "end", "set": { "out": "'x'" } }, "my-node": { "type": "end", "set": { "out": "'x'" } } }
                """))).anySatisfy(p -> assertThat(p).startsWith("f@1 nodes.my-node: invalid node id: it names"));
    }

    @Test
    void aFlowCannotShareItsIdWithATableOrCallAnotherFlow() {
        String flow = """
                { "id": "grade", "version": "1", "inputs": {}, "outputs": {}, "start": "e",
                  "nodes": { "e": { "type": "end", "set": {} } } }
                """;
        String caller = """
                { "id": "caller", "version": "1", "inputs": {}, "outputs": {}, "start": "a",
                  "nodes": { "a": { "type": "table", "decision": "other", "next": "e" }, "e": { "type": "end", "set": {} } } }
                """;
        String other = flow.replace("\"grade\"", "\"other\"");
        assertThatThrownBy(() -> RuleStone.builder().decision(DecisionTableJson.read(TABLE))
                .flow(DecisionFlowJson.read(flow)).flow(DecisionFlowJson.read(caller)).flow(DecisionFlowJson.read(other)).build())
                .isInstanceOfSatisfying(InvalidDecisionException.class, e -> assertThat(e.problems()).containsExactly(
                        "grade@1 id: a decision table has the same id; tables and flows share one id namespace",
                        "caller@1 nodes.a.decision: 'other' is a decision flow; calling another flow is not supported yet"));
    }

    @Test
    void flowVersionsMustBeConsistentLikeTableVersions() {
        DecisionFlow flow = flow();
        assertThatThrownBy(() -> RuleStone.builder().decisions(tables()).flow(flow).flow(flow).build())
                .isInstanceOfSatisfying(InvalidDecisionException.class, e -> assertThat(e.problems())
                        .containsExactly("card_origination@1: version registered more than once"));
    }

    // --- File format ----------------------------------------------------------------------------

    @Test
    void modelNodesAreReservedButNotSupportedYet() {
        String json = flowWith("""
                { "a": { "type": "model", "mode": "invoke", "model": { "id": "pd", "version": "1" } },
                  "b": { "type": "flow" }, "c": { "type": "rules" } }
                """);
        assertThatThrownBy(() -> DecisionFlowJson.read(json))
                .isInstanceOfSatisfying(InvalidDecisionException.class, e -> assertThat(e.problems()).containsExactly(
                        "$.nodes.a.type: model nodes are not supported yet; they are planned for a later release "
                                + "(see Hybrid AI decisioning). Until then, pass the model's score as an ordinary flow input",
                        "$.nodes.b.type: flow nodes (calling another flow) are not supported yet",
                        "$.nodes.c.type: unknown node type 'rules' (expected table, switch, derive, split or end)"));
    }

    @Test
    void readingIsStrictAndReportsEveryProblem() {
        String json = """
                { "id": "f", "version": "1", "inputs": {}, "outputs": {}, "start": "a", "colour": "red",
                  "nodes": {
                    "a": { "type": "switch", "cases": [ { "to": "b" }, { "when": "true", "to": "b" } ] },
                    "b": { "type": "table", "decision": "t", "nxt": "c" },
                    "c": { "type": "split", "key": "k", "salt": "s", "buckets": [ { "name": "x", "percent": 12.5, "to": "d" } ] },
                    "d": { "type": "end", "set": { "x": 1 } } } }
                """;
        assertThatThrownBy(() -> DecisionFlowJson.read(json))
                .isInstanceOfSatisfying(InvalidDecisionException.class, e -> assertThat(e.problems()).containsExactly(
                        "$.colour: unknown field (allowed: description, effectiveFrom, effectiveTo, id, inputs, nodes, "
                                + "outputs, start, version)",
                        "$.nodes.a.cases[0].when: required: only the last alternative may leave out \"when\"",
                        "$.nodes.a.cases[1].when: the last alternative must have no \"when\", so that one always "
                                + "applies; add { \"to\": … } after it",
                        "$.nodes.b.nxt: unknown field (allowed: as, decision, description, facts, next, type, version)",
                        "$.nodes.b.next: required: a node id, or an array of { \"when\", \"to\" } ending with { \"to\" }",
                        "$.nodes.c.buckets[0].percent: required whole number from 1 to 100",
                        "$.nodes.d.set.x: expected a CEL expression string (string literals need inner quotes, "
                                + "e.g. \"'APPROVE'\")"));
    }

    @Test
    void javaDefinitionsAreCheckedLikeFiles() {
        DecisionFlow flow = new DecisionFlow("java_flow", "1", Optional.empty(), Optional.empty(), Optional.empty(),
                Map.of("x", vn.io.dxa.rulestone.cel.ValueType.INT), Map.of("y", vn.io.dxa.rulestone.cel.ValueType.INT),
                "a", Map.of("a", new FlowNode.Switch(Optional.empty(), List.of(FlowNode.Branch.to("e"))),
                        "e", new FlowNode.End(Optional.empty(), Map.of("y", "x + 1"))));
        DecisionResult result = RuleStone.builder().flow(flow).build().evaluate(DecisionRef.of("java_flow"), Facts.of("x", 1));
        assertThat(result.output()).containsEntry("y", 2L);
    }
}
