//
// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.rulestone.governance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static vn.io.dxa.rulestone.governance.GovernanceFixture.TARGET;
import static vn.io.dxa.rulestone.governance.GovernanceFixture.gate;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import vn.io.dxa.rulestone.api.ArtifactKind;
import vn.io.dxa.rulestone.core.Release;
import vn.io.dxa.rulestone.core.ReleaseJson;

/** The store applies an update completely or not at all, and refuses what would break its invariants. */
class InMemoryGovernanceStoreTest {

    private final GovernanceFixture f = new GovernanceFixture();

    private LogEntry next(LogEntry head, String id) {
        return LogEntry.create(head.seq() + 1, id, "2026-11-20T03:00:00.000Z", EventType.COMMENTED,
                new LogEntry.Actor("u:a", Optional.empty()), Optional.empty(), List.of(), Map.of(), head.hash());
    }

    private static void conflict(Runnable call, String message) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(GovernanceException.class, e -> {
            assertThat(e.code()).isEqualTo(GovernanceException.Code.CONFLICT);
            assertThat(e.getMessage()).contains(message);
        });
    }

    @Test
    void anUpdateAppendsToTheHeadItNamesAndNoOther() {
        f.activated("CHG-1", gate("1", "2026-01-01", 70));
        LogEntry head = f.store.head().orElseThrow();
        int size = f.store.log().size();

        conflict(() -> f.store.commit(GovernanceStore.Update.builder(LogEntry.GENESIS).entry(next(head, "x")).build()), "the log head moved");
        conflict(() -> f.store.commit(GovernanceStore.Update.builder(head.hash())
                .entry(LogEntry.create(head.seq() + 2, "x", "2026-11-20T03:00:00.000Z", EventType.COMMENTED,
                        new LogEntry.Actor("u:a", Optional.empty()), Optional.empty(), List.of(), Map.of(), head.hash())).build()),
                "does not chain");
        conflict(() -> f.store.commit(GovernanceStore.Update.builder(head.hash())
                .entry(LogEntry.create(head.seq() + 1, "x", "2026-11-20T03:00:00.000Z", EventType.COMMENTED,
                        new LogEntry.Actor("u:a", Optional.empty()), Optional.empty(), List.of(), Map.of(), LogEntry.GENESIS)).build()),
                "does not chain");
        assertThat(f.store.log()).hasSize(size);

        LogEntry good = next(head, "ok");
        f.store.commit(GovernanceStore.Update.builder(head.hash()).entry(good).build());
        assertThat(f.store.head()).contains(good);
        conflict(() -> f.store.commit(GovernanceStore.Update.builder(head.hash()).entry(next(head, "again")).build()), "the log head moved");
    }

    @Test
    void anUpdateThatFailsChangesNothing() {
        f.activated("CHG-1", gate("1", "2026-01-01", 70));
        LogEntry head = f.store.head().orElseThrow();
        ChangeSet cs = f.service.changeSet("CHG-1");
        int size = f.store.log().size();
        // A valid entry and a change set update, together with a version label that is already bound.
        GovernanceStore.ArtifactVersion duplicate = new GovernanceStore.ArtifactVersion(ArtifactKind.TABLE, "gate_decision", "1",
                "1@sha256:" + "ab".repeat(32), "{}", "CHG-9", "2026-11-20T03:00:00.000Z");
        conflict(() -> f.store.commit(GovernanceStore.Update.builder(head.hash()).entry(next(head, "x"))
                .changeSet(cs.withRevision(cs.revision() + 1)).version(duplicate).build()), "already bound to a content hash");
        assertThat(f.store.log()).hasSize(size);
        assertThat(f.store.changeSet("CHG-1")).contains(cs);
    }

    @Test
    void aChangeSetIsReplacedOnlyByTheNextRevision() {
        f.as(GovernanceFixture.MAKER).service.createChangeSet("CHG-1", "x");
        LogEntry head = f.store.head().orElseThrow();
        ChangeSet cs = f.service.changeSet("CHG-1");
        conflict(() -> f.store.commit(GovernanceStore.Update.builder(head.hash()).changeSet(cs).build()), "was changed meanwhile");
        conflict(() -> f.store.commit(GovernanceStore.Update.builder(head.hash()).changeSet(cs.withRevision(cs.revision() + 2)).build()),
                "was changed meanwhile");
        conflict(() -> f.store.commit(GovernanceStore.Update.builder(head.hash())
                .changeSet(ChangeSet.create("CHG-2", "x", "u:a").withRevision(2)).build()), "CHG-2 was changed meanwhile");
        f.store.commit(GovernanceStore.Update.builder(head.hash()).changeSet(cs.withRevision(cs.revision() + 1)).build());
        assertThat(f.store.changeSet("CHG-1").orElseThrow().revision()).isEqualTo(cs.revision() + 1);
    }

    @Test
    void aReleaseThatDoesNotFollowTheLatestIsRefused() {
        f.activated("CHG-1", gate("1", "2026-01-01", 70));
        f.activated("CHG-2", gate("2", "2027-01-01", 60));
        GovernanceStore.IssuedRelease first = f.store.releases(TARGET).get(0);
        GovernanceStore.IssuedRelease second = f.store.releases(TARGET).get(1);
        LogEntry head = f.store.head().orElseThrow();
        List<Release.Entry> entries = second.release().artifacts();

        // A rollback: an older sequence, a repeated one, one that skips a number, and a broken chain.
        for (Release release : List.of(
                new Release(TARGET, 1, Optional.empty(), first.release().artifacts(), List.of(), "2026-11-20T03:00:00Z"),
                new Release(TARGET, 2, Optional.of(first.hash()), entries, List.of(), "2026-11-20T03:00:01Z"),
                new Release(TARGET, 4, Optional.of(second.hash()), entries, List.of(), "2026-11-20T03:00:01Z"),
                new Release(TARGET, 3, Optional.of(first.hash()), entries, List.of(), "2026-11-20T03:00:01Z"),
                new Release(TARGET, 3, Optional.empty(), entries, List.of(), "2026-11-20T03:00:01Z"))) {
            conflict(() -> f.store.commit(GovernanceStore.Update.builder(head.hash())
                    .release(new GovernanceStore.IssuedRelease(release, "jws", ReleaseJson.hash(release))).build()),
                    "does not follow the latest release");
        }
        // A hash that is not the release's own.
        Release third = new Release(TARGET, 3, Optional.of(second.hash()), entries, List.of(), "2026-11-20T03:00:01Z");
        conflict(() -> f.store.commit(GovernanceStore.Update.builder(head.hash())
                .release(new GovernanceStore.IssuedRelease(third, "jws", "sha256:" + "00".repeat(32))).build()), "does not follow");
        assertThat(f.store.releases(TARGET)).hasSize(2);
        f.store.commit(GovernanceStore.Update.builder(head.hash())
                .release(new GovernanceStore.IssuedRelease(third, "jws", ReleaseJson.hash(third))).build());
        assertThat(f.store.latestRelease(TARGET).orElseThrow().release().sequence()).isEqualTo(3);
    }

    @Test
    void aStatementNameIsUsedOnce() {
        f.activated("CHG-1", gate("1", "2026-01-01", 70));
        LogEntry head = f.store.head().orElseThrow();
        GovernanceStore.IssuedApproval existing = f.store.approvals().get(0);
        conflict(() -> f.store.commit(GovernanceStore.Update.builder(head.hash()).approval(existing).build()),
                "a statement named CHG-1.approval.jws exists");
    }

    @Test
    void whatTheStoreReturnsCannotBeUsedToChangeIt() {
        f.activated("CHG-1", gate("1", "2026-01-01", 70));
        assertThatThrownBy(() -> f.store.log().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> f.store.approvals().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> f.store.changeSets().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> f.store.log().get(0).details().put("x", "y")).isInstanceOf(UnsupportedOperationException.class);
        assertThat(f.store.targets()).containsExactly(TARGET);
        assertThat(f.store.releases("other")).isEmpty();
        assertThat(f.store.latestRelease("other")).isEmpty();
    }

    @Test
    void theRepositoryOverTheStoreReadsWhatWasIssued() {
        f.activated("CHG-1", gate("1", "2026-01-01", 70));
        var repository = f.service.repository();
        var snapshot = repository.load(TARGET);
        assertThat(snapshot.sequence()).isEqualTo(1);
        assertThat(snapshot.release().name()).isEqualTo("retail-credit-prod.release.jws");
        assertThat(snapshot.approvals()).extracting(a -> a.name()).containsExactly("CHG-1.approval.jws");
        assertThat(snapshot.artifacts()).singleElement().satisfies(a -> {
            assertThat(a.kind()).isEqualTo(ArtifactKind.TABLE);
            assertThat(a.id()).isEqualTo("gate_decision");
            assertThat(a.version()).isEqualTo("1");
            assertThat(a.source()).isEqualTo("change set CHG-1");
        });
        assertThat(repository.latestSequence(TARGET)).isEqualTo(1);
        assertThat(repository.find(ArtifactKind.TABLE, "gate_decision", f.service.changeSet("CHG-1").items().get(0).exactVersion()))
                .isPresent();
        assertThat(repository.find(ArtifactKind.TABLE, "gate_decision", "1@sha256:" + "00".repeat(32))).isEmpty();
        assertThatThrownBy(() -> repository.load("other")).isInstanceOf(vn.io.dxa.rulestone.api.RepositoryException.class)
                .hasMessage("no release of target 'other'");
        assertThatThrownBy(() -> repository.latestSequence("other")).isInstanceOf(vn.io.dxa.rulestone.api.RepositoryException.class);
    }
}
