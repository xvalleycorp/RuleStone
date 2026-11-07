//
// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.rulestone.governance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static vn.io.dxa.rulestone.governance.GovernanceFixture.CHECKER;
import static vn.io.dxa.rulestone.governance.GovernanceFixture.COMPLIANCE;
import static vn.io.dxa.rulestone.governance.GovernanceFixture.CREDIT;
import static vn.io.dxa.rulestone.governance.GovernanceFixture.MAKER;
import static vn.io.dxa.rulestone.governance.GovernanceFixture.RELEASER;
import static vn.io.dxa.rulestone.governance.GovernanceFixture.TARGET;
import static vn.io.dxa.rulestone.governance.GovernanceFixture.catalog;
import static vn.io.dxa.rulestone.governance.GovernanceFixture.creditParams;
import static vn.io.dxa.rulestone.governance.GovernanceFixture.gate;

import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import vn.io.dxa.rulestone.api.ArtifactKind;
import vn.io.dxa.rulestone.api.DecisionRecord;
import vn.io.dxa.rulestone.api.DecisionRef;
import vn.io.dxa.rulestone.api.Facts;
import vn.io.dxa.rulestone.api.RuleRepository;
import vn.io.dxa.rulestone.api.RuleStoneEngine;
import vn.io.dxa.rulestone.core.ApprovalVerifier;
import vn.io.dxa.rulestone.core.DecisionTableJson;
import vn.io.dxa.rulestone.core.FileRuleRepository;
import vn.io.dxa.rulestone.core.Release;
import vn.io.dxa.rulestone.core.RepositoryReloader;
import vn.io.dxa.rulestone.core.InvalidDecisionException;
import vn.io.dxa.rulestone.core.RuleStone;

/**
 * The service issues what engines accept: an engine built with {@code requireApproved} and
 * {@code requireRelease} from the service's repository loads exactly what was approved and released,
 * and refuses everything else.
 */
class GovernanceEngineAcceptanceTest {

    private static final LocalDate DEC_2026 = LocalDate.of(2026, 12, 1);
    private static final LocalDate FEB_2027 = LocalDate.of(2027, 2, 1);

    private static String action(RuleStoneEngine engine, LocalDate day, int score) {
        return String.valueOf(engine.evaluate(DecisionRef.of("gate_decision").asOf(day), Facts.of("score", score))
                .output().get("action"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"gov-ed", "gov-ec"})
    void anEngineAcceptsExactlyWhatTheServiceIssued(String kid) {
        GovernanceFixture f = new GovernanceFixture(kid);

        // Release 1: a table and the reason-code catalog, one checker (standard tier).
        GovernanceStore.IssuedRelease first = f.activated("CHG-1", gate("1", "2026-01-01", 70), catalog("1"));
        assertThat(first.release().sequence()).isEqualTo(1);
        assertThat(first.release().previous()).isEmpty();
        RuleStoneEngine engine1 = f.engine();
        assertThat(action(engine1, DEC_2026, 65)).isEqualTo("ALLOW");
        assertThat(action(engine1, DEC_2026, 75)).isEqualTo("BLOCK");

        // Release 2: a table and a credit parameter set, which the credit tier of two approvers covers.
        f.as(MAKER).service.createChangeSet("CHG-2", "Lower the gate");
        f.service.saveDraft("CHG-2", ArtifactKind.TABLE, gate("2", "2027-01-01", 60).json());
        f.service.saveDraft("CHG-2", ArtifactKind.PARAMS, creditParams("1", "2027-01-01", 18).json());
        f.service.submit("CHG-2");
        f.as(CREDIT).service.approve("CHG-2", "CREDIT_RISK", null);
        assertThat(f.service.changeSet("CHG-2").state()).isEqualTo(LifecycleState.SUBMITTED);
        f.as(COMPLIANCE).service.approve("CHG-2", "COMPLIANCE", null);
        assertThat(f.service.changeSet("CHG-2").state()).isEqualTo(LifecycleState.APPROVED);
        GovernanceStore.IssuedRelease second = f.as(RELEASER).service.activate("CHG-2", TARGET);

        assertThat(second.release().sequence()).isEqualTo(2);
        assertThat(second.release().previous()).contains(first.hash());
        assertThat(second.release().artifacts()).hasSize(4);
        RuleStoneEngine engine2 = f.engine();
        assertThat(action(engine2, DEC_2026, 65)).isEqualTo("ALLOW");
        assertThat(action(engine2, FEB_2027, 65)).isEqualTo("BLOCK");

        // Retirement is bookkeeping once version 2 is in force: a third release lists version 1 as retired.
        f.clock.set(Instant.parse("2027-01-02T03:00:00Z"));
        f.as(RELEASER);
        GovernanceStore.IssuedRelease third = f.service.retireSuperseded(TARGET).orElseThrow();
        assertThat(third.release().sequence()).isEqualTo(3);
        assertThat(third.release().artifacts()).filteredOn(e -> e.state() == Release.State.RETIRED)
                .extracting(Release.Entry::kind).containsExactly(ArtifactKind.TABLE);
        RuleStoneEngine engine3 = f.engine();
        assertThat(action(engine3, DEC_2026, 75)).isEqualTo("BLOCK");
        assertThat(action(engine3, FEB_2027, 65)).isEqualTo("BLOCK");
        assertThat(f.service.retireSuperseded(TARGET)).isEmpty();
        assertThat(f.service.stateOf(ArtifactKind.TABLE, "gate_decision", "1")).contains(LifecycleState.RETIRED);
        assertThat(f.service.stateOf(ArtifactKind.TABLE, "gate_decision", "2")).contains(LifecycleState.ACTIVE);
        assertThat(f.service.changeSet("CHG-1").state()).isEqualTo(LifecycleState.ACTIVE);

        // The decision record names the exact version the service released.
        assertThat(f.engine().evaluate(DecisionRef.of("gate_decision").asOf(FEB_2027), Facts.of("score", 1)).ruleVersion())
                .isEqualTo(DecisionTableJson.exactVersion(DecisionTableJson.read(gate("2", "2027-01-01", 60).json())));
    }

    @Test
    void theReloaderPicksUpTheNewRelease() {
        GovernanceFixture f = new GovernanceFixture();
        f.activated("CHG-1", gate("1", "2026-01-01", 70));
        AtomicReference<RuleStoneEngine> current = new AtomicReference<>();
        List<String> failures = new ArrayList<>();
        RepositoryReloader reloader = RepositoryReloader.builder(f.service.repository(), TARGET, f.verifier)
                .engine(builder -> builder.clock(f.clock))
                .onReload(current::set)
                .onFailure((sequence, e) -> failures.add(sequence + ": " + e.getMessage()))
                .build();
        assertThat(reloader.reloadIfNewer()).isTrue();
        assertThat(action(current.get(), FEB_2027, 65)).isEqualTo("ALLOW");
        assertThat(reloader.reloadIfNewer()).isFalse();

        f.activated("CHG-2", gate("2", "2027-01-01", 60));
        assertThat(reloader.reloadIfNewer()).isTrue();
        assertThat(action(current.get(), FEB_2027, 65)).isEqualTo("BLOCK");
        assertThat(failures).isEmpty();
    }

    @Test
    void theEngineRefusesWhatTheServiceDidNotIssue() {
        GovernanceFixture f = new GovernanceFixture();
        f.activated("CHG-1", gate("1", "2026-01-01", 70));
        RuleRepository.Snapshot snapshot = f.service.repository().load(TARGET);
        assertThat(build(f, snapshot)).isNotNull();

        // Content changed after approval.
        RuleRepository.Artifact table = snapshot.artifacts().get(0);
        RuleRepository.Artifact tampered = new RuleRepository.Artifact(table.kind(), table.id(), table.version(), table.source(),
                table.content().replace("score >= 70", "score >= 90"));
        assertThatThrownBy(() -> build(f, new RuleRepository.Snapshot(TARGET, snapshot.sequence(), snapshot.release(),
                List.of(tampered), snapshot.approvals())))
                .isInstanceOf(InvalidDecisionException.class).hasMessageContaining("gate_decision@1: no approval covers");

        // A release that is missing from the files, and an approval that is missing.
        assertThatThrownBy(() -> build(f, new RuleRepository.Snapshot(TARGET, snapshot.sequence(), snapshot.release(),
                List.of(), snapshot.approvals())))
                .isInstanceOf(InvalidDecisionException.class).hasMessageContaining("which is not loaded");
        assertThatThrownBy(() -> build(f, new RuleRepository.Snapshot(TARGET, snapshot.sequence(), snapshot.release(),
                snapshot.artifacts(), List.of())))
                .isInstanceOf(InvalidDecisionException.class).hasMessageContaining("no approval covers");

        // A submitted change that nobody approved is not in the repository, and not accepted when added by hand.
        f.submit("CHG-2", gate("2", "2027-01-01", 60));
        assertThat(f.service.repository().load(TARGET).artifacts()).hasSize(1);
        assertThatThrownBy(() -> RuleStone.builder().snapshot(snapshot)
                .decision(DecisionTableJson.read(gate("2", "2027-01-01", 60).json()))
                .requireApproved(f.verifier).requireRelease(TARGET).build())
                .isInstanceOf(InvalidDecisionException.class).hasMessageContaining("gate_decision@2");

        // Another key does not verify what the service signed.
        ApprovalVerifier other = new GovernanceFixture("gov-ec").verifier;
        assertThatThrownBy(() -> RuleStone.builder().snapshot(snapshot).requireApproved(other).requireRelease(TARGET).build())
                .isInstanceOf(InvalidDecisionException.class).hasMessageContaining("unknown key 'gov-ed'");
    }

    @Test
    void anOlderReleaseIsARollbackTheEngineRefuses() {
        GovernanceFixture f = new GovernanceFixture();
        f.activated("CHG-1", gate("1", "2026-01-01", 70));
        RuleRepository.Snapshot first = f.service.repository().load(TARGET);
        GovernanceStore.IssuedRelease second = f.activated("CHG-2", gate("2", "2027-01-01", 60));

        assertThatThrownBy(() -> RuleStone.builder().snapshot(first).requireApproved(f.verifier).requireRelease(TARGET)
                .lastAcceptedRelease(second.release()).build())
                .isInstanceOf(InvalidDecisionException.class)
                .hasMessageContaining("sequence 1 is older than release 2, which this engine already accepted");
        assertThat(RuleStone.builder().snapshot(f.service.repository().load(TARGET)).requireApproved(f.verifier)
                .requireRelease(TARGET).lastAcceptedRelease(second.release()).build()).isNotNull();
    }

    @Test
    void aNewCatalogReplacesTheOldOneInTheRelease() {
        GovernanceFixture f = new GovernanceFixture();
        f.activated("CHG-1", gate("1", "2026-01-01", 70), catalog("1"));
        GovernanceStore.IssuedRelease second = f.activated("CHG-2", catalog("2"));

        assertThat(second.release().artifacts()).filteredOn(e -> e.kind() == ArtifactKind.CATALOG)
                .singleElement().satisfies(e -> {
                    assertThat(e.version()).startsWith("2@sha256:");
                    assertThat(e.state()).isEqualTo(Release.State.ACTIVE);
                });
        assertThat(f.engine()).isNotNull();
        assertThat(f.service.stateOf(ArtifactKind.CATALOG, "reason-codes", "1")).contains(LifecycleState.RETIRED);
        assertThat(f.service.changeSet("CHG-1").state()).isEqualTo(LifecycleState.ACTIVE);
        assertThat(f.service.repository().find(ArtifactKind.CATALOG, "reason-codes",
                f.service.store().binding(ArtifactKind.CATALOG, "reason-codes", "1").orElseThrow().exactVersion())).isPresent();
        assertThat(f.store.log()).filteredOn(e -> e.type() == EventType.RETIRED).singleElement()
                .satisfies(e -> assertThat(e.details()).containsEntry("reason", "replaced"));
    }

    @Test
    void routingIsPartOfTheSignedRelease() {
        GovernanceFixture f = new GovernanceFixture();
        f.activated("CHG-1", gate("1", "2026-01-01", 70));
        f.approved("CHG-2", gate("2", "2027-01-01", 60));
        String v2 = f.service.changeSet("CHG-2").items().get(0).exactVersion();

        assertThatThrownBy(() -> f.as(MAKER).service.activate("CHG-2", TARGET, List.of(Release.Route.shadow("gate_decision", v2))))
                .isInstanceOf(GovernanceException.class).extracting("code").isEqualTo(GovernanceException.Code.FORBIDDEN);
        GovernanceStore.IssuedRelease release = f.as(RELEASER).service.activate("CHG-2", TARGET,
                List.of(Release.Route.shadow("gate_decision", v2)));
        assertThat(release.release().routing()).containsExactly(Release.Route.shadow("gate_decision", v2));

        List<DecisionRecord> records = new ArrayList<>();
        RuleStoneEngine engine = RuleStone.builder().snapshot(f.service.repository().load(TARGET))
                .requireApproved(f.verifier).requireRelease(TARGET).auditSink(records::add).build();
        engine.evaluate(DecisionRef.of("gate_decision").asOf(DEC_2026).correlationId("TXN-1"), Facts.of("score", 65));
        assertThat(records).hasSize(2);
        assertThat(records.get(1).routing()).hasValueSatisfying(r -> assertThat(r.kind()).isEqualTo(DecisionRecord.Routing.Kind.SHADOW));

        // A routing that carries over until its version is no longer active, and a route to an unlisted version is refused.
        f.activated("CHG-3", gate("3", "2027-06-01", 50));
        assertThat(f.store.latestRelease(TARGET).orElseThrow().release().routing()).hasSize(1);
        assertThatThrownBy(() -> f.as(RELEASER).service.activate("CHG-3", "other-target",
                List.of(Release.Route.shadow("gate_decision", "9@sha256:" + "0".repeat(64)))))
                .isInstanceOf(GovernanceException.class);
    }

    @Test
    void anExportLoadsFromFilesAndVerifies(@TempDir Path dir) {
        GovernanceFixture f = new GovernanceFixture();
        f.activated("CHG-1", gate("1", "2026-01-01", 70), catalog("1"));
        f.activated("CHG-2", gate("2", "2027-01-01", 60));
        GovernanceExport.write(f.store, dir);

        RuleStoneEngine engine = RuleStone.builder().snapshot(FileRuleRepository.of(dir).load(TARGET))
                .requireApproved(f.verifier).requireRelease(TARGET).build();
        assertThat(action(engine, FEB_2027, 65)).isEqualTo("BLOCK");
        assertThat(Path.of(dir.toString(), "releases", "history", "retail-credit-prod.2.release.jws")).exists();
    }

    private static RuleStoneEngine build(GovernanceFixture f, RuleRepository.Snapshot snapshot) {
        return RuleStone.builder().snapshot(snapshot).requireApproved(f.verifier).requireRelease(TARGET).build();
    }
}
