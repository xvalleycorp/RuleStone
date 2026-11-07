//
// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.rulestone.core;

import com.fasterxml.jackson.core.JsonProcessingException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.EdECPrivateKey;
import java.util.Base64;
import java.util.Map;
import java.util.TreeMap;

/**
 * Writes JWS compact serialization (RFC 7515) in the form {@link ApprovalVerifier} reads: the
 * protected header is exactly {@code {"alg": …, "kid": …, "typ": …}}, and the signature is the raw
 * R and S of 32 bytes each for {@code ES256}. Issuers of approval statements and releases share it:
 * {@code ApprovalIssuer} in {@code rulestone-test} and the signers of {@code rulestone-governance}.
 *
 * <p>The steps are separate so that a key that cannot leave an HSM or a KMS can do the middle
 * one: {@link #signingInput} builds the text to sign, whoever holds the key signs its bytes, and
 * {@link #compact} joins the signature to it.
 */
public final class JwsWriter {

    private JwsWriter() {}

    /** The canonical protected header: keys sorted, compact. */
    public static String header(String algorithm, String kid, String type) {
        try {
            return DecisionTableJson.MAPPER.writeValueAsString(
                    new TreeMap<>(Map.of("alg", algorithm, "kid", kid, "typ", type)));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    /** {@code base64url(header).base64url(payload)}: the text that is signed, as ASCII. */
    public static String signingInput(String header, String payload) {
        Base64.Encoder base64 = Base64.getUrlEncoder().withoutPadding();
        return base64.encodeToString(header.getBytes(StandardCharsets.UTF_8)) + "."
                + base64.encodeToString(payload.getBytes(StandardCharsets.UTF_8));
    }

    /** The signed JWS: the signing input and the base64url signature. */
    public static String compact(String signingInput, byte[] signature) {
        return signingInput + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(signature);
    }

    /**
     * The JWS algorithm that signs with {@code key}: {@link ApprovalVerifier#EDDSA} for an Ed25519
     * private key, {@link ApprovalVerifier#ES256} for an EC private key.
     *
     * @throws IllegalArgumentException for any other key
     */
    public static String algorithmOf(PrivateKey key) {
        if (key instanceof EdECPrivateKey) {
            return ApprovalVerifier.EDDSA;
        }
        if (key instanceof ECPrivateKey) {
            return ApprovalVerifier.ES256;
        }
        throw new IllegalArgumentException("the key must be an Ed25519 or a P-256 EC private key");
    }

    /**
     * Signs {@code signingInput} with the JDK. {@code EdDSA} signatures are repeatable; the nonces of
     * {@code ES256} come from {@code random}, or from the platform's generator if it is null, and a
     * seeded generator makes them repeatable for test fixtures.
     *
     * @param algorithm {@link ApprovalVerifier#EDDSA} or {@link ApprovalVerifier#ES256}
     */
    public static byte[] sign(String algorithm, PrivateKey key, SecureRandom random, byte[] signingInput) {
        try {
            Signature signer = Signature.getInstance(
                    algorithm.equals(ApprovalVerifier.EDDSA) ? "Ed25519" : "SHA256withECDSAinP1363Format");
            if (random == null) {
                signer.initSign(key);
            } else {
                signer.initSign(key, random);
            }
            signer.update(signingInput);
            return signer.sign();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("cannot sign with a " + algorithm + " key", e);
        }
    }
}
