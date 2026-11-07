//
// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.rulestone.governance;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import vn.io.dxa.rulestone.core.ApprovalVerifier;

/**
 * The command-line tool {@code rulestone-governance verify}: checks the governance log and the release
 * chain of a directory written by {@link GovernanceExport}, and prints a report that an auditor can
 * keep. See {@link GovernanceVerifier} for the checks.
 *
 * <pre>
 * java -cp rulestone-governance.jar:rulestone-core.jar:rulestone-api.jar:jackson-... \
 *     vn.io.dxa.rulestone.governance.VerifyTool verify ./governance-export \
 *     --key gov-2026=gov-2026-public.pem [--key gov-2025=gov-2025-public.pem] [--min-approvers 2]
 * </pre>
 *
 * <p>A key file holds a public key in X.509 SubjectPublicKeyInfo form, Ed25519 or P-256, as PEM or plain
 * base64. The exit status is 0 if everything verifies, 1 if a problem was found and 2 if the arguments
 * or files cannot be used.
 */
public final class VerifyTool {

    private VerifyTool() {}

    public static void main(String[] args) {
        System.exit(run(args, System.out, System.err));
    }

    /**
     * Runs the tool.
     *
     * @return the exit status
     */
    public static int run(String[] args, PrintStream out, PrintStream err) {
        if (args.length < 2 || !args[0].equals("verify")) {
            return usage(err);
        }
        Path directory = Path.of(args[1]);
        Map<String, Path> keyFiles = new LinkedHashMap<>();
        int minApprovers = 1;
        for (int i = 2; i < args.length; i++) {
            if (args[i].equals("--key") && i + 1 < args.length && args[i + 1].indexOf('=') > 0) {
                String spec = args[++i];
                keyFiles.put(spec.substring(0, spec.indexOf('=')), Path.of(spec.substring(spec.indexOf('=') + 1)));
            } else if (args[i].equals("--min-approvers") && i + 1 < args.length) {
                try {
                    minApprovers = Integer.parseInt(args[++i]);
                } catch (NumberFormatException e) {
                    return usage(err);
                }
            } else {
                return usage(err);
            }
        }
        if (keyFiles.isEmpty()) {
            err.println("trust at least one key: --key <kid>=<public key file>");
            return 2;
        }
        try {
            ApprovalVerifier.Builder verifier = ApprovalVerifier.builder()
                    .minApprovers(minApprovers);
            for (Map.Entry<String, Path> key : keyFiles.entrySet()) {
                verifier.trustedKey(key.getKey(), publicKey(key.getValue()));
            }
            VerificationReport report = GovernanceVerifier.builder().verifier(verifier.build()).build().verify(directory);
            out.print(report.format());
            return report.ok() ? 0 : 1;
        } catch (IllegalArgumentException | IOException | GeneralSecurityException e) {
            err.println("cannot verify: " + e.getMessage());
            return 2;
        }
    }

    private static int usage(PrintStream err) {
        err.println("usage: verify <directory> --key <kid>=<public key file> [--key ...] [--min-approvers <n>]");
        return 2;
    }

    private static PublicKey publicKey(Path file) throws IOException, GeneralSecurityException {
        StringBuilder base64 = new StringBuilder();
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            if (!line.startsWith("-----")) {
                base64.append(line.strip());
            }
        }
        X509EncodedKeySpec spec = new X509EncodedKeySpec(Base64.getMimeDecoder().decode(base64.toString()));
        try {
            return KeyFactory.getInstance("Ed25519").generatePublic(spec);
        } catch (GeneralSecurityException e) {
            return KeyFactory.getInstance("EC").generatePublic(spec);
        }
    }
}
