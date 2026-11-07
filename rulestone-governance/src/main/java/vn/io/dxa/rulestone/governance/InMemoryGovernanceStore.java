//
// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.rulestone.governance;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import vn.io.dxa.rulestone.api.ArtifactKind;
import vn.io.dxa.rulestone.core.ReleaseJson;

/**
 * A {@link GovernanceStore} in memory, for tests, demos and embedding. It enforces the rules a
 * durable store must: the log chains, labels are bound once, releases follow each other, and an
 * update applies completely or not at all. Nothing survives the process; {@link GovernanceExport}
 * writes the signed files and the log to a directory.
 */
public final class InMemoryGovernanceStore implements GovernanceStore {

    private final Map<String, ChangeSet> changeSets = new LinkedHashMap<>();
    private final Map<String, ArtifactVersion> bindings = new HashMap<>();
    private final Map<String, ArtifactVersion> versions = new HashMap<>();
    private final List<LogEntry> log = new ArrayList<>();
    private final List<IssuedApproval> approvals = new ArrayList<>();
    private final Map<String, List<IssuedRelease>> releases = new TreeMap<>();
    private final List<GovernancePolicy> policies = new ArrayList<>();

    private static String bindingKey(ArtifactKind kind, String id, String label) {
        return kind.jsonName() + " " + id + "@" + label;
    }

    private static String versionKey(ArtifactKind kind, String id, String exactVersion) {
        return kind.jsonName() + " " + id + "@" + exactVersion;
    }

    @Override
    public synchronized Optional<ChangeSet> changeSet(String id) {
        return Optional.ofNullable(changeSets.get(id));
    }

    @Override
    public synchronized List<ChangeSet> changeSets() {
        return List.copyOf(changeSets.values());
    }

    @Override
    public synchronized Optional<ArtifactVersion> binding(ArtifactKind kind, String id, String label) {
        return Optional.ofNullable(bindings.get(bindingKey(kind, id, label)));
    }

    @Override
    public synchronized Optional<ArtifactVersion> version(ArtifactKind kind, String id, String exactVersion) {
        return Optional.ofNullable(versions.get(versionKey(kind, id, exactVersion)));
    }

    @Override
    public synchronized Optional<LogEntry> head() {
        return log.isEmpty() ? Optional.empty() : Optional.of(log.get(log.size() - 1));
    }

    @Override
    public synchronized List<LogEntry> log() {
        return List.copyOf(log);
    }

    @Override
    public synchronized List<IssuedApproval> approvals() {
        return List.copyOf(approvals);
    }

    @Override
    public synchronized Set<String> targets() {
        return Set.copyOf(releases.keySet());
    }

    @Override
    public synchronized List<IssuedRelease> releases(String target) {
        return List.copyOf(releases.getOrDefault(target, List.of()));
    }

    @Override
    public synchronized Optional<GovernancePolicy> policy() {
        return policies.isEmpty() ? Optional.empty() : Optional.of(policies.get(policies.size() - 1));
    }

    @Override
    public synchronized void commit(Update update) {
        String head = log.isEmpty() ? LogEntry.GENESIS : log.get(log.size() - 1).hash();
        if (!update.expectedHead().equals(head)) {
            throw conflict("the log head moved: another update was committed meanwhile");
        }
        String prev = head;
        long seq = log.size();
        for (LogEntry entry : update.entries()) {
            if (entry.seq() != ++seq || !entry.prev().equals(prev) || !entry.hashMatches()) {
                throw conflict("log entry " + entry.seq() + " does not chain to the log");
            }
            prev = entry.hash();
        }
        for (ChangeSet changeSet : update.changeSets()) {
            ChangeSet stored = changeSets.get(changeSet.id());
            if (changeSet.revision() != (stored == null ? 1 : stored.revision() + 1)) {
                throw conflict("change set " + changeSet.id() + " was changed meanwhile");
            }
        }
        Set<String> bound = new HashSet<>();
        for (ArtifactVersion version : update.versions()) {
            String key = bindingKey(version.kind(), version.id(), version.label());
            if (bindings.containsKey(key) || !bound.add(key)) {
                throw conflict(version.kind().jsonName() + " " + version.id() + "@" + version.label()
                        + " is already bound to a content hash");
            }
        }
        Map<String, Long> sequences = new HashMap<>();
        Map<String, String> hashes = new HashMap<>();
        for (IssuedRelease issued : update.releases()) {
            String target = issued.release().target();
            List<IssuedRelease> existing = releases.getOrDefault(target, List.of());
            long lastSequence = sequences.getOrDefault(target, existing.isEmpty() ? 0L : existing.get(existing.size() - 1).release().sequence());
            String lastHash = hashes.getOrDefault(target, existing.isEmpty() ? null : existing.get(existing.size() - 1).hash());
            boolean chained = lastHash == null ? issued.release().previous().isEmpty()
                    : issued.release().previous().equals(Optional.of(lastHash));
            if (issued.release().sequence() != lastSequence + 1 || !chained
                    || !ReleaseJson.hash(issued.release()).equals(issued.hash())) {
                throw conflict("release " + issued.release().sequence() + " of target '" + target
                        + "' does not follow the latest release");
            }
            sequences.put(target, issued.release().sequence());
            hashes.put(target, issued.hash());
        }
        Set<String> names = new HashSet<>();
        approvals.forEach(a -> names.add(a.name()));
        for (IssuedApproval approval : update.approvals()) {
            if (!names.add(approval.name())) {
                throw conflict("a statement named " + approval.name() + " exists");
            }
        }

        log.addAll(update.entries());
        update.changeSets().forEach(c -> changeSets.put(c.id(), c));
        for (ArtifactVersion version : update.versions()) {
            bindings.put(bindingKey(version.kind(), version.id(), version.label()), version);
            versions.put(versionKey(version.kind(), version.id(), version.exactVersion()), version);
        }
        approvals.addAll(update.approvals());
        for (IssuedRelease issued : update.releases()) {
            releases.computeIfAbsent(issued.release().target(), t -> new ArrayList<>()).add(issued);
        }
        policies.addAll(update.policies());
    }

    private static GovernanceException conflict(String message) {
        return new GovernanceException(GovernanceException.Code.CONFLICT, message);
    }
}
