//
// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.rulestone.core;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import vn.io.dxa.rulestone.api.RuleRepository;
import vn.io.dxa.rulestone.api.RuleStoneEngine;

/**
 * Builds an engine from the current release of a target in a {@link RuleRepository}, and builds a new
 * one whenever a newer release appears, swapping it in only if it builds.
 *
 * <pre>{@code
 * RepositoryReloader reloader = RepositoryReloader.builder(repository, "retail-credit-prod", verifier)
 *         .engine(builder -> builder.auditSink(auditSink).clock(clock))
 *         .cache(Path.of("/var/lib/app/retail-credit-prod.release.jws"))
 *         .onReload(engine::set)                                  // AtomicReference<RuleStoneEngine>
 *         .onFailure((sequence, e) -> log.error("release {} not loaded", sequence, e))
 *         .build();
 * reloader.reloadIfNewer();                                       // at start-up
 * reloader.pollEvery(Duration.ofSeconds(30), scheduler);          // optional
 * }</pre>
 *
 * <p>Each engine is built with {@link RuleStone.Builder#snapshot}, {@link RuleStone.Builder#requireApproved}
 * with the verifier, {@link RuleStone.Builder#requireRelease} with the target and, as
 * {@link RuleStone.Builder#lastAcceptedRelease}, the release the current engine runs, or at start-up
 * the release in the cache. A release that fails verification or validation is never swapped in: the
 * previous engine keeps running and the failure goes to {@code onFailure}. The cache file holds the
 * last release accepted, signed, so that a restarted application cannot be rolled back to an older
 * release; it is written before the new engine is handed over.
 *
 * <p>The reloader starts no thread of its own: call {@link #reloadIfNewer()}, or let
 * {@link #pollEvery} or {@link #watch()} call it. It is thread-safe; reloads never run concurrently.
 */
public final class RepositoryReloader implements AutoCloseable {

    private final RuleRepository repository;
    private final String target;
    private final ApprovalVerifier verifier;
    private final Consumer<RuleStone.Builder> configure;
    private final Consumer<RuleStoneEngine> onReload;
    private final BiConsumer<Long, Exception> onFailure;
    private final Path cache;
    private final List<AutoCloseable> running = new ArrayList<>();
    private volatile RuleStoneEngine engine;
    private volatile Release release;
    private Release floor;

    private RepositoryReloader(Builder builder) {
        this.repository = builder.repository;
        this.target = builder.target;
        this.verifier = builder.verifier;
        this.configure = builder.configure;
        this.onReload = builder.onReload;
        this.onFailure = builder.onFailure;
        this.cache = builder.cache;
        this.floor = cachedRelease();
    }

    public static Builder builder(RuleRepository repository, String target, ApprovalVerifier verifier) {
        return new Builder(repository, target, verifier);
    }

    /** The engine of the last release accepted, if one was. */
    public Optional<RuleStoneEngine> engine() {
        return Optional.ofNullable(engine);
    }

    /** The release the current engine runs, if one was accepted. */
    public Optional<Release> release() {
        return Optional.ofNullable(release);
    }

    /**
     * Loads the target's release and swaps in a new engine if the repository has a release newer than
     * the one running, or if none is running yet. Failures, of the repository or of the build, go to
     * {@code onFailure} and leave the running engine in place.
     *
     * @return whether a new engine was swapped in
     */
    public synchronized boolean reloadIfNewer() {
        long sequence = 0;
        try {
            sequence = repository.latestSequence(target);
            if (release != null && sequence <= release.sequence()) {
                return false;
            }
            RuleRepository.Snapshot snapshot = repository.load(target);
            sequence = snapshot.sequence();
            RuleStone.Builder builder = RuleStone.builder();
            configure.accept(builder);
            builder.snapshot(snapshot).requireApproved(verifier).requireRelease(target);
            if (floor != null) {
                builder.lastAcceptedRelease(floor);
            }
            RuleStoneEngine built = builder.build();
            Release accepted = verifier.verifyRelease(snapshot.release().jws());
            if (release != null && accepted.sequence() == release.sequence()) {
                return false;
            }
            if (cache != null && (floor == null || accepted.sequence() > floor.sequence())) {
                writeCache(snapshot.release().jws());
            }
            floor = accepted;
            release = accepted;
            engine = built;
            onReload.accept(built);
            return true;
        } catch (RuntimeException e) {
            onFailure.accept(sequence, e);
            return false;
        }
    }

    /**
     * Calls {@link #reloadIfNewer()} every {@code interval} on {@code scheduler}, until {@link #close()}.
     */
    public synchronized ScheduledFuture<?> pollEvery(Duration interval, ScheduledExecutorService scheduler) {
        Objects.requireNonNull(interval, "interval");
        Objects.requireNonNull(scheduler, "scheduler");
        if (interval.isNegative() || interval.isZero()) {
            throw new IllegalArgumentException("interval must be positive");
        }
        ScheduledFuture<?> future = scheduler.scheduleWithFixedDelay(this::reloadIfNewer, interval.toMillis(),
                interval.toMillis(), TimeUnit.MILLISECONDS);
        running.add(() -> future.cancel(false));
        return future;
    }

    /**
     * Calls {@link #reloadIfNewer()} whenever the repository notifies a newer release, on the
     * repository's thread, until {@link #close()}. Repositories that cannot notify do nothing.
     */
    public synchronized void watch() {
        RuleRepository.Subscription subscription = repository.watch(target, sequence -> reloadIfNewer());
        running.add(subscription);
    }

    /** Stops polling and watching; the current engine stays usable. */
    @Override
    public synchronized void close() {
        for (AutoCloseable r : running) {
            try {
                r.close();
            } catch (Exception e) {
                // Closing is best effort.
            }
        }
        running.clear();
    }

    private Release cachedRelease() {
        if (cache == null || !Files.exists(cache)) {
            return null;
        }
        String jws;
        try {
            jws = Files.readString(cache, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        ApprovalVerifier.Checked<Release> checked = verifier.checkRelease(jws);
        List<String> problems = new ArrayList<>();
        checked.problems().forEach(p -> problems.add("release cache " + cache.getFileName() + ": " + p));
        checked.value().filter(r -> !r.target().equals(target)).ifPresent(r -> problems.add("release cache "
                + cache.getFileName() + ": target is '" + r.target() + "', but this reloader loads '" + target + "'"));
        if (!problems.isEmpty()) {
            throw new InvalidDecisionException(problems);
        }
        return checked.value().get();
    }

    private void writeCache(String jws) {
        try {
            Path dir = cache.toAbsolutePath().getParent();
            Files.createDirectories(dir);
            Path temp = Files.createTempFile(dir, cache.getFileName().toString(), ".tmp");
            Files.writeString(temp, jws.strip() + "\n", StandardCharsets.UTF_8);
            try {
                Files.move(temp, cache, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, cache, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static final class Builder {

        private final RuleRepository repository;
        private final String target;
        private final ApprovalVerifier verifier;
        private Consumer<RuleStone.Builder> configure = b -> {};
        private Consumer<RuleStoneEngine> onReload = e -> {};
        private BiConsumer<Long, Exception> onFailure = (s, e) -> {};
        private Path cache;

        private Builder(RuleRepository repository, String target, ApprovalVerifier verifier) {
            this.repository = Objects.requireNonNull(repository, "repository");
            this.target = Objects.requireNonNull(target, "target");
            this.verifier = Objects.requireNonNull(verifier, "verifier");
        }

        /**
         * Configures each engine before the reloader adds the snapshot, the verifier and the target:
         * the clock, audit sinks, model invokers and so on. Must not register content.
         */
        public Builder engine(Consumer<RuleStone.Builder> configure) {
            this.configure = Objects.requireNonNull(configure, "configure");
            return this;
        }

        /** Receives each new engine, after the cache is written; e.g. {@code AtomicReference::set}. */
        public Builder onReload(Consumer<RuleStoneEngine> onReload) {
            this.onReload = Objects.requireNonNull(onReload, "onReload");
            return this;
        }

        /**
         * Receives each failure, with the release sequence the repository claimed (0 if unknown):
         * a {@link vn.io.dxa.rulestone.api.RepositoryException}, or an {@link InvalidDecisionException}
         * listing every problem of the release. The running engine stays in place.
         */
        public Builder onFailure(BiConsumer<Long, Exception> onFailure) {
            this.onFailure = Objects.requireNonNull(onFailure, "onFailure");
            return this;
        }

        /**
         * The file that keeps the last release accepted, so that after a restart no older release is
         * accepted. Read when the reloader is built; written atomically on every newer release.
         */
        public Builder cache(Path file) {
            this.cache = Objects.requireNonNull(file, "file");
            return this;
        }

        /**
         * @throws InvalidDecisionException if the cache holds a release that does not verify, or is of another target
         * @throws UncheckedIOException     if the cache cannot be read
         */
        public RepositoryReloader build() {
            return new RepositoryReloader(this);
        }
    }
}
