//
// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.rulestone.core;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.PublicKey;
import java.security.Signature;
import java.security.interfaces.ECPublicKey;
import java.security.interfaces.EdECPublicKey;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * Verifies signed approval statements: JWS compact serialization (RFC 7515) whose payload is an
 * {@link ApprovalStatement} in the canonical form of {@link ApprovalStatementJson#write}.
 *
 * <pre>{@code
 * ApprovalVerifier verifier = ApprovalVerifier.builder()
 *     .trustedKey("gov-2026", publicKey)   // the governance service's or CI job's signing key
 *     .minApprovers(1)                     // a floor the engine checks itself
 *     .build();
 * }</pre>
 *
 * <p>The reader is deliberately small and strict. The protected header must be exactly
 * {@code {"alg": …, "kid": …, "typ": "rulestone-approval+jws"}}: an algorithm from the allowlist
 * ({@code EdDSA} with Ed25519, or {@code ES256}), and the id of a key configured here. Keys never
 * come from the statement, so headers such as {@code jwk}, {@code jku}, {@code x5u}, {@code x5c}
 * and {@code crit} are refused, as is {@code alg: none}. Only JDK cryptography is used.
 *
 * <p>Pass the verifier to {@link RuleStone.Builder#requireApproved}, which also checks that the
 * statements cover the exact content of every registered artifact, that no approver is a maker
 * and that at least {@link #minApprovers()} approvers are named. A verifier is immutable and
 * thread-safe.
 */
public final class ApprovalVerifier {

    /** The {@code typ} header of a signed approval statement. */
    public static final String TYPE = "rulestone-approval+jws";
    /** EdDSA with Ed25519 (RFC 8037). */
    public static final String EDDSA = "EdDSA";
    /** ECDSA with P-256 and SHA-256, the signature as R and S of 32 bytes each (RFC 7518). */
    public static final String ES256 = "ES256";

    /** The supported algorithms, in the order problems list them. */
    private static final List<String> SUPPORTED = List.of(EDDSA, ES256);
    private static final Set<String> HEADER_FIELDS = Set.of("alg", "kid", "typ");
    private static final Pattern BASE64URL = Pattern.compile("[A-Za-z0-9_-]*");
    /** The order of the P-256 group, which identifies the curve of an EC key. */
    private static final BigInteger P256_ORDER =
            new BigInteger("FFFFFFFF00000000FFFFFFFFFFFFFFFFBCE6FAADA7179E84F3B9CAC2FC632551", 16);

    private final Map<String, TrustedKey> keys;
    private final Set<String> allowedAlgorithms;
    private final int minApprovers;

    private ApprovalVerifier(Builder builder) {
        this.keys = Map.copyOf(builder.keys);
        this.allowedAlgorithms = Set.copyOf(builder.allowedAlgorithms);
        this.minApprovers = builder.minApprovers;
    }

    public static Builder builder() {
        return new Builder();
    }

    /** The least number of distinct approvers every statement must name. */
    public int minApprovers() {
        return minApprovers;
    }

    /**
     * Verifies one signed statement: its header, its signature with a trusted key, and its payload,
     * which must be a valid statement in canonical form. Four-eyes and {@link #minApprovers()} are
     * checked per artifact by {@link RuleStone.Builder#requireApproved}, not here.
     *
     * @throws InvalidDecisionException listing the problems; messages never contain the payload
     */
    public ApprovalStatement verify(String jws) {
        Checked checked = check(jws);
        if (checked.statement().isEmpty()) {
            throw new InvalidDecisionException(checked.problems());
        }
        return checked.statement().get();
    }

    /** A verified statement, or the problems that prevent verifying it. */
    record Checked(Optional<ApprovalStatement> statement, List<String> problems) {

        static Checked failed(String problem) {
            return new Checked(Optional.empty(), List.of(problem));
        }
    }

    Checked check(String jws) {
        Objects.requireNonNull(jws, "jws");
        String[] parts = jws.replaceAll("^[ \t\r\n]+|[ \t\r\n]+$", "").split("\\.", -1);
        if (parts.length != 3 || parts[0].isEmpty() || parts[1].isEmpty()
                || !Arrays.stream(parts).allMatch(p -> BASE64URL.matcher(p).matches() && p.length() % 4 != 1)) {
            return Checked.failed("not a JWS compact serialization (three base64url parts separated by '.')");
        }
        JsonNode header = json(parts[0]);
        if (header == null || !header.isObject()) {
            return Checked.failed("header is not a JSON object");
        }
        List<String> problems = new ArrayList<>();
        header.fieldNames().forEachRemaining(name -> {
            if (!HEADER_FIELDS.contains(name)) {
                problems.add("header parameter '" + name + "' is not allowed");
            }
        });
        JsonNode typ = header.get("typ");
        if (typ == null || !typ.isTextual() || !typ.textValue().equals(TYPE)) {
            problems.add("header typ must be '" + TYPE + "'");
        }
        JsonNode alg = header.get("alg");
        if (alg == null || !alg.isTextual()) {
            problems.add("header alg is required");
        } else if (!allowedAlgorithms.contains(alg.textValue())) {
            problems.add("algorithm '" + alg.textValue() + "' is not allowed (allowed: "
                    + String.join(", ", SUPPORTED.stream().filter(allowedAlgorithms::contains).toList()) + ")");
        }
        JsonNode kid = header.get("kid");
        TrustedKey key = null;
        if (kid == null || !kid.isTextual() || kid.textValue().isEmpty()) {
            problems.add("header kid is required");
        } else {
            key = keys.get(kid.textValue());
            if (key == null) {
                problems.add("unknown key '" + kid.textValue() + "'");
            }
        }
        if (!problems.isEmpty()) {
            return new Checked(Optional.empty(), problems);
        }
        if (!key.algorithm().equals(alg.textValue())) {
            return Checked.failed("key '" + kid.textValue() + "' is an " + key.algorithm() + " key, not "
                    + alg.textValue());
        }
        if (!signatureVerifies(key, parts)) {
            return Checked.failed("signature does not verify with key '" + kid.textValue() + "'");
        }
        Optional<String> payload = utf8(parts[1]);
        JsonNode root = payload.map(ApprovalVerifier::parse).orElse(null);
        if (root == null || !root.isObject()) {
            return Checked.failed("payload is not a JSON object");
        }
        ApprovalStatement statement;
        try {
            statement = ApprovalStatementJson.statement(root);
        } catch (InvalidDecisionException e) {
            return new Checked(Optional.empty(), e.problems());
        }
        if (!ApprovalStatementJson.write(statement).equals(payload.get())) {
            return Checked.failed("payload is not in canonical form");
        }
        return new Checked(Optional.of(statement), List.of());
    }

    private static boolean signatureVerifies(TrustedKey key, String[] parts) {
        byte[] signature = Base64.getUrlDecoder().decode(parts[2]);
        if (signature.length != 64) {
            return false;
        }
        try {
            Signature verifier = Signature.getInstance(
                    key.algorithm().equals(EDDSA) ? "Ed25519" : "SHA256withECDSAinP1363Format");
            verifier.initVerify(key.key());
            verifier.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
            return verifier.verify(signature);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            return false;
        }
    }

    /** The JSON value of a base64url part, or null if it is not UTF-8 JSON. */
    private static JsonNode json(String part) {
        return utf8(part).map(ApprovalVerifier::parse).orElse(null);
    }

    private static JsonNode parse(String text) {
        try {
            return DecisionTableJson.MAPPER.readTree(text);
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    private static Optional<String> utf8(String part) {
        try {
            return Optional.of(StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(Base64.getUrlDecoder().decode(part)))
                    .toString());
        } catch (CharacterCodingException | IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    /** The JWS algorithm of a supported public key, if it is one. */
    static Optional<String> algorithmOf(PublicKey key) {
        if (key instanceof EdECPublicKey ed && ed.getParams().getName().equalsIgnoreCase("Ed25519")) {
            return Optional.of(EDDSA);
        }
        if (key instanceof ECPublicKey ec && ec.getParams().getOrder().equals(P256_ORDER)) {
            return Optional.of(ES256);
        }
        return Optional.empty();
    }

    private record TrustedKey(PublicKey key, String algorithm) {}

    public static final class Builder {

        private final Map<String, TrustedKey> keys = new LinkedHashMap<>();
        private Set<String> allowedAlgorithms = Set.copyOf(SUPPORTED);
        private int minApprovers = 1;

        private Builder() {}

        /**
         * Trusts statements signed with {@code key}, whose header names it as {@code kid}. Keep
         * retired keys configured: a statement stays valid for the content it covers.
         *
         * @param key an Ed25519 public key, for {@code EdDSA}, or a P-256 EC public key, for {@code ES256}
         * @throws IllegalArgumentException if the key is of another type, or {@code kid} already has a key
         */
        public Builder trustedKey(String kid, PublicKey key) {
            Objects.requireNonNull(kid, "kid");
            Objects.requireNonNull(key, "key");
            if (kid.isEmpty()) {
                throw new IllegalArgumentException("kid must not be empty");
            }
            String algorithm = algorithmOf(key).orElseThrow(() -> new IllegalArgumentException(
                    "key '" + kid + "' must be an Ed25519 or a P-256 EC public key"));
            if (keys.putIfAbsent(kid, new TrustedKey(key, algorithm)) != null) {
                throw new IllegalArgumentException("key '" + kid + "' is already trusted");
            }
            return this;
        }

        /**
         * The algorithms accepted; by default {@code EdDSA} and {@code ES256}, the only two supported.
         *
         * @throws IllegalArgumentException if none is given or one is not supported
         */
        public Builder allowedAlgorithms(String... algorithms) {
            Set<String> allowed = new TreeSet<>();
            for (String algorithm : algorithms) {
                if (!SUPPORTED.contains(Objects.requireNonNull(algorithm, "algorithm"))) {
                    throw new IllegalArgumentException("algorithm '" + algorithm + "' is not supported (supported: "
                            + EDDSA + ", " + ES256 + ")");
                }
                allowed.add(algorithm);
            }
            if (allowed.isEmpty()) {
                throw new IllegalArgumentException("allow at least one algorithm");
            }
            this.allowedAlgorithms = allowed;
            return this;
        }

        /**
         * The least number of distinct approvers every statement must name; default 1. Roles and
         * tiers are the governance policy's business; this is a floor the engine checks itself.
         */
        public Builder minApprovers(int minApprovers) {
            if (minApprovers < 1) {
                throw new IllegalArgumentException("minApprovers must be at least 1");
            }
            this.minApprovers = minApprovers;
            return this;
        }

        /** @throws IllegalStateException if no key is trusted */
        public ApprovalVerifier build() {
            if (keys.isEmpty()) {
                throw new IllegalStateException("trust at least one key");
            }
            return new ApprovalVerifier(this);
        }
    }
}
