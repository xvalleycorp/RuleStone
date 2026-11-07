//
// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.rulestone.governance;

/**
 * Signs the statements and releases the governance service issues. The private key never leaves the
 * implementation: a bank's implementation calls its HSM or KMS, {@link PrivateKeySigner} signs with
 * a key held in memory, for tests and for a CI job.
 *
 * <p>The service builds the JWS and asks for the signature of its signing input; see
 * {@link vn.io.dxa.rulestone.core.JwsWriter}. Implementations must be thread-safe.
 */
public interface Signer {

    /** The id of the signing key, written as {@code kid} in the header and configured in the engines' verifiers. */
    String keyId();

    /** {@code EdDSA} (Ed25519) or {@code ES256} (ECDSA on P-256 with SHA-256). */
    String algorithm();

    /**
     * Signs {@code signingInput}, the ASCII bytes of {@code base64url(header).base64url(payload)}.
     *
     * @return for {@code EdDSA} the 64 bytes of the signature; for {@code ES256} the 64 bytes of R and
     *         S, 32 bytes each, as JWS writes them, not the DER form that some HSMs return
     * @throws IllegalStateException if the key cannot sign, such as an HSM that is unreachable
     */
    byte[] sign(byte[] signingInput);
}
