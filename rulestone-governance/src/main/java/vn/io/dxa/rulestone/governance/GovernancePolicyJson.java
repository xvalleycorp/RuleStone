//
// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.rulestone.governance;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Reads and writes a {@link GovernancePolicy} as JSON, so that a bank keeps its policy in a file:
 *
 * <pre>{@code
 * {
 *   "version": 1,
 *   "tiers": {
 *     "standard":      { "approvals": 1, "roles": ["RULE_CHECKER"] },
 *     "credit-policy": { "approvals": 2, "roles": ["CREDIT_RISK", "COMPLIANCE"], "distinctUnits": true }
 *   },
 *   "assign": [
 *     { "kind": "params", "id": "retail_credit", "tier": "credit-policy" },
 *     { "kind": "*", "id": "*", "tier": "standard" }
 *   ],
 *   "roles": {
 *     "maker": ["RULE_MAKER"], "emergency": ["EMERGENCY_APPROVER"],
 *     "release": ["RELEASE_MANAGER"], "administrator": ["GOVERNANCE_ADMIN"]
 *   },
 *   "emergency": { "postApprovalBusinessDays": 1, "zone": "Asia/Ho_Chi_Minh" }
 * }
 * }</pre>
 *
 * <p>Reading is strict: unknown keys are errors. {@code version}, {@code roles} and {@code emergency}
 * are optional. The canonical form that {@link #write} produces, with keys sorted, is what
 * {@link GovernancePolicy#hash()} hashes.
 */
public final class GovernancePolicyJson {

    private static final Set<String> POLICY_FIELDS = Set.of("version", "tiers", "assign", "roles", "emergency");
    private static final Set<String> TIER_FIELDS = Set.of("approvals", "roles", "distinctUnits");
    private static final Set<String> ASSIGN_FIELDS = Set.of("kind", "id", "tier");
    private static final Set<String> ROLE_FIELDS = Set.of("maker", "emergency", "release", "administrator");
    private static final Set<String> EMERGENCY_FIELDS = Set.of("postApprovalBusinessDays", "zone");

    private GovernancePolicyJson() {}

    /** @throws GovernanceException with {@link GovernanceException.Code#INVALID_POLICY} if the text is not a valid policy */
    public static GovernancePolicy read(String json) {
        JsonNode root;
        try {
            root = Canonical.parse(json);
        } catch (IllegalArgumentException e) {
            throw invalid(e.getMessage());
        }
        object(root, "$", POLICY_FIELDS);
        GovernancePolicy.Builder builder = GovernancePolicy.builder();
        if (root.has("version")) {
            builder.version(integer(root.get("version"), "$.version"));
        }
        JsonNode tiers = root.get("tiers");
        if (tiers == null || !tiers.isObject()) {
            throw invalid("$.tiers: expected an object of tiers by name");
        }
        for (Iterator<String> names = tiers.fieldNames(); names.hasNext();) {
            String name = names.next();
            String path = "$.tiers." + name;
            JsonNode tier = tiers.get(name);
            object(tier, path, TIER_FIELDS);
            JsonNode distinct = tier.get("distinctUnits");
            if (distinct != null && !distinct.isBoolean()) {
                throw invalid(path + ".distinctUnits: expected a boolean");
            }
            GovernancePolicy.Tier t = new GovernancePolicy.Tier(name, integer(tier.get("approvals"), path + ".approvals"),
                    strings(tier.get("roles"), path + ".roles", true), distinct != null && distinct.booleanValue());
            builder.tier(t);
        }
        JsonNode assign = root.get("assign");
        if (assign == null || !assign.isArray()) {
            throw invalid("$.assign: expected an array of { kind, id, tier }");
        }
        for (int i = 0; i < assign.size(); i++) {
            String path = "$.assign[" + i + "]";
            JsonNode a = assign.get(i);
            object(a, path, ASSIGN_FIELDS);
            builder.assign(text(a.get("kind"), path + ".kind"), text(a.get("id"), path + ".id"),
                    text(a.get("tier"), path + ".tier"));
        }
        JsonNode roles = root.get("roles");
        if (roles != null) {
            object(roles, "$.roles", ROLE_FIELDS);
            builder.makerRoles(roleArray(roles, "maker"));
            builder.emergencyRoles(roleArray(roles, "emergency"));
            builder.releaseRoles(roleArray(roles, "release"));
            builder.administratorRoles(roleArray(roles, "administrator"));
        }
        JsonNode emergency = root.get("emergency");
        if (emergency != null) {
            object(emergency, "$.emergency", EMERGENCY_FIELDS);
            int days = emergency.has("postApprovalBusinessDays")
                    ? integer(emergency.get("postApprovalBusinessDays"), "$.emergency.postApprovalBusinessDays") : 1;
            ZoneId zone;
            try {
                zone = ZoneId.of(emergency.has("zone") ? text(emergency.get("zone"), "$.emergency.zone") : "UTC");
            } catch (DateTimeException e) {
                throw invalid("$.emergency.zone: not a time zone");
            }
            builder.postApprovalDue(days, zone);
        }
        return builder.build();
    }

    /** Writes the canonical form: compact, keys sorted, tiers by name, assignments in their order. */
    public static String write(GovernancePolicy policy) {
        Map<String, Object> json = new TreeMap<>();
        json.put("version", (long) policy.version());
        Map<String, Object> tiers = new TreeMap<>();
        for (GovernancePolicy.Tier tier : policy.tiers().values()) {
            Map<String, Object> t = new TreeMap<>();
            t.put("approvals", (long) tier.approvals());
            t.put("roles", tier.roles());
            if (tier.distinctUnits()) {
                t.put("distinctUnits", true);
            }
            tiers.put(tier.name(), t);
        }
        json.put("tiers", tiers);
        List<Object> assign = new ArrayList<>();
        for (GovernancePolicy.Assignment a : policy.assign()) {
            assign.add(new TreeMap<>(Map.of("kind", a.kind(), "id", a.id(), "tier", a.tier())));
        }
        json.put("assign", assign);
        Map<String, Object> roles = new TreeMap<>();
        roles.put("maker", new TreeSet<>(policy.makerRoles()));
        roles.put("emergency", new TreeSet<>(policy.emergencyRoles()));
        roles.put("release", new TreeSet<>(policy.releaseRoles()));
        roles.put("administrator", new TreeSet<>(policy.administratorRoles()));
        json.put("roles", roles);
        json.put("emergency", new TreeMap<>(Map.of("postApprovalBusinessDays", (long) policy.postApprovalBusinessDays(),
                "zone", policy.zone().getId())));
        return Canonical.write(json);
    }

    private static String[] roleArray(JsonNode roles, String name) {
        JsonNode node = roles.get(name);
        return node == null ? new String[0] : strings(node, "$.roles." + name, false).toArray(new String[0]);
    }

    private static void object(JsonNode node, String path, Set<String> fields) {
        if (node == null || !node.isObject()) {
            throw invalid(path + ": expected an object");
        }
        for (Iterator<String> names = node.fieldNames(); names.hasNext();) {
            String name = names.next();
            if (!fields.contains(name)) {
                throw invalid(path + "." + name + ": unknown key");
            }
        }
    }

    private static int integer(JsonNode node, String path) {
        if (node == null || !node.isInt()) {
            throw invalid(path + ": expected a whole number");
        }
        return node.intValue();
    }

    private static String text(JsonNode node, String path) {
        if (node == null || !node.isTextual()) {
            throw invalid(path + ": expected a string");
        }
        return node.textValue();
    }

    private static List<String> strings(JsonNode node, String path, boolean required) {
        if (node == null || !node.isArray() || (required && node.isEmpty())) {
            throw invalid(path + ": expected " + (required ? "a non-empty " : "an ") + "array of strings");
        }
        List<String> list = new ArrayList<>();
        for (int i = 0; i < node.size(); i++) {
            list.add(text(node.get(i), path + "[" + i + "]"));
        }
        return list;
    }

    private static GovernanceException invalid(String message) {
        return new GovernanceException(GovernanceException.Code.INVALID_POLICY, message);
    }
}
