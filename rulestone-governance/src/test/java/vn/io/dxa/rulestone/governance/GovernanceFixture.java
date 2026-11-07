//
// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.rulestone.governance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import vn.io.dxa.rulestone.api.ArtifactKind;
import vn.io.dxa.rulestone.api.RuleStoneEngine;
import vn.io.dxa.rulestone.core.ApprovalVerifier;
import vn.io.dxa.rulestone.core.RuleStone;

/**
 * A governance service with a fixed clock, counted ids, fixed test keys and the principals of the
 * tests, on an in-memory store. The policy is the one of the design: a standard tier of one checker, a
 * credit tier of two approvers in two roles from two units, emergency, release and administrator roles.
 */
final class GovernanceFixture {

    static final Principal MAKER = Principal.of("u:nguyen.van.a", "RULE_MAKER");
    static final Principal MAKER_2 = Principal.of("u:pham.van.d", "RULE_MAKER");
    static final Principal CHECKER = Principal.of("u:tran.thi.b", "RULE_CHECKER").withUnit("retail-risk");
    static final Principal CHECKER_2 = Principal.of("u:ho.thi.f", "RULE_CHECKER").withUnit("operations");
    static final Principal CREDIT = Principal.of("u:cr.one", "CREDIT_RISK").withUnit("credit-risk");
    static final Principal CREDIT_2 = Principal.of("u:cr.two", "CREDIT_RISK").withUnit("credit-risk-2");
    static final Principal COMPLIANCE = Principal.of("u:co.one", "COMPLIANCE").withUnit("compliance");
    static final Principal COMPLIANCE_IN_CREDIT_UNIT = Principal.of("u:co.two", "COMPLIANCE").withUnit("credit-risk");
    static final Principal COMPLIANCE_NO_UNIT = Principal.of("u:co.three", "COMPLIANCE");
    static final Principal EMERGENCY = Principal.of("u:em.one", "EMERGENCY_APPROVER");
    static final Principal RELEASER = Principal.of("u:rm.one", "RELEASE_MANAGER");
    static final Principal ADMIN = Principal.of("u:admin.one", "GOVERNANCE_ADMIN");
    static final Principal AI = Principal.of("ai:assistant", "RULE_MAKER", "RULE_CHECKER");

    static final String TARGET = "retail-credit-prod";

    final InMemoryGovernanceStore store = new InMemoryGovernanceStore();
    final InMemoryPrincipalResolver who = new InMemoryPrincipalResolver();
    final MutableClock clock = new MutableClock(Instant.parse("2026-11-20T03:00:00Z"));
    final PublicKey publicKey;
    final ApprovalVerifier verifier;
    final PrivateKeySigner signer;
    final GovernanceService service;
    private final String kid;
    private final AtomicInteger counter = new AtomicInteger();

    GovernanceFixture() {
        this("gov-ed");
    }

    /** @param kid {@code gov-ed} (EdDSA) or {@code gov-ec} (ES256) */
    GovernanceFixture(String kid) {
        this(kid, policy());
    }

    GovernanceFixture(String kid, GovernancePolicy policy) {
        this.kid = kid;
        JsonNode key = key(kid);
        this.publicKey = publicKey(key);
        this.verifier = ApprovalVerifier.builder().trustedKey(kid, publicKey).build();
        this.signer = PrivateKeySigner.of(kid, privateKey(key));
        this.service = GovernanceService.builder()
                .store(store)
                .principals(who)
                .signer(signer)
                .policy(policy)
                .clock(clock)
                .idGenerator(() -> String.format("00000000-0000-4000-8000-%012d", counter.incrementAndGet()))
                .issuer("rulestone-governance@bank.example")
                .build();
    }

    static GovernancePolicy policy() {
        return GovernancePolicy.builder()
                .tier(GovernancePolicy.Tier.of("standard", 1, "RULE_CHECKER"))
                .tier(GovernancePolicy.Tier.of("credit-policy", 2, "CREDIT_RISK", "COMPLIANCE").withDistinctUnits())
                .assign(ArtifactKind.PARAMS, "retail_credit", "credit-policy")
                .assign("*", "*", "standard")
                .makerRoles("RULE_MAKER")
                .emergencyRoles("EMERGENCY_APPROVER")
                .releaseRoles("RELEASE_MANAGER")
                .administratorRoles("GOVERNANCE_ADMIN")
                .postApprovalDue(1, ZoneOffset.UTC)
                .build();
    }

    String kid() {
        return kid;
    }

    GovernanceFixture as(Principal principal) {
        who.actAs(principal);
        return this;
    }

    /** An engine built from the current release of {@link #TARGET}, as a bank's application builds it. */
    RuleStoneEngine engine() {
        return engine(TARGET);
    }

    RuleStoneEngine engine(String target) {
        return RuleStone.builder()
                .snapshot(service.repository().load(target))
                .requireApproved(verifier)
                .requireRelease(target)
                .clock(clock)
                .build();
    }

    /** Creates a change set as the maker and saves and submits the documents. */
    ChangeSet submit(String id, Doc... docs) {
        as(MAKER).service.createChangeSet(id, "Change " + id);
        for (Doc doc : docs) {
            service.saveDraft(id, doc.kind(), doc.json());
        }
        return service.submit(id);
    }

    /** Submits and approves with one checker: a change of the standard tier. */
    ChangeSet approved(String id, Doc... docs) {
        submit(id, docs);
        return as(CHECKER).service.approve(id, "RULE_CHECKER", "Reviewed");
    }

    /** Submits, approves and activates in {@link #TARGET}. */
    GovernanceStore.IssuedRelease activated(String id, Doc... docs) {
        approved(id, docs);
        return as(RELEASER).service.activate(id, TARGET);
    }

    /** A document: a kind and its JSON. */
    record Doc(ArtifactKind kind, String json) {}

    static Doc gate(String version, String effectiveFrom, int threshold) {
        return new Doc(ArtifactKind.TABLE, gateJson(version, effectiveFrom, threshold));
    }

    static String gateJson(String version, String effectiveFrom, int threshold) {
        return """
                {
                  "id": "gate_decision",
                  "version": "%s",
                  "effectiveFrom": "%s",
                  "hitPolicy": "FIRST",
                  "inputs": { "score": "int" },
                  "outputs": { "action": "string" },
                  "rules": [
                    { "id": "BLOCK", "when": "score >= %d", "then": { "action": "'BLOCK'" } },
                    { "id": "ALLOW", "when": "true", "then": { "action": "'ALLOW'" } }
                  ]
                }
                """.formatted(version, effectiveFrom, threshold);
    }

    static Doc gate(String version, String effectiveFrom, String effectiveTo, int threshold) {
        String json = gateJson(version, effectiveFrom, threshold).replace("\"hitPolicy\"",
                "\"effectiveTo\": \"" + effectiveTo + "\",\n  \"hitPolicy\"");
        return new Doc(ArtifactKind.TABLE, json);
    }

    static Doc creditParams(String version, String effectiveFrom, int minAge) {
        return new Doc(ArtifactKind.PARAMS, """
                {
                  "id": "retail_credit",
                  "version": "%s",
                  "effectiveFrom": "%s",
                  "values": { "minAge": { "type": "int", "value": %d } }
                }
                """.formatted(version, effectiveFrom, minAge));
    }

    static Doc catalog(String version) {
        try {
            String json = Files.readString(Path.of("..", "examples", "reason-codes.json"), StandardCharsets.UTF_8);
            return new Doc(ArtifactKind.CATALOG, json.replaceFirst("\"version\": \"1\"", "\"version\": \"" + version + "\""));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static JsonNode key(String kid) {
        try (InputStream in = GovernanceFixture.class.getResourceAsStream("/governance-test-keys.json")) {
            for (JsonNode k : JsonMapper.builder().build().readTree(in).get("keys")) {
                if (k.get("kid").textValue().equals(kid)) {
                    return k;
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        throw new IllegalArgumentException("no test key " + kid);
    }

    private static PrivateKey privateKey(JsonNode key) {
        try {
            return KeyFactory.getInstance(key.get("alg").textValue().equals("EdDSA") ? "Ed25519" : "EC")
                    .generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(key.get("pkcs8").textValue())));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private static PublicKey publicKey(JsonNode key) {
        try {
            return KeyFactory.getInstance(key.get("alg").textValue().equals("EdDSA") ? "Ed25519" : "EC")
                    .generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(key.get("spki").textValue())));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** A clock that stands still until a test moves it. */
    static final class MutableClock extends Clock {

        private final AtomicReference<Instant> now;

        MutableClock(Instant start) {
            this.now = new AtomicReference<>(start);
        }

        void advance(Duration duration) {
            now.updateAndGet(i -> i.plus(duration));
        }

        void set(Instant instant) {
            now.set(instant);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now.get();
        }
    }
}
