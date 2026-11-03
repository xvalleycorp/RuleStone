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
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import vn.io.dxa.rulestone.api.ArtifactKind;
import vn.io.dxa.rulestone.core.ApprovalStatement;
import vn.io.dxa.rulestone.core.ApprovalStatementJson;
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
import vn.io.dxa.rulestone.core.RuleStone;

/**
 * The cases of {@code java-approvals.json}: signed approval statements, the verifier's settings and
 * the problems an engine built with {@code requireApproved} reports for a fixed set of artifacts.
 * The keys are test-only keys in {@code approval-test-keys.json}; ES256 signatures use a seeded
 * random generator, so that the fixture is the same on every run.
 */
final class ApprovalFixtures {

    static final Path REPO = Path.of("..");
    static final List<String> TABLES = List.of("examples/loan_eligibility.v3.stone.json",
            "examples/loan_eligibility.v4.stone.json", "examples/flows/card_policy.stone.json",
            "examples/flows/card_grade_existing.stone.json", "examples/flows/card_grade_new.stone.json",
            "examples/flows/card_limit.stone.json");
    static final List<String> FLOWS = List.of("examples/flows/card_origination.flow.json");
    static final List<String> PARAM_SETS = List.of("examples/retail_credit.v1.params.json",
            "examples/retail_credit.v2.params.json");
    static final String CATALOG = "examples/reason-codes.json";

    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;
    private static final String TYP = ApprovalVerifier.TYPE;

    final Map<String, JsonNode> keys = new LinkedHashMap<>();
    private final List<DecisionTable> tables = TABLES.stream().map(f -> DecisionTableJson.read(REPO.resolve(f))).toList();
    private final List<DecisionFlow> flows = FLOWS.stream().map(f -> DecisionFlowJson.read(REPO.resolve(f))).toList();
    private final List<ParameterSet> sets = PARAM_SETS.stream().map(f -> ParameterSetJson.read(REPO.resolve(f))).toList();
    private final ReasonCodeCatalog catalog = ReasonCodeCatalogJson.read(REPO.resolve(CATALOG));
    private final ArrayNode cases = NODES.arrayNode();

    ApprovalFixtures() {
        try (InputStream in = ApprovalFixtures.class.getResourceAsStream("/approval-test-keys.json")) {
            JsonMapper.builder().build().readTree(in).get("keys").forEach(k -> keys.put(k.get("kid").textValue(), k));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The fixture: the trusted keys, the artifacts and every case with the problems the engine reports. */
    JsonNode write() throws IOException {
        ObjectNode json = NODES.objectNode();
        ArrayNode trusted = json.putArray("keys");
        for (String kid : List.of("gov-ed", "gov-ec")) {
            trusted.addObject().put("kid", kid).put("alg", keys.get(kid).get("alg").textValue())
                    .put("spki", keys.get(kid).get("spki").textValue());
        }
        TABLES.forEach(json.putArray("tables")::add);
        FLOWS.forEach(json.putArray("flows")::add);
        PARAM_SETS.forEach(json.putArray("paramSets")::add);
        json.put("catalog", CATALOG);
        cases();
        for (JsonNode testCase : cases) {
            ArrayNode problems = ((ObjectNode) testCase).putArray("problems");
            run(testCase).forEach(problems::add);
        }
        json.set("cases", cases);
        return json;
    }

    private void cases() throws IOException {
        String valid = sign("gov-ed", statement(s -> true).build());
        String validEc = sign("gov-ec", statement(s -> true).build());
        add("valid, EdDSA", 1, null, named("CHG-2026-0042.approval.jws", valid));
        add("valid, ES256", 1, null, unnamed(validEc));
        add("valid, split across two statements signed with EdDSA and ES256", 1, null,
                named("CHG-2026-0042.approval.jws",
                        sign("gov-ed", statement(s -> s.kind() == ArtifactKind.TABLE || s.kind() == ArtifactKind.FLOW).build())),
                named("CHG-2026-0043.approval.jws", sign("gov-ec", statement(
                        s -> s.kind() == ArtifactKind.PARAMS || s.kind() == ArtifactKind.CATALOG)
                        .approvalId("8c3d1f0a-2b4e-4c6d-9e8f-1a2b3c4d5e6f").build())));
        add("surrounding whitespace, such as a final newline, is ignored", 1, null, unnamed(valid + "\n"));
        add("no approvals", 1, null);
        add("missing approval: the catalog is not covered", 1, null,
                unnamed(sign("gov-ed", statement(s -> s.kind() != ArtifactKind.CATALOG).build())));

        String hash = "sha256:" + "0".repeat(63) + "1";
        ApprovalIssuer.StatementBuilder tampered = statement(s -> !(s.kind() == ArtifactKind.TABLE
                && s.id().equals("loan_eligibility") && s.version().startsWith("4@")));
        tampered.subject(ArtifactKind.TABLE, "loan_eligibility", "4@" + hash);
        add("tampered hash: the approved content of a table differs", 1, null, unnamed(sign("gov-ed", tampered.build())));

        String[] parts = valid.split("\\.");
        String otherPayload = ApprovalStatementJson.write(statement(s -> true).approvalId("tampered").build());
        add("tampered payload, signature kept", 1, null, unnamed(validEc),
                named("tampered.approval.jws", parts[0] + "." + base64(otherPayload) + "." + parts[2]));
        add("wrong key: signed by another key under a trusted kid", 1, null, unnamed(validEc),
                unnamed(signWith("rogue-ed", "gov-ed", ApprovalVerifier.EDDSA, statement(s -> true).build())));
        add("unknown kid", 1, null, unnamed(validEc),
                unnamed(signWith("rogue-ed", "gov-2025", ApprovalVerifier.EDDSA, statement(s -> true).build())));
        String payload = ApprovalStatementJson.write(statement(s -> true).build());
        add("alg none, without a signature", 1, null, unnamed(validEc),
                unnamed(base64("{\"alg\":\"none\",\"kid\":\"gov-ed\",\"typ\":\"" + TYP + "\"}") + "."
                        + base64(payload) + "."));
        add("alg HS256", 1, null, unnamed(validEc), unnamed(signRaw("gov-ed",
                "{\"alg\":\"HS256\",\"kid\":\"gov-ed\",\"typ\":\"" + TYP + "\"}", payload)));
        add("embedded jwk", 1, null, unnamed(validEc), unnamed(signRaw("gov-ed",
                "{\"alg\":\"EdDSA\",\"jwk\":{\"kty\":\"OKP\",\"crv\":\"Ed25519\",\"x\":\"" + "A".repeat(43)
                        + "\"},\"kid\":\"gov-ed\",\"typ\":\"" + TYP + "\"}", payload)));
        add("jku, x5u, x5c and crit", 1, null, unnamed(validEc), unnamed(signRaw("gov-ed",
                "{\"alg\":\"EdDSA\",\"crit\":[\"exp\"],\"jku\":\"https://keys.example/jwks.json\",\"kid\":\"gov-ed\","
                        + "\"typ\":\"" + TYP + "\",\"x5c\":[\"MIIB\"],\"x5u\":\"https://keys.example/cert.pem\"}",
                payload)));
        add("wrong typ", 1, null, unnamed(validEc), unnamed(signRaw("gov-ed",
                "{\"alg\":\"EdDSA\",\"kid\":\"gov-ed\",\"typ\":\"JWT\"}", payload)));
        add("missing typ and kid", 1, null, unnamed(validEc), unnamed(signRaw("gov-ed",
                "{\"alg\":\"EdDSA\"}", payload)));
        add("header names the algorithm of another key", 1, null, unnamed(validEc), unnamed(signRaw("gov-ed",
                "{\"alg\":\"ES256\",\"kid\":\"gov-ed\",\"typ\":\"" + TYP + "\"}", payload)));
        add("algorithm not allowed by the verifier", 1, List.of(ApprovalVerifier.EDDSA), unnamed(valid),
                unnamed(validEc));
        String pretty = JsonMapper.builder().enable(SerializationFeature.INDENT_OUTPUT).build()
                .writeValueAsString(JsonMapper.builder().build().readTree(payload));
        add("payload not in canonical form", 1, null, unnamed(validEc), unnamed(signRaw("gov-ed", header("gov-ed"), pretty)));
        add("payload with keys out of order", 1, null, unnamed(validEc), unnamed(signRaw("gov-ed", header("gov-ed"),
                "{\"type\":\"rulestone.approval\"," + payload.substring(1).replace(",\"type\":\"rulestone.approval\"", ""))));
        add("payload not JSON", 1, null, unnamed(validEc), unnamed(signRaw("gov-ed", header("gov-ed"), "approved")));
        add("invalid statement", 1, null, unnamed(validEc), unnamed(signRaw("gov-ed", header("gov-ed"),
                "{\"approvers\":[{\"at\":\"yesterday\",\"principal\":\"u:b\"},{\"at\":\"2026-11-20T03:12:00Z\","
                        + "\"principal\":\"u:b\",\"seat\":\"1\"}],\"changeSet\":\"\",\"emergency\":\"no\","
                        + "\"issuedAt\":\"2026-02-30T00:00:00Z\",\"issuer\":\"ci\",\"logEntry\":\"md5:00\",\"makers\":[],"
                        + "\"subjects\":[{\"id\":\"loan_eligibility\",\"kind\":\"rule\",\"version\":\"4\"}],"
                        + "\"type\":\"rulestone.approval.v2\"}")));
        add("not a JWS", 1, null, unnamed(validEc), unnamed("not-a-jws"), unnamed("a.b.c.d"),
                unnamed("eyJhbGciOiJFZERTQSJ9.e30.!!"));

        ApprovalIssuer.StatementBuilder self = statement(s -> s.kind() == ArtifactKind.PARAMS && s.id().equals("retail_credit")
                && s.version().startsWith("2@"));
        self.changeSet("CHG-2026-0050").maker("u:tran.thi.b");
        add("maker as approver", 1, null,
                unnamed(sign("gov-ec", statement(s -> !(s.kind() == ArtifactKind.PARAMS && s.version().startsWith("2@")))
                        .build())),
                named("CHG-2026-0050.approval.jws", sign("gov-ed", self.build())));
        add("a valid statement makes up for one that fails four-eyes", 1, null, unnamed(valid),
                unnamed(sign("gov-ec", statement(s -> true).changeSet("CHG-2026-0051").maker("u:le.van.c").build())));
        ApprovalIssuer.StatementBuilder one = statement(s -> s.kind() == ArtifactKind.TABLE
                && s.id().equals("loan_eligibility") && s.version().startsWith("4@"), "u:tran.thi.b");
        add("too few approvers", 2, null,
                unnamed(sign("gov-ec", statement(s -> !(s.kind() == ArtifactKind.TABLE && s.id().equals("loan_eligibility")
                        && s.version().startsWith("4@"))).build())),
                unnamed(sign("gov-ed", one.changeSet("CHG-2026-0052").build())));
        add("emergency approval with one approver", 1, null, unnamed(sign("gov-ed",
                statement(s -> true, "u:tran.thi.b").emergency("2026-11-21T17:00:00Z").build())));
    }

    /** The problems the engine reports for one case, in order; empty if it builds. */
    private List<String> run(JsonNode testCase) {
        ApprovalVerifier.Builder verifier = ApprovalVerifier.builder().minApprovers(testCase.get("minApprovers").intValue());
        keys.values().stream().filter(k -> !k.get("kid").textValue().startsWith("rogue"))
                .forEach(k -> verifier.trustedKey(k.get("kid").textValue(), publicKey(k)));
        if (testCase.has("allowedAlgorithms")) {
            List<String> allowed = new ArrayList<>();
            testCase.get("allowedAlgorithms").forEach(a -> allowed.add(a.textValue()));
            verifier.allowedAlgorithms(allowed.toArray(String[]::new));
        }
        RuleStone.Builder builder = RuleStone.builder().paramSets(sets).decisions(tables).flows(flows)
                .reasonCodeCatalog(catalog).requireApproved(verifier.build());
        for (JsonNode approval : testCase.get("approvals")) {
            if (approval.has("name")) {
                builder.approval(approval.get("name").textValue(), approval.get("jws").textValue());
            } else {
                builder.approval(approval.get("jws").textValue());
            }
        }
        try {
            builder.build();
            return List.of();
        } catch (InvalidDecisionException e) {
            return e.problems();
        }
    }

    private void add(String name, int minApprovers, List<String> allowed, ObjectNode... approvals) {
        ObjectNode testCase = cases.addObject().put("name", name).put("minApprovers", minApprovers);
        if (allowed != null) {
            allowed.forEach(testCase.putArray("allowedAlgorithms")::add);
        }
        ArrayNode list = testCase.putArray("approvals");
        for (ObjectNode approval : approvals) {
            list.add(approval);
        }
    }

    private static ObjectNode named(String name, String jws) {
        return NODES.objectNode().put("name", name).put("jws", jws);
    }

    private static ObjectNode unnamed(String jws) {
        return NODES.objectNode().put("jws", jws);
    }

    /** A statement of change set CHG-2026-0042 covering the artifacts {@code covered} accepts, with two approvers by default. */
    private ApprovalIssuer.StatementBuilder statement(Predicate<ApprovalStatement.Subject> covered, String... approvers) {
        ApprovalIssuer.StatementBuilder builder = ApprovalIssuer.statement("CHG-2026-0042")
                .approvalId("4a1f9c2e-7b3d-4e8a-b5c6-0d9e8f7a6b5c")
                .maker("u:nguyen.van.a")
                .policy("credit-policy@3")
                .issuer("rulestone-governance@bank.example")
                .issuedAt("2026-11-20T07:41:02Z")
                .logEntry("sha256:5e0b" + "0".repeat(60));
        if (approvers.length == 0) {
            builder.approver("u:tran.thi.b", "CREDIT_RISK").approver("u:le.van.c", "COMPLIANCE");
        }
        for (String approver : approvers) {
            builder.approver(approver);
        }
        List<ApprovalStatement.Subject> subjects = new ArrayList<>();
        sets.forEach(s -> subjects.add(new ApprovalStatement.Subject(ArtifactKind.PARAMS, s.id(), ParameterSetJson.exactVersion(s))));
        tables.forEach(t -> subjects.add(new ApprovalStatement.Subject(ArtifactKind.TABLE, t.id(), DecisionTableJson.exactVersion(t))));
        flows.forEach(f -> subjects.add(new ApprovalStatement.Subject(ArtifactKind.FLOW, f.id(), DecisionFlowJson.exactVersion(f))));
        subjects.add(new ApprovalStatement.Subject(ArtifactKind.CATALOG, ApprovalStatement.CATALOG_ID,
                ReasonCodeCatalogJson.exactVersion(catalog)));
        subjects.stream().filter(covered).forEach(s -> builder.subject(s.kind(), s.id(), s.version()));
        return builder;
    }

    private static String header(String kid) {
        return "{\"alg\":\"EdDSA\",\"kid\":\"" + kid + "\",\"typ\":\"" + TYP + "\"}";
    }

    private String sign(String kid, ApprovalStatement statement) {
        return issuer(kid, kid).issue(statement);
    }

    private String signWith(String keyKid, String headerKid, String algorithm, ApprovalStatement statement) {
        ApprovalIssuer issuer = issuer(keyKid, headerKid);
        if (!issuer.algorithm().equals(algorithm)) {
            throw new IllegalArgumentException("key " + keyKid + " is not " + algorithm);
        }
        return issuer.issue(statement);
    }

    private String signRaw(String kid, String header, String payload) {
        return issuer(kid, kid).sign(header, payload);
    }

    /** An issuer with the private key {@code keyKid} that names itself {@code headerKid}; ES256 nonces are seeded. */
    ApprovalIssuer issuer(String keyKid, String headerKid) {
        JsonNode key = keys.get(keyKid);
        try {
            boolean ed = key.get("alg").textValue().equals(ApprovalVerifier.EDDSA);
            PrivateKey privateKey = KeyFactory.getInstance(ed ? "Ed25519" : "EC")
                    .generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(key.get("pkcs8").textValue())));
            SecureRandom random = SecureRandom.getInstance("SHA1PRNG");
            random.setSeed(20261120L);
            return ApprovalIssuer.of(headerKid, privateKey, random);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    static PublicKey publicKey(JsonNode key) {
        try {
            boolean ed = key.get("alg").textValue().equals(ApprovalVerifier.EDDSA);
            return KeyFactory.getInstance(ed ? "Ed25519" : "EC")
                    .generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(key.get("spki").textValue())));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    static String base64(String text) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(text.getBytes(StandardCharsets.UTF_8));
    }
}
