//
// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.rulestone.test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import vn.io.dxa.rulestone.api.ArtifactKind;
import vn.io.dxa.rulestone.core.ApprovalVerifier;
import vn.io.dxa.rulestone.core.DecisionFlow;
import vn.io.dxa.rulestone.core.DecisionFlowJson;
import vn.io.dxa.rulestone.core.DecisionTable;
import vn.io.dxa.rulestone.core.DecisionTableJson;
import vn.io.dxa.rulestone.core.InvalidDecisionException;
import vn.io.dxa.rulestone.core.ParameterSet;
import vn.io.dxa.rulestone.core.ParameterSetJson;
import vn.io.dxa.rulestone.core.ReasonCodeCatalog;
import vn.io.dxa.rulestone.core.ReasonCodeCatalogJson;
import vn.io.dxa.rulestone.core.Release;
import vn.io.dxa.rulestone.core.ReleaseJson;
import vn.io.dxa.rulestone.core.RuleStone;

/**
 * The cases of {@code java-releases.json}: signed releases, the release accepted before, the
 * approval statements and the artifacts loaded, with the problems an engine built with
 * {@code requireApproved} and {@code requireRelease} reports for each. The artifacts are those of
 * {@link ApprovalFixtures}, and a version 5 of {@code loan_eligibility}, which some cases load as a
 * shadow or canary version. The keys and seeded ES256 nonces are those of {@link ApprovalFixtures}.
 */
final class ReleaseFixtures {

    static final String TARGET = "retail-credit-prod";

    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;
    private static final String V3 = "examples/loan_eligibility.v3.stone.json";
    private static final String V2_PARAMS = "examples/retail_credit.v2.params.json";
    private static final String ZEROS = "0".repeat(64);

    private final ApprovalFixtures keys = new ApprovalFixtures();
    private final List<DecisionTable> tables = ApprovalFixtures.TABLES.stream()
            .map(f -> DecisionTableJson.read(ApprovalFixtures.REPO.resolve(f))).toList();
    private final List<DecisionFlow> flows = ApprovalFixtures.FLOWS.stream()
            .map(f -> DecisionFlowJson.read(ApprovalFixtures.REPO.resolve(f))).toList();
    private final List<ParameterSet> sets = ApprovalFixtures.PARAM_SETS.stream()
            .map(f -> ParameterSetJson.read(ApprovalFixtures.REPO.resolve(f))).toList();
    private final ReasonCodeCatalog catalog = ReasonCodeCatalogJson.read(ApprovalFixtures.REPO.resolve(ApprovalFixtures.CATALOG));
    /** Version 5 of loan_eligibility: version 4 with another label, in force from 2027-06-01. */
    private final DecisionTable v5 = DecisionTableJson.read(DecisionTableJson.write(tables.get(1))
            .replace("\"version\":\"4\"", "\"version\":\"5\"")
            .replace("\"effectiveFrom\":\"2027-01-01\"", "\"effectiveFrom\":\"2027-06-01\""));
    private final ArrayNode cases = NODES.arrayNode();

    JsonNode write() throws IOException {
        ObjectNode json = NODES.objectNode();
        json.put("target", TARGET);
        ApprovalFixtures.TABLES.forEach(json.putArray("tables")::add);
        ApprovalFixtures.FLOWS.forEach(json.putArray("flows")::add);
        ApprovalFixtures.PARAM_SETS.forEach(json.putArray("paramSets")::add);
        json.put("catalog", ApprovalFixtures.CATALOG);
        json.put("extraTable", DecisionTableJson.write(v5));
        cases();
        for (JsonNode testCase : cases) {
            ArrayNode problems = ((ObjectNode) testCase).putArray("problems");
            run(testCase).forEach(problems::add);
        }
        json.set("cases", cases);
        return json;
    }

    private void cases() throws IOException {
        String approved = approval(true);
        String approvedWithoutV5 = approval(false);
        Release r116 = base(116, "sha256:9a1e" + ZEROS.substring(4)).build();
        Release r117 = base(117, "sha256:c41d" + ZEROS.substring(4)).build();
        Release r118 = base(118, ReleaseJson.hash(r117)).active(v5).shadow(v5).build();
        String r117jws = sign("gov-ed", r117);
        String r118jws = sign("gov-ed", r118);

        add("valid release, EdDSA: the retired version is loaded for replay, the shadow version is listed",
                true, List.of(), null, named(TARGET + ".release.jws", r118jws), approved);
        add("valid release, ES256", true, List.of(), null, unnamed(sign("gov-ec", r118)), approved);
        add("valid release without routing", false, List.of(), null, unnamed(r117jws), approved);
        add("a canary version from the release", true, List.of(), null,
                unnamed(sign("gov-ed", base(118, ReleaseJson.hash(r117)).active(v5).canary(v5, 0.05).build())), approved);
        add("the first release", false, List.of(), null, unnamed(sign("gov-ed", base(1, null).build())), approved);
        add("follows the release accepted before", true, List.of(), r117jws, unnamed(r118jws), approved);
        add("the release accepted before, again", true, List.of(), r118jws, unnamed(r118jws), approved);
        add("a gap after the release accepted before is accepted", true, List.of(), sign("gov-ed", r116),
                unnamed(r118jws), approved);
        add("older sequence: a rollback is refused", false, List.of(), r118jws, unnamed(r117jws), approved);
        add("same sequence, other content", true, List.of(), r118jws, unnamed(sign("gov-ed",
                base(118, ReleaseJson.hash(r117)).active(v5).shadow(v5).issuedAt("2026-11-20T08:00:00Z").build())),
                approved);
        add("broken chain: previous is not the release accepted before", true, List.of(), r117jws,
                unnamed(sign("gov-ed", base(118, "sha256:" + ZEROS.substring(1) + "1").active(v5).shadow(v5).build())),
                approved);

        String[] parts = r118jws.split("\\.");
        Release other = base(118, ReleaseJson.hash(r117)).active(v5).canary(v5, 1).build();
        add("tampered release payload, signature kept", true, List.of(), null,
                unnamed(parts[0] + "." + ApprovalFixtures.base64(ReleaseJson.write(other)) + "." + parts[2]), approved);
        add("wrong key: signed by another key under a trusted kid", true, List.of(), null,
                unnamed(keys.issuer("rogue-ed", "gov-ed").issue(r118)), approved);
        add("unknown kid", true, List.of(), null, unnamed(keys.issuer("rogue-ed", "gov-2025").issue(r118)), approved);
        add("an approval statement given as the release", false, List.of(), null, unnamed(approved), approved);
        add("a release given as an approval statement", false, List.of(), null, unnamed(r117jws), approved,
                r117jws);
        ApprovalIssuer.ReleaseBuilder cards = ApprovalIssuer.release("cards-prod", 117).previous(r116)
                .issuedAt("2026-11-20T07:45:00Z");
        r117.artifacts().forEach(e -> cards.artifact(e.kind(), e.id(), e.version(), e.state()));
        add("release of another target", false, List.of(), null, unnamed(sign("gov-ed", cards.build())), approved);
        add("no release", false, List.of(), null, null, approved);

        add("unlisted artifact: a loaded version that the release does not list", true, List.of(), null,
                unnamed(r117jws), approved);
        add("missing artifact: a listed version is not loaded", false, List.of(V2_PARAMS), null, unnamed(r117jws),
                approved);
        add("every version together: the retired version is not loaded", false, List.of(V3), null, unnamed(r117jws),
                approved);
        ApprovalIssuer.ReleaseBuilder otherContent = ApprovalIssuer.release(TARGET, 117).previous(r116);
        for (Release.Entry entry : r117.artifacts()) {
            boolean v4 = entry.kind() == ArtifactKind.TABLE && entry.version().startsWith("4@");
            otherContent.artifact(entry.kind(), entry.id(), v4 ? "4@sha256:" + ZEROS.substring(1) + "1" : entry.version(),
                    entry.state());
        }
        add("content differs from the release", false, List.of(), null,
                unnamed(sign("gov-ed", otherContent.issuedAt("2026-11-20T07:45:00Z").build())), approved);
        add("routing to a version that the release does not list", false, List.of(), null,
                unnamed(sign("gov-ed", base(118, ReleaseJson.hash(r117)).canary(v5, 0.05).build())), approved);
        add("routing to a retired version", false, List.of(), null,
                unnamed(sign("gov-ed", base(118, ReleaseJson.hash(r117)).shadow(tables.get(0)).build())), approved);
        add("routing to a listed version that is not approved", true, List.of(), null, unnamed(r118jws),
                approvedWithoutV5);

        String header = "{\"alg\":\"EdDSA\",\"kid\":\"gov-ed\",\"typ\":\"" + ApprovalVerifier.RELEASE_TYPE + "\"}";
        String version1 = "1@sha256:" + ZEROS;
        add("invalid release", false, List.of(), null, unnamed(keys.issuer("gov-ed", "gov-ed").sign(header,
                "{\"artifacts\":[{\"id\":\"x\",\"kind\":\"rule\",\"state\":\"LIVE\",\"version\":\"4\"},"
                        + "{\"id\":\"reason-codes\",\"kind\":\"catalog\",\"state\":\"ACTIVE\",\"version\":\"" + version1 + "\"},"
                        + "{\"id\":\"cards\",\"kind\":\"catalog\",\"state\":\"ACTIVE\",\"version\":\"" + version1 + "\"},"
                        + "{\"id\":\"t\",\"kind\":\"table\",\"state\":\"ACTIVE\",\"version\":\"" + version1 + "\"},"
                        + "{\"id\":\"t\",\"kind\":\"table\",\"seat\":1,\"state\":\"RETIRED\",\"version\":\"" + version1 + "\"}],"
                        + "\"issuedAt\":\"2026-13-01T00:00:00Z\",\"previous\":\"md5:1\","
                        + "\"routing\":[{\"decision\":\"t\",\"kind\":\"CANARY\",\"share\":1.5,\"version\":\"" + version1 + "\"},"
                        + "{\"decision\":\"t\",\"kind\":\"SHADOW\",\"share\":0.5,\"version\":\"x\"},"
                        + "{\"decision\":\"t\",\"kind\":\"BLUE\",\"version\":\"" + version1 + "\"},"
                        + "{\"decision\":\"t\",\"kind\":\"CANARY\",\"share\":0.1234567,\"version\":\"" + version1 + "\"}],"
                        + "\"sequence\":0,\"target\":\"\",\"type\":\"rulestone.release.v2\",\"x\":1}")), approved);
        add("a sequence that is not a whole number, empty artifacts and routing", false, List.of(), null, unnamed(keys.issuer("gov-ed", "gov-ed")
                .sign(header, "{\"artifacts\":[],\"issuedAt\":\"2026-11-20T07:45:00Z\",\"routing\":[],"
                        + "\"sequence\":1.0,\"target\":\"t\",\"type\":\"rulestone.release\"}")), approved);
        add("a release after the first without previous", false, List.of(), null,
                unnamed(sign("gov-ed", base(2, null).build())), approved);
        add("the first release with previous", false, List.of(), null,
                unnamed(sign("gov-ed", base(1, "sha256:" + ZEROS).build())), approved);
        add("a sequence above 2^53 - 1", false, List.of(), null, unnamed(keys.issuer("gov-ed", "gov-ed").sign(header,
                ReleaseJson.write(r117).replace("\"sequence\":117", "\"sequence\":9007199254740992"))), approved);
        String pretty = JsonMapper.builder().enable(SerializationFeature.INDENT_OUTPUT).build()
                .writeValueAsString(JsonMapper.builder().build().readTree(ReleaseJson.write(r117)));
        add("payload not in canonical form", false, List.of(), null,
                unnamed(keys.issuer("gov-ed", "gov-ed").sign(header, pretty)), approved);
    }

    /** The problems the engine reports for one case, in order; empty if it builds. */
    private List<String> run(JsonNode testCase) {
        ApprovalVerifier.Builder builder = ApprovalVerifier.builder();
        keys.keys.values().stream().filter(k -> !k.get("kid").textValue().startsWith("rogue"))
                .forEach(k -> builder.trustedKey(k.get("kid").textValue(), ApprovalFixtures.publicKey(k)));
        ApprovalVerifier verifier = builder.build();
        List<String> omitted = new ArrayList<>();
        testCase.get("omit").forEach(o -> omitted.add(o.textValue()));
        RuleStone.Builder engine = RuleStone.builder().reasonCodeCatalog(catalog).flows(flows)
                .requireApproved(verifier).requireRelease(TARGET);
        for (int i = 0; i < sets.size(); i++) {
            if (!omitted.contains(ApprovalFixtures.PARAM_SETS.get(i))) {
                engine.paramSet(sets.get(i));
            }
        }
        for (int i = 0; i < tables.size(); i++) {
            if (!omitted.contains(ApprovalFixtures.TABLES.get(i))) {
                engine.decision(tables.get(i));
            }
        }
        if (testCase.get("loadExtraTable").booleanValue()) {
            engine.decision(v5);
        }
        testCase.get("approvals").forEach(a -> engine.approval(a.textValue()));
        JsonNode release = testCase.get("release");
        if (release != null) {
            if (release.has("name")) {
                engine.release(release.get("name").textValue(), release.get("jws").textValue());
            } else {
                engine.release(release.get("jws").textValue());
            }
        }
        if (testCase.has("lastAccepted")) {
            engine.lastAcceptedRelease(verifier.verifyRelease(testCase.get("lastAccepted").textValue()));
        }
        try {
            engine.build();
            return List.of();
        } catch (InvalidDecisionException e) {
            return e.problems();
        }
    }

    private void add(String name, boolean loadExtraTable, List<String> omit, String lastAccepted, ObjectNode release,
                     String... approvals) {
        ObjectNode testCase = cases.addObject().put("name", name).put("loadExtraTable", loadExtraTable);
        omit.forEach(testCase.putArray("omit")::add);
        if (lastAccepted != null) {
            testCase.put("lastAccepted", lastAccepted);
        }
        if (release != null) {
            testCase.set("release", release);
        }
        ArrayNode list = testCase.putArray("approvals");
        for (String approval : approvals) {
            list.add(approval);
        }
    }

    private static ObjectNode named(String name, String jws) {
        return NODES.objectNode().put("name", name).put("jws", jws);
    }

    private static ObjectNode unnamed(String jws) {
        return NODES.objectNode().put("jws", jws);
    }

    /** Every fixture artifact, version 3 of loan_eligibility retired and the rest active, without version 5. */
    private ApprovalIssuer.ReleaseBuilder base(long sequence, String previous) {
        ApprovalIssuer.ReleaseBuilder builder = ApprovalIssuer.release(TARGET, sequence).issuedAt("2026-11-20T07:45:00Z");
        if (previous != null) {
            builder.previous(previous);
        }
        sets.forEach(builder::active);
        builder.retired(tables.get(0));
        tables.subList(1, tables.size()).forEach(builder::active);
        flows.forEach(builder::active);
        return builder.active(catalog);
    }

    /** One statement approving every fixture artifact, and version 5 of loan_eligibility if {@code v5}. */
    private String approval(boolean withV5) {
        ApprovalIssuer.StatementBuilder statement = ApprovalIssuer.statement("CHG-2026-0042")
                .approvalId("4a1f9c2e-7b3d-4e8a-b5c6-0d9e8f7a6b5c")
                .maker("u:nguyen.van.a")
                .approver("u:tran.thi.b", "CREDIT_RISK")
                .issuer("rulestone-governance@bank.example")
                .issuedAt("2026-11-20T07:41:02Z");
        sets.forEach(statement::covering);
        tables.forEach(statement::covering);
        flows.forEach(statement::covering);
        statement.covering(catalog);
        if (withV5) {
            statement.covering(v5);
        }
        return keys.issuer("gov-ed", "gov-ed").issue(statement.build());
    }

    private String sign(String kid, Release release) {
        return keys.issuer(kid, kid).issue(release);
    }

}
