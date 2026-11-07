//
// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.rulestone.governance;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import vn.io.dxa.rulestone.api.ArtifactKind;
import vn.io.dxa.rulestone.api.RepositoryException;
import vn.io.dxa.rulestone.api.RuleRepository;
import vn.io.dxa.rulestone.core.Release;
import vn.io.dxa.rulestone.core.RuleStone;

/**
 * A read-only {@link RuleRepository} over a {@link GovernanceStore}: the current release the service
 * issued for a target, the content of every version it lists and the signed approval statements.
 * An engine built from {@link #load} with {@code requireApproved} and {@code requireRelease}
 * accepts exactly what the service issued:
 *
 * <pre>{@code
 * RuleStoneEngine engine = RuleStone.builder()
 *         .snapshot(new StoreRuleRepository(store).load("retail-credit-prod"))
 *         .requireApproved(verifier)
 *         .requireRelease("retail-credit-prod")
 *         .build();
 * }</pre>
 *
 * <p>It returns text as stored and trusts nothing: the engine verifies every signature and hash.
 */
public final class StoreRuleRepository implements RuleRepository {

    private final GovernanceStore store;

    public StoreRuleRepository(GovernanceStore store) {
        this.store = Objects.requireNonNull(store, "store");
    }

    @Override
    public Snapshot load(String target) {
        GovernanceStore.IssuedRelease latest = store.latestRelease(target)
                .orElseThrow(() -> new RepositoryException("no release of target '" + target + "'"));
        List<Artifact> artifacts = new ArrayList<>();
        for (Release.Entry entry : latest.release().artifacts()) {
            GovernanceStore.ArtifactVersion version = store.version(entry.kind(), entry.id(), entry.version())
                    .orElseThrow(() -> new RepositoryException("release " + latest.release().sequence() + " of target '"
                            + target + "' lists " + entry.id() + "@" + entry.version() + ", which the store does not hold"));
            artifacts.add(artifact(version));
        }
        List<Signed> approvals = store.approvals().stream()
                .map(a -> new Signed(a.name(), a.jws()))
                .toList();
        return new Snapshot(target, latest.release().sequence(),
                new Signed(target + RuleStone.RELEASE_FILE_SUFFIX, latest.jws()), artifacts, approvals);
    }

    @Override
    public Optional<Artifact> find(ArtifactKind kind, String id, String exactVersion) {
        return store.version(kind, id, exactVersion).map(StoreRuleRepository::artifact);
    }

    @Override
    public long latestSequence(String target) {
        return store.latestRelease(target)
                .orElseThrow(() -> new RepositoryException("no release of target '" + target + "'"))
                .release().sequence();
    }

    private static Artifact artifact(GovernanceStore.ArtifactVersion version) {
        return new Artifact(version.kind(), version.id(), version.label(), "change set " + version.changeSet(),
                version.content());
    }
}
