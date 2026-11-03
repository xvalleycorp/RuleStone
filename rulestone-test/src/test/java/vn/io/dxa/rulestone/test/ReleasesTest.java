//
// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.rulestone.test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import vn.io.dxa.rulestone.api.ArtifactKind;
import vn.io.dxa.rulestone.api.DecisionRecord;
import vn.io.dxa.rulestone.api.DecisionRef;
import vn.io.dxa.rulestone.api.Facts;
import vn.io.dxa.rulestone.api.RepositoryException;
import vn.io.dxa.rulestone.api.RuleRepository;
import vn.io.dxa.rulestone.api.RuleStoneEngine;
import vn.io.dxa.rulestone.core.ApprovalVerifier;
import vn.io.dxa.rulestone.core.DecisionTable;
import vn.io.dxa.rulestone.core.DecisionTableJson;
import vn.io.dxa.rulestone.core.FileRuleRepository;
import vn.io.dxa.rulestone.core.InvalidDecisionException;
import vn.io.dxa.rulestone.core.ReasonCodeCatalog;
import vn.io.dxa.rulestone.core.ReasonCodeCatalogJson;
import vn.io.dxa.rulestone.core.Release;
import vn.io.dxa.rulestone.core.ReleaseJson;
import vn.io.dxa.rulestone.core.RepositoryReloader;
import vn.io.dxa.rulestone.core.RuleStone;

/**
 * Releases, RuleStone.Builder#requireRelease, FileRuleRepository and RepositoryReloader; the
 * conformance cases are in ReleaseFixtures.
 */
class ReleasesTest {

    private static final String TARGET = "gate-prod";
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 1);

    private static String gate(String version, String effectiveFrom, String when) {
        return """
                {
                  "id": "gate_decision",
                  "version": "%s",
                  "effectiveFrom": "%s",
                  "hitPolicy": "FIRST",
                  "inputs": { "score": "int" },
                  "outputs": { "action": "string" },
                  "rules": [
                    { "id": "BLOCK", "when": "%s", "then": { "action": "'BLOCK'" } },
                    { "id": "ALLOW", "when": "true", "then": { "action": "'ALLOW'" } }
                  ]
                }
                """.formatted(version, effectiveFrom, when);
    }

    private final DecisionTable v1 = DecisionTableJson.read(gate("1", "2026-01-01", "score >= 70"));
    private final DecisionTable v2 = DecisionTableJson.read(gate("2", "2027-01-01", "score >= 60"));
    private final ApprovalIssuer issuer = ApprovalIssuer.generate("k", ApprovalVerifier.EDDSA);
    private final String approval = issuer.issue(ApprovalIssuer.statement("CHG-1").covering(v1).covering(v2)
            .maker("u:a").approver("u:b").build());

    private Release release(long sequence, Release previous) {
        ApprovalIssuer.ReleaseBuilder builder = ApprovalIssuer.release(TARGET, sequence).active(v1);
        if (previous != null) {
            builder.previous(previous);
        }
        return builder.build();
    }

    @Test
    void releasesAreWrittenCanonicallyAndVerified() {
        Release release = ApprovalIssuer.release(TARGET, 2).previous("sha256:" + "a".repeat(64)).active(v1).active(v2)
                .canary(v2, 0.05).issuedAt("2026-11-20T07:45:00Z").build();
        String json = ReleaseJson.write(release);
        assertThat(json).contains("\"share\":0.05").startsWith("{\"artifacts\":[");
        assertThat(ReleaseJson.read(json)).isEqualTo(release);
        assertThat(issuer.verifier().verifyRelease(issuer.issue(release))).isEqualTo(release);
        assertThat(ReleaseJson.write(ApprovalIssuer.release(TARGET, 1).active(v1).canary(v1, 1).issuedAt("2026-11-20T07:45:00Z")
                .build())).contains("\"share\":1,");
        assertThatThrownBy(() -> issuer.verifier().verify(issuer.issue(release)))
                .hasMessageContaining("header typ must be 'rulestone-approval+jws'");
    }

    @Test
    void routingComesFromTheRelease() {
        List<DecisionRecord> records = new ArrayList<>();
        Release release = ApprovalIssuer.release(TARGET, 1).active(v1).active(v2).shadow(v2).build();
        RuleStoneEngine engine = RuleStone.builder().decision(v1).decision(v2).approval(approval)
                .release(issuer.issue(release)).requireApproved(issuer.verifier()).requireRelease(TARGET)
                .auditSink(records::add).build();
        engine.evaluate(DecisionRef.of("gate_decision").asOf(TODAY).correlationId("TXN-1"), Facts.of("score", 65));
        assertThat(records).hasSize(2);
        assertThat(records.get(1).routing()).hasValueSatisfying(r -> assertThat(r.kind()).isEqualTo(DecisionRecord.Routing.Kind.SHADOW));

        Release canary = ApprovalIssuer.release(TARGET, 1).active(v1).active(v2).canary(v2, 1).build();
        RuleStoneEngine canaryEngine = RuleStone.builder().decision(v1).decision(v2).approval(approval)
                .release(issuer.issue(canary)).requireApproved(issuer.verifier()).requireRelease(TARGET).build();
        assertThat(canaryEngine.evaluate(DecisionRef.of("gate_decision").asOf(TODAY).correlationId("TXN-1"),
                Facts.of("score", 65)).output()).containsEntry("action", "BLOCK");
    }

    @Test
    void withoutRequireReleaseAReleaseIsIgnored() {
        assertThat(RuleStone.builder().decision(v1).release("not-a-jws").build()).isNotNull();
    }

    @Test
    void requireReleaseNeedsRequireApprovedAndNoBuilderRouting() {
        String jws = issuer.issue(release(1, null));
        assertThatThrownBy(() -> RuleStone.builder().decision(v1).release(jws).requireRelease(TARGET).build())
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> RuleStone.builder().decision(v1).decision(v2).approval(approval).release(jws)
                .requireApproved(issuer.verifier()).requireRelease(TARGET).shadow("gate_decision", "2").build())
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> RuleStone.builder().release(jws).release(jws)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theFileRepositoryLoadsWhatTheReleaseLists(@TempDir Path dir) throws IOException {
        Release release = ApprovalIssuer.release(TARGET, 1).active(v1).build();
        layout(dir, release);
        Files.writeString(dir.resolve("broken.stone.json"), "{", StandardCharsets.UTF_8);
        FileRuleRepository repository = FileRuleRepository.of(dir);

        RuleRepository.Snapshot snapshot = repository.load(TARGET);
        assertThat(snapshot.sequence()).isEqualTo(1);
        assertThat(snapshot.release().name()).isEqualTo("gate-prod.release.jws");
        assertThat(snapshot.artifacts()).extracting(RuleRepository.Artifact::source).containsExactly("rules/gate.v1.stone.json");
        assertThat(snapshot.approvals()).extracting(RuleRepository.Signed::name).containsExactly("CHG-1.approval.jws");
        assertThat(repository.latestSequence(TARGET)).isEqualTo(1);
        assertThat(repository.find(ArtifactKind.TABLE, "gate_decision", DecisionTableJson.exactVersion(v2)))
                .hasValueSatisfying(a -> assertThat(a.source()).isEqualTo("rules/gate.v2.stone.json"));
        assertThat(repository.find(ArtifactKind.TABLE, "gate_decision", "2@sha256:" + "0".repeat(64))).isEmpty();

        RuleStoneEngine engine = RuleStone.builder().snapshot(snapshot).requireApproved(issuer.verifier())
                .requireRelease(TARGET).build();
        assertThat(engine.evaluate(DecisionRef.of("gate_decision").asOf(TODAY), Facts.of("score", 65)).ruleVersion())
                .isEqualTo(DecisionTableJson.exactVersion(v1));

        assertThatThrownBy(() -> repository.load("cards-prod")).isInstanceOf(RepositoryException.class);
        assertThatThrownBy(() -> repository.load("../x")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theFileRepositoryNamesCatalogsAfterTheirFiles(@TempDir Path dir) throws IOException {
        ReasonCodeCatalog catalog = ReasonCodeCatalogJson.read(Path.of("..", "examples", "reason-codes.json"));
        Release release = ApprovalIssuer.release(TARGET, 1).active(v1)
                .artifact(ArtifactKind.CATALOG, "gate", ReasonCodeCatalogJson.exactVersion(catalog), Release.State.ACTIVE)
                .build();
        layout(dir, release);
        Files.writeString(dir.resolve("gate.reason-codes.json"), ReasonCodeCatalogJson.write(catalog), StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("approvals/CHG-2.approval.jws"), issuer.issue(ApprovalIssuer.statement("CHG-2")
                .subject(ArtifactKind.CATALOG, "gate", ReasonCodeCatalogJson.exactVersion(catalog))
                .maker("u:a").approver("u:b").build()), StandardCharsets.UTF_8);
        RuleRepository.Snapshot snapshot = FileRuleRepository.of(dir).load(TARGET);
        assertThat(snapshot.artifacts()).extracting(RuleRepository.Artifact::id).containsExactly("gate", "gate_decision");
        assertThat(RuleStone.builder().snapshot(snapshot).requireApproved(issuer.verifier()).requireRelease(TARGET)
                .build()).isNotNull();
    }

    @Test
    void theReloaderSwapsInNewerReleasesOnly(@TempDir Path dir) throws IOException {
        Release r1 = release(1, null);
        layout(dir, r1);
        Path cache = dir.resolve("cache/gate-prod.release.jws");
        AtomicReference<RuleStoneEngine> current = new AtomicReference<>();
        List<String> failures = new ArrayList<>();
        RepositoryReloader reloader = reloader(dir, cache, current, failures);

        assertThat(reloader.reloadIfNewer()).isTrue();
        assertThat(current.get()).isNotNull();
        assertThat(reloader.release()).contains(r1);
        assertThat(Files.readString(cache)).isEqualTo(issuer.issue(r1) + "\n");
        assertThat(reloader.reloadIfNewer()).isFalse();

        // A newer release that does not verify is not swapped in.
        Release r2 = ApprovalIssuer.release(TARGET, 2).previous(r1).active(v1).active(v2).build();
        Files.writeString(dir.resolve("releases/gate-prod.release.jws"),
                ApprovalIssuer.generate("k", ApprovalVerifier.EDDSA).issue(r2), StandardCharsets.UTF_8);
        RuleStoneEngine first = current.get();
        assertThat(reloader.reloadIfNewer()).isFalse();
        assertThat(current.get()).isSameAs(first);
        assertThat(failures).containsExactly("2: invalid decision definition (1 problem):\n"
                + "  - release gate-prod.release.jws: signature does not verify with key 'k'");

        Files.writeString(dir.resolve("releases/gate-prod.release.jws"), issuer.issue(r2), StandardCharsets.UTF_8);
        assertThat(reloader.reloadIfNewer()).isTrue();
        assertThat(current.get()).isNotSameAs(first);
        assertThat(reloader.release()).contains(r2);

        // After a restart, the cache refuses a rollback to release 1.
        Files.writeString(dir.resolve("releases/gate-prod.release.jws"), issuer.issue(r1), StandardCharsets.UTF_8);
        failures.clear();
        AtomicReference<RuleStoneEngine> restarted = new AtomicReference<>();
        RepositoryReloader again = reloader(dir, cache, restarted, failures);
        assertThat(again.reloadIfNewer()).isFalse();
        assertThat(restarted.get()).isNull();
        assertThat(failures).containsExactly("1: invalid decision definition (1 problem):\n"
                + "  - release gate-prod.release.jws: sequence 1 is older than release 2, which this engine already accepted");

        Files.writeString(cache, "tampered", StandardCharsets.UTF_8);
        assertThatThrownBy(() -> reloader(dir, cache, restarted, failures)).isInstanceOf(InvalidDecisionException.class)
                .hasMessageContaining("release cache gate-prod.release.jws: not a JWS compact serialization");
    }

    private RepositoryReloader reloader(Path dir, Path cache, AtomicReference<RuleStoneEngine> current, List<String> failures) {
        return RepositoryReloader.builder(FileRuleRepository.of(dir), TARGET, issuer.verifier())
                .engine(builder -> builder.recordIds(() -> "r"))
                .cache(cache)
                .onReload(current::set)
                .onFailure((sequence, e) -> failures.add(sequence + ": " + e.getMessage()))
                .build();
    }

    /** rules/ with both versions, the approval and the release. */
    private void layout(Path dir, Release release) throws IOException {
        Files.createDirectories(dir.resolve("rules"));
        Files.createDirectories(dir.resolve("approvals"));
        Files.createDirectories(dir.resolve("releases"));
        Files.writeString(dir.resolve("rules/gate.v1.stone.json"), gate("1", "2026-01-01", "score >= 70"), StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("rules/gate.v2.stone.json"), gate("2", "2027-01-01", "score >= 60"), StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("approvals/CHG-1.approval.jws"), approval + "\n", StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("releases/" + TARGET + ".release.jws"), issuer.issue(release) + "\n",
                StandardCharsets.UTF_8);
    }
}
