//
// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.rulestone.test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPairGenerator;
import java.security.PublicKey;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import vn.io.dxa.rulestone.api.RuleStoneEngine;
import vn.io.dxa.rulestone.core.ApprovalStatement;
import vn.io.dxa.rulestone.core.ApprovalStatementJson;
import vn.io.dxa.rulestone.core.ApprovalVerifier;
import vn.io.dxa.rulestone.core.DecisionTable;
import vn.io.dxa.rulestone.core.DecisionTableJson;
import vn.io.dxa.rulestone.core.InvalidDecisionException;
import vn.io.dxa.rulestone.core.ParameterSet;
import vn.io.dxa.rulestone.core.ParameterSetJson;
import vn.io.dxa.rulestone.core.RuleStone;

/** Approval statements, ApprovalVerifier and RuleStone.Builder#requireApproved; the conformance cases are in ApprovalFixtures. */
class ApprovalsTest {

    private static final Path EXAMPLES = Path.of("..", "examples");
    private final DecisionTable table = DecisionTableJson.read(EXAMPLES.resolve("loan_eligibility.v4.stone.json"));
    private final ParameterSet set = ParameterSetJson.read(EXAMPLES.resolve("retail_credit.v1.params.json"));

    private ApprovalStatement statement() {
        return ApprovalIssuer.statement("CHG-1").covering(table).covering(set)
                .maker("u:nguyen.van.a").approver("u:tran.thi.b", "RULE_CHECKER").build();
    }

    @Test
    void anEngineLoadsApprovedContent() {
        for (String algorithm : List.of(ApprovalVerifier.EDDSA, ApprovalVerifier.ES256)) {
            ApprovalIssuer issuer = ApprovalIssuer.generate("test-key", algorithm);
            RuleStoneEngine engine = RuleStone.builder().decision(table).paramSet(set)
                    .approval(issuer.issue(statement()))
                    .requireApproved(issuer.verifier())
                    .build();
            assertThat(engine).isNotNull();
        }
    }

    @Test
    void verifyReturnsTheStatement() {
        ApprovalIssuer issuer = ApprovalIssuer.generate("k", ApprovalVerifier.ES256);
        ApprovalStatement statement = statement();
        assertThat(issuer.verifier().verify(issuer.issue(statement))).isEqualTo(statement);
        assertThat(ApprovalStatementJson.read(ApprovalStatementJson.write(statement))).isEqualTo(statement);
    }

    @Test
    void verifyListsProblemsWithoutTheSource() {
        ApprovalIssuer issuer = ApprovalIssuer.generate("k", ApprovalVerifier.EDDSA);
        ApprovalVerifier other = ApprovalIssuer.generate("other", ApprovalVerifier.EDDSA).verifier();
        assertThatThrownBy(() -> other.verify(issuer.issue(statement())))
                .isInstanceOf(InvalidDecisionException.class)
                .hasMessage("invalid decision definition (1 problem):\n  - unknown key 'k'");
    }

    @Test
    void unapprovedContentFailsTheBuildListingEveryProblem() {
        ApprovalIssuer issuer = ApprovalIssuer.generate("k", ApprovalVerifier.EDDSA);
        String onlyTable = issuer.issue(ApprovalIssuer.statement("CHG-2").covering(table)
                .maker("u:a").approver("u:a").build());
        assertThatThrownBy(() -> RuleStone.builder().decision(table).paramSet(set)
                .approval(onlyTable).requireApproved(issuer.verifier()).build())
                .isInstanceOf(InvalidDecisionException.class)
                .hasMessage("invalid decision definition (2 problems):\n"
                        + "  - retail_credit@1 (parameter set): no approval covers " + ParameterSetJson.exactVersion(set) + "\n"
                        + "  - loan_eligibility@4: approval CHG-2 names approver u:a, who is also a maker");
    }

    @Test
    void withoutRequireApprovedStatementsAreIgnored() {
        RuleStoneEngine engine = RuleStone.builder().decision(table).paramSet(set).approval("not-a-jws").build();
        assertThat(engine).isNotNull();
    }

    @Test
    void approvalsAreReadFromADirectory(@TempDir Path dir) throws IOException {
        ApprovalIssuer issuer = ApprovalIssuer.generate("k", ApprovalVerifier.EDDSA);
        Files.writeString(dir.resolve("CHG-1.approval.jws"), issuer.issue(statement()) + "\n", StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("CHG-0.approval.jws"), "tampered", StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("README.md"), "not a statement", StandardCharsets.UTF_8);
        assertThatThrownBy(() -> RuleStone.builder().decision(table).paramSet(set)
                .approvals(dir).requireApproved(issuer.verifier()).build())
                .isInstanceOf(InvalidDecisionException.class)
                .hasMessage("invalid decision definition (1 problem):\n  - approval CHG-0.approval.jws: "
                        + "not a JWS compact serialization (three base64url parts separated by '.')");
        Files.delete(dir.resolve("CHG-0.approval.jws"));
        assertThat(RuleStone.builder().decision(table).paramSet(set).approvals(dir)
                .requireApproved(issuer.verifier()).build()).isNotNull();
    }

    @Test
    void theVerifierAcceptsOnlySupportedKeysAndSettings() throws Exception {
        PublicKey rsa = KeyPairGenerator.getInstance("RSA").generateKeyPair().getPublic();
        assertThatThrownBy(() -> ApprovalVerifier.builder().trustedKey("rsa", rsa))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("key 'rsa' must be an Ed25519 or a P-256 EC public key");
        PublicKey key = ApprovalIssuer.generate("k", ApprovalVerifier.EDDSA).publicKey();
        assertThatThrownBy(() -> ApprovalVerifier.builder().trustedKey("k", key).trustedKey("k", key))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ApprovalVerifier.builder().allowedAlgorithms("RS256"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ApprovalVerifier.builder().allowedAlgorithms())
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ApprovalVerifier.builder().minApprovers(0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ApprovalVerifier.builder().build()).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void readingIsStrict() {
        assertThatThrownBy(() -> ApprovalStatementJson.read("{\"type\":\"rulestone.approval\",\"type\":\"x\"}"))
                .isInstanceOf(InvalidDecisionException.class)
                .hasMessageContaining("invalid JSON: Duplicate field 'type'");
    }
}
