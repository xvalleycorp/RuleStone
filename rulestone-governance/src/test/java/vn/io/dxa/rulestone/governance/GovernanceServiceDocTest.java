//
// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.rulestone.governance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import vn.io.dxa.rulestone.api.ArtifactKind;
import vn.io.dxa.rulestone.api.DecisionRef;
import vn.io.dxa.rulestone.api.Facts;
import vn.io.dxa.rulestone.api.RuleStoneEngine;
import vn.io.dxa.rulestone.core.ApprovalVerifier;
import vn.io.dxa.rulestone.core.RepositoryReloader;
import vn.io.dxa.rulestone.core.RuleStone;

/** The snippets of docs/governance-service.md. */
class GovernanceServiceDocTest {

    @Test
    void theExampleOfTheGuide() throws Exception {
        KeyPair pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        PrivateKey privateKey = pair.getPrivate();
        PublicKey publicKey = pair.getPublic();
        String tableJson = GovernanceFixture.gateJson("1", "2026-01-01", 70);

        GovernancePolicy policy = GovernancePolicy.builder()
                .tier(GovernancePolicy.Tier.of("standard", 1, "RULE_CHECKER"))
                .assign("*", "*", "standard")
                .makerRoles("RULE_MAKER")
                .releaseRoles("RELEASE_MANAGER")
                .build();

        InMemoryPrincipalResolver who = new InMemoryPrincipalResolver();
        GovernanceService service = GovernanceService.builder()
                .store(new InMemoryGovernanceStore())
                .principals(who)
                .signer(PrivateKeySigner.of("gov-2026", privateKey))
                .policy(policy)
                .build();

        who.actAs(Principal.of("u:nguyen.van.a", "RULE_MAKER"));
        service.createChangeSet("CHG-2026-0042", "Raise the score gate");
        service.saveDraft("CHG-2026-0042", ArtifactKind.TABLE, tableJson);
        service.submit("CHG-2026-0042");

        who.actAs(Principal.of("u:tran.thi.b", "RULE_CHECKER"));
        service.approve("CHG-2026-0042", "RULE_CHECKER", "Matches the committee minutes");

        who.actAs(Principal.of("u:rm.one", "RELEASE_MANAGER"));
        service.activate("CHG-2026-0042", "retail-credit-prod");

        RuleStoneEngine engine = RuleStone.builder()
                .snapshot(service.repository().load("retail-credit-prod"))
                .requireApproved(ApprovalVerifier.builder().trustedKey("gov-2026", publicKey).build())
                .requireRelease("retail-credit-prod")
                .build();
        assertThat(engine.evaluate(DecisionRef.of("gate_decision").asOf(LocalDate.of(2026, 12, 1)), Facts.of("score", 75))
                .output()).containsEntry("action", "BLOCK");

        // The log of the guide.
        List<LogEntry> log = service.store().log();
        assertThat(log).extracting(e -> e.seq() + " " + e.type() + " " + e.actor().principal()).containsExactly(
                "1 POLICY_CHANGED svc:rulestone-governance",
                "2 DRAFT_CREATED u:nguyen.van.a",
                "3 DRAFT_SAVED u:nguyen.van.a",
                "4 SUBMITTED u:nguyen.van.a",
                "5 APPROVED u:tran.thi.b",
                "6 APPROVAL_ISSUED svc:rulestone-governance",
                "7 RELEASED u:rm.one",
                "8 ACTIVATED u:rm.one");

        // His own change set cannot be approved by the maker.
        who.actAs(Principal.of("u:nguyen.van.a", "RULE_MAKER", "RULE_CHECKER"));
        service.createChangeSet("CHG-2", "x");
        service.saveDraft("CHG-2", ArtifactKind.TABLE, GovernanceFixture.gateJson("2", "2027-01-01", 60));
        service.submit("CHG-2");
        assertThatThrownBy(() -> service.approve("CHG-2", "RULE_CHECKER", null))
                .isInstanceOfSatisfying(GovernanceException.class,
                        e -> assertThat(e.code()).isEqualTo(GovernanceException.Code.FOUR_EYES));
    }

    @Test
    void theReloaderAndTheVerifierOfTheGuide() throws Exception {
        GovernanceFixture f = new GovernanceFixture();
        f.activated("CHG-1", GovernanceFixture.gate("1", "2026-01-01", 70));
        AtomicReference<RuleStoneEngine> engine = new AtomicReference<>();
        RepositoryReloader reloader = RepositoryReloader.builder(f.service.repository(), "retail-credit-prod", f.verifier)
                .onReload(engine::set)
                .build();
        assertThat(reloader.reloadIfNewer()).isTrue();
        assertThat(engine.get()).isNotNull();

        GovernanceVerifier verifier = GovernanceVerifier.builder()
                .verifier(ApprovalVerifier.builder().trustedKey("gov-ed", f.publicKey).build())
                .build();
        VerificationReport report = verifier.verify(f.store);
        assertThat(report.format()).isEqualTo("""
                log entries: 8
                approval statements: 1
                releases: 1
                OK: the log, the statements and the releases verify
                """);
    }
}
