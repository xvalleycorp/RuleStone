//
// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.rulestone.governance;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;
import vn.io.dxa.rulestone.api.ArtifactKind;
import vn.io.dxa.rulestone.api.RuleRepository;
import vn.io.dxa.rulestone.core.ApprovalStatement;
import vn.io.dxa.rulestone.core.ApprovalVerifier;
import vn.io.dxa.rulestone.core.FileRuleRepository;
import vn.io.dxa.rulestone.core.InvalidDecisionException;
import vn.io.dxa.rulestone.core.Release;
import vn.io.dxa.rulestone.core.ReleaseJson;
import vn.io.dxa.rulestone.core.RuleStone;

/**
 * Verifies a governance log together with the statements and releases it issued, and reports every
 * problem. It checks:
 *
 * <ul>
 *   <li>the chain: {@code seq} without gaps, every {@code prev} the hash of the previous entry, every
 *       {@code hash} recomputed from the entry. The first entry where a check fails is reported, and
 *       the checks that rely on the log are then skipped;
 *   <li>the anchors, through an {@link AnchorCheck} (none configured by default);
 *   <li>every approval statement: its signature with the trusted keys, a {@code logEntry} that exists, is
 *       an {@code APPROVAL_ISSUED} or {@code POST_APPROVED} entry and names the same change set,
 *       statement id and versions, makers that include everyone the log shows saving or submitting the
 *       change set, and approvers that are no maker and that the log shows approving;
 *   <li>every issuing entry of the log has its statement;
 *   <li>every release: signature, sequence from 1 without gaps, {@code previous} the hash of the release
 *       before, a {@code RELEASED} entry of the same hash, and every listed version named in an
 *       {@code APPROVAL_ISSUED} entry and in a {@code RELEASED} entry of the target;
 *   <li>with a {@link ContentLookup}, that the content of every version the latest release of each
 *       target lists still hashes to it.
 * </ul>
 *
 * <p>Use it as a library call, or from the command line with {@link VerifyTool}. The report holds no rule
 * content.
 */
public final class GovernanceVerifier {

    /** Finds the content stored under an exact version, for the check that it still hashes to it. */
    @FunctionalInterface
    public interface ContentLookup {

        /** The JSON text stored for the version, if any. */
        Optional<String> find(ArtifactKind kind, String id, String exactVersion);
    }

    private final ApprovalVerifier verifier;
    private final AnchorCheck anchors;
    private final ContentLookup content;

    private GovernanceVerifier(Builder builder) {
        this.verifier = builder.verifier;
        this.anchors = builder.anchors;
        this.content = builder.content;
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Verifies what a store holds, including the content of the latest releases. */
    public VerificationReport verify(GovernanceStore store) {
        GovernanceVerifier withContent = content != null ? this : new GovernanceVerifier(
                builder().verifier(verifier).anchors(anchors).content(
                        (kind, id, exactVersion) -> store.version(kind, id, exactVersion)
                                .map(GovernanceStore.ArtifactVersion::content)));
        List<RuleRepository.Signed> approvals = store.approvals().stream()
                .map(a -> new RuleRepository.Signed(a.name(), a.jws())).toList();
        List<RuleRepository.Signed> releases = new ArrayList<>();
        for (String target : store.targets()) {
            for (GovernanceStore.IssuedRelease issued : store.releases(target)) {
                releases.add(new RuleRepository.Signed(target + "." + issued.release().sequence() + ".release.jws",
                        issued.jws()));
            }
        }
        return withContent.verify(store.log(), approvals, releases);
    }

    /**
     * Verifies a directory written by {@link GovernanceExport}: {@code governance-log.jsonl}, the
     * statements in {@code approvals/}, every release in {@code releases/history/}, and the content of the
     * rule files, which are looked up in the directory as {@link FileRuleRepository} reads it.
     *
     * @throws IllegalArgumentException if the log or the directory cannot be read
     */
    public VerificationReport verify(Path directory) {
        List<LogEntry> log = new ArrayList<>();
        List<VerificationReport.Problem> unreadable = new ArrayList<>();
        Path logFile = directory.resolve(GovernanceExport.LOG_FILE);
        try {
            List<String> lines = Files.readAllLines(logFile, StandardCharsets.UTF_8);
            for (int i = 0; i < lines.size(); i++) {
                if (lines.get(i).isBlank()) {
                    continue;
                }
                try {
                    log.add(LogEntryJson.read(lines.get(i)));
                } catch (IllegalArgumentException e) {
                    unreadable.add(new VerificationReport.Problem(OptionalLong.empty(),
                            GovernanceExport.LOG_FILE + " line " + (i + 1) + ": " + e.getMessage()));
                }
            }
        } catch (IOException e) {
            throw new IllegalArgumentException("cannot read " + logFile, e);
        }
        List<RuleRepository.Signed> approvals = signedFiles(directory.resolve(FileRuleRepository.APPROVALS_DIR),
                RuleStone.APPROVAL_FILE_SUFFIX);
        Path history = directory.resolve(FileRuleRepository.RELEASES_DIR).resolve(GovernanceExport.HISTORY_DIR);
        List<RuleRepository.Signed> releases = Files.isDirectory(history)
                ? signedFiles(history, RuleStone.RELEASE_FILE_SUFFIX)
                : signedFiles(directory.resolve(FileRuleRepository.RELEASES_DIR), RuleStone.RELEASE_FILE_SUFFIX);
        FileRuleRepository repository = FileRuleRepository.of(directory);
        GovernanceVerifier withContent = content != null ? this : new GovernanceVerifier(
                builder().verifier(verifier).anchors(anchors).content(
                        (kind, id, exactVersion) -> repository.find(kind, id, exactVersion)
                                .map(RuleRepository.Artifact::content)));
        VerificationReport report = withContent.verify(log, approvals, releases);
        if (unreadable.isEmpty()) {
            return report;
        }
        List<VerificationReport.Problem> problems = new ArrayList<>(unreadable);
        problems.addAll(report.problems());
        return new VerificationReport(report.entries(), report.statements(), report.releases(), problems);
    }

    private static List<RuleRepository.Signed> signedFiles(Path dir, String suffix) {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(dir)) {
            List<RuleRepository.Signed> signed = new ArrayList<>();
            for (Path file : files.filter(f -> f.getFileName().toString().endsWith(suffix) && Files.isRegularFile(f))
                    .sorted().toList()) {
                signed.add(new RuleRepository.Signed(file.getFileName().toString(), Files.readString(file, StandardCharsets.UTF_8)));
            }
            return signed;
        } catch (IOException e) {
            throw new IllegalArgumentException("cannot read " + dir, e);
        }
    }

    /**
     * Verifies a log with the statements and releases it issued.
     *
     * @param log       the entries, in order
     * @param approvals the signed approval statements
     * @param releases  the signed releases of all targets
     */
    public VerificationReport verify(List<LogEntry> log, List<RuleRepository.Signed> approvals,
            List<RuleRepository.Signed> releases) {
        return new Run(log, approvals, releases).run();
    }

    /** One verification. */
    private final class Run {

        private final List<LogEntry> log;
        private final List<RuleRepository.Signed> approvals;
        private final List<RuleRepository.Signed> releases;
        private final List<VerificationReport.Problem> problems = new ArrayList<>();
        private final Map<String, LogEntry> byHash = new HashMap<>();

        Run(List<LogEntry> log, List<RuleRepository.Signed> approvals, List<RuleRepository.Signed> releases) {
            this.log = List.copyOf(log);
            this.approvals = List.copyOf(approvals);
            this.releases = List.copyOf(releases);
        }

        VerificationReport run() {
            if (chain()) {
                for (String problem : anchors.check(log)) {
                    problem(OptionalLong.empty(), problem);
                }
                log.forEach(e -> byHash.put(e.hash(), e));
                statements();
                releases();
            } else {
                problem(OptionalLong.empty(), "the checks of statements and releases need an intact log, and were skipped");
            }
            return new VerificationReport(log.size(), approvals.size(), releases.size(), problems);
        }

        private void problem(OptionalLong seq, String message) {
            problems.add(new VerificationReport.Problem(seq, message));
        }

        private void problem(LogEntry entry, String message) {
            problem(OptionalLong.of(entry.seq()), message);
        }

        /** Checks the chain; returns false at the first entry where it fails. */
        private boolean chain() {
            String prev = LogEntry.GENESIS;
            long expected = 1;
            for (LogEntry entry : log) {
                if (entry.seq() != expected) {
                    problem(OptionalLong.of(entry.seq()), "expected entry " + expected + ": entries are missing, "
                            + "added or reordered");
                    return false;
                }
                if (!entry.prev().equals(prev)) {
                    problem(entry, "prev is not the hash of entry " + (expected - 1) + ": the log was changed before this entry");
                    return false;
                }
                if (!entry.hashMatches()) {
                    problem(entry, "hash does not match the entry's content: the entry was edited");
                    return false;
                }
                prev = entry.hash();
                expected++;
            }
            return true;
        }

        private void statements() {
            Set<String> issuing = new HashSet<>();
            for (RuleRepository.Signed signed : approvals) {
                ApprovalStatement statement;
                try {
                    statement = verifier.verify(signed.jws());
                } catch (InvalidDecisionException e) {
                    problem(OptionalLong.empty(), "approval " + signed.name() + ": " + String.join("; ", e.problems()));
                    continue;
                }
                statement(signed.name(), statement, issuing);
            }
            for (LogEntry entry : log) {
                if ((entry.type() == EventType.APPROVAL_ISSUED || entry.type() == EventType.POST_APPROVED)
                        && !issuing.contains(entry.hash())) {
                    problem(entry, entry.type() + " has no approval statement among those given: it was removed or never written");
                }
            }
        }

        private void statement(String name, ApprovalStatement statement, Set<String> issuing) {
            String label = "approval " + name + " (change set " + statement.changeSet() + ")";
            if (statement.logEntry().isEmpty()) {
                problem(OptionalLong.empty(), label + ": names no log entry");
                return;
            }
            LogEntry entry = byHash.get(statement.logEntry().get());
            if (entry == null) {
                problem(OptionalLong.empty(), label + ": names log entry " + statement.logEntry().get() + ", which is not in the log");
                return;
            }
            issuing.add(entry.hash());
            if (entry.type() != EventType.APPROVAL_ISSUED && entry.type() != EventType.POST_APPROVED) {
                problem(entry, label + ": names an entry of type " + entry.type() + ", not one that issues a statement");
                return;
            }
            if (!entry.changeSet().equals(Optional.of(statement.changeSet()))) {
                problem(entry, label + ": the entry belongs to another change set");
            }
            if (!Optional.of(statement.approvalId()).equals(Optional.ofNullable(entry.details().get("approvalId"))
                    .map(Object::toString))) {
                problem(entry, label + ": the entry names another statement id");
            }
            if (!entry.subjects().equals(statement.subjects())) {
                problem(entry, label + ": the versions differ from those the entry logs");
            }
            Set<String> makers = new LinkedHashSet<>();
            Set<String> approvedInLog = new HashSet<>();
            for (LogEntry earlier : log) {
                if (earlier.seq() >= entry.seq()) {
                    break;
                }
                if (!earlier.changeSet().equals(Optional.of(statement.changeSet()))) {
                    continue;
                }
                if (earlier.type() == EventType.DRAFT_SAVED || earlier.type() == EventType.SUBMITTED) {
                    makers.add(earlier.actor().principal());
                    Object onBehalfOf = earlier.details().get("onBehalfOf");
                    if (onBehalfOf != null) {
                        makers.add(onBehalfOf.toString());
                    }
                } else if (earlier.type() == EventType.APPROVED || earlier.type() == EventType.EMERGENCY_APPROVED) {
                    approvedInLog.add(earlier.actor().principal());
                }
            }
            List<String> missing = makers.stream().filter(m -> !statement.makers().contains(m)).toList();
            if (!missing.isEmpty()) {
                problem(entry, label + ": omits makers that the log shows saving or submitting the change set: " + missing);
            }
            for (ApprovalStatement.Approver approver : statement.approvers()) {
                if (makers.contains(approver.principal()) || statement.makers().contains(approver.principal())) {
                    problem(entry, label + ": approver " + approver.principal() + " is also a maker");
                }
                if (!approvedInLog.contains(approver.principal())) {
                    problem(entry, label + ": the log shows no approval by " + approver.principal() + " before the statement");
                }
            }
        }

        private void releases() {
            Map<String, List<Release>> byTarget = new TreeMap<>();
            Map<String, String> signedNames = new HashMap<>();
            for (RuleRepository.Signed signed : releases) {
                try {
                    Release release = verifier.verifyRelease(signed.jws());
                    byTarget.computeIfAbsent(release.target(), t -> new ArrayList<>()).add(release);
                    signedNames.put(release.target() + "#" + release.sequence(), signed.name());
                } catch (InvalidDecisionException e) {
                    problem(OptionalLong.empty(), "release " + signed.name() + ": " + String.join("; ", e.problems()));
                }
            }
            for (Map.Entry<String, List<Release>> target : byTarget.entrySet()) {
                List<Release> chain = target.getValue().stream().sorted(Comparator.comparingLong(Release::sequence)).toList();
                String previousHash = null;
                long expected = 1;
                for (Release release : chain) {
                    String label = "release " + release.sequence() + " of target '" + target.getKey() + "'";
                    if (release.sequence() != expected) {
                        problem(OptionalLong.empty(), label + ": expected sequence " + expected + ": releases are missing or duplicated");
                        expected = release.sequence();
                    }
                    if (!release.previous().equals(Optional.ofNullable(previousHash))) {
                        problem(OptionalLong.empty(), label + ": previous is "
                                + release.previous().orElse("absent") + ", but the release before it has "
                                + (previousHash == null ? "no predecessor" : "hash " + previousHash));
                    }
                    release(label, release);
                    previousHash = ReleaseJson.hash(release);
                    expected++;
                }
                if (!chain.isEmpty() && content != null) {
                    contents(target.getKey(), chain.get(chain.size() - 1));
                }
            }
        }

        private void release(String label, Release release) {
            String hash = ReleaseJson.hash(release);
            Optional<LogEntry> released = log.stream().filter(e -> e.type() == EventType.RELEASED
                    && release.target().equals(e.details().get("target"))
                    && Long.valueOf(release.sequence()).equals(e.details().get("sequence"))
                    && hash.equals(e.details().get("releaseHash"))).findFirst();
            if (released.isEmpty()) {
                problem(OptionalLong.empty(), label + ": the log has no RELEASED entry for this release");
                return;
            }
            for (Release.Entry listed : release.artifacts()) {
                ApprovalStatement.Subject subject = new ApprovalStatement.Subject(listed.kind(), listed.id(), listed.version());
                String version = listed.id() + "@" + listed.version();
                boolean approved = log.stream().anyMatch(e -> e.type() == EventType.APPROVAL_ISSUED
                        && e.seq() < released.get().seq() && e.subjects().contains(subject));
                if (!approved) {
                    problem(released.get(), label + ": lists " + version + ", which no earlier APPROVAL_ISSUED entry names");
                }
                boolean releasedBefore = log.stream().anyMatch(e -> e.type() == EventType.RELEASED
                        && e.seq() <= released.get().seq() && release.target().equals(e.details().get("target"))
                        && e.subjects().contains(subject));
                if (!releasedBefore) {
                    problem(released.get(), label + ": lists " + version + ", which no RELEASED entry of the target names");
                }
            }
        }

        private void contents(String target, Release latest) {
            for (Release.Entry listed : latest.artifacts()) {
                String version = listed.id() + "@" + listed.version();
                Optional<String> text = content.find(listed.kind(), listed.id(), listed.version());
                if (text.isEmpty()) {
                    problem(OptionalLong.empty(), "target '" + target + "': no content is stored for " + version);
                    continue;
                }
                try {
                    String computed = ArtifactContent.parse(listed.kind(), listed.id(), text.get()).exactVersion();
                    if (!computed.equals(listed.version())) {
                        problem(OptionalLong.empty(), "target '" + target + "': the content stored for " + version
                                + " hashes to " + computed + ": it was changed after approval");
                    }
                } catch (GovernanceException e) {
                    problem(OptionalLong.empty(), "target '" + target + "': the content stored for " + version
                            + " is not valid: it was changed after approval");
                }
            }
        }
    }

    /** Builds a {@link GovernanceVerifier}. */
    public static final class Builder {

        private ApprovalVerifier verifier;
        private AnchorCheck anchors = AnchorCheck.NONE;
        private ContentLookup content;

        private Builder() {}

        /** Verifies the signatures of statements and releases with the keys it trusts. Required. */
        public Builder verifier(ApprovalVerifier verifier) {
            this.verifier = Objects.requireNonNull(verifier, "verifier");
            return this;
        }

        /** Compares the log with the latest anchor; default none. */
        public Builder anchors(AnchorCheck anchors) {
            this.anchors = Objects.requireNonNull(anchors, "anchors");
            return this;
        }

        /**
         * Where the content of versions is looked up, to check that it still hashes to what was approved. For
         * {@link GovernanceVerifier#verify(GovernanceStore)} and {@link GovernanceVerifier#verify(Path)} the
         * store or the directory is the default; for a bare log there is none and content is not checked.
         */
        public Builder content(ContentLookup content) {
            this.content = Objects.requireNonNull(content, "content");
            return this;
        }

        public GovernanceVerifier build() {
            Objects.requireNonNull(verifier, "verifier");
            return new GovernanceVerifier(this);
        }
    }
}
