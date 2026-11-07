//
// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.rulestone.governance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static vn.io.dxa.rulestone.governance.GovernanceFixture.CHECKER;
import static vn.io.dxa.rulestone.governance.GovernanceFixture.MAKER;
import static vn.io.dxa.rulestone.governance.GovernanceFixture.RELEASER;
import static vn.io.dxa.rulestone.governance.GovernanceFixture.TARGET;
import static vn.io.dxa.rulestone.governance.GovernanceFixture.gate;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import vn.io.dxa.rulestone.api.ArtifactKind;
import vn.io.dxa.rulestone.api.RuleRepository;
import vn.io.dxa.rulestone.core.ApprovalStatement;
import vn.io.dxa.rulestone.core.ApprovalStatementJson;
import vn.io.dxa.rulestone.core.ApprovalVerifier;
import vn.io.dxa.rulestone.core.JwsWriter;
import vn.io.dxa.rulestone.core.Release;
import vn.io.dxa.rulestone.core.ReleaseJson;

/** The verification tool (design section 4.2): the log, the statements it issued and the release chain. */
class GovernanceVerifierTest {

    private final GovernanceFixture f = new GovernanceFixture();
    private final GovernanceVerifier verifier = GovernanceVerifier.builder().verifier(f.verifier).build();

    @BeforeEach
    void history() {
        f.activated("CHG-1", gate("1", "2026-01-01", 70));
        f.as(MAKER).service.createChangeSet("CHG-2", "Second");
        f.service.saveDraft("CHG-2", ArtifactKind.TABLE, gate("2", "2027-01-01", 60).json());
        f.service.submit("CHG-2");
        f.as(CHECKER).service.approve("CHG-2", "RULE_CHECKER", "Reviewed");
        f.as(RELEASER).service.activate("CHG-2", TARGET);
    }

    private List<RuleRepository.Signed> approvals() {
        return f.store.approvals().stream().map(a -> new RuleRepository.Signed(a.name(), a.jws())).toList();
    }

    private List<RuleRepository.Signed> releases() {
        return f.store.releases(TARGET).stream()
                .map(r -> new RuleRepository.Signed(TARGET + "." + r.release().sequence(), r.jws())).toList();
    }

    private List<String> problems(List<LogEntry> log, List<RuleRepository.Signed> approvals, List<RuleRepository.Signed> releases) {
        return verifier.verify(log, approvals, releases).problems().stream().map(VerificationReport.Problem::message).toList();
    }

    private String sign(ApprovalStatement statement) {
        String input = JwsWriter.signingInput(JwsWriter.header(f.signer.algorithm(), f.signer.keyId(), ApprovalVerifier.TYPE),
                ApprovalStatementJson.write(statement));
        return JwsWriter.compact(input, f.signer.sign(input.getBytes(StandardCharsets.US_ASCII)));
    }

    private String sign(Release release) {
        String input = JwsWriter.signingInput(JwsWriter.header(f.signer.algorithm(), f.signer.keyId(), ApprovalVerifier.RELEASE_TYPE),
                ReleaseJson.write(release));
        return JwsWriter.compact(input, f.signer.sign(input.getBytes(StandardCharsets.US_ASCII)));
    }

    private ApprovalStatement real(String changeSet) {
        return f.store.approvals().stream().filter(a -> a.statement().changeSet().equals(changeSet)).findFirst().orElseThrow()
                .statement();
    }

    private static ApprovalStatement with(ApprovalStatement s, List<String> makers, List<ApprovalStatement.Approver> approvers,
            Optional<String> logEntry) {
        return new ApprovalStatement(s.approvalId(), s.changeSet(), s.subjects(), makers, approvers, s.policy(), s.emergency(),
                s.postApprovalDue(), s.issuer(), s.issuedAt(), logEntry);
    }

    private List<RuleRepository.Signed> replaced(String changeSet, String jws) {
        List<RuleRepository.Signed> list = new ArrayList<>();
        for (RuleRepository.Signed signed : approvals()) {
            list.add(signed.name().equals(changeSet + ".approval.jws") ? new RuleRepository.Signed(signed.name(), jws) : signed);
        }
        return list;
    }

    // ---------------------------------------------------------------------------------------------

    @Test
    void whatTheServiceIssuedVerifies() {
        VerificationReport report = verifier.verify(f.store);
        assertThat(report.problems()).isEmpty();
        assertThat(report.statements()).isEqualTo(2);
        assertThat(report.releases()).isEqualTo(2);
        assertThat(report.entries()).isEqualTo(f.store.log().size());
    }

    @Test
    void aStatementThatTheLogDoesNotKnowIsFound() {
        ApprovalStatement real = real("CHG-2");
        ApprovalStatement unknown = with(real, real.makers(), real.approvers(), Optional.of("sha256:" + "ab".repeat(32)));
        assertThat(problems(f.store.log(), replaced("CHG-2", sign(unknown)), releases()))
                .anyMatch(p -> p.startsWith("approval CHG-2.approval.jws (change set CHG-2): names log entry sha256:abab")
                        && p.endsWith("which is not in the log"))
                .anyMatch(p -> p.contains("APPROVAL_ISSUED has no approval statement among those given"));
        ApprovalStatement none = with(real, real.makers(), real.approvers(), Optional.empty());
        assertThat(problems(f.store.log(), replaced("CHG-2", sign(none)), releases()))
                .contains("approval CHG-2.approval.jws (change set CHG-2): names no log entry");
    }

    @Test
    void aStatementThatNamesTheWrongEntryIsFound() {
        ApprovalStatement real = real("CHG-2");
        LogEntry submitted = f.store.log().stream().filter(e -> e.type() == EventType.SUBMITTED).reduce((a, b) -> b).orElseThrow();
        ApprovalStatement wrong = with(real, real.makers(), real.approvers(), Optional.of(submitted.hash()));
        assertThat(problems(f.store.log(), replaced("CHG-2", sign(wrong)), releases()))
                .contains("approval CHG-2.approval.jws (change set CHG-2): names an entry of type SUBMITTED, not one that issues a statement");

        // The entry of another change set, with a statement that borrows it.
        ApprovalStatement borrowed = with(real, real.makers(), real.approvers(), real("CHG-1").logEntry());
        assertThat(problems(f.store.log(), replaced("CHG-2", sign(borrowed)), releases()))
                .contains("approval CHG-2.approval.jws (change set CHG-2): the entry belongs to another change set");
    }

    @Test
    void aStatementThatHidesAMakerOrNamesAMakerAsApproverIsFound() {
        ApprovalStatement real = real("CHG-2");
        ApprovalStatement hidden = with(real, List.of("u:someone.else"), real.approvers(), real.logEntry());
        assertThat(problems(f.store.log(), replaced("CHG-2", sign(hidden)), releases()))
                .anyMatch(p -> p.contains("omits makers that the log shows saving or submitting the change set: [u:nguyen.van.a]"));

        List<ApprovalStatement.Approver> self = List.of(new ApprovalStatement.Approver("u:nguyen.van.a", Optional.of("RULE_CHECKER"),
                "2026-11-20T03:00:00Z"));
        ApprovalStatement selfApproved = with(real, List.of("u:someone.else"), self, real.logEntry());
        assertThat(problems(f.store.log(), replaced("CHG-2", sign(selfApproved)), releases()))
                .anyMatch(p -> p.contains("approver u:nguyen.van.a is also a maker"));
    }

    @Test
    void anApproverTheLogNeverSawIsFound() {
        ApprovalStatement real = real("CHG-2");
        List<ApprovalStatement.Approver> ghost = List.of(new ApprovalStatement.Approver("u:ghost", Optional.of("RULE_CHECKER"),
                "2026-11-20T03:00:00Z"));
        assertThat(problems(f.store.log(), replaced("CHG-2", sign(with(real, real.makers(), ghost, real.logEntry()))), releases()))
                .anyMatch(p -> p.contains("the log shows no approval by u:ghost before the statement"));
    }

    @Test
    void aStatementForOtherVersionsIsFound() {
        ApprovalStatement real = real("CHG-2");
        ApprovalStatement other = new ApprovalStatement(real.approvalId(), real.changeSet(),
                List.of(new ApprovalStatement.Subject(ArtifactKind.TABLE, "gate_decision", "2@sha256:" + "cd".repeat(32))),
                real.makers(), real.approvers(), real.policy(), false, Optional.empty(), real.issuer(), real.issuedAt(), real.logEntry());
        assertThat(problems(f.store.log(), replaced("CHG-2", sign(other)), releases()))
                .contains("approval CHG-2.approval.jws (change set CHG-2): the versions differ from those the entry logs");
        ApprovalStatement otherId = new ApprovalStatement("another-id", real.changeSet(), real.subjects(), real.makers(),
                real.approvers(), real.policy(), false, Optional.empty(), real.issuer(), real.issuedAt(), real.logEntry());
        assertThat(problems(f.store.log(), replaced("CHG-2", sign(otherId)), releases()))
                .contains("approval CHG-2.approval.jws (change set CHG-2): the entry names another statement id");
    }

    @Test
    void aRemovedStatementIsFound() {
        List<RuleRepository.Signed> withoutSecond = new ArrayList<>(approvals());
        withoutSecond.remove(1);
        assertThat(problems(f.store.log(), withoutSecond, releases())).singleElement().asString()
                .contains("APPROVAL_ISSUED has no approval statement among those given");
    }

    @Test
    void aStatementOrReleaseSignedWithAnotherKeyIsFound() {
        GovernanceVerifier other = GovernanceVerifier.builder().verifier(new GovernanceFixture("gov-ec").verifier).build();
        assertThat(other.verify(f.store).problems()).extracting(VerificationReport.Problem::message)
                .anyMatch(p -> p.equals("approval CHG-1.approval.jws: unknown key 'gov-ed'"))
                .anyMatch(p -> p.equals("release retail-credit-prod.1.release.jws: unknown key 'gov-ed'"));
    }

    @Test
    void aMissingReleaseOrABrokenChainIsFound() {
        List<RuleRepository.Signed> withoutFirst = new ArrayList<>(releases());
        withoutFirst.remove(0);
        assertThat(problems(f.store.log(), approvals(), withoutFirst))
                .anyMatch(p -> p.equals("release 2 of target 'retail-credit-prod': expected sequence 1: releases are missing or duplicated"))
                .anyMatch(p -> p.contains("previous is sha256:") && p.contains("but the release before it has no predecessor"));

        Release second = f.store.releases(TARGET).get(1).release();
        Release rewired = new Release(TARGET, 2, Optional.of("sha256:" + "ef".repeat(32)), second.artifacts(), second.routing(), second.issuedAt());
        List<RuleRepository.Signed> chained = List.of(releases().get(0), new RuleRepository.Signed("r2", sign(rewired)));
        assertThat(problems(f.store.log(), approvals(), chained)).anyMatch(p -> p.contains("previous is sha256:efef")
                && p.contains("but the release before it has hash sha256:"));
    }

    @Test
    void aReleaseTheLogDoesNotKnowIsFound() {
        Release second = f.store.releases(TARGET).get(1).release();
        Release third = new Release(TARGET, 3, Optional.of(ReleaseJson.hash(second)), second.artifacts(), second.routing(),
                "2026-12-01T00:00:00Z");
        List<RuleRepository.Signed> forged = new ArrayList<>(releases());
        forged.add(new RuleRepository.Signed("r3", sign(third)));
        assertThat(problems(f.store.log(), approvals(), forged))
                .containsExactly("release 3 of target 'retail-credit-prod': the log has no RELEASED entry for this release");
    }

    @Test
    void aReleaseThatListsAVersionNobodyApprovedIsFound() {
        // Forge the log too: a RELEASED entry for a release that lists a version no APPROVAL_ISSUED entry names.
        Release second = f.store.releases(TARGET).get(1).release();
        List<Release.Entry> artifacts = new ArrayList<>(second.artifacts());
        artifacts.add(new Release.Entry(ArtifactKind.TABLE, "unapproved", "1@sha256:" + "12".repeat(32), Release.State.ACTIVE));
        Release third = new Release(TARGET, 3, Optional.of(ReleaseJson.hash(second)), artifacts, List.of(), "2026-12-01T00:00:00Z");
        LogEntry head = f.store.head().orElseThrow();
        LogEntry released = LogEntry.create(head.seq() + 1, "forged", "2026-12-01T00:00:00.000Z", EventType.RELEASED,
                new LogEntry.Actor("svc:forger", Optional.empty()), Optional.empty(),
                List.of(new ApprovalStatement.Subject(ArtifactKind.TABLE, "unapproved", "1@sha256:" + "12".repeat(32))),
                java.util.Map.of("target", TARGET, "sequence", 3L, "releaseHash", ReleaseJson.hash(third)), head.hash());
        List<LogEntry> log = new ArrayList<>(f.store.log());
        log.add(released);
        List<RuleRepository.Signed> forgedReleases = new ArrayList<>(releases());
        forgedReleases.add(new RuleRepository.Signed("r3", sign(third)));
        assertThat(problems(log, approvals(), forgedReleases)).containsExactly(
                "release 3 of target 'retail-credit-prod': lists unapproved@1@sha256:" + "12".repeat(32)
                        + ", which no earlier APPROVAL_ISSUED entry names");
    }

    // ---------------------------------------------------------------------------------------------

    @Test
    void anExportedDirectoryVerifiesAndTamperingWithItsFilesIsFound(@TempDir Path dir) throws IOException {
        GovernanceExport.write(f.store, dir);
        assertThat(verifier.verify(dir).problems()).isEmpty();
        assertThat(dir.resolve("governance-log.jsonl")).exists();
        assertThat(dir.resolve("governance-policy.json")).exists();
        assertThat(dir.resolve("approvals/CHG-1.approval.jws")).exists();
        assertThat(dir.resolve("releases/retail-credit-prod.release.jws")).exists();
        assertThat(dir.resolve("releases/history/retail-credit-prod.1.release.jws")).exists();
        assertThat(dir.resolve("gate_decision.v2.stone.json")).exists();

        // Content edited after approval.
        Path table = dir.resolve("gate_decision.v1.stone.json");
        String content = Files.readString(table);
        Files.writeString(table, content.replace("score >= 70", "score >= 99"));
        assertThat(verifier.verify(dir).problems()).extracting(VerificationReport.Problem::message)
                .containsExactly("target 'retail-credit-prod': no content is stored for gate_decision@"
                        + f.service.changeSet("CHG-1").items().get(0).exactVersion());
        Files.delete(table);
        assertThat(verifier.verify(dir).problems()).hasSize(1);
        Files.writeString(table, content);
        assertThat(verifier.verify(dir).ok()).isTrue();

        // A statement file removed.
        Path statement = dir.resolve("approvals/CHG-2.approval.jws");
        String jws = Files.readString(statement);
        Files.delete(statement);
        assertThat(verifier.verify(dir).problems()).singleElement().asString().contains("has no approval statement");
        Files.writeString(statement, jws.replace('A', 'B'));
        assertThat(verifier.verify(dir).ok()).isFalse();
        Files.writeString(statement, jws);

        // A log line edited, and a line that is not an entry.
        Path logFile = dir.resolve("governance-log.jsonl");
        List<String> lines = new ArrayList<>(Files.readAllLines(logFile));
        String original = lines.get(3);
        lines.set(3, original.replace("CHG-1", "CHG-9"));
        Files.write(logFile, lines);
        VerificationReport edited = verifier.verify(dir);
        assertThat(edited.problems().get(0).seq()).hasValue(4);
        lines.set(3, "not json");
        Files.write(logFile, lines);
        assertThat(verifier.verify(dir).problems().get(0).message()).startsWith("governance-log.jsonl line 4: invalid JSON");
        lines.set(3, original);
        Files.write(logFile, lines);
        assertThat(verifier.verify(dir).ok()).isTrue();

        // A release file replaced by one signed with another key.
        Path release = dir.resolve("releases/history/retail-credit-prod.2.release.jws");
        Files.writeString(release, new GovernanceFixture("gov-ec").signer.keyId() + ".x.y");
        assertThat(verifier.verify(dir).problems().get(0).message()).startsWith("release retail-credit-prod.2.release.jws:");
    }

    @Test
    void theToolPrintsAReportAndReturnsTheExitStatus(@TempDir Path dir) throws IOException {
        GovernanceExport.write(f.store, dir);
        Path key = dir.resolveSibling(dir.getFileName() + "-gov.pem");
        Files.writeString(key, "-----BEGIN PUBLIC KEY-----\n"
                + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII)).encodeToString(f.publicKey.getEncoded())
                + "\n-----END PUBLIC KEY-----\n");
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ByteArrayOutputStream err = new ByteArrayOutputStream();
            int status = VerifyTool.run(new String[] {"verify", dir.toString(), "--key", "gov-ed=" + key}, new PrintStream(out),
                    new PrintStream(err));
            assertThat(status).isZero();
            assertThat(out.toString(StandardCharsets.UTF_8)).contains("approval statements: 2").contains("releases: 2")
                    .contains("OK: the log, the statements and the releases verify");
            assertThat(err.toString(StandardCharsets.UTF_8)).isEmpty();

            Path logFile = dir.resolve("governance-log.jsonl");
            List<String> lines = new ArrayList<>(Files.readAllLines(logFile));
            lines.remove(2);
            Files.write(logFile, lines);
            out.reset();
            assertThat(VerifyTool.run(new String[] {"verify", dir.toString(), "--key", "gov-ed=" + key, "--min-approvers", "1"},
                    new PrintStream(out), new PrintStream(err))).isEqualTo(1);
            assertThat(out.toString(StandardCharsets.UTF_8)).contains("FAILED")
                    .contains("entry 4: expected entry 3: entries are missing, added or reordered");
        } finally {
            Files.deleteIfExists(key);
        }
    }

    @Test
    void theToolRefusesBadArguments(@TempDir Path dir) {
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        PrintStream stderr = new PrintStream(err);
        PrintStream stdout = new PrintStream(new ByteArrayOutputStream());
        assertThat(VerifyTool.run(new String[] {}, stdout, stderr)).isEqualTo(2);
        assertThat(VerifyTool.run(new String[] {"check", dir.toString()}, stdout, stderr)).isEqualTo(2);
        assertThat(VerifyTool.run(new String[] {"verify", dir.toString()}, stdout, stderr)).isEqualTo(2);
        assertThat(VerifyTool.run(new String[] {"verify", dir.toString(), "--key", "nokey"}, stdout, stderr)).isEqualTo(2);
        assertThat(VerifyTool.run(new String[] {"verify", dir.toString(), "--min-approvers", "x"}, stdout, stderr)).isEqualTo(2);
        assertThat(VerifyTool.run(new String[] {"verify", dir.toString(), "--key", "k=" + dir.resolve("missing.pem")}, stdout, stderr))
                .isEqualTo(2);
        assertThat(err.toString(StandardCharsets.UTF_8)).contains("usage: verify <directory>").contains("trust at least one key");
        assertThatThrownBy(() -> verifier.verify(dir)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageStartingWith("cannot read ");
    }
}
