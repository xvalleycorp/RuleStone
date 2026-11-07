//
// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.rulestone.governance;

import java.security.PrivateKey;
import java.security.SecureRandom;
import java.util.Objects;
import vn.io.dxa.rulestone.core.JwsWriter;

/**
 * A {@link Signer} that signs in-process with a JDK {@link PrivateKey}: an Ed25519 key signs with
 * {@code EdDSA}, a P-256 EC key with {@code ES256}. Use it in tests and in a CI job that gets the key
 * from its secret store; in production the key belongs in the bank's HSM or KMS behind another
 * {@link Signer}.
 */
public final class PrivateKeySigner implements Signer {

    private final String keyId;
    private final String algorithm;
    private final PrivateKey key;
    private final SecureRandom random;

    private PrivateKeySigner(String keyId, PrivateKey key, SecureRandom random) {
        this.keyId = Objects.requireNonNull(keyId, "keyId");
        if (keyId.isEmpty()) {
            throw new IllegalArgumentException("keyId must not be empty");
        }
        this.key = Objects.requireNonNull(key, "key");
        this.algorithm = JwsWriter.algorithmOf(key);
        this.random = random;
    }

    /**
     * @param keyId the {@code kid} that engines trust the public key under
     * @param key   an Ed25519 or a P-256 EC private key
     */
    public static PrivateKeySigner of(String keyId, PrivateKey key) {
        return new PrivateKeySigner(keyId, key, null);
    }

    /** Like {@link #of(String, PrivateKey)}, with the source of the nonces of {@code ES256} signatures, which a seed makes repeatable. */
    public static PrivateKeySigner of(String keyId, PrivateKey key, SecureRandom random) {
        return new PrivateKeySigner(keyId, key, Objects.requireNonNull(random, "random"));
    }

    @Override
    public String keyId() {
        return keyId;
    }

    @Override
    public String algorithm() {
        return algorithm;
    }

    @Override
    public byte[] sign(byte[] signingInput) {
        return JwsWriter.sign(algorithm, key, random, signingInput);
    }
}
