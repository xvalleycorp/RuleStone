//
// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.rulestone.core;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import vn.io.dxa.rulestone.cel.Bindings;

/**
 * Computes {@code DecisionRecord.factsHash}: a hash of a decision's declared inputs in
 * canonical JSON form (keys sorted, decimals without trailing zeros, written as by
 * {@link FactsJson}). The implicit {@code businessDate} is left out; the record holds it.
 *
 * <p>Without a key the hash is SHA-256. With a key it is HMAC-SHA256, which stops anyone
 * without the key from guessing low-variety inputs, such as a birth date, from the hash.
 * Immutable and thread-safe.
 */
final class FactsHasher {

    private final byte[] key;

    /** @param key HMAC key, or {@code null} for plain SHA-256 */
    FactsHasher(byte[] key) {
        this.key = key == null ? null : key.clone();
    }

    String hash(Bindings bindings) {
        Map<String, Object> inputs = new TreeMap<>(bindings.canonicalValues());
        inputs.remove(CompiledDecision.BUSINESS_DATE);
        byte[] canonical = FactsJson.write(inputs).getBytes(StandardCharsets.UTF_8);
        try {
            if (key == null) {
                return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical));
            }
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return "hmac-sha256:" + HexFormat.of().formatHex(mac.doFinal(canonical));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("SHA-256 and HmacSHA256 are required by every Java platform", e);
        }
    }
}
