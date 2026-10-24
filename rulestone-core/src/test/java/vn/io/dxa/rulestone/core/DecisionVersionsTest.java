//
// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.rulestone.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import vn.io.dxa.rulestone.api.DecisionNotFoundException;
import vn.io.dxa.rulestone.api.DecisionRef;
import vn.io.dxa.rulestone.api.Facts;
import vn.io.dxa.rulestone.api.RuleStoneEngine;

/** "Until superseded": adding a version never requires editing, and re-hashing, the previous one. */
class DecisionVersionsTest {

    private static DecisionTable version(String version, String from, String to) {
        return DecisionTableJson.read("""
                {
                  "id": "consent_request", "version": "%s",%s%s
                  "hitPolicy": "FIRST",
                  "inputs": { "purpose": "string" },
                  "outputs": { "decision": "string" },
                  "rules": [ { "id": "ALL", "when": "true", "then": { "decision": "'V%s'" } } ]
                }""".formatted(version,
                from == null ? "" : " \"effectiveFrom\": \"" + from + "\",",
                to == null ? "" : " \"effectiveTo\": \"" + to + "\",",
                version));
    }

    private static final Facts FACTS = Facts.of("purpose", "ACCOUNT_INFO");

    private static Object decide(RuleStoneEngine engine, String date) {
        return engine.evaluate(DecisionRef.of("consent_request").asOf(LocalDate.parse(date)), FACTS)
                .output().get("decision");
    }

    @Test
    void addingAVersionKeepsThePreviousVersionsHash() {
        DecisionTable v1 = version("1", "2026-01-01", null);
        DecisionTable v2 = version("2", "2027-03-01", null);
        String published = RuleStone.builder().decision(v1).build()
                .evaluate(DecisionRef.of("consent_request").asOf(LocalDate.of(2026, 6, 1)), FACTS).ruleVersion();

        RuleStoneEngine both = RuleStone.builder().decision(v1).decision(v2).build();

        assertThat(both.evaluate(DecisionRef.of("consent_request").asOf(LocalDate.of(2027, 2, 28)), FACTS).ruleVersion())
                .isEqualTo(published);
        assertThat(both.evaluate(DecisionRef.of("consent_request").version("1"), FACTS).ruleVersion())
                .isEqualTo(published);
        assertThat(decide(both, "2027-02-28")).isEqualTo("V1");
        assertThat(decide(both, "2027-03-01")).isEqualTo("V2");
    }

    @Test
    void aVersionWithoutEffectiveFromStartsAtTheBeginning() {
        RuleStoneEngine engine = RuleStone.builder()
                .decision(version("2", "2027-03-01", null))
                .decision(version("1", null, null))
                .build();

        assertThat(decide(engine, "1990-01-01")).isEqualTo("V1");
        assertThat(decide(engine, "2027-03-01")).isEqualTo("V2");
    }

    @Test
    void anEarlierEffectiveToLeavesAGapAndRetiresALastVersion() {
        RuleStoneEngine engine = RuleStone.builder()
                .decision(version("1", "2026-01-01", "2026-07-01"))
                .decision(version("2", "2026-09-01", "2027-01-01"))
                .build();

        assertThat(decide(engine, "2026-06-30")).isEqualTo("V1");
        assertThatThrownBy(() -> decide(engine, "2026-07-01"))
                .isInstanceOf(DecisionNotFoundException.class)
                .hasMessage("no version of decision 'consent_request' is effective on 2026-07-01");
        assertThat(decide(engine, "2026-09-01")).isEqualTo("V2");
        assertThatThrownBy(() -> decide(engine, "2027-01-01")).isInstanceOf(DecisionNotFoundException.class);
        assertThatThrownBy(() -> decide(engine, "2025-12-31")).isInstanceOf(DecisionNotFoundException.class);
    }

    @Test
    void anExplicitEffectiveToEqualToTheNextStartIsStillValid() {
        RuleStoneEngine engine = RuleStone.builder()
                .decision(version("1", "2026-01-01", "2027-03-01"))
                .decision(version("2", "2027-03-01", null))
                .build();

        assertThat(decide(engine, "2027-02-28")).isEqualTo("V1");
        assertThat(decide(engine, "2027-03-01")).isEqualTo("V2");
    }

    @Test
    void anEffectiveToAfterTheNextStartContradictsIt() {
        assertThatThrownBy(() -> RuleStone.builder()
                .decision(version("1", "2026-01-01", "2027-06-01"))
                .decision(version("2", "2027-03-01", null))
                .build())
                .isInstanceOf(InvalidDecisionException.class)
                .satisfies(e -> assertThat(((InvalidDecisionException) e).problems()).containsExactly(
                        "consent_request@1 and consent_request@2: consent_request@1 is in force until 2027-06-01 "
                                + "(effectiveTo), after consent_request@2 takes effect on 2027-03-01; "
                                + "remove effectiveTo from consent_request@1 or set it to 2027-03-01"));
    }

    @Test
    void versionsThatStartTogetherAreAmbiguous() {
        assertThat(DecisionVersions.problems(List.of(version("1", "2026-01-01", null), version("2", "2026-01-01", null))))
                .containsExactly("consent_request@1 and consent_request@2: both take effect on 2026-01-01, so neither "
                        + "supersedes the other; give the later version a later effectiveFrom");
        assertThat(DecisionVersions.problems(List.of(version("1", null, null), version("2", null, null))))
                .containsExactly("consent_request@1 and consent_request@2: both take effect from the beginning "
                        + "(no effectiveFrom), so neither supersedes the other; give the later version a later effectiveFrom");
        assertThat(DecisionVersions.problems(List.of(version("1", "2026-01-01", null), version("1", "2026-01-01", null))))
                .containsExactly("consent_request@1: version registered more than once");
    }

    @Test
    void selectionDoesNotDependOnRegistrationOrder() {
        List<DecisionTable> versions = List.of(
                version("3", "2028-01-01", null), version("1", "2026-01-01", null), version("2", "2027-01-01", null));

        assertThat(DecisionVersions.problems(versions)).isEmpty();
        assertThat(DecisionVersions.inForce(versions, LocalDate.of(2027, 12, 31)).map(DecisionTable::version))
                .contains("2");
        assertThat(DecisionVersions.select("consent_request", versions, Optional.of("3"), LocalDate.of(2000, 1, 1))
                .version()).isEqualTo("3");
        assertThatThrownBy(() -> DecisionVersions.select("consent_request", versions, Optional.of("9"), LocalDate.MAX))
                .isInstanceOf(DecisionNotFoundException.class)
                .hasMessage("decision 'consent_request' has no version '9'");
    }
}
