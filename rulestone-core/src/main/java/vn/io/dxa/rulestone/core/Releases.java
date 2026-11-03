//
// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.rulestone.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import vn.io.dxa.rulestone.api.ArtifactKind;

/**
 * The checks of {@link RuleStone.Builder#requireRelease}: the release verifies, is for the engine's
 * target, is not older than the release accepted before and chains to it, lists exactly the loaded
 * versions, and routes only to listed {@code ACTIVE} versions. Problems name releases, versions and
 * hashes, never content.
 */
final class Releases {

    private Releases() {}

    /** The problems found, and the routes to apply: those to a listed, active and loaded version. */
    record Outcome(List<String> problems, List<Release.Route> routes) {}

    static Outcome check(ApprovalVerifier verifier, String target, Approvals.Named named, Release accepted,
                         List<Approvals.Artifact> loaded) {
        List<String> problems = new ArrayList<>();
        if (named == null) {
            problems.add("release: required for target '" + target + "', but none is registered");
            return new Outcome(problems, List.of());
        }
        String at = named.name() == null ? "release: " : "release " + named.name() + ": ";
        ApprovalVerifier.Checked<Release> checked = verifier.checkRelease(named.jws());
        if (checked.value().isEmpty()) {
            checked.problems().forEach(p -> problems.add(at + p));
            return new Outcome(problems, List.of());
        }
        Release release = checked.value().get();
        if (!release.target().equals(target)) {
            problems.add(at + "target is '" + release.target() + "', but this engine loads '" + target + "'");
            return new Outcome(problems, List.of());
        }
        if (accepted != null) {
            long sequence = release.sequence();
            long last = accepted.sequence();
            if (sequence < last) {
                problems.add(at + "sequence " + sequence + " is older than release " + last
                        + ", which this engine already accepted");
            } else if (sequence == last && !ReleaseJson.write(release).equals(ReleaseJson.write(accepted))) {
                problems.add(at + "sequence " + sequence + " was already accepted with other content");
            } else if (sequence == last + 1 && !release.previous().orElse("").equals(ReleaseJson.hash(accepted))) {
                problems.add(at + "previous is " + release.previous().orElse("absent") + ", but release " + last
                        + " accepted before is " + ReleaseJson.hash(accepted));
            }
            if (!problems.isEmpty()) {
                return new Outcome(problems, List.of());
            }
        }
        long sequence = release.sequence();
        for (Approvals.Artifact artifact : loaded) {
            boolean listed = release.artifacts().stream().anyMatch(e -> e.kind() == artifact.kind()
                    && e.id().equals(artifact.id()) && e.version().equals(artifact.exactVersion()));
            if (!listed) {
                List<String> sameLabel = release.artifacts().stream()
                        .filter(e -> e.kind() == artifact.kind() && e.id().equals(artifact.id())
                                && e.label().equals(artifact.label()))
                        .map(Release.Entry::version).toList();
                problems.add(artifact.ref() + ": " + artifact.exactVersion() + " is not in release " + sequence
                        + (sameLabel.isEmpty() ? "" : "; the release lists " + String.join(", ", sameLabel)));
            }
        }
        for (Release.Entry entry : release.artifacts()) {
            boolean sameLabelLoaded = loaded.stream().anyMatch(a -> a.kind() == entry.kind()
                    && a.id().equals(entry.id()) && a.label().equals(entry.label()));
            if (!sameLabelLoaded) {
                problems.add(new Approvals.Artifact(entry.kind(), entry.id(), entry.label(), entry.version()).ref()
                        + ": release " + sequence + " lists " + entry.version() + ", which is not loaded");
            }
        }
        List<Release.Route> routes = new ArrayList<>();
        for (Release.Route route : release.routing()) {
            String name = route.kind().name().toLowerCase(Locale.ROOT) + " " + route.decision() + "@"
                    + route.label();
            Optional<Release.Entry> entry = release.artifacts().stream()
                    .filter(e -> (e.kind() == ArtifactKind.TABLE || e.kind() == ArtifactKind.FLOW)
                            && e.id().equals(route.decision()) && e.version().equals(route.version()))
                    .findFirst();
            if (entry.isEmpty()) {
                problems.add(at + name + " is not listed: the release lists no table or flow " + route.version());
            } else if (entry.get().state() != Release.State.ACTIVE) {
                problems.add(at + name + " is RETIRED; only an ACTIVE version can be routed to");
            } else if (loaded.stream().anyMatch(a -> a.kind() == entry.get().kind() && a.id().equals(route.decision())
                    && a.exactVersion().equals(route.version()))) {
                routes.add(route);
            }
        }
        return new Outcome(problems, routes);
    }
}
