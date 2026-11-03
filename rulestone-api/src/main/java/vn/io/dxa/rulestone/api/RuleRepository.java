//
// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.rulestone.api;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.LongConsumer;

/**
 * Where an engine reads its rules from: the current release of a target, with the content of every
 * version it lists and the approval statements, and single exact versions for replay. The interface
 * is read-only and returns raw content, never parsed objects or trusted hashes, so that the engine
 * computes every hash itself and verifies every signature; a repository is not trusted.
 *
 * <p>Implementations: files and Git checkouts ({@code FileRuleRepository} in {@code rulestone-core}),
 * or the bank's own store. Writing drafts, submissions and governance events is not this interface's
 * business. Implementations must be thread-safe.
 */
public interface RuleRepository {

    /**
     * The current release of {@code target}, the content of every version it lists and the approval
     * statements, for the caller to verify.
     *
     * @throws RepositoryException if the repository cannot be read or has no release for the target
     */
    Snapshot load(String target);

    /**
     * One exact version, for replay, also when it is retired or not in the current release.
     *
     * @param exactVersion {@code <label>@sha256:<hex>}
     * @throws RepositoryException if the repository cannot be read
     */
    Optional<Artifact> find(ArtifactKind kind, String id, String exactVersion);

    /**
     * The sequence of the current release of {@code target}, as the repository claims it, to decide
     * cheaply whether to {@link #load} it. The default loads the whole snapshot.
     *
     * @throws RepositoryException if the repository cannot be read or has no release for the target
     */
    default long latestSequence(String target) {
        return load(target).sequence();
    }

    /**
     * Calls {@code newSequence} when a newer release of {@code target} is issued, if the repository
     * can tell; otherwise returns {@link Subscription#NONE} and the caller polls. Optional.
     */
    default Subscription watch(String target, LongConsumer newSequence) {
        return Subscription.NONE;
    }

    /**
     * The content of one version, as stored.
     *
     * @param kind    the kind of artifact
     * @param id      its id; for a catalog, the name the repository gives it, by default {@code reason-codes}
     * @param version its version label as stored, not verified
     * @param source  where it came from, such as a file name, for problems
     * @param content the JSON text, exactly as stored
     */
    record Artifact(ArtifactKind kind, String id, String version, String source, String content) {

        public Artifact {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(version, "version");
            Objects.requireNonNull(source, "source");
            Objects.requireNonNull(content, "content");
        }
    }

    /**
     * A signed document: an approval statement or a release, in JWS compact serialization.
     *
     * @param name the name problems give it, such as its file name
     * @param jws  the text
     */
    record Signed(String name, String jws) {

        public Signed {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(jws, "jws");
        }
    }

    /**
     * What an engine loads for one target.
     *
     * @param target    the target
     * @param sequence  the sequence of the release, as the repository read it, not verified; 0 if unreadable
     * @param release   the signed release
     * @param artifacts the content of the versions the release lists
     * @param approvals the approval statements
     */
    record Snapshot(String target, long sequence, Signed release, List<Artifact> artifacts, List<Signed> approvals) {

        public Snapshot {
            Objects.requireNonNull(target, "target");
            Objects.requireNonNull(release, "release");
            artifacts = List.copyOf(artifacts);
            approvals = List.copyOf(approvals);
        }
    }

    /** A registration made by {@link #watch}; closing it stops the notifications. */
    @FunctionalInterface
    interface Subscription extends AutoCloseable {

        /** A subscription that notifies nothing. */
        Subscription NONE = () -> {};

        @Override
        void close();
    }
}
