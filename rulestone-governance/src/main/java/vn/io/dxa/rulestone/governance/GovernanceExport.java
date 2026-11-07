//
// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.rulestone.governance;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import vn.io.dxa.rulestone.core.ApprovalStatement;
import vn.io.dxa.rulestone.core.DecisionFlowJson;
import vn.io.dxa.rulestone.core.FileRuleRepository;
import vn.io.dxa.rulestone.core.ParameterSetJson;
import vn.io.dxa.rulestone.core.ReasonCodeCatalogJson;
import vn.io.dxa.rulestone.core.Release;
import vn.io.dxa.rulestone.core.RuleStone;

/**
 * Writes what a {@link GovernanceStore} holds to a directory in the layout of {@link FileRuleRepository},
 * so that an engine can load it from files, the log can be kept and an auditor can run
 * {@link VerifyTool} on it:
 *
 * <pre>
 * out/
 *   loan_eligibility.v4.stone.json            the versions the latest release of each target lists
 *   retail_credit.v8.params.json
 *   reason-codes.json
 *   approvals/CHG-2026-0042.approval.jws      every statement issued
 *   releases/retail-credit-prod.release.jws   the latest release of each target
 *   releases/history/retail-credit-prod.118.release.jws   every release, for the chain
 *   governance-log.jsonl                      the log, one canonical JSON entry per line
 *   governance-policy.json                    the policy in force
 * </pre>
 *
 * <p>Only the current catalog of an id can be a file, because a catalog is named by its file; the store
 * keeps every version.
 */
public final class GovernanceExport {

    /** The log, one entry per line, in the export directory. */
    public static final String LOG_FILE = "governance-log.jsonl";
    /** The policy in force, in the export directory. */
    public static final String POLICY_FILE = "governance-policy.json";
    /** The directory of all releases, under {@code releases}. */
    public static final String HISTORY_DIR = "history";

    private GovernanceExport() {}

    /**
     * @throws UncheckedIOException if a file cannot be written
     * @throws IllegalStateException if two targets list different content that would need the same file
     */
    public static void write(GovernanceStore store, Path directory) {
        try {
            Files.createDirectories(directory);
            StringBuilder log = new StringBuilder();
            store.log().forEach(entry -> log.append(LogEntryJson.write(entry)).append('\n'));
            Files.writeString(directory.resolve(LOG_FILE), log, StandardCharsets.UTF_8);
            store.policy().ifPresent(policy -> write(directory.resolve(POLICY_FILE), GovernancePolicyJson.write(policy) + "\n"));
            for (GovernanceStore.IssuedApproval approval : store.approvals()) {
                write(directory.resolve(FileRuleRepository.APPROVALS_DIR).resolve(approval.name()), approval.jws() + "\n");
            }
            Map<String, String> files = new HashMap<>();
            for (String target : store.targets()) {
                for (GovernanceStore.IssuedRelease issued : store.releases(target)) {
                    write(directory.resolve(FileRuleRepository.RELEASES_DIR).resolve(HISTORY_DIR)
                            .resolve(target + "." + issued.release().sequence() + RuleStone.RELEASE_FILE_SUFFIX), issued.jws() + "\n");
                }
                GovernanceStore.IssuedRelease latest = store.latestRelease(target).orElseThrow();
                write(directory.resolve(FileRuleRepository.RELEASES_DIR).resolve(target + RuleStone.RELEASE_FILE_SUFFIX),
                        latest.jws() + "\n");
                for (Release.Entry entry : latest.release().artifacts()) {
                    GovernanceStore.ArtifactVersion version = store.version(entry.kind(), entry.id(), entry.version()).orElseThrow();
                    String file = fileName(version);
                    String previous = files.put(file, version.content());
                    if (previous != null && !previous.equals(version.content())) {
                        throw new IllegalStateException("two targets list different content that needs the file " + file);
                    }
                    write(directory.resolve(file), version.content().endsWith("\n") ? version.content() : version.content() + "\n");
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String fileName(GovernanceStore.ArtifactVersion version) {
        String label = version.label().replaceAll("[^A-Za-z0-9._-]", "_");
        return switch (version.kind()) {
            case TABLE -> version.id() + ".v" + label + ".stone.json";
            case FLOW -> version.id() + ".v" + label + DecisionFlowJson.FILE_SUFFIX;
            case PARAMS -> version.id() + ".v" + label + ParameterSetJson.FILE_SUFFIX;
            case CATALOG -> version.id().equals(ApprovalStatement.CATALOG_ID) ? ReasonCodeCatalogJson.FILE_NAME
                    : version.id() + "." + ReasonCodeCatalogJson.FILE_NAME;
        };
    }

    private static void write(Path file, String text) {
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, text, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
