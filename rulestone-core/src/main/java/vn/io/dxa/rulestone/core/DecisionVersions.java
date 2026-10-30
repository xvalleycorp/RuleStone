//
// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.rulestone.core;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import vn.io.dxa.rulestone.api.DecisionNotFoundException;

/**
 * Which version of a decision, a {@link DecisionTable} or a {@link DecisionFlow}, is in force on a date.
 *
 * <p>Versions of one decision are ordered by {@code effectiveFrom}; a version without one
 * starts at the beginning of time. A version stays in force until the earlier of its own
 * {@code effectiveTo}, if it has one, and the {@code effectiveFrom} of the next version.
 * A version without {@code effectiveTo} therefore ends exactly where its successor begins,
 * so adding a version never requires editing, and re-hashing, the previous one.
 *
 * <p>The engine and every integration select versions through this class, so they all
 * agree. {@link #problems} lists what makes a set of versions inconsistent; the engine
 * refuses to build while there are any.
 */
public final class DecisionVersions {

    /** By effective start; a version without {@code effectiveFrom} comes first. */
    private static final Comparator<VersionedDefinition> BY_START =
            Comparator.comparing(t -> t.effectiveFrom().orElse(LocalDate.MIN));

    private DecisionVersions() {}

    /**
     * Problems with the versions of one decision.
     *
     * <ul>
     *   <li>the same version registered more than once;</li>
     *   <li>two versions with the same {@code effectiveFrom}, or both without one, so that
     *       neither supersedes the other;</li>
     *   <li>an {@code effectiveTo} after the next version's {@code effectiveFrom}: the two
     *       files contradict each other.</li>
     * </ul>
     *
     * An {@code effectiveTo} before the next version's start is allowed: no version is in
     * force in between.
     *
     * @param versions every registered version of one decision
     * @return the problems, each starting with the versions involved; empty if consistent
     */
    public static <T extends VersionedDefinition> List<String> problems(Collection<T> versions) {
        List<T> sorted = sorted(versions);
        List<String> problems = new ArrayList<>();
        for (int i = 0; i < sorted.size(); i++) {
            for (int j = i + 1; j < sorted.size(); j++) {
                if (sorted.get(i).version().equals(sorted.get(j).version())) {
                    problems.add(sorted.get(i).ref() + ": version registered more than once");
                }
            }
        }
        for (int i = 0; i + 1 < sorted.size(); i++) {
            VersionedDefinition current = sorted.get(i);
            VersionedDefinition next = sorted.get(i + 1);
            if (current.version().equals(next.version())) {
                continue;   // already reported
            }
            if (current.effectiveFrom().equals(next.effectiveFrom())) {
                problems.add(current.ref() + " and " + next.ref() + ": both take effect "
                        + current.effectiveFrom().map(d -> "on " + d).orElse("from the beginning (no effectiveFrom)")
                        + ", so neither supersedes the other; give the later version a later effectiveFrom");
            } else if (current.effectiveTo().isPresent()
                    && next.effectiveFrom().orElseThrow().isBefore(current.effectiveTo().get())) {
                problems.add(current.ref() + " and " + next.ref() + ": " + current.ref() + " is in force until "
                        + current.effectiveTo().get() + " (effectiveTo), after " + next.ref() + " takes effect on "
                        + next.effectiveFrom().get() + "; remove effectiveTo from " + current.ref()
                        + " or set it to " + next.effectiveFrom().get());
            }
        }
        return problems;
    }

    /**
     * The version in force on {@code date}: the one with the latest {@code effectiveFrom} on
     * or before {@code date}, unless its own {@code effectiveTo} has passed.
     *
     * @param versions every registered version of one decision, consistent by {@link #problems}
     */
    public static <T extends VersionedDefinition> Optional<T> inForce(Collection<T> versions, LocalDate date) {
        T candidate = null;
        for (T table : sorted(versions)) {
            if (table.effectiveFrom().isPresent() && table.effectiveFrom().get().isAfter(date)) {
                break;
            }
            candidate = table;
        }
        if (candidate == null || candidate.effectiveTo().map(to -> !date.isBefore(to)).orElse(false)) {
            return Optional.empty();
        }
        return Optional.of(candidate);
    }

    /**
     * Selects the version an evaluation uses: {@code version} if given, else the version in
     * force on {@code date}.
     *
     * @param id       the decision id, for messages
     * @param versions every registered version of decision {@code id}
     * @throws DecisionNotFoundException if there is no such version, or none is in force on {@code date}
     */
    public static <T extends VersionedDefinition> T select(String id, Collection<T> versions,
                                                         Optional<String> version, LocalDate date) {
        if (version.isPresent()) {
            return versions.stream()
                    .filter(t -> t.version().equals(version.get()))
                    .findFirst()
                    .orElseThrow(() -> new DecisionNotFoundException(
                            "decision '" + id + "' has no version '" + version.get() + "'"));
        }
        return inForce(versions, date).orElseThrow(() -> new DecisionNotFoundException(
                "no version of decision '" + id + "' is effective on " + date));
    }

    private static <T extends VersionedDefinition> List<T> sorted(Collection<T> versions) {
        List<T> sorted = new ArrayList<>(versions);
        sorted.sort(BY_START);
        return sorted;
    }
}
