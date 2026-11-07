//
// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.rulestone.governance;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;
import vn.io.dxa.rulestone.api.ArtifactKind;

/**
 * The governance policy: how many approvals a change needs, and from whom, per artifact kind, per
 * artifact id pattern and per risk tier, and which roles may make, release, handle emergencies and
 * administer the policy. The service holds it; changing it is itself an event of the log
 * ({@code POLICY_CHANGED}) and needs an administrator. Read it from JSON with
 * {@link GovernancePolicyJson}, or build it in code:
 *
 * <pre>{@code
 * GovernancePolicy policy = GovernancePolicy.builder()
 *         .tier(Tier.of("standard", 1, "RULE_CHECKER"))
 *         .tier(Tier.of("credit-policy", 2, "CREDIT_RISK", "COMPLIANCE").withDistinctUnits())
 *         .assign(ArtifactKind.PARAMS, "retail_credit", "credit-policy")
 *         .assign("*", "*", "standard")
 *         .makerRoles("RULE_MAKER")
 *         .emergencyRoles("EMERGENCY_APPROVER")
 *         .administratorRoles("GOVERNANCE_ADMIN")
 *         .build();
 * }</pre>
 *
 * <p>Assignments are tried in order and the first that matches the kind and the id pattern ({@code *}
 * matches any characters) gives the tier of an artifact. A change set must satisfy the tier of each
 * of its items, so it takes the {@link #strictest} of them by name and is checked against all.
 *
 * @param version                  the policy's version, written to approval statements as {@code tier@version};
 *                                 the service sets it when the policy is changed
 * @param tiers                    the risk tiers by name
 * @param assign                   which tier an artifact gets, first match wins
 * @param makerRoles               who may save drafts and submit; empty: every authenticated principal
 * @param emergencyRoles           who may approve an emergency change; empty: emergency changes are off
 * @param releaseRoles             who may activate approved change sets; empty: every principal except an AI
 * @param administratorRoles       who may change the policy; empty: nobody. Administrators cannot approve rule changes
 * @param postApprovalBusinessDays how many business days after an emergency approval the full policy must post-approve
 * @param zone                     the time zone of those business days, Monday to Friday
 */
public record GovernancePolicy(
        int version,
        Map<String, Tier> tiers,
        List<Assignment> assign,
        Set<String> makerRoles,
        Set<String> emergencyRoles,
        Set<String> releaseRoles,
        Set<String> administratorRoles,
        int postApprovalBusinessDays,
        ZoneId zone) {

    public GovernancePolicy {
        if (version < 1) {
            throw invalid("version must be at least 1");
        }
        tiers = Map.copyOf(new TreeMap<>(tiers));
        assign = List.copyOf(assign);
        makerRoles = Set.copyOf(makerRoles);
        emergencyRoles = Set.copyOf(emergencyRoles);
        releaseRoles = Set.copyOf(releaseRoles);
        administratorRoles = Set.copyOf(administratorRoles);
        if (postApprovalBusinessDays < 1) {
            throw invalid("postApprovalBusinessDays must be at least 1");
        }
        Objects.requireNonNull(zone, "zone");
        if (tiers.isEmpty()) {
            throw invalid("define at least one tier");
        }
        for (Assignment a : assign) {
            if (!tiers.containsKey(a.tier())) {
                throw invalid("assignment of " + a.kind() + " " + a.id() + " names unknown tier '" + a.tier() + "'");
            }
        }
    }

    private static GovernanceException invalid(String message) {
        return new GovernanceException(GovernanceException.Code.INVALID_POLICY, message);
    }

    public static Builder builder() {
        return new Builder();
    }

    /** This policy with another version. */
    public GovernancePolicy withVersion(int version) {
        return new GovernancePolicy(version, tiers, assign, makerRoles, emergencyRoles, releaseRoles,
                administratorRoles, postApprovalBusinessDays, zone);
    }

    /**
     * The tier of an artifact.
     *
     * @throws GovernanceException with {@link GovernanceException.Code#INVALID_POLICY} if no assignment matches
     */
    public Tier tierFor(ArtifactKind kind, String id) {
        for (Assignment a : assign) {
            if (a.matches(kind, id)) {
                return tiers.get(a.tier());
            }
        }
        throw invalid("no tier is assigned to " + kind.jsonName() + " " + id);
    }

    /** The strictest of {@code tiers}: most approvals, then most roles, then units must differ, then by name. */
    public static Tier strictest(Collection<Tier> tiers) {
        return tiers.stream().max(Comparator.comparingInt(Tier::approvals)
                .thenComparingInt(t -> t.roles().size())
                .thenComparing(Tier::distinctUnits)
                .thenComparing(Tier::name, Comparator.reverseOrder())).orElseThrow();
    }

    /** {@code tier@version}, as the approval statement's {@code policy} field names it. */
    public String ref(String tierName) {
        return tierName + "@" + version;
    }

    /**
     * What is still missing for {@code approvals} to satisfy every tier in {@code tiers}, or empty if
     * they do. Messages name tiers, counts and roles.
     */
    public static Optional<String> unmet(Collection<Tier> tiers, List<ChangeSet.Approval> approvals) {
        List<String> missing = new ArrayList<>();
        for (Tier tier : tiers) {
            tier.unmet(approvals).ifPresent(missing::add);
        }
        return missing.isEmpty() ? Optional.empty() : Optional.of(String.join("; ", missing));
    }

    /**
     * The instant by which the full policy must post-approve an emergency approval made at
     * {@code approvedAt}: the end (23:59:59) of the {@link #postApprovalBusinessDays()}-th business
     * day after it, in {@link #zone()}.
     */
    public Instant postApprovalDue(Instant approvedAt) {
        LocalDate day = approvedAt.atZone(zone).toLocalDate();
        int remaining = postApprovalBusinessDays;
        while (remaining > 0) {
            day = day.plusDays(1);
            if (day.getDayOfWeek() != DayOfWeek.SATURDAY && day.getDayOfWeek() != DayOfWeek.SUNDAY) {
                remaining--;
            }
        }
        return ZonedDateTime.of(day, LocalTime.of(23, 59, 59), zone).toInstant().truncatedTo(ChronoUnit.SECONDS);
    }

    /** {@code sha256:<hex>} of the policy's canonical JSON, which {@code POLICY_CHANGED} entries name. */
    public String hash() {
        return Canonical.sha256(GovernancePolicyJson.write(this));
    }

    /**
     * A risk tier.
     *
     * @param name          the tier's name, such as {@code credit-policy}
     * @param approvals     how many distinct approvers the tier needs, at least the number of roles
     * @param roles         the roles that count; every role listed needs an approver who approved in it
     * @param distinctUnits whether the approvers must come from different organisational units
     */
    public record Tier(String name, int approvals, List<String> roles, boolean distinctUnits) {

        public Tier {
            Objects.requireNonNull(name, "name");
            if (name.isBlank()) {
                throw invalid("a tier needs a name");
            }
            roles = List.copyOf(roles);
            if (roles.isEmpty() || new TreeSet<>(roles).size() != roles.size() || roles.stream().anyMatch(String::isBlank)) {
                throw invalid("tier '" + name + "' needs at least one role, each named once");
            }
            if (approvals < roles.size()) {
                throw invalid("tier '" + name + "' needs at least " + roles.size() + " approvals, one for each of its roles");
            }
        }

        /** A tier of {@code approvals} approvers, one at least from each of {@code roles}. */
        public static Tier of(String name, int approvals, String... roles) {
            return new Tier(name, approvals, List.of(roles), false);
        }

        /** The same tier, with approvers from different organisational units. */
        public Tier withDistinctUnits() {
            return new Tier(name, approvals, roles, true);
        }

        /** What this tier still misses for {@code approvals}, or empty if it is satisfied. */
        Optional<String> unmet(List<ChangeSet.Approval> approvals) {
            List<ChangeSet.Approval> counting = approvals.stream().filter(a -> roles.contains(a.role())).toList();
            Set<String> covered = new LinkedHashSet<>();
            counting.forEach(a -> covered.add(a.role()));
            List<String> uncovered = roles.stream().filter(r -> !covered.contains(r)).toList();
            long count = distinctUnits
                    ? counting.stream().map(a -> a.unit().orElse("")).filter(u -> !u.isEmpty()).distinct().count()
                    : counting.size();
            if (count >= this.approvals && uncovered.isEmpty()) {
                return Optional.empty();
            }
            StringBuilder message = new StringBuilder("tier '").append(name).append("' has ").append(count)
                    .append(" of ").append(this.approvals).append(" approvals");
            if (distinctUnits) {
                message.append(" from different units");
            }
            if (!uncovered.isEmpty()) {
                message.append(", missing roles ").append(uncovered);
            }
            return Optional.of(message.toString());
        }
    }

    /**
     * Which tier artifacts get.
     *
     * @param kind {@code table}, {@code flow}, {@code params}, {@code catalog} or {@code *}
     * @param id   the artifact id, with {@code *} matching any characters
     * @param tier the tier's name
     */
    public record Assignment(String kind, String id, String tier) {

        public Assignment {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(tier, "tier");
            if (!kind.equals("*") && ArtifactKind.fromJsonName(kind).isEmpty()) {
                throw invalid("unknown kind '" + kind + "' (expected table, flow, params, catalog or *)");
            }
            if (id.isEmpty()) {
                throw invalid("an assignment needs an id pattern");
            }
        }

        boolean matches(ArtifactKind artifactKind, String artifactId) {
            if (!kind.equals("*") && !kind.equals(artifactKind.jsonName())) {
                return false;
            }
            StringBuilder regex = new StringBuilder();
            for (String part : id.split("\\*", -1)) {
                if (regex.length() > 0) {
                    regex.append(".*");
                }
                regex.append(Pattern.quote(part));
            }
            return artifactId.matches(regex.toString());
        }
    }

    /** Builds a {@link GovernancePolicy}; the version is 1, business days 1 and the zone UTC unless set. */
    public static final class Builder {

        private int version = 1;
        private final Map<String, Tier> tiers = new TreeMap<>();
        private final List<Assignment> assign = new ArrayList<>();
        private Set<String> makerRoles = Set.of();
        private Set<String> emergencyRoles = Set.of();
        private Set<String> releaseRoles = Set.of();
        private Set<String> administratorRoles = Set.of();
        private int postApprovalBusinessDays = 1;
        private ZoneId zone = ZoneOffset.UTC;

        private Builder() {}

        public Builder version(int version) {
            this.version = version;
            return this;
        }

        public Builder tier(Tier tier) {
            if (tiers.putIfAbsent(tier.name(), tier) != null) {
                throw invalid("tier '" + tier.name() + "' is defined twice");
            }
            return this;
        }

        /** Assigns {@code tier} to artifacts of {@code kind} whose id matches {@code idPattern}. */
        public Builder assign(ArtifactKind kind, String idPattern, String tier) {
            return assign(kind.jsonName(), idPattern, tier);
        }

        /** Like {@link #assign(ArtifactKind, String, String)}; {@code kind} may be {@code *}. */
        public Builder assign(String kind, String idPattern, String tier) {
            assign.add(new Assignment(kind, idPattern, tier));
            return this;
        }

        public Builder makerRoles(String... roles) {
            this.makerRoles = Set.copyOf(List.of(roles));
            return this;
        }

        public Builder emergencyRoles(String... roles) {
            this.emergencyRoles = Set.copyOf(List.of(roles));
            return this;
        }

        public Builder releaseRoles(String... roles) {
            this.releaseRoles = Set.copyOf(List.of(roles));
            return this;
        }

        public Builder administratorRoles(String... roles) {
            this.administratorRoles = Set.copyOf(List.of(roles));
            return this;
        }

        /** The full policy must post-approve an emergency change by the end of the {@code businessDays}-th business day after it, in {@code zone}. */
        public Builder postApprovalDue(int businessDays, ZoneId zone) {
            this.postApprovalBusinessDays = businessDays;
            this.zone = Objects.requireNonNull(zone, "zone");
            return this;
        }

        /** @throws GovernanceException with {@link GovernanceException.Code#INVALID_POLICY} if the policy is not valid */
        public GovernancePolicy build() {
            return new GovernancePolicy(version, tiers, assign, makerRoles, emergencyRoles, releaseRoles,
                    administratorRoles, postApprovalBusinessDays, zone);
        }
    }
}
