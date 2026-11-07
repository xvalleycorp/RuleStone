//
// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.rulestone.governance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static vn.io.dxa.rulestone.governance.GovernanceFixture.CHECKER;
import static vn.io.dxa.rulestone.governance.GovernanceFixture.COMPLIANCE;
import static vn.io.dxa.rulestone.governance.GovernanceFixture.COMPLIANCE_IN_CREDIT_UNIT;
import static vn.io.dxa.rulestone.governance.GovernanceFixture.CREDIT;
import static vn.io.dxa.rulestone.governance.GovernanceFixture.EMERGENCY;
import static vn.io.dxa.rulestone.governance.GovernanceFixture.MAKER;
import static vn.io.dxa.rulestone.governance.GovernanceFixture.RELEASER;
import static vn.io.dxa.rulestone.governance.GovernanceFixture.TARGET;
import static vn.io.dxa.rulestone.governance.GovernanceFixture.creditParams;
import static vn.io.dxa.rulestone.governance.GovernanceFixture.gate;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;
import vn.io.dxa.rulestone.api.RuleStoneEngine;
import vn.io.dxa.rulestone.core.ApprovalStatement;
import vn.io.dxa.rulestone.core.ApprovalVerifier;
import vn.io.dxa.rulestone.core.InvalidDecisionException;
import vn.io.dxa.rulestone.core.RuleStone;
import vn.io.dxa.rulestone.governance.GovernanceException.Code;

/** Emergency changes (design section 3.5): one non-maker approver now, the full policy afterwards. */
class EmergencyChangeTest {

    private final List<LogEntry> alerts = new ArrayList<>();
    private final GovernanceFixture f = new GovernanceFixture();

    private static void refused(ThrowingCallable call, Code code) {
        assertThatThrownBy(call).isInstanceOfSatisfying(GovernanceException.class, e -> assertThat(e.code()).isEqualTo(code));
    }

    /** A credit parameter change, which needs two approvers in two roles, declared an emergency. */
    private void emergencyChange() {
        f.submit("CHG-E", creditParams("1", "2026-01-01", 18));
        f.as(MAKER).service.declareEmergency("CHG-E", "A fraud pattern is under way");
    }

    private ApprovalStatement statement(String name) {
        return f.store.approvals().stream().filter(a -> a.name().equals(name)).findFirst().orElseThrow().statement();
    }

    @Test
    void oneNonMakerEmergencyApproverIsEnoughAndTheStatementSaysSo() {
        emergencyChange();
        assertThat(f.service.changeSet("CHG-E").emergency()).hasValueSatisfying(e -> {
            assertThat(e.status()).isEqualTo(ChangeSet.EmergencyStatus.DECLARED);
            assertThat(e.reason()).isEqualTo("A fraud pattern is under way");
            assertThat(e.declaredBy()).isEqualTo("u:nguyen.van.a");
        });

        ChangeSet approved = f.as(EMERGENCY).service.approve("CHG-E", "EMERGENCY_APPROVER", "Block it now");
        assertThat(approved.state()).isEqualTo(LifecycleState.APPROVED);
        ApprovalStatement statement = statement("CHG-E.approval.jws");
        assertThat(statement.emergency()).isTrue();
        // 2026-11-20 is a Friday: the end of the next business day is Monday.
        assertThat(statement.postApprovalDue()).contains("2026-11-23T23:59:59Z");
        assertThat(statement.policy()).contains("emergency@1");
        assertThat(statement.makers()).containsExactly("u:nguyen.van.a");
        assertThat(statement.approvers()).singleElement().satisfies(a -> {
            assertThat(a.principal()).isEqualTo("u:em.one");
            assertThat(a.role()).contains("EMERGENCY_APPROVER");
        });
        assertThat(approved.emergency().orElseThrow().status()).isEqualTo(ChangeSet.EmergencyStatus.AWAITING_POST_APPROVAL);
        assertThat(f.store.log().stream().map(LogEntry::type)).containsSubsequence(EventType.EMERGENCY_DECLARED,
                EventType.EMERGENCY_APPROVED, EventType.APPROVAL_ISSUED);
        LogEntry issued = f.store.log().get(f.store.log().size() - 1);
        assertThat(statement.logEntry()).contains(issued.hash());
        assertThat(issued.details()).containsEntry("emergency", true).containsEntry("postApprovalDue", "2026-11-23T23:59:59Z");

        // The engine accepts the statement like any other; it checks what does not depend on the bank's organisation.
        f.as(RELEASER).service.activate("CHG-E", TARGET);
        assertThat(f.engine()).isNotNull();
        ApprovalVerifier strict = ApprovalVerifier.builder().trustedKey("gov-ed", f.publicKey).minApprovers(2).build();
        assertThatThrownBy(() -> RuleStone.builder().snapshot(f.service.repository().load(TARGET)).requireApproved(strict)
                .requireRelease(TARGET).build()).isInstanceOf(InvalidDecisionException.class)
                .hasMessageContaining("names 1 approver; at least 2 are required");
    }

    @Test
    void fourEyesIsReducedNeverRemoved() {
        Principal emergencyMaker = Principal.of("u:em.maker", "RULE_MAKER", "EMERGENCY_APPROVER");
        f.as(emergencyMaker).service.createChangeSet("CHG-E", "Block now");
        f.service.saveDraft("CHG-E", vn.io.dxa.rulestone.api.ArtifactKind.TABLE, gate("1", "2026-01-01", 70).json());
        f.service.submit("CHG-E");
        f.service.declareEmergency("CHG-E", "Fraud");
        refused(() -> f.service.approve("CHG-E", "EMERGENCY_APPROVER", null), Code.FOUR_EYES);
        // Not even an approver of the emergency role who is the maker's colleague without the role.
        refused(() -> f.as(CHECKER).service.approve("CHG-E", "EMERGENCY_APPROVER", null), Code.FORBIDDEN);
        assertThat(f.store.approvals()).isEmpty();
        f.as(EMERGENCY).service.approve("CHG-E", "EMERGENCY_APPROVER", null);
        assertThat(f.service.changeSet("CHG-E").state()).isEqualTo(LifecycleState.APPROVED);
    }

    @Test
    void anEmergencyRoleOnlyApprovesEmergencies() {
        f.submit("CHG-1", gate("1", "2026-01-01", 70));
        refused(() -> f.as(EMERGENCY).service.approve("CHG-1", "EMERGENCY_APPROVER", null), Code.ROLE_NOT_REQUIRED);
        refused(() -> f.service.reject("CHG-1", "Not an emergency"), Code.FORBIDDEN);
        // A checker's role is not an emergency role either.
        emergencyChange();
        refused(() -> f.as(CHECKER).service.approve("CHG-E", "RULE_CHECKER", null), Code.ROLE_NOT_REQUIRED);
    }

    @Test
    void declaringNeedsTheMakerRoleAnEnabledPolicyAndAChangeNotYetApproved() {
        f.as(MAKER).service.createChangeSet("CHG-1", "x");
        refused(() -> f.as(CHECKER).service.declareEmergency("CHG-1", "Fraud"), Code.FORBIDDEN);
        refused(() -> f.as(MAKER).service.declareEmergency("CHG-1", " "), Code.INVALID_REQUEST);
        f.service.declareEmergency("CHG-1", "Fraud");
        refused(() -> f.service.declareEmergency("CHG-1", "Again"), Code.ILLEGAL_STATE);

        f.approved("CHG-2", gate("1", "2026-01-01", 70));
        refused(() -> f.as(MAKER).service.declareEmergency("CHG-2", "Too late"), Code.ILLEGAL_STATE);

        GovernancePolicy off = GovernancePolicy.builder().tier(GovernancePolicy.Tier.of("standard", 1, "RULE_CHECKER"))
                .assign("*", "*", "standard").build();
        GovernanceFixture g = new GovernanceFixture("gov-ed", off);
        g.as(MAKER).service.createChangeSet("CHG-1", "x");
        refused(() -> g.service.declareEmergency("CHG-1", "Fraud"), Code.FORBIDDEN);
    }

    @Test
    void theFullPolicyPostApprovesTheSameHashesAndASecondStatementIsIssued() {
        emergencyChange();
        f.as(EMERGENCY).service.approve("CHG-E", "EMERGENCY_APPROVER", null);
        f.as(RELEASER).service.activate("CHG-E", TARGET);

        refused(() -> f.as(MAKER).service.postApprove("CHG-E", "RULE_MAKER", null), Code.FOUR_EYES);
        refused(() -> f.as(EMERGENCY).service.postApprove("CHG-E", "EMERGENCY_APPROVER", null), Code.ROLE_NOT_REQUIRED);
        refused(() -> f.as(CHECKER).service.postApprove("CHG-E", "RULE_CHECKER", null), Code.ROLE_NOT_REQUIRED);

        f.as(CREDIT).service.postApprove("CHG-E", "CREDIT_RISK", "Within the credit policy");
        assertThat(f.service.changeSet("CHG-E").emergency().orElseThrow().status())
                .isEqualTo(ChangeSet.EmergencyStatus.AWAITING_POST_APPROVAL);
        refused(() -> f.as(CREDIT).service.postApprove("CHG-E", "CREDIT_RISK", null), Code.DUPLICATE_APPROVAL);
        refused(() -> f.as(COMPLIANCE_IN_CREDIT_UNIT).service.postApprove("CHG-E", "COMPLIANCE", null), Code.UNIT_NOT_DISTINCT);
        f.as(COMPLIANCE).service.postApprove("CHG-E", "COMPLIANCE", null);

        ChangeSet cs = f.service.changeSet("CHG-E");
        assertThat(cs.emergency().orElseThrow().status()).isEqualTo(ChangeSet.EmergencyStatus.POST_APPROVED);
        assertThat(cs.postApprovals()).extracting(ChangeSet.Approval::principal).containsExactly("u:cr.one", "u:co.one");
        assertThat(f.store.log().stream().map(LogEntry::type)).containsSubsequence(EventType.EMERGENCY_APPROVED,
                EventType.ACTIVATED, EventType.APPROVED, EventType.APPROVED, EventType.POST_APPROVED);

        ApprovalStatement post = statement("CHG-E.post.approval.jws");
        assertThat(post.emergency()).isFalse();
        assertThat(post.postApprovalDue()).isEmpty();
        assertThat(post.subjects()).isEqualTo(statement("CHG-E.approval.jws").subjects());
        assertThat(post.approvers()).extracting(ApprovalStatement.Approver::principal).containsExactly("u:cr.one", "u:co.one");
        assertThat(post.policy()).contains("credit-policy@1");
        assertThat(post.logEntry()).contains(f.store.log().stream().filter(e -> e.type() == EventType.POST_APPROVED)
                .findFirst().orElseThrow().hash());

        // An engine that wants two approvers is satisfied by the post-approval statement.
        ApprovalVerifier strict = ApprovalVerifier.builder().trustedKey("gov-ed", f.publicKey).minApprovers(2).build();
        assertThat(RuleStone.builder().snapshot(f.service.repository().load(TARGET)).requireApproved(strict)
                .requireRelease(TARGET).build()).isNotNull();
        refused(() -> f.as(CREDIT).service.postApprove("CHG-E", "CREDIT_RISK", null), Code.ILLEGAL_STATE);
    }

    @Test
    void aRefusedPostApprovalLeavesTheVersionToBeSuperseded() {
        emergencyChange();
        f.as(EMERGENCY).service.approve("CHG-E", "EMERGENCY_APPROVER", null);
        f.as(RELEASER).service.activate("CHG-E", TARGET);

        refused(() -> f.as(MAKER).service.rejectPostApproval("CHG-E", "No"), Code.FOUR_EYES);
        refused(() -> f.as(EMERGENCY).service.rejectPostApproval("CHG-E", "Not mine to refuse"), Code.FORBIDDEN);
        refused(() -> f.as(CREDIT).service.rejectPostApproval("CHG-E", " "), Code.INVALID_REQUEST);
        f.as(CREDIT).service.rejectPostApproval("CHG-E", "The threshold is not covered by the credit policy");

        assertThat(f.service.changeSet("CHG-E").emergency().orElseThrow().status())
                .isEqualTo(ChangeSet.EmergencyStatus.POST_APPROVAL_REJECTED);
        assertThat(f.store.log().get(f.store.log().size() - 1)).satisfies(e -> {
            assertThat(e.type()).isEqualTo(EventType.POST_APPROVAL_REJECTED);
            assertThat(e.details()).containsEntry("reason", "The threshold is not covered by the credit policy");
        });
        refused(() -> f.as(RELEASER).service.activate("CHG-E", "cards-prod"), Code.ILLEGAL_STATE);
        refused(() -> f.as(CREDIT).service.postApprove("CHG-E", "CREDIT_RISK", null), Code.ILLEGAL_STATE);
        // Nothing is undone: the running release keeps the version, which the log shows as refused.
        assertThat(f.engine()).isNotNull();
        f.clock.advance(Duration.ofDays(30));
        assertThat(f.service.checkOverdue()).isEmpty();
    }

    @Test
    void anOverduePostApprovalIsAnAlertAndALogEntryNotABuildFailure() {
        GovernanceService service = GovernanceService.builder().store(f.store).principals(f.who)
                .policy(GovernanceFixture.policy()).signer(PrivateKeySigner.of("gov-ed", signingKey()))
                .clock(f.clock).listener(alerts::add).build();
        emergencyChange();
        f.as(EMERGENCY).service.approve("CHG-E", "EMERGENCY_APPROVER", null);
        f.as(RELEASER).service.activate("CHG-E", TARGET);

        // On time: nothing to raise, up to and including the due instant.
        f.clock.set(Instant.parse("2026-11-23T23:59:59Z"));
        assertThat(service.checkOverdue()).isEmpty();
        int entries = f.store.log().size();

        f.clock.set(Instant.parse("2026-11-24T00:00:00Z"));
        assertThat(service.checkOverdue()).containsExactly("CHG-E");
        assertThat(f.store.log()).hasSize(entries + 1);
        LogEntry alert = f.store.log().get(entries);
        assertThat(alert.type()).isEqualTo(EventType.POST_APPROVAL_OVERDUE);
        assertThat(alert.actor().principal()).isEqualTo("svc:rulestone-governance");
        assertThat(alert.changeSet()).contains("CHG-E");
        assertThat(alert.details()).containsEntry("due", "2026-11-23T23:59:59Z").containsEntry("overdueSeconds", 1L);
        assertThat(alerts).containsExactly(alert);
        assertThat(f.service.changeSet("CHG-E").emergency().orElseThrow().overdueAlerted()).isTrue();

        // Once only, and the engine still builds and the service still releases.
        f.clock.advance(Duration.ofDays(3));
        assertThat(service.checkOverdue()).isEmpty();
        assertThat(alerts).hasSize(1);
        RuleStoneEngine engine = f.engine();
        assertThat(engine).isNotNull();
        f.activated("CHG-2", gate("2", "2027-01-01", 60));
        assertThat(f.engine()).isNotNull();

        // The full policy can still post-approve late.
        f.as(CREDIT).service.postApprove("CHG-E", "CREDIT_RISK", null);
        f.as(COMPLIANCE).service.postApprove("CHG-E", "COMPLIANCE", null);
        assertThat(f.service.changeSet("CHG-E").emergency().orElseThrow().status()).isEqualTo(ChangeSet.EmergencyStatus.POST_APPROVED);
    }

    @Test
    void aPostApprovedEmergencyIsNeverOverdue() {
        emergencyChange();
        f.as(EMERGENCY).service.approve("CHG-E", "EMERGENCY_APPROVER", null);
        f.as(CREDIT).service.postApprove("CHG-E", "CREDIT_RISK", null);
        f.as(COMPLIANCE).service.postApprove("CHG-E", "COMPLIANCE", null);
        f.clock.advance(Duration.ofDays(30));
        assertThat(f.service.checkOverdue()).isEmpty();
        // And an emergency that is not approved yet has no due instant.
        f.submit("CHG-F", gate("1", "2026-01-01", 70));
        f.as(MAKER).service.declareEmergency("CHG-F", "Fraud");
        assertThat(f.service.checkOverdue()).isEmpty();
    }

    @Test
    void ifTheFullPolicyApprovesFirstTheStatementIsAnOrdinaryOne() {
        emergencyChange();
        f.as(CREDIT).service.approve("CHG-E", "CREDIT_RISK", null);
        f.as(COMPLIANCE).service.approve("CHG-E", "COMPLIANCE", null);
        ApprovalStatement statement = statement("CHG-E.approval.jws");
        assertThat(statement.emergency()).isFalse();
        assertThat(statement.postApprovalDue()).isEmpty();
        assertThat(statement.policy()).contains("credit-policy@1");
        assertThat(f.service.changeSet("CHG-E").emergency().orElseThrow().status()).isEqualTo(ChangeSet.EmergencyStatus.POST_APPROVED);
        f.clock.advance(Duration.ofDays(30));
        assertThat(f.service.checkOverdue()).isEmpty();
    }

    private static java.security.PrivateKey signingKey() {
        try {
            return java.security.KeyPairGenerator.getInstance("Ed25519").generateKeyPair().getPrivate();
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
