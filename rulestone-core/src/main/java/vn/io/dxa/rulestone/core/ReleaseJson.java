//
// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.rulestone.core;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.TreeMap;
import vn.io.dxa.rulestone.api.ArtifactKind;

/**
 * Reads and writes {@link Release releases}, the payload of {@code .release.jws} files. Reading is
 * strict: unknown or duplicate keys and every other problem are errors, each reported with its
 * location, all together. A signed release must be written exactly in the canonical form of
 * {@link #write}, which {@link ApprovalVerifier#verifyRelease} checks, and its {@link #hash} is what
 * the next release names as {@code previous}.
 */
public final class ReleaseJson {

    private static final Set<String> RELEASE_FIELDS =
            Set.of("type", "target", "sequence", "previous", "artifacts", "routing", "issuedAt");
    private static final Set<String> ARTIFACT_FIELDS = Set.of("kind", "id", "version", "state");
    private static final Set<String> ROUTE_FIELDS = Set.of("decision", "kind", "version", "share");

    private ReleaseJson() {}

    /** @throws InvalidDecisionException listing every problem found */
    public static Release read(String json) {
        JsonNode root;
        try {
            root = DecisionTableJson.MAPPER.readTree(json);
        } catch (JsonProcessingException e) {
            String location = e.getLocation() == null ? ""
                    : " (line " + e.getLocation().getLineNr() + ", column " + e.getLocation().getColumnNr() + ")";
            throw new InvalidDecisionException(List.of("invalid JSON: " + e.getOriginalMessage() + location));
        }
        return release(root);
    }

    /**
     * Writes the canonical form: compact, keys sorted, artifacts and routing in their order,
     * {@code previous} omitted in the first release, {@code routing} omitted when empty, a share
     * as a plain decimal without trailing zeros.
     */
    public static String write(Release release) {
        Map<String, Object> json = new TreeMap<>();
        json.put("type", Release.TYPE);
        json.put("target", release.target());
        json.put("sequence", release.sequence());
        release.previous().ifPresent(p -> json.put("previous", p));
        List<Object> artifacts = new ArrayList<>();
        for (Release.Entry entry : release.artifacts()) {
            artifacts.add(new TreeMap<>(Map.of("kind", entry.kind().jsonName(), "id", entry.id(),
                    "version", entry.version(), "state", entry.state().name())));
        }
        json.put("artifacts", artifacts);
        if (!release.routing().isEmpty()) {
            List<Object> routing = new ArrayList<>();
            for (Release.Route route : release.routing()) {
                Map<String, Object> r = new TreeMap<>();
                r.put("decision", route.decision());
                r.put("kind", route.kind().name());
                r.put("version", route.version());
                route.share().ifPresent(s -> r.put("share", Release.shareDecimal(s)));
                routing.add(r);
            }
            json.put("routing", routing);
        }
        json.put("issuedAt", release.issuedAt());
        return DecisionTableJson.writeCanonical(json);
    }

    /** {@code sha256:<hex>} of the canonical form: what the next release names as {@code previous}. */
    public static String hash(Release release) {
        return "sha256:" + CompiledDecision.sha256(write(release));
    }

    static Release release(JsonNode root) {
        if (root == null || !root.isObject()) {
            throw new InvalidDecisionException(List.of("$: expected a JSON object"));
        }
        DecisionTableJson.Reader reader = new DecisionTableJson.Reader();
        reader.unknownFields(root, RELEASE_FIELDS, "$");
        String type = reader.requiredText(root, "type", "$");
        if (!type.isEmpty() && !type.equals(Release.TYPE)) {
            reader.problem("$.type", "must be '" + Release.TYPE + "'");
        }
        String target = reader.requiredText(root, "target", "$");
        long sequence = sequence(root.get("sequence"));
        if (sequence == 0) {
            reader.problem("$.sequence", "required whole number from 1 to " + Release.MAX_SEQUENCE);
        }
        Optional<String> previous = reader.optionalText(root, "previous", "$");
        if (previous.isPresent() && !ApprovalStatementJson.HASH.matcher(previous.get()).matches()) {
            reader.problem("$.previous", "expected sha256:<64 hex digits>");
        } else if (previous.isPresent() && sequence == 1) {
            reader.problem("$.previous", "must be absent in the first release (sequence 1)");
        } else if (previous.isEmpty() && sequence > 1) {
            reader.problem("$.previous", "required after the first release: the hash of release " + (sequence - 1));
        }
        List<Release.Entry> artifacts = artifacts(root, reader);
        List<Release.Route> routing = routing(root, reader);
        String issuedAt = reader.requiredText(root, "issuedAt", "$");
        if (!issuedAt.isEmpty()) {
            ApprovalStatementJson.instant(issuedAt, "$.issuedAt", reader);
        }
        if (!reader.problems.isEmpty()) {
            throw new InvalidDecisionException(reader.problems);
        }
        return new Release(target, sequence, previous, artifacts, routing, issuedAt);
    }

    /** The sequence, or 0 if it is not a whole number from 1 to {@link Release#MAX_SEQUENCE}. */
    private static long sequence(JsonNode node) {
        if (node == null || !node.isIntegralNumber()) {
            return 0;
        }
        BigInteger value = node.bigIntegerValue();
        return value.signum() > 0 && value.compareTo(BigInteger.valueOf(Release.MAX_SEQUENCE)) <= 0
                ? value.longValueExact() : 0;
    }

    private static List<Release.Entry> artifacts(JsonNode root, DecisionTableJson.Reader reader) {
        JsonNode node = root.get("artifacts");
        List<Release.Entry> artifacts = new ArrayList<>();
        if (node == null || !node.isArray() || node.isEmpty()) {
            reader.problem("$.artifacts", "required non-empty array of { kind, id, version, state }");
            return artifacts;
        }
        Set<String> listed = new HashSet<>();
        boolean catalog = false;
        for (int i = 0; i < node.size(); i++) {
            String path = "$.artifacts[" + i + "]";
            JsonNode a = node.get(i);
            if (!a.isObject()) {
                reader.problem(path, "expected an object");
                continue;
            }
            reader.unknownFields(a, ARTIFACT_FIELDS, path);
            String kindName = reader.requiredText(a, "kind", path);
            Optional<ArtifactKind> kind = ArtifactKind.fromJsonName(kindName);
            if (!kindName.isEmpty() && kind.isEmpty()) {
                reader.problem(path + ".kind", "unknown kind '" + kindName + "' (expected table, flow, params or catalog)");
            }
            String id = reader.requiredText(a, "id", path);
            String version = reader.requiredText(a, "version", path);
            boolean exact = ApprovalStatementJson.EXACT_VERSION.matcher(version).matches();
            if (!version.isEmpty() && !exact) {
                reader.problem(path + ".version", "expected an exact version, <label>@sha256:<64 hex digits>");
            }
            String stateName = reader.requiredText(a, "state", path);
            Optional<Release.State> state = stateName.equals("ACTIVE") ? Optional.of(Release.State.ACTIVE)
                    : stateName.equals("RETIRED") ? Optional.of(Release.State.RETIRED) : Optional.empty();
            if (!stateName.isEmpty() && state.isEmpty()) {
                reader.problem(path + ".state", "expected ACTIVE or RETIRED");
            }
            if (kind.isPresent() && !id.isEmpty() && exact) {
                if (kind.get() == ArtifactKind.CATALOG) {
                    if (catalog) {
                        reader.problem(path, "a release lists at most one reason-code catalog");
                    }
                    catalog = true;
                } else if (!listed.add(kind.get().jsonName() + " " + id + "@" + Release.label(version))) {
                    reader.problem(path, kind.get().jsonName() + " " + id + "@" + Release.label(version)
                            + " is listed more than once");
                }
            }
            if (kind.isPresent() && state.isPresent()) {
                artifacts.add(new Release.Entry(kind.get(), id, version, state.get()));
            }
        }
        return artifacts;
    }

    private static List<Release.Route> routing(JsonNode root, DecisionTableJson.Reader reader) {
        JsonNode node = root.get("routing");
        List<Release.Route> routing = new ArrayList<>();
        if (node == null) {
            return routing;
        }
        if (!node.isArray() || node.isEmpty()) {
            reader.problem("$.routing", "expected a non-empty array of { decision, kind, version, share }");
            return routing;
        }
        Set<String> routed = new HashSet<>();
        for (int i = 0; i < node.size(); i++) {
            String path = "$.routing[" + i + "]";
            JsonNode r = node.get(i);
            if (!r.isObject()) {
                reader.problem(path, "expected an object");
                continue;
            }
            reader.unknownFields(r, ROUTE_FIELDS, path);
            String decision = reader.requiredText(r, "decision", path);
            String kindName = reader.requiredText(r, "kind", path);
            Optional<Release.RouteKind> kind = kindName.equals("CANARY") ? Optional.of(Release.RouteKind.CANARY)
                    : kindName.equals("SHADOW") ? Optional.of(Release.RouteKind.SHADOW) : Optional.empty();
            if (!kindName.isEmpty() && kind.isEmpty()) {
                reader.problem(path + ".kind", "expected CANARY or SHADOW");
            }
            String version = reader.requiredText(r, "version", path);
            if (!version.isEmpty() && !ApprovalStatementJson.EXACT_VERSION.matcher(version).matches()) {
                reader.problem(path + ".version", "expected an exact version, <label>@sha256:<64 hex digits>");
            }
            JsonNode shareNode = r.get("share");
            OptionalDouble share = OptionalDouble.empty();
            if (kind.isPresent() && kind.get() == Release.RouteKind.CANARY) {
                if (shareNode == null || !shareNode.isNumber() || !Release.validShare(shareNode.doubleValue())) {
                    reader.problem(path + ".share",
                            "required for a canary: a number above 0 and at most 1, with at most 6 decimals");
                } else {
                    share = OptionalDouble.of(shareNode.doubleValue());
                }
            } else if (kind.isPresent() && shareNode != null) {
                reader.problem(path + ".share", "only a canary has a share");
            }
            if (kind.isPresent() && !decision.isEmpty() && !routed.add(kind.get() + " " + decision)) {
                reader.problem(path, "decision '" + decision + "' has more than one "
                        + kind.get().name().toLowerCase(Locale.ROOT));
            }
            if (kind.isPresent()) {
                routing.add(new Release.Route(decision, kind.get(), version, share));
            }
        }
        return routing;
    }
}
