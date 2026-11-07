//
// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.rulestone.governance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static vn.io.dxa.rulestone.governance.GovernanceFixture.CHECKER;
import static vn.io.dxa.rulestone.governance.GovernanceFixture.gate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;
import vn.io.dxa.rulestone.api.ArtifactKind;
import vn.io.dxa.rulestone.api.RuleRepository;
import vn.io.dxa.rulestone.core.ApprovalStatement;

/** The hash-chained governance log (design section 4): canonical entries, the chain, and tamper detection. */
class GovernanceLogTest {

    private static final ApprovalStatement.Subject SUBJECT =
            new ApprovalStatement.Subject(ArtifactKind.TABLE, "loan_eligibility", "4@sha256:" + "9f".repeat(32));

    private static LogEntry entry() {
        return LogEntry.create(1, "9d2b7a1e-3c4f-4a5b-8e6d-7f0a1b2c3d4e", "2026-11-20T03:12:00.481Z", EventType.APPROVED,
                new LogEntry.Actor("u:tran.thi.b", Optional.of("camunda8:rule-change/2251799813685321")), Optional.of("CHG-2026-0042"),
                List.of(SUBJECT), Map.of("role", "CREDIT_RISK", "comment", "DTI limit matches the minutes"), LogEntry.GENESIS);
    }

    private static String sha256(String text) throws Exception {
        return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
    }

    // ---------------------------------------------------------------------------------------------

    @Test
    void anEntryIsWrittenCanonicallyAndItsHashIsTheSha256OfTheRest() throws Exception {
        LogEntry entry = entry();
        String withoutHash = "{\"actor\":{\"principal\":\"u:tran.thi.b\",\"via\":\"camunda8:rule-change/2251799813685321\"},"
                + "\"at\":\"2026-11-20T03:12:00.481Z\",\"changeSet\":\"CHG-2026-0042\","
                + "\"details\":{\"comment\":\"DTI limit matches the minutes\",\"role\":\"CREDIT_RISK\"},"
                + "\"eventId\":\"9d2b7a1e-3c4f-4a5b-8e6d-7f0a1b2c3d4e\","
                + "\"prev\":\"sha256:" + "0".repeat(64) + "\",\"seq\":1,"
                + "\"subjects\":[{\"id\":\"loan_eligibility\",\"kind\":\"table\",\"version\":\"4@sha256:" + "9f".repeat(32) + "\"}],"
                + "\"type\":\"APPROVED\"}";
        assertThat(entry.hash()).isEqualTo(sha256(withoutHash));
        assertThat(LogEntryJson.write(entry)).isEqualTo(withoutHash.replace("\"prev\"", "\"hash\":\"" + entry.hash() + "\",\"prev\""));
        assertThat(entry.hashMatches()).isTrue();
        assertThat(LogEntry.GENESIS).isEqualTo("sha256:" + "0".repeat(64));
    }

    @Test
    void anEntryReadsBackExactly() {
        LogEntry entry = entry();
        assertThat(LogEntryJson.read(LogEntryJson.write(entry))).isEqualTo(entry);
        LogEntry minimal = LogEntry.create(2, "e-2", "2026-11-20T03:12:01.000Z", EventType.POLICY_CHANGED,
                new LogEntry.Actor("svc:gov", Optional.empty()), Optional.empty(), List.of(),
                Map.of("tiers", List.of("standard"), "toVersion", 2, "nested", Map.of("flag", true)), entry.hash());
        assertThat(LogEntryJson.write(minimal)).doesNotContain("changeSet").doesNotContain("via");
        assertThat(LogEntryJson.read(LogEntryJson.write(minimal))).isEqualTo(minimal);
        assertThat(minimal.details()).containsEntry("toVersion", 2L);
    }

    @Test
    void anEntryReadsStrictly() {
        String json = LogEntryJson.write(entry());
        assertThatThrownBy(() -> LogEntryJson.read(json.replace("\"seq\":1", "\"seq\":1,\"extra\":1"))).hasMessage("$.extra: unknown key");
        assertThatThrownBy(() -> LogEntryJson.read(json.replace("APPROVED", "APPROVE"))).hasMessage("$.type: unknown event type");
        assertThatThrownBy(() -> LogEntryJson.read(json.replace("\"seq\":1", "\"seq\":0"))).hasMessageContaining("$.seq");
        assertThatThrownBy(() -> LogEntryJson.read(json.replace("\"role\":\"CREDIT_RISK\"", "\"role\":1.5")))
                .hasMessageContaining("$.details.role: expected a string, a whole number");
        assertThatThrownBy(() -> LogEntryJson.read(json.replace("\"seq\":1", "\"seq\":1,\"seq\":2"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> LogEntryJson.read("[]")).hasMessage("$: expected a JSON object");
        assertThatThrownBy(() -> LogEntryJson.read("{")).hasMessageStartingWith("invalid JSON");
    }

    @Test
    void detailsHoldOnlyPlainValues() {
        assertThatThrownBy(() -> LogEntry.create(1, "e", "2026-11-20T03:12:00.000Z", EventType.COMMENTED,
                new LogEntry.Actor("u:a", Optional.empty()), Optional.empty(), List.of(), Map.of("score", 1.5), LogEntry.GENESIS))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("details.score");
        assertThatThrownBy(() -> LogEntry.create(1, "e", "2026-11-20T03:12:00.000Z", EventType.COMMENTED,
                new LogEntry.Actor("u:a", Optional.empty()), Optional.empty(), List.of(), Map.of("object", new Object()), LogEntry.GENESIS))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aChangedEntryNoLongerMatchesItsHash() {
        LogEntry entry = entry();
        LogEntry edited = new LogEntry(entry.seq(), entry.eventId(), entry.at(), entry.type(), entry.actor(), entry.changeSet(),
                entry.subjects(), Map.of("role", "COMPLIANCE", "comment", "x"), entry.prev(), entry.hash());
        assertThat(edited.hashMatches()).isFalse();
    }

    // ---------------------------------------------------------------------------------------------

    private final GovernanceFixture f = new GovernanceFixture();
    private List<LogEntry> log;
    private List<RuleRepository.Signed> approvals;
    private List<RuleRepository.Signed> releases;

    private void history() {
        f.activated("CHG-1", gate("1", "2026-01-01", 70));
        f.as(GovernanceFixture.MAKER).service.createChangeSet("CHG-2", "Second");
        f.service.saveDraft("CHG-2", ArtifactKind.TABLE, gate("2", "2027-01-01", 60).json());
        f.service.submit("CHG-2");
        f.as(CHECKER).service.comment("CHG-2", null, "Looks right");
        f.service.approve("CHG-2", "RULE_CHECKER", "Reviewed");
        f.as(GovernanceFixture.RELEASER).service.activate("CHG-2", GovernanceFixture.TARGET);
        log = new ArrayList<>(f.store.log());
        approvals = f.store.approvals().stream().map(a -> new RuleRepository.Signed(a.name(), a.jws())).toList();
        releases = f.store.releases(GovernanceFixture.TARGET).stream()
                .map(r -> new RuleRepository.Signed("r" + r.release().sequence(), r.jws())).toList();
    }

    private VerificationReport verify(List<LogEntry> entries) {
        return GovernanceVerifier.builder().verifier(f.verifier).build().verify(entries, approvals, releases);
    }

    private static LogEntry with(LogEntry e, Map<String, Object> details) {
        return new LogEntry(e.seq(), e.eventId(), e.at(), e.type(), e.actor(), e.changeSet(), e.subjects(), details, e.prev(), e.hash());
    }

    private static LogEntry renumbered(LogEntry e, long seq) {
        return new LogEntry(seq, e.eventId(), e.at(), e.type(), e.actor(), e.changeSet(), e.subjects(), e.details(), e.prev(), e.hash());
    }

    @Test
    void theLogOfAServiceChainsFromGenesisWithoutGaps() {
        history();
        assertThat(log.get(0).prev()).isEqualTo(LogEntry.GENESIS);
        for (int i = 0; i < log.size(); i++) {
            assertThat(log.get(i).seq()).isEqualTo(i + 1);
            assertThat(log.get(i).hashMatches()).isTrue();
            if (i > 0) {
                assertThat(log.get(i).prev()).isEqualTo(log.get(i - 1).hash());
            }
        }
        VerificationReport report = verify(log);
        assertThat(report.problems()).isEmpty();
        assertThat(report.ok()).isTrue();
        assertThat(report.entries()).isEqualTo(log.size());
        assertThat(report.format()).contains("log entries: " + log.size()).contains("approval statements: 2").contains("releases: 2")
                .contains("OK: the log, the statements and the releases verify");
    }

    @Test
    void anEditedEntryIsFoundAtThatEntry() {
        history();
        int index = 5;
        List<LogEntry> edited = new ArrayList<>(log);
        edited.set(index, with(log.get(index), Map.of("role", "CREDIT_RISK", "comment", "Rewritten afterwards")));
        VerificationReport report = verify(edited);
        assertThat(report.ok()).isFalse();
        assertThat(report.problems().get(0).seq()).isEqualTo(OptionalLong.of(index + 1));
        assertThat(report.problems().get(0).message()).isEqualTo("hash does not match the entry's content: the entry was edited");
        assertThat(report.format()).contains("FAILED: 2 problems").contains("entry " + (index + 1) + ": hash does not match");
    }

    @Test
    void anEditedEntryWithItsHashRecomputedBreaksTheNextEntry() {
        history();
        int index = 4;
        LogEntry original = log.get(index);
        LogEntry forged = LogEntry.create(original.seq(), original.eventId(), original.at(), original.type(), original.actor(),
                original.changeSet(), original.subjects(), Map.of("role", "COMPLIANCE"), original.prev());
        List<LogEntry> edited = new ArrayList<>(log);
        edited.set(index, forged);
        VerificationReport report = verify(edited);
        assertThat(report.problems().get(0).seq()).isEqualTo(OptionalLong.of(index + 2));
        assertThat(report.problems().get(0).message()).startsWith("prev is not the hash of entry " + (index + 1));
    }

    @Test
    void aRemovedEntryLeavesAGap() {
        history();
        List<LogEntry> removed = new ArrayList<>(log);
        removed.remove(6);
        VerificationReport report = verify(removed);
        assertThat(report.problems().get(0).seq()).isEqualTo(OptionalLong.of(8));
        assertThat(report.problems().get(0).message()).isEqualTo("expected entry 7: entries are missing, added or reordered");

        List<LogEntry> noFirst = new ArrayList<>(log);
        noFirst.remove(0);
        assertThat(verify(noFirst).problems().get(0).message()).startsWith("expected entry 1");
    }

    @Test
    void aRemovedEntryWhoseSuccessorsAreRenumberedBreaksThePrevChain() {
        history();
        List<LogEntry> removed = new ArrayList<>(log.subList(0, 6));
        for (int i = 7; i < log.size(); i++) {
            removed.add(renumbered(log.get(i), i));
        }
        VerificationReport report = verify(removed);
        assertThat(report.problems().get(0).seq()).isEqualTo(OptionalLong.of(7));
        assertThat(report.problems().get(0).message()).startsWith("prev is not the hash of entry 6");
    }

    @Test
    void reorderedEntriesAreFound() {
        history();
        List<LogEntry> reordered = new ArrayList<>(log);
        reordered.set(3, log.get(4));
        reordered.set(4, log.get(3));
        VerificationReport report = verify(reordered);
        assertThat(report.problems().get(0).seq()).isEqualTo(OptionalLong.of(5));
        assertThat(report.problems().get(0).message()).startsWith("expected entry 4");

        List<LogEntry> renumbered = new ArrayList<>(log);
        renumbered.set(3, renumbered(log.get(4), 4));
        renumbered.set(4, renumbered(log.get(3), 5));
        assertThat(verify(renumbered).problems().get(0).message()).startsWith("prev is not the hash of entry 3");
    }

    @Test
    void anAppendedForgedEntryIsFound() {
        history();
        List<LogEntry> forged = new ArrayList<>(log);
        forged.add(LogEntry.create(log.size() + 1, "forged", "2026-12-01T00:00:00.000Z", EventType.ACTIVATED,
                new LogEntry.Actor("u:nguyen.van.a", Optional.empty()), Optional.of("CHG-1"), List.of(), Map.of(), LogEntry.GENESIS));
        assertThat(verify(forged).problems().get(0).message()).startsWith("prev is not the hash of entry " + log.size());
    }

    @Test
    void whenTheChainFailsTheLaterChecksAreSaidToBeSkipped() {
        history();
        List<LogEntry> removed = new ArrayList<>(log);
        removed.remove(2);
        VerificationReport report = verify(removed);
        assertThat(report.problems()).hasSize(2);
        assertThat(report.problems().get(1).message()).isEqualTo("the checks of statements and releases need an intact log, and were skipped");
    }

    @Test
    void aTruncatedLogIsOnlyFoundThroughAnAnchor() {
        history();
        LogEntry anchored = log.get(log.size() - 1);
        AnchorCheck anchor = entries -> entries.isEmpty() || entries.get(entries.size() - 1).hash().equals(anchored.hash())
                ? List.of()
                : List.of("the head is " + entries.get(entries.size() - 1).seq() + ", but entry " + anchored.seq() + " was anchored");
        GovernanceVerifier verifier = GovernanceVerifier.builder().verifier(f.verifier).anchors(anchor).build();

        assertThat(verifier.verify(log, approvals, releases).ok()).isTrue();
        // The chain of a log that lost its last entries is intact; only the anchor knows it is shorter.
        List<LogEntry> truncated = new ArrayList<>(log.subList(0, log.size() - 2));
        assertThat(verify(truncated).problems().stream().map(VerificationReport.Problem::message))
                .doesNotContain("expected entry " + (truncated.size() + 1));
        VerificationReport report = verifier.verify(truncated, approvals, releases);
        assertThat(report.problems()).extracting(VerificationReport.Problem::message)
                .contains("the head is " + truncated.size() + ", but entry " + anchored.seq() + " was anchored");
    }

    @Test
    void aWholeChainRebuiltByAnInsiderDoesNotMatchTheAnchor() {
        history();
        LogEntry anchored = log.get(log.size() - 1);
        AnchorCheck anchor = entries -> entries.get(entries.size() - 1).hash().equals(anchored.hash())
                ? List.of() : List.of("the head differs from the anchored one");
        // The insider changes entry 2 and rebuilds every hash after it.
        List<LogEntry> rebuilt = new ArrayList<>(log.subList(0, 1));
        String prev = log.get(0).hash();
        for (int i = 1; i < log.size(); i++) {
            LogEntry e = log.get(i);
            Map<String, Object> changed = Map.of("title", "Another title");
            Map<String, Object> details = i == 1 ? changed : e.details();
            LogEntry next = LogEntry.create(e.seq(), e.eventId(), e.at(), e.type(), e.actor(), e.changeSet(), e.subjects(), details, prev);
            rebuilt.add(next);
            prev = next.hash();
        }
        GovernanceVerifier verifier = GovernanceVerifier.builder().verifier(f.verifier).anchors(anchor).build();
        VerificationReport report = verifier.verify(rebuilt, approvals, releases);
        assertThat(report.problems().stream().map(VerificationReport.Problem::message)).contains("the head differs from the anchored one");
    }
}
