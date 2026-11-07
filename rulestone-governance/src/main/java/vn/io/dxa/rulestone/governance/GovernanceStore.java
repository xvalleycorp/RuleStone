//
// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.rulestone.governance;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import vn.io.dxa.rulestone.api.ArtifactKind;
import vn.io.dxa.rulestone.core.ApprovalStatement;
import vn.io.dxa.rulestone.core.Release;

/**
 * Where the governance service keeps its state: change sets with their drafts, the content of every
 * approved version, the signed statements and releases, the policy and the audit log.
 * {@link InMemoryGovernanceStore} is the implementation of this step; a JDBC store follows, on the
 * rules of the design: versions, statements, releases and log entries are insert-only, and drafts
 * are the only mutable data.
 *
 * <p>The service changes the store only through {@link #commit}, which applies one {@link Update}
 * <b>atomically</b>: a change set moves on, its statement and log entries are added, and the log
 * head moves, or nothing happens. The log has one writer at a time: an update names the head it
 * chains to, and is refused if the head moved. Implementations must be thread-safe.
 */
public interface GovernanceStore {

    Optional<ChangeSet> changeSet(String id);

    /** All change sets, in the order they were created. */
    List<ChangeSet> changeSets();

    /** The version bound to {@code id@label} by an approval, if any. */
    Optional<ArtifactVersion> binding(ArtifactKind kind, String id, String label);

    /** One approved version by its exact version. */
    Optional<ArtifactVersion> version(ArtifactKind kind, String id, String exactVersion);

    /** The last entry of the log. */
    Optional<LogEntry> head();

    /** The whole log, in order. */
    List<LogEntry> log();

    /** Every statement issued, in the order issued. */
    List<IssuedApproval> approvals();

    /** The targets that have a release. */
    Set<String> targets();

    /** The releases of {@code target}, in sequence order. */
    List<IssuedRelease> releases(String target);

    default Optional<IssuedRelease> latestRelease(String target) {
        List<IssuedRelease> releases = releases(target);
        return releases.isEmpty() ? Optional.empty() : Optional.of(releases.get(releases.size() - 1));
    }

    /** The current policy, if one was stored. */
    Optional<GovernancePolicy> policy();

    /**
     * Applies {@code update} atomically.
     *
     * @throws GovernanceException with {@link GovernanceException.Code#CONFLICT} if the log head moved, a change
     *         set was changed meanwhile, a label is bound to other content, a release does not follow
     *         the latest, or the entries do not chain
     */
    void commit(Update update);

    /**
     * The content of an approved version, bound to its label by the approval: insert-only, never
     * changed or deleted.
     *
     * @param exactVersion {@code <label>@sha256:<hex>}
     * @param content      the JSON text as saved
     * @param changeSet    the change set whose approval bound it
     * @param boundAt      when
     */
    record ArtifactVersion(ArtifactKind kind, String id, String label, String exactVersion, String content,
            String changeSet, String boundAt) {

        public ArtifactVersion {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(label, "label");
            Objects.requireNonNull(exactVersion, "exactVersion");
            Objects.requireNonNull(content, "content");
            Objects.requireNonNull(changeSet, "changeSet");
            Objects.requireNonNull(boundAt, "boundAt");
        }
    }

    /**
     * A signed approval statement the service issued.
     *
     * @param name      what a repository calls it, such as {@code CHG-1.approval.jws}
     * @param statement the statement
     * @param jws       the signed text
     */
    record IssuedApproval(String name, ApprovalStatement statement, String jws) {

        public IssuedApproval {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(statement, "statement");
            Objects.requireNonNull(jws, "jws");
        }
    }

    /**
     * A signed release the service issued.
     *
     * @param release the release
     * @param jws     the signed text
     * @param hash    the release's hash, which the next release names as {@code previous}
     */
    record IssuedRelease(Release release, String jws, String hash) {

        public IssuedRelease {
            Objects.requireNonNull(release, "release");
            Objects.requireNonNull(jws, "jws");
            Objects.requireNonNull(hash, "hash");
        }
    }

    /**
     * What one operation changes. Change sets are the records after the change: a new one has revision 1,
     * a changed one the revision of the stored record plus one.
     *
     * @param expectedHead the hash of the log head the entries chain to, {@link LogEntry#GENESIS} for an empty log
     * @param entries      log entries to append, in order
     * @param changeSets   change sets to add or replace
     * @param versions     approved versions to bind
     * @param approvals    statements to add
     * @param releases     releases to add
     * @param policies     policies to store, the last being the current one
     */
    record Update(String expectedHead, List<LogEntry> entries, List<ChangeSet> changeSets, List<ArtifactVersion> versions,
            List<IssuedApproval> approvals, List<IssuedRelease> releases, List<GovernancePolicy> policies) {

        public Update {
            Objects.requireNonNull(expectedHead, "expectedHead");
            entries = List.copyOf(entries);
            changeSets = List.copyOf(changeSets);
            versions = List.copyOf(versions);
            approvals = List.copyOf(approvals);
            releases = List.copyOf(releases);
            policies = List.copyOf(policies);
        }

        public static Builder builder(String expectedHead) {
            return new Builder(expectedHead);
        }

        /** Collects the parts of an {@link Update}. */
        public static final class Builder {

            private final String expectedHead;
            private final List<LogEntry> entries = new ArrayList<>();
            private final List<ChangeSet> changeSets = new ArrayList<>();
            private final List<ArtifactVersion> versions = new ArrayList<>();
            private final List<IssuedApproval> approvals = new ArrayList<>();
            private final List<IssuedRelease> releases = new ArrayList<>();
            private final List<GovernancePolicy> policies = new ArrayList<>();

            private Builder(String expectedHead) {
                this.expectedHead = expectedHead;
            }

            public Builder entry(LogEntry entry) {
                entries.add(entry);
                return this;
            }

            public Builder changeSet(ChangeSet changeSet) {
                changeSets.add(changeSet);
                return this;
            }

            public Builder version(ArtifactVersion version) {
                versions.add(version);
                return this;
            }

            public Builder approval(IssuedApproval approval) {
                approvals.add(approval);
                return this;
            }

            public Builder release(IssuedRelease release) {
                releases.add(release);
                return this;
            }

            public Builder policy(GovernancePolicy policy) {
                policies.add(policy);
                return this;
            }

            public Update build() {
                return new Update(expectedHead, entries, changeSets, versions, approvals, releases, policies);
            }
        }
    }
}
