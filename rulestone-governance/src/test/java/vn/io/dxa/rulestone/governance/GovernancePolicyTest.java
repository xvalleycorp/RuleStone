//
// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.rulestone.governance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import vn.io.dxa.rulestone.api.ArtifactKind;
import vn.io.dxa.rulestone.governance.GovernancePolicy.Tier;

/** Approval policies and tiers (design section 3.2), and their JSON form. */
class GovernancePolicyTest {

    private static final String POLICY_JSON = """
            {
              "version": 3,
              "tiers": {
                "standard": { "approvals": 1, "roles": ["RULE_CHECKER"] },
                "credit-policy": { "approvals": 2, "roles": ["CREDIT_RISK", "COMPLIANCE"], "distinctUnits": true },
                "aml-thresholds": { "approvals": 2, "roles": ["AML_OFFICER", "COMPLIANCE"] }
              },
              "assign": [
                { "kind": "params", "id": "retail_credit", "tier": "credit-policy" },
                { "kind": "table", "id": "aml_*", "tier": "aml-thresholds" },
                { "kind": "*", "id": "*", "tier": "standard" }
              ],
              "roles": {
                "maker": ["RULE_MAKER"], "emergency": ["EMERGENCY_APPROVER"],
                "release": ["RELEASE_MANAGER"], "administrator": ["GOVERNANCE_ADMIN"]
              },
              "emergency": { "postApprovalBusinessDays": 1, "zone": "Asia/Ho_Chi_Minh" }
            }
            """;

    private static ChangeSet.Approval approval(String principal, String role, String unit) {
        return new ChangeSet.Approval(principal, role, Optional.ofNullable(unit), Instant.parse("2026-11-20T03:00:00Z"),
                Optional.empty(), false);
    }

    @Test
    void theFirstMatchingAssignmentGivesTheTier() {
        GovernancePolicy policy = GovernancePolicyJson.read(POLICY_JSON);
        assertThat(policy.tierFor(ArtifactKind.PARAMS, "retail_credit").name()).isEqualTo("credit-policy");
        assertThat(policy.tierFor(ArtifactKind.TABLE, "aml_screening").name()).isEqualTo("aml-thresholds");
        assertThat(policy.tierFor(ArtifactKind.TABLE, "aml_").name()).isEqualTo("aml-thresholds");
        assertThat(policy.tierFor(ArtifactKind.FLOW, "aml_screening").name()).isEqualTo("standard");
        assertThat(policy.tierFor(ArtifactKind.PARAMS, "retail_credit_2").name()).isEqualTo("standard");
        assertThat(policy.tierFor(ArtifactKind.CATALOG, "reason-codes").name()).isEqualTo("standard");
    }

    @Test
    void anArtifactWithoutATierIsAPolicyError() {
        GovernancePolicy policy = GovernancePolicy.builder().tier(Tier.of("standard", 1, "RULE_CHECKER"))
                .assign(ArtifactKind.TABLE, "loan_*", "standard").build();
        assertThat(policy.tierFor(ArtifactKind.TABLE, "loan_eligibility").name()).isEqualTo("standard");
        assertThatThrownBy(() -> policy.tierFor(ArtifactKind.PARAMS, "loan_limits"))
                .isInstanceOf(GovernanceException.class).hasMessage("no tier is assigned to params loan_limits")
                .extracting("code").isEqualTo(GovernanceException.Code.INVALID_POLICY);
    }

    @Test
    void theStrictestTierNeedsTheMostApprovals() {
        Tier standard = Tier.of("standard", 1, "RULE_CHECKER");
        Tier credit = Tier.of("credit-policy", 2, "CREDIT_RISK", "COMPLIANCE").withDistinctUnits();
        assertThat(GovernancePolicy.strictest(List.of(standard))).isEqualTo(standard);
        // A catalog message changed together with a credit parameter is approved by the credit approvers alone.
        assertThat(GovernancePolicy.strictest(List.of(standard, credit))).isEqualTo(credit);
    }

    @Test
    void tiersThatNeedAsManyApprovalsAreCombined() {
        Tier credit = Tier.of("credit-policy", 2, "CREDIT_RISK", "COMPLIANCE").withDistinctUnits();
        Tier aml = Tier.of("aml-thresholds", 2, "AML_OFFICER", "COMPLIANCE");
        Tier both = GovernancePolicy.strictest(List.of(credit, aml));
        assertThat(both.name()).isEqualTo("aml-thresholds+credit-policy");
        assertThat(both.roles()).containsExactlyInAnyOrder("AML_OFFICER", "COMPLIANCE", "CREDIT_RISK");
        assertThat(both.approvals()).isEqualTo(3);
        assertThat(both.distinctUnits()).isTrue();

        Tier same = GovernancePolicy.strictest(List.of(Tier.of("a", 2, "X", "Y"), Tier.of("b", 2, "X", "Y")));
        assertThat(same.approvals()).isEqualTo(2);
        assertThat(same.roles()).containsExactlyInAnyOrder("X", "Y");
    }

    @Test
    void aTierNeedsEnoughApproversInEveryRole() {
        Tier credit = Tier.of("credit-policy", 2, "CREDIT_RISK", "COMPLIANCE");
        assertThat(credit.unmet(List.of())).contains("tier 'credit-policy' has 0 of 2 approvals, missing roles [CREDIT_RISK, COMPLIANCE]");
        assertThat(credit.unmet(List.of(approval("u:a", "CREDIT_RISK", null))))
                .contains("tier 'credit-policy' has 1 of 2 approvals, missing roles [COMPLIANCE]");
        // Two approvals in one role are not enough: the other role is still missing.
        assertThat(credit.unmet(List.of(approval("u:a", "CREDIT_RISK", null), approval("u:b", "CREDIT_RISK", null))))
                .contains("tier 'credit-policy' has 2 of 2 approvals, missing roles [COMPLIANCE]");
        assertThat(credit.unmet(List.of(approval("u:a", "CREDIT_RISK", null), approval("u:b", "COMPLIANCE", null)))).isEmpty();
        // An approval in a role the tier does not list does not count.
        assertThat(credit.unmet(List.of(approval("u:a", "CREDIT_RISK", null), approval("u:b", "RULE_CHECKER", null)))).isPresent();

        Tier three = new Tier("three", 3, List.of("X"), false);
        assertThat(three.unmet(List.of(approval("u:a", "X", null), approval("u:b", "X", null)))).isPresent();
        assertThat(three.unmet(List.of(approval("u:a", "X", null), approval("u:b", "X", null), approval("u:c", "X", null)))).isEmpty();
    }

    @Test
    void distinctUnitsCountUnitsNotPeople() {
        Tier credit = Tier.of("credit-policy", 2, "CREDIT_RISK", "COMPLIANCE").withDistinctUnits();
        assertThat(credit.unmet(List.of(approval("u:a", "CREDIT_RISK", "risk"), approval("u:b", "COMPLIANCE", "risk"))))
                .contains("tier 'credit-policy' has 1 of 2 approvals from different units");
        assertThat(credit.unmet(List.of(approval("u:a", "CREDIT_RISK", "risk"), approval("u:b", "COMPLIANCE", null)))).isPresent();
        assertThat(credit.unmet(List.of(approval("u:a", "CREDIT_RISK", "risk"), approval("u:b", "COMPLIANCE", "legal")))).isEmpty();
    }

    @Test
    void invalidTiersAndAssignmentsAreRefused() {
        assertThatThrownBy(() -> Tier.of("t", 1, "A", "B")).hasMessageContaining("at least 2 approvals, one for each of its roles");
        assertThatThrownBy(() -> Tier.of("t", 1)).hasMessageContaining("at least one role");
        assertThatThrownBy(() -> Tier.of("t", 2, "A", "A")).hasMessageContaining("each named once");
        assertThatThrownBy(() -> Tier.of(" ", 1, "A")).hasMessageContaining("needs a name");
        assertThatThrownBy(() -> GovernancePolicy.builder().tier(Tier.of("t", 1, "A")).assign("*", "*", "missing").build())
                .hasMessageContaining("unknown tier 'missing'");
        assertThatThrownBy(() -> GovernancePolicy.builder().tier(Tier.of("t", 1, "A")).assign("rule", "*", "t").build())
                .hasMessageContaining("unknown kind 'rule'");
        assertThatThrownBy(() -> GovernancePolicy.builder().build()).hasMessageContaining("at least one tier");
        assertThatThrownBy(() -> GovernancePolicy.builder().tier(Tier.of("t", 1, "A")).tier(Tier.of("t", 2, "A")))
                .hasMessageContaining("defined twice");
        assertThatThrownBy(() -> GovernancePolicy.builder().tier(Tier.of("t", 1, "A")).version(0).build())
                .isInstanceOf(GovernanceException.class);
    }

    @Test
    void thePolicyReadsFromJsonAndWritesCanonically() {
        GovernancePolicy policy = GovernancePolicyJson.read(POLICY_JSON);
        assertThat(policy.version()).isEqualTo(3);
        assertThat(policy.tiers()).containsOnlyKeys("standard", "credit-policy", "aml-thresholds");
        assertThat(policy.makerRoles()).containsExactly("RULE_MAKER");
        assertThat(policy.zone()).isEqualTo(ZoneId.of("Asia/Ho_Chi_Minh"));
        assertThat(policy.ref("credit-policy")).isEqualTo("credit-policy@3");

        String canonical = GovernancePolicyJson.write(policy);
        assertThat(canonical).startsWith("{\"assign\":[{\"id\":\"retail_credit\",\"kind\":\"params\",\"tier\":\"credit-policy\"}")
                .doesNotContain(" ").doesNotContain("\n");
        assertThat(GovernancePolicyJson.read(canonical)).isEqualTo(policy);
        assertThat(GovernancePolicyJson.write(GovernancePolicyJson.read(canonical))).isEqualTo(canonical);
    }

    @Test
    void theHashChangesWithAnyChangeAndNotWithTheTextualForm() {
        GovernancePolicy policy = GovernancePolicyJson.read(POLICY_JSON);
        GovernancePolicy reordered = GovernancePolicyJson.read(POLICY_JSON.replace(
                "\"standard\": { \"approvals\": 1, \"roles\": [\"RULE_CHECKER\"] },\n", "")
                .replace("\"tiers\": {", "\"tiers\": {\"standard\": { \"roles\": [\"RULE_CHECKER\"], \"approvals\": 1 },"));
        assertThat(reordered.hash()).isEqualTo(policy.hash());
        assertThat(policy.hash()).matches("sha256:[0-9a-f]{64}");
        assertThat(GovernancePolicyJson.read(POLICY_JSON.replace("\"approvals\": 1", "\"approvals\": 2").replace(
                "[\"RULE_CHECKER\"] }", "[\"RULE_CHECKER\", \"OTHER\"] }")).hash()).isNotEqualTo(policy.hash());
        assertThat(policy.withVersion(4).hash()).isNotEqualTo(policy.hash());
    }

    @Test
    void theJsonIsReadStrictly() {
        assertThatThrownBy(() -> GovernancePolicyJson.read("{}")).hasMessageContaining("$.tiers");
        assertThatThrownBy(() -> GovernancePolicyJson.read("{\"tiers\":{},\"assign\":[]}")).hasMessageContaining("at least one tier");
        assertThatThrownBy(() -> GovernancePolicyJson.read(POLICY_JSON.replace("\"version\": 3", "\"versions\": 3")))
                .hasMessage("$.versions: unknown key");
        assertThatThrownBy(() -> GovernancePolicyJson.read(POLICY_JSON.replace("\"approvals\": 1", "\"aproovals\": 1")))
                .hasMessage("$.tiers.standard.aproovals: unknown key");
        assertThatThrownBy(() -> GovernancePolicyJson.read(POLICY_JSON.replace("Asia/Ho_Chi_Minh", "Mars/Olympus")))
                .hasMessage("$.emergency.zone: not a time zone");
        assertThatThrownBy(() -> GovernancePolicyJson.read("{")).isInstanceOf(GovernanceException.class);
        assertThatThrownBy(() -> GovernancePolicyJson.read(POLICY_JSON.replace("\"distinctUnits\": true", "\"distinctUnits\": 1")))
                .hasMessage("$.tiers.credit-policy.distinctUnits: expected a boolean");
    }

    @Test
    void thePostApprovalIsDueAtTheEndOfTheNextBusinessDay() {
        GovernancePolicy utc = GovernancePolicy.builder().tier(Tier.of("t", 1, "A")).assign("*", "*", "t").build();
        // 2026-11-19 is a Thursday, 2026-11-20 a Friday.
        assertThat(utc.postApprovalDue(Instant.parse("2026-11-19T10:00:00Z"))).isEqualTo(Instant.parse("2026-11-20T23:59:59Z"));
        assertThat(utc.postApprovalDue(Instant.parse("2026-11-20T03:00:00Z"))).isEqualTo(Instant.parse("2026-11-23T23:59:59Z"));
        assertThat(utc.postApprovalDue(Instant.parse("2026-11-21T10:00:00Z"))).isEqualTo(Instant.parse("2026-11-23T23:59:59Z"));

        GovernancePolicy hanoi = GovernancePolicy.builder().tier(Tier.of("t", 1, "A")).assign("*", "*", "t")
                .postApprovalDue(2, ZoneId.of("Asia/Ho_Chi_Minh")).build();
        // 22:00 on Thursday in Vietnam is 15:00 UTC; two business days later is Monday, 23:59:59 at UTC+7.
        assertThat(hanoi.postApprovalDue(Instant.parse("2026-11-19T15:00:00Z"))).isEqualTo(Instant.parse("2026-11-23T16:59:59Z"));
        // 22:00 on Friday in Vietnam is still Friday there, although it is 15:00 on Friday UTC.
        assertThat(hanoi.postApprovalDue(Instant.parse("2026-11-20T15:00:00Z"))).isEqualTo(Instant.parse("2026-11-24T16:59:59Z"));
    }
}
