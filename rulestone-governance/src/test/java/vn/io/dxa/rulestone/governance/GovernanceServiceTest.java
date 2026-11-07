//
// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.rulestone.governance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static vn.io.dxa.rulestone.governance.GovernanceFixture.ADMIN;
import static vn.io.dxa.rulestone.governance.GovernanceFixture.AI;
import static vn.io.dxa.rulestone.governance.GovernanceFixture.CHECKER;
import static vn.io.dxa.rulestone.governance.GovernanceFixture.CHECKER_2;
import static vn.io.dxa.rulestone.governance.GovernanceFixture.COMPLIANCE;
import static vn.io.dxa.rulestone.governance.GovernanceFixture.COMPLIANCE_IN_CREDIT_UNIT;
import static vn.io.dxa.rulestone.governance.GovernanceFixture.COMPLIANCE_NO_UNIT;
import static vn.io.dxa.rulestone.governance.GovernanceFixture.CREDIT;
import static vn.io.dxa.rulestone.governance.GovernanceFixture.CREDIT_2;
import static vn.io.dxa.rulestone.governance.GovernanceFixture.EMERGENCY;
import static vn.io.dxa.rulestone.governance.GovernanceFixture.MAKER;
import static vn.io.dxa.rulestone.governance.GovernanceFixture.MAKER_2;
import static vn.io.dxa.rulestone.governance.GovernanceFixture.RELEASER;
import static vn.io.dxa.rulestone.governance.GovernanceFixture.TARGET;
import static vn.io.dxa.rulestone.governance.GovernanceFixture.creditParams;
import static vn.io.dxa.rulestone.governance.GovernanceFixture.gate;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;
import vn.io.dxa.rulestone.api.ArtifactKind;
import vn.io.dxa.rulestone.core.ApprovalStatement;
import vn.io.dxa.rulestone.governance.GovernanceException.Code;

/** Lifecycle, change sets and maker-checker (design sections 2 and 3). */
class GovernanceServiceTest {

    private final GovernanceFixture f = new GovernanceFixture();

    private static void refused(ThrowingCallable call, Code code) {
        assertThatThrownBy(call).isInstanceOfSatisfying(GovernanceException.class, e -> assertThat(e.code()).isEqualTo(code));
    }

    private List<EventType> events() {
        return f.store.log().stream().map(LogEntry::type).toList();
    }

    private ApprovalStatement statementOf(String changeSet) {
        return f.store.approvals().stream().filter(a -> a.statement().changeSet().equals(changeSet)).findFirst().orElseThrow()
                .statement();
    }

    // ---------------------------------------------------------------------------------------------

    @Test
    void aChangeGoesFromDraftToActiveAndTheStatementTiesToTheLog() {
        f.as(MAKER).service.createChangeSet("CHG-1", "Raise the gate");
        assertThat(f.service.changeSet("CHG-1").state()).isEqualTo(LifecycleState.DRAFT);
        f.service.saveDraft("CHG-1", ArtifactKind.TABLE, gate("1", "2026-01-01", 70).json());
        assertThat(f.service.stateOf(ArtifactKind.TABLE, "gate_decision", "1")).contains(LifecycleState.DRAFT);
        f.service.submit("CHG-1");
        assertThat(f.service.stateOf(ArtifactKind.TABLE, "gate_decision", "1")).contains(LifecycleState.SUBMITTED);
        f.as(CHECKER).service.approve("CHG-1", "RULE_CHECKER", "Matches the minutes");
        assertThat(f.service.stateOf(ArtifactKind.TABLE, "gate_decision", "1")).contains(LifecycleState.APPROVED);
        f.as(RELEASER).service.activate("CHG-1", TARGET);
        assertThat(f.service.stateOf(ArtifactKind.TABLE, "gate_decision", "1")).contains(LifecycleState.ACTIVE);

        assertThat(events()).containsExactly(EventType.POLICY_CHANGED, EventType.DRAFT_CREATED, EventType.DRAFT_SAVED,
                EventType.SUBMITTED, EventType.APPROVED, EventType.APPROVAL_ISSUED, EventType.RELEASED, EventType.ACTIVATED);

        ApprovalStatement statement = statementOf("CHG-1");
        assertThat(statement.makers()).containsExactly("u:nguyen.van.a");
        assertThat(statement.approvers()).singleElement().satisfies(a -> {
            assertThat(a.principal()).isEqualTo("u:tran.thi.b");
            assertThat(a.role()).contains("RULE_CHECKER");
            assertThat(a.at()).isEqualTo("2026-11-20T03:00:00Z");
        });
        assertThat(statement.policy()).contains("standard@1");
        assertThat(statement.emergency()).isFalse();
        assertThat(statement.issuer()).isEqualTo("rulestone-governance@bank.example");
        assertThat(statement.issuedAt()).isEqualTo("2026-11-20T03:00:00Z");
        LogEntry issued = f.store.log().get(5);
        assertThat(issued.type()).isEqualTo(EventType.APPROVAL_ISSUED);
        assertThat(statement.logEntry()).contains(issued.hash());
        assertThat(issued.subjects()).isEqualTo(statement.subjects());
        assertThat(issued.details()).containsEntry("approvalId", statement.approvalId());
        assertThat(f.verifier.verify(f.store.approvals().get(0).jws())).isEqualTo(statement);
        assertThat(f.store.approvals().get(0).name()).isEqualTo("CHG-1.approval.jws");
    }

    @Test
    void theLogHoldsIdsAndHashesNotRuleContent() {
        f.activated("CHG-1", gate("1", "2026-01-01", 70));
        for (LogEntry entry : f.store.log()) {
            String json = LogEntryJson.write(entry);
            assertThat(json).doesNotContain("score").doesNotContain("BLOCK").doesNotContain("hitPolicy");
        }
    }

    @Test
    void everyoneWhoSavedOrSubmittedIsAMakerAndCannotApprove() {
        Principal dual = Principal.of("u:dual", "RULE_MAKER", "RULE_CHECKER");
        Principal dual2 = Principal.of("u:dual.two", "RULE_MAKER", "RULE_CHECKER");
        f.as(MAKER).service.createChangeSet("CHG-1", "Change");
        f.service.saveDraft("CHG-1", ArtifactKind.TABLE, gate("1", "2026-01-01", 70).json());
        f.as(dual).service.saveDraft("CHG-1", ArtifactKind.TABLE, gate("1", "2026-01-01", 75).json());
        // dual2 only submits: A wrote, B submitted, and neither may approve.
        f.as(dual2).service.submit("CHG-1");

        assertThat(f.service.changeSet("CHG-1").makers()).containsExactly("u:nguyen.van.a", "u:dual", "u:dual.two");
        for (Principal maker : List.of(dual, dual2)) {
            refused(() -> f.as(maker).service.approve("CHG-1", "RULE_CHECKER", null), Code.FOUR_EYES);
            refused(() -> f.as(maker).service.reject("CHG-1", "No"), Code.FOUR_EYES);
        }
        assertThat(f.service.changeSet("CHG-1").state()).isEqualTo(LifecycleState.SUBMITTED);

        f.as(CHECKER).service.approve("CHG-1", "RULE_CHECKER", null);
        assertThat(statementOf("CHG-1").makers()).containsExactly("u:nguyen.van.a", "u:dual", "u:dual.two");
    }

    @Test
    void aMakerCannotApproveTheirOwnChangeSet() {
        Principal both = Principal.of("u:both", "RULE_MAKER", "RULE_CHECKER");
        f.as(both).service.createChangeSet("CHG-1", "Mine");
        f.service.saveDraft("CHG-1", ArtifactKind.TABLE, gate("1", "2026-01-01", 70).json());
        f.service.submit("CHG-1");
        assertThatThrownBy(() -> f.service.approve("CHG-1", "RULE_CHECKER", null)).isInstanceOf(GovernanceException.class)
                .hasMessage("u:both is a maker of change set CHG-1 and cannot check it");
        assertThat(f.store.approvals()).isEmpty();
    }

    @Test
    void anAiPrincipalMakesAndNeverChecksAndItsRequesterIsAMakerToo() {
        Principal requester = Principal.of("u:nguyen.van.a", "RULE_MAKER", "RULE_CHECKER");
        f.as(AI.actingFor("u:nguyen.van.a")).service.createChangeSet("CHG-1", "Drafted by the assistant");
        f.service.saveDraft("CHG-1", ArtifactKind.TABLE, gate("1", "2026-01-01", 70).json());
        f.service.submit("CHG-1");

        assertThat(f.service.changeSet("CHG-1").makers()).containsExactly("ai:assistant", "u:nguyen.van.a");
        refused(() -> f.as(AI).service.approve("CHG-1", "RULE_CHECKER", null), Code.FORBIDDEN);
        refused(() -> f.as(AI).service.reject("CHG-1", "No"), Code.FORBIDDEN);
        refused(() -> f.as(requester).service.approve("CHG-1", "RULE_CHECKER", null), Code.FOUR_EYES);
        assertThat(f.store.log()).filteredOn(e -> e.type() == EventType.DRAFT_SAVED).singleElement()
                .satisfies(e -> assertThat(e.details()).containsEntry("onBehalfOf", "u:nguyen.van.a"));
        f.as(CHECKER).service.approve("CHG-1", "RULE_CHECKER", null);
        assertThat(statementOf("CHG-1").makers()).containsExactly("ai:assistant", "u:nguyen.van.a");
        refused(() -> f.as(AI).service.activate("CHG-1", TARGET), Code.FORBIDDEN);
    }

    @Test
    void aGovernanceAdministratorCannotApproveRuleChanges() {
        Principal admin = Principal.of("u:admin.checker", "GOVERNANCE_ADMIN", "RULE_CHECKER");
        f.submit("CHG-1", gate("1", "2026-01-01", 70));
        refused(() -> f.as(admin).service.approve("CHG-1", "RULE_CHECKER", null), Code.FORBIDDEN);
        refused(() -> f.as(ADMIN).service.reject("CHG-1", "No"), Code.FORBIDDEN);
    }

    @Test
    void callersMustBeAuthenticatedAndHoldTheMakerRole() {
        f.who.clear();
        refused(() -> f.service.createChangeSet("CHG-1", "x"), Code.UNAUTHENTICATED);
        refused(() -> f.as(CHECKER).service.createChangeSet("CHG-1", "x"), Code.FORBIDDEN);
        f.as(MAKER).service.createChangeSet("CHG-1", "x");
        refused(() -> f.as(CHECKER).service.saveDraft("CHG-1", ArtifactKind.TABLE, gate("1", "2026-01-01", 70).json()), Code.FORBIDDEN);
        refused(() -> f.as(CHECKER).service.submit("CHG-1"), Code.FORBIDDEN);
        assertThat(f.service.changeSet("CHG-1").items()).isEmpty();
    }

    @Test
    void anEditAfterSubmissionReturnsToDraftAndVoidsTheApprovals() {
        f.submit("CHG-1", creditParams("1", "2026-01-01", 18));
        f.as(CREDIT).service.approve("CHG-1", "CREDIT_RISK", null);
        assertThat(f.service.changeSet("CHG-1").approvals()).hasSize(1);

        f.as(MAKER_2).service.saveDraft("CHG-1", ArtifactKind.PARAMS, creditParams("1", "2026-01-01", 21).json());
        ChangeSet cs = f.service.changeSet("CHG-1");
        assertThat(cs.state()).isEqualTo(LifecycleState.DRAFT);
        assertThat(cs.approvals()).isEmpty();
        assertThat(cs.makers()).containsExactly("u:nguyen.van.a", "u:pham.van.d");
        assertThat(cs.items()).singleElement().satisfies(i -> assertThat(i.state()).isEqualTo(LifecycleState.DRAFT));
        assertThat(f.store.log()).filteredOn(e -> e.type() == EventType.DRAFT_SAVED).last()
                .satisfies(e -> assertThat(e.details()).containsEntry("voidedApprovals", 1L));
        refused(() -> f.as(COMPLIANCE).service.approve("CHG-1", "COMPLIANCE", null), Code.ILLEGAL_STATE);

        f.as(MAKER).service.submit("CHG-1");
        assertThat(f.service.changeSet("CHG-1").submission()).isEqualTo(2);
        f.as(CREDIT).service.approve("CHG-1", "CREDIT_RISK", null);
        f.as(COMPLIANCE).service.approve("CHG-1", "COMPLIANCE", null);
        assertThat(f.service.changeSet("CHG-1").state()).isEqualTo(LifecycleState.APPROVED);
    }

    @Test
    void aMakerCanWithdrawBeforeADecision() {
        f.submit("CHG-1", gate("1", "2026-01-01", 70));
        refused(() -> f.as(CHECKER).service.withdraw("CHG-1", "Changed my mind"), Code.FORBIDDEN);
        refused(() -> f.as(MAKER).service.withdraw("CHG-1", " "), Code.INVALID_REQUEST);
        f.service.withdraw("CHG-1", "Wrong threshold");
        assertThat(f.service.changeSet("CHG-1").state()).isEqualTo(LifecycleState.DRAFT);
        assertThat(f.store.log().get(f.store.log().size() - 1)).satisfies(e -> {
            assertThat(e.type()).isEqualTo(EventType.WITHDRAWN);
            assertThat(e.details()).containsEntry("reason", "Wrong threshold");
        });
        refused(() -> f.service.withdraw("CHG-1", "Again"), Code.ILLEGAL_STATE);
        f.service.submit("CHG-1");
        assertThat(f.service.changeSet("CHG-1").submission()).isEqualTo(2);
    }

    @Test
    void aRejectionNeedsAReasonAndAChecker() {
        f.submit("CHG-1", gate("1", "2026-01-01", 70));
        refused(() -> f.as(CHECKER).service.reject("CHG-1", ""), Code.INVALID_REQUEST);
        refused(() -> f.as(MAKER).service.reject("CHG-1", "Mine"), Code.FOUR_EYES);
        refused(() -> f.as(RELEASER).service.reject("CHG-1", "Not a checker"), Code.FORBIDDEN);

        f.as(CHECKER).service.reject("CHG-1", "The threshold contradicts the committee minutes");
        ChangeSet cs = f.service.changeSet("CHG-1");
        assertThat(cs.state()).isEqualTo(LifecycleState.REJECTED);
        assertThat(cs.rejection()).contains("The threshold contradicts the committee minutes");
        assertThat(f.store.log().get(f.store.log().size() - 1)).satisfies(e -> {
            assertThat(e.type()).isEqualTo(EventType.REJECTED);
            assertThat(e.actor().principal()).isEqualTo("u:tran.thi.b");
            assertThat(e.details()).containsEntry("reason", "The threshold contradicts the committee minutes");
        });
        assertThat(f.store.approvals()).isEmpty();
        refused(() -> f.as(CHECKER_2).service.approve("CHG-1", "RULE_CHECKER", null), Code.ILLEGAL_STATE);
        refused(() -> f.as(MAKER).service.submit("CHG-1"), Code.ILLEGAL_STATE);
    }

    @Test
    void aRejectedLabelCanBeResubmittedWithOtherContentAndApprovalsDoNotCarryOver() {
        f.submit("CHG-1", gate("1", "2026-01-01", 70));
        f.as(CHECKER).service.reject("CHG-1", "Threshold too high");
        String rejectedHash = f.service.changeSet("CHG-1").items().get(0).exactVersion();

        f.as(MAKER).service.saveDraft("CHG-1", ArtifactKind.TABLE, gate("1", "2026-01-01", 60).json());
        assertThat(f.service.changeSet("CHG-1").state()).isEqualTo(LifecycleState.DRAFT);
        assertThat(f.service.changeSet("CHG-1").rejection()).isEmpty();
        f.service.submit("CHG-1");
        f.as(CHECKER_2).service.approve("CHG-1", "RULE_CHECKER", null);

        String approvedHash = statementOf("CHG-1").subjects().get(0).version();
        assertThat(approvedHash).isNotEqualTo(rejectedHash).startsWith("1@sha256:");
        assertThat(f.store.binding(ArtifactKind.TABLE, "gate_decision", "1").orElseThrow().exactVersion()).isEqualTo(approvedHash);
        assertThat(events()).containsSubsequence(EventType.SUBMITTED, EventType.REJECTED, EventType.DRAFT_SAVED,
                EventType.SUBMITTED, EventType.APPROVED, EventType.APPROVAL_ISSUED);
    }

    @Test
    void anApprovedLabelIsBoundToItsHashForever() {
        f.approved("CHG-1", gate("1", "2026-01-01", 70));
        String bound = f.store.binding(ArtifactKind.TABLE, "gate_decision", "1").orElseThrow().exactVersion();

        // Not in the approved change set, and not in another one, with other content or even with the same.
        refused(() -> f.as(MAKER).service.saveDraft("CHG-1", ArtifactKind.TABLE, gate("1", "2026-01-01", 99).json()), Code.ILLEGAL_STATE);
        f.service.createChangeSet("CHG-2", "Second");
        for (int threshold : new int[] {99, 70}) {
            assertThatThrownBy(() -> f.service.saveDraft("CHG-2", ArtifactKind.TABLE, gate("1", "2026-01-01", threshold).json()))
                    .isInstanceOfSatisfying(GovernanceException.class, e -> {
                        assertThat(e.code()).isEqualTo(Code.LABEL_BOUND);
                        assertThat(e.getMessage()).contains("table gate_decision@1 is bound to " + bound)
                                .contains("change set CHG-1").doesNotContain("score");
                    });
        }
        f.service.saveDraft("CHG-2", ArtifactKind.TABLE, gate("2", "2027-01-01", 60).json());
        assertThat(f.store.binding(ArtifactKind.TABLE, "gate_decision", "1").orElseThrow().exactVersion()).isEqualTo(bound);
    }

    @Test
    void aLabelThatTwoChangeSetsDraftIsBoundToTheFirstApproved() {
        f.submit("CHG-A", gate("5", "2027-01-01", 70));
        f.submit("CHG-B", gate("5", "2027-01-01", 60));
        f.as(CHECKER).service.approve("CHG-A", "RULE_CHECKER", null);
        refused(() -> f.as(CHECKER).service.approve("CHG-B", "RULE_CHECKER", null), Code.LABEL_BOUND);
        assertThat(f.service.changeSet("CHG-B").state()).isEqualTo(LifecycleState.SUBMITTED);
        // The second change set must use a new label.
        f.as(MAKER_2).service.removeDraft("CHG-B", ArtifactKind.TABLE, "gate_decision", "5");
        assertThat(f.service.changeSet("CHG-B").state()).isEqualTo(LifecycleState.DRAFT);
        assertThat(f.service.changeSet("CHG-B").items()).isEmpty();
        assertThat(f.service.changeSet("CHG-B").makers()).containsExactly("u:nguyen.van.a", "u:pham.van.d");
        f.service.saveDraft("CHG-B", ArtifactKind.TABLE, gate("6", "2027-01-01", 60).json());
        refused(() -> f.service.removeDraft("CHG-B", ArtifactKind.TABLE, "gate_decision", "5"), Code.NOT_FOUND);
        f.service.submit("CHG-B");
        f.as(CHECKER).service.approve("CHG-B", "RULE_CHECKER", null);
        assertThat(f.service.changeSet("CHG-B").state()).isEqualTo(LifecycleState.APPROVED);
    }

    @Test
    void operationsOutOfOrderAreRefused() {
        refused(() -> f.as(MAKER).service.submit("CHG-X"), Code.NOT_FOUND);
        f.service.createChangeSet("CHG-1", "x");
        refused(() -> f.service.createChangeSet("CHG-1", "again"), Code.ALREADY_EXISTS);
        refused(() -> f.service.createChangeSet("bad id", "x"), Code.INVALID_REQUEST);
        refused(() -> f.service.createChangeSet("CHG-2", " "), Code.INVALID_REQUEST);
        refused(() -> f.service.submit("CHG-1"), Code.ILLEGAL_STATE);
        refused(() -> f.as(CHECKER).service.approve("CHG-1", "RULE_CHECKER", null), Code.ILLEGAL_STATE);
        refused(() -> f.service.reject("CHG-1", "No"), Code.ILLEGAL_STATE);
        refused(() -> f.as(RELEASER).service.activate("CHG-1", TARGET), Code.ILLEGAL_STATE);

        f.as(MAKER).service.saveDraft("CHG-1", ArtifactKind.TABLE, gate("1", "2026-01-01", 70).json());
        f.service.submit("CHG-1");
        refused(() -> f.service.submit("CHG-1"), Code.ILLEGAL_STATE);
        refused(() -> f.as(RELEASER).service.activate("CHG-1", TARGET), Code.ILLEGAL_STATE);

        f.as(CHECKER).service.approve("CHG-1", "RULE_CHECKER", null);
        refused(() -> f.as(CHECKER_2).service.approve("CHG-1", "RULE_CHECKER", null), Code.ILLEGAL_STATE);
        refused(() -> f.as(MAKER).service.withdraw("CHG-1", "Too late"), Code.ILLEGAL_STATE);
        refused(() -> f.service.submit("CHG-1"), Code.ILLEGAL_STATE);
        refused(() -> f.as(RELEASER).service.activate("CHG-1", "bad target!"), Code.INVALID_REQUEST);
        refused(() -> f.service.retireSuperseded(TARGET), Code.NOT_FOUND);
    }

    @Test
    void aChangeSetNeedsItemsAndValidContentWithTheExpectedId() {
        f.as(MAKER).service.createChangeSet("CHG-1", "x");
        refused(() -> f.service.submit("CHG-1"), Code.ILLEGAL_STATE);
        assertThatThrownBy(() -> f.service.saveDraft("CHG-1", ArtifactKind.TABLE, "{\"id\": \"x\"}"))
                .isInstanceOfSatisfying(GovernanceException.class, e -> {
                    assertThat(e.code()).isEqualTo(Code.INVALID_CONTENT);
                    assertThat(e.getMessage()).startsWith("the table is not valid: ");
                });
        refused(() -> f.service.saveDraft("CHG-1", ArtifactKind.TABLE, "not json"), Code.INVALID_CONTENT);
        refused(() -> f.service.saveDraft("CHG-1", ArtifactKind.PARAMS, gate("1", "2026-01-01", 70).json()), Code.INVALID_CONTENT);
        refused(() -> f.service.saveDraft("CHG-1", ArtifactKind.TABLE, "other_id", gate("1", "2026-01-01", 70).json()), Code.INVALID_REQUEST);
        assertThat(f.service.changeSet("CHG-1").items()).isEmpty();
        f.service.saveDraft("CHG-1", ArtifactKind.TABLE, "gate_decision", gate("1", "2026-01-01", 70).json());
        assertThat(f.service.changeSet("CHG-1").items()).hasSize(1);
    }

    @Test
    void savingTheSameLabelAgainReplacesTheDraft() {
        f.as(MAKER).service.createChangeSet("CHG-1", "x");
        f.service.saveDraft("CHG-1", ArtifactKind.TABLE, gate("1", "2026-01-01", 70).json());
        f.service.saveDraft("CHG-1", ArtifactKind.TABLE, gate("1", "2026-01-01", 65).json());
        f.service.saveDraft("CHG-1", ArtifactKind.TABLE, gate("2", "2027-01-01", 60).json());
        assertThat(f.service.changeSet("CHG-1").items()).extracting(ChangeSet.Item::label).containsExactly("1", "2");
        assertThat(f.service.changeSet("CHG-1").items().get(0).content()).contains("score >= 65");
        assertThat(events().stream().filter(t -> t == EventType.DRAFT_SAVED).count()).isEqualTo(3);
    }

    // ---------------------------------------------------------------------------------------------

    @Test
    void aTierWithTwoRolesNeedsBothAndNeverTheSamePersonTwice() {
        f.submit("CHG-1", creditParams("1", "2026-01-01", 18));
        f.as(CREDIT).service.approve("CHG-1", "CREDIT_RISK", "Credit committee minutes of 2026-11-18");
        assertThat(f.service.changeSet("CHG-1").state()).isEqualTo(LifecycleState.SUBMITTED);
        refused(() -> f.as(CREDIT).service.approve("CHG-1", "CREDIT_RISK", null), Code.DUPLICATE_APPROVAL);

        // A second approver in the same role does not make up for the missing role.
        f.as(CREDIT_2).service.approve("CHG-1", "CREDIT_RISK", null);
        assertThat(f.service.changeSet("CHG-1").state()).isEqualTo(LifecycleState.SUBMITTED);
        assertThat(f.store.approvals()).isEmpty();

        f.as(COMPLIANCE).service.approve("CHG-1", "COMPLIANCE", null);
        assertThat(f.service.changeSet("CHG-1").state()).isEqualTo(LifecycleState.APPROVED);
        assertThat(statementOf("CHG-1").approvers()).extracting(ApprovalStatement.Approver::principal)
                .containsExactly("u:cr.one", "u:cr.two", "u:co.one");
        assertThat(statementOf("CHG-1").policy()).contains("credit-policy@1");
    }

    @Test
    void anApproverNeedsTheRoleTheyApproveInAndTheTierMustRequireIt() {
        f.submit("CHG-1", creditParams("1", "2026-01-01", 18));
        // A standard checker holds RULE_CHECKER, which the credit tier does not accept.
        assertThatThrownBy(() -> f.as(CHECKER).service.approve("CHG-1", "RULE_CHECKER", null))
                .isInstanceOfSatisfying(GovernanceException.class, e -> {
                    assertThat(e.code()).isEqualTo(Code.ROLE_NOT_REQUIRED);
                    assertThat(e.getMessage()).isEqualTo("role RULE_CHECKER is not required by change set CHG-1: "
                            + "it needs one of [COMPLIANCE, CREDIT_RISK]");
                });
        // Claiming a role they do not hold.
        refused(() -> f.as(CHECKER).service.approve("CHG-1", "CREDIT_RISK", null), Code.FORBIDDEN);
        refused(() -> f.as(CHECKER).service.reject("CHG-1", "I am not credit risk"), Code.FORBIDDEN);

        // And a credit approver cannot approve a standard change.
        f.submit("CHG-2", gate("1", "2026-01-01", 70));
        refused(() -> f.as(CREDIT).service.approve("CHG-2", "CREDIT_RISK", null), Code.ROLE_NOT_REQUIRED);
        assertThat(f.store.approvals()).isEmpty();
    }

    @Test
    void approversOfADistinctUnitsTierComeFromDifferentUnits() {
        f.submit("CHG-1", creditParams("1", "2026-01-01", 18));
        f.as(CREDIT).service.approve("CHG-1", "CREDIT_RISK", null);
        refused(() -> f.as(COMPLIANCE_IN_CREDIT_UNIT).service.approve("CHG-1", "COMPLIANCE", null), Code.UNIT_NOT_DISTINCT);
        refused(() -> f.as(COMPLIANCE_NO_UNIT).service.approve("CHG-1", "COMPLIANCE", null), Code.UNIT_NOT_DISTINCT);
        assertThat(f.service.changeSet("CHG-1").approvals()).hasSize(1);
        f.as(COMPLIANCE).service.approve("CHG-1", "COMPLIANCE", null);
        assertThat(f.service.changeSet("CHG-1").state()).isEqualTo(LifecycleState.APPROVED);
    }

    @Test
    void aChangeSetOfSeveralItemsTakesTheStrictestTier() {
        f.submit("CHG-1", gate("2", "2027-01-01", 60), creditParams("1", "2027-01-01", 18));
        assertThat(f.store.log()).filteredOn(e -> e.type() == EventType.SUBMITTED).singleElement().satisfies(e -> {
            assertThat(e.details()).containsEntry("tier", "credit-policy").containsEntry("requiredApprovals", 2L);
            assertThat(e.details().get("tiers")).isEqualTo(List.of("credit-policy", "standard"));
        });
        refused(() -> f.as(CHECKER).service.approve("CHG-1", "RULE_CHECKER", null), Code.ROLE_NOT_REQUIRED);
        f.as(CREDIT).service.approve("CHG-1", "CREDIT_RISK", null);
        f.as(COMPLIANCE).service.approve("CHG-1", "COMPLIANCE", null);
        ApprovalStatement statement = statementOf("CHG-1");
        assertThat(statement.subjects()).extracting(ApprovalStatement.Subject::kind)
                .containsExactly(ArtifactKind.TABLE, ArtifactKind.PARAMS);
        assertThat(statement.policy()).contains("credit-policy@1");
    }

    // ---------------------------------------------------------------------------------------------

    @Test
    void policyChangesAreLoggedNeedAnAdministratorAndApplyFromThen() {
        GovernancePolicy stricter = GovernancePolicy.builder()
                .tier(GovernancePolicy.Tier.of("standard", 2, "RULE_CHECKER", "COMPLIANCE"))
                .assign("*", "*", "standard")
                .makerRoles("RULE_MAKER").releaseRoles("RELEASE_MANAGER").administratorRoles("GOVERNANCE_ADMIN").build();
        for (Principal outsider : List.of(MAKER, CHECKER, RELEASER, AI, EMERGENCY)) {
            refused(() -> f.as(outsider).service.changePolicy(stricter), Code.FORBIDDEN);
        }
        assertThat(f.service.policy().version()).isEqualTo(1);

        GovernancePolicy stored = f.as(ADMIN).service.changePolicy(stricter);
        assertThat(stored.version()).isEqualTo(2);
        assertThat(f.service.policy()).isEqualTo(stored);
        LogEntry changed = f.store.log().get(f.store.log().size() - 1);
        assertThat(changed.type()).isEqualTo(EventType.POLICY_CHANGED);
        assertThat(changed.actor().principal()).isEqualTo("u:admin.one");
        assertThat(changed.details()).containsEntry("fromVersion", 1L).containsEntry("toVersion", 2L)
                .containsEntry("policyHash", stored.hash());

        f.submit("CHG-1", gate("1", "2026-01-01", 70));
        f.as(CHECKER).service.approve("CHG-1", "RULE_CHECKER", null);
        assertThat(f.service.changeSet("CHG-1").state()).isEqualTo(LifecycleState.SUBMITTED);
        f.as(COMPLIANCE).service.approve("CHG-1", "COMPLIANCE", null);
        assertThat(statementOf("CHG-1").policy()).contains("standard@2");
    }

    @Test
    void theFirstEntryRecordsThePolicyAndAPolicyThatDiffersIsRefusedForAnExistingStore() {
        LogEntry first = f.store.log().get(0);
        assertThat(first.type()).isEqualTo(EventType.POLICY_CHANGED);
        assertThat(first.seq()).isEqualTo(1);
        assertThat(first.prev()).isEqualTo(LogEntry.GENESIS);
        assertThat(first.actor().principal()).isEqualTo("svc:rulestone-governance@bank.example");
        assertThat(first.details()).containsEntry("fromVersion", 0L).containsEntry("toVersion", 1L);

        GovernancePolicy other = GovernancePolicy.builder().tier(GovernancePolicy.Tier.of("standard", 1, "RULE_CHECKER"))
                .assign("*", "*", "standard").build();
        assertThatThrownBy(() -> GovernanceService.builder().store(f.store).principals(f.who).policy(other)
                .signer(PrivateKeySigner.of("k", java.security.KeyPairGenerator.getInstance("Ed25519").generateKeyPair().getPrivate()))
                .build()).isInstanceOfSatisfying(GovernanceException.class, e -> assertThat(e.code()).isEqualTo(Code.INVALID_POLICY));
        // The same policy, in a service started over the store later, is accepted and writes nothing.
        int entries = f.store.log().size();
        GovernanceService.builder().store(f.store).principals(f.who).policy(GovernanceFixture.policy().withVersion(7))
                .signer(PrivateKeySigner.of("k", generate())).build();
        assertThat(f.store.log()).hasSize(entries);
    }

    private static java.security.PrivateKey generate() {
        try {
            return java.security.KeyPairGenerator.getInstance("Ed25519").generateKeyPair().getPrivate();
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    // ---------------------------------------------------------------------------------------------

    @Test
    void activationNeedsAReleaseRoleAndGrowsTheSequence() {
        f.approved("CHG-1", gate("1", "2026-01-01", 70));
        refused(() -> f.as(MAKER).service.activate("CHG-1", TARGET), Code.FORBIDDEN);
        refused(() -> f.as(CHECKER).service.activate("CHG-1", TARGET), Code.FORBIDDEN);
        GovernanceStore.IssuedRelease first = f.as(RELEASER).service.activate("CHG-1", TARGET);
        assertThat(first.release().sequence()).isEqualTo(1);
        refused(() -> f.service.activate("CHG-1", TARGET), Code.ILLEGAL_STATE);

        // The same approved content can enter another target's release.
        GovernanceStore.IssuedRelease other = f.service.activate("CHG-1", "cards-prod");
        assertThat(other.release().sequence()).isEqualTo(1);
        assertThat(other.release().target()).isEqualTo("cards-prod");
        assertThat(f.service.changeSet("CHG-1").activations()).extracting(ChangeSet.Activation::target)
                .containsExactly(TARGET, "cards-prod");

        f.approved("CHG-2", gate("2", "2027-01-01", 60));
        GovernanceStore.IssuedRelease second = f.as(RELEASER).service.activate("CHG-2", TARGET);
        assertThat(second.release().sequence()).isEqualTo(2);
        assertThat(second.release().previous()).contains(first.hash());
        assertThat(f.store.releases(TARGET)).hasSize(2);
        assertThat(f.store.releases("cards-prod")).hasSize(1);
        assertThat(events().stream().filter(t -> t == EventType.RELEASED).count()).isEqualTo(3);
    }

    @Test
    void anyoneMayActivateIfThePolicyNamesNoReleaseRole() {
        GovernancePolicy open = GovernancePolicy.builder().tier(GovernancePolicy.Tier.of("standard", 1, "RULE_CHECKER"))
                .assign("*", "*", "standard").build();
        GovernanceFixture g = new GovernanceFixture("gov-ed", open);
        Principal anybody = Principal.of("u:anybody");
        g.as(anybody).service.createChangeSet("CHG-1", "x");
        g.service.saveDraft("CHG-1", ArtifactKind.TABLE, gate("1", "2026-01-01", 70).json());
        g.service.submit("CHG-1");
        g.as(CHECKER).service.approve("CHG-1", "RULE_CHECKER", null);
        assertThat(g.as(anybody).service.activate("CHG-1", TARGET).release().sequence()).isEqualTo(1);
        refused(() -> g.as(AI).service.activate("CHG-1", "t2"), Code.FORBIDDEN);
        refused(() -> g.as(anybody).service.activate("CHG-1", "t3", List.of()), Code.FORBIDDEN);
    }

    @Test
    void aRetiredChangeSetIsNotActivatedAgain() {
        f.activated("CHG-1", gate("1", "2026-01-01", "2026-12-01", 70));
        f.clock.set(java.time.Instant.parse("2026-12-02T00:00:00Z"));
        f.activated("CHG-2", gate("2", "2026-12-01", 60));
        assertThat(f.service.changeSet("CHG-1").state()).isEqualTo(LifecycleState.RETIRED);
        refused(() -> f.as(RELEASER).service.activate("CHG-1", "cards-prod"), Code.ILLEGAL_STATE);
        assertThat(f.service.stateOf(ArtifactKind.TABLE, "gate_decision", "1")).contains(LifecycleState.RETIRED);
    }

    @Test
    void anOlderApprovedVersionActivatedLaterDoesNotLowerTheSequence() {
        f.approved("CHG-1", gate("1", "2026-01-01", 70));
        f.activated("CHG-2", gate("2", "2027-01-01", 60));
        GovernanceStore.IssuedRelease late = f.as(RELEASER).service.activate("CHG-1", TARGET);
        assertThat(late.release().sequence()).isEqualTo(2);
        assertThat(late.release().artifacts()).hasSize(2);
        assertThat(f.store.releases(TARGET)).extracting(r -> r.release().sequence()).containsExactly(1L, 2L);
    }

    @Test
    void versionsExpireAndAreSupersededWhenTheirDatesPass() {
        f.activated("CHG-1", gate("1", "2026-01-01", "2026-12-01", 70));
        f.activated("CHG-2", gate("2", "2026-12-01", 60));
        f.activated("CHG-3", gate("3", "2027-06-01", 50));
        assertThat(f.store.latestRelease(TARGET).orElseThrow().release().artifacts())
                .extracting(vn.io.dxa.rulestone.core.Release.Entry::state)
                .containsOnly(vn.io.dxa.rulestone.core.Release.State.ACTIVE);

        // On 2026-12-02 version 1 has expired and version 2 is in force; version 3 is still to come.
        f.clock.set(java.time.Instant.parse("2026-12-02T00:00:00Z"));
        f.as(RELEASER);
        GovernanceStore.IssuedRelease release = f.service.retireSuperseded(TARGET).orElseThrow();
        assertThat(release.release().artifacts()).extracting(e -> e.label() + ":" + e.state())
                .containsExactly("1:RETIRED", "2:ACTIVE", "3:ACTIVE");
        assertThat(f.store.log()).filteredOn(e -> e.type() == EventType.RETIRED).singleElement().satisfies(e -> {
            assertThat(e.details()).containsEntry("reason", "expired").containsEntry("target", TARGET);
            assertThat(e.changeSet()).contains("CHG-1");
        });

        // On 2027-06-02 version 3 has taken effect and supersedes version 2.
        f.clock.set(java.time.Instant.parse("2027-06-02T00:00:00Z"));
        assertThat(f.service.retireSuperseded(TARGET).orElseThrow().release().artifacts())
                .extracting(e -> e.label() + ":" + e.state()).containsExactly("1:RETIRED", "2:RETIRED", "3:ACTIVE");
        assertThat(f.service.changeSet("CHG-2").state()).isEqualTo(LifecycleState.RETIRED);
        assertThat(f.service.changeSet("CHG-3").state()).isEqualTo(LifecycleState.ACTIVE);
        assertThat(f.service.retireSuperseded(TARGET)).isEmpty();
    }

    // ---------------------------------------------------------------------------------------------

    @Test
    void commentsAreEventsWithAnOptionalLocation() {
        f.submit("CHG-1", gate("1", "2026-01-01", 70));
        f.as(CHECKER).service.comment("CHG-1", "gate_decision@1 $.rules[0].when", "Why 70?");
        f.as(MAKER).service.comment("CHG-1", null, "Because of the committee minutes");
        refused(() -> f.service.comment("CHG-1", null, " "), Code.INVALID_REQUEST);
        refused(() -> f.service.comment("CHG-9", null, "x"), Code.NOT_FOUND);

        List<LogEntry> comments = f.store.log().stream().filter(e -> e.type() == EventType.COMMENTED).toList();
        assertThat(comments).hasSize(2);
        assertThat(comments.get(0).details()).containsEntry("text", "Why 70?")
                .containsEntry("location", "gate_decision@1 $.rules[0].when");
        assertThat(comments.get(0).actor().principal()).isEqualTo("u:tran.thi.b");
        assertThat(comments.get(1).details()).doesNotContainKey("location");
        // Comments change nothing: the checker may still approve.
        f.as(CHECKER).service.approve("CHG-1", "RULE_CHECKER", null);
        assertThat(f.service.changeSet("CHG-1").state()).isEqualTo(LifecycleState.APPROVED);
    }

    @Test
    void theViaOfTheCallerIsLoggedAndListenersSeeEveryEntryEvenWhenTheyFail() {
        List<LogEntry> seen = new ArrayList<>();
        GovernanceFixture g = new GovernanceFixture();
        GovernanceService service = GovernanceService.builder().store(g.store).principals(g.who).policy(GovernanceFixture.policy())
                .signer(PrivateKeySigner.of("k", generate())).clock(g.clock).idGenerator(() -> "id-" + seen.size())
                .listener(entry -> {
                    seen.add(entry);
                    throw new IllegalStateException("the listener fails");
                }).build();
        g.who.actAs(MAKER).via("camunda8:rule-change/2251799813685321");
        service.createChangeSet("CHG-1", "x");
        assertThat(seen).singleElement().satisfies(e -> {
            assertThat(e.type()).isEqualTo(EventType.DRAFT_CREATED);
            assertThat(e.actor()).isEqualTo(new LogEntry.Actor("u:nguyen.van.a", Optional.of("camunda8:rule-change/2251799813685321")));
        });
        assertThat(service.changeSet("CHG-1")).isNotNull();
    }

    @Test
    void aSignerThatFailsChangesNothing() {
        GovernanceFixture g = new GovernanceFixture();
        boolean[] failing = {false};
        PrivateKeySigner real = PrivateKeySigner.of("gov-ed", generate());
        Signer flaky = new Signer() {
            @Override
            public String keyId() {
                return real.keyId();
            }

            @Override
            public String algorithm() {
                return real.algorithm();
            }

            @Override
            public byte[] sign(byte[] signingInput) {
                if (failing[0]) {
                    throw new IllegalStateException("the HSM is unreachable");
                }
                return real.sign(signingInput);
            }
        };
        GovernanceService service = GovernanceService.builder().store(g.store).principals(g.who).policy(GovernanceFixture.policy())
                .signer(flaky).clock(g.clock).build();
        g.as(MAKER);
        service.createChangeSet("CHG-1", "x");
        service.saveDraft("CHG-1", ArtifactKind.TABLE, gate("1", "2026-01-01", 70).json());
        service.submit("CHG-1");
        int entries = g.store.log().size();
        failing[0] = true;
        assertThatThrownBy(() -> {
            g.who.actAs(CHECKER);
            service.approve("CHG-1", "RULE_CHECKER", null);
        }).isInstanceOfSatisfying(GovernanceException.class, e -> assertThat(e.code()).isEqualTo(Code.SIGNING_FAILED));
        assertThat(g.store.log()).hasSize(entries);
        assertThat(service.changeSet("CHG-1").state()).isEqualTo(LifecycleState.SUBMITTED);
        assertThat(service.changeSet("CHG-1").approvals()).isEmpty();
        assertThat(g.store.binding(ArtifactKind.TABLE, "gate_decision", "1")).isEmpty();

        failing[0] = false;
        service.approve("CHG-1", "RULE_CHECKER", null);
        assertThat(service.changeSet("CHG-1").state()).isEqualTo(LifecycleState.APPROVED);
    }
}
