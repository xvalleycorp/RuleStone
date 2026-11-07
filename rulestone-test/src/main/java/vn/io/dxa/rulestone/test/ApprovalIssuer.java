//
// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.rulestone.test;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.spec.ECGenParameterSpec;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import vn.io.dxa.rulestone.api.ArtifactKind;
import vn.io.dxa.rulestone.core.ApprovalStatement;
import vn.io.dxa.rulestone.core.ApprovalStatementJson;
import vn.io.dxa.rulestone.core.ApprovalVerifier;
import vn.io.dxa.rulestone.core.DecisionFlow;
import vn.io.dxa.rulestone.core.DecisionFlowJson;
import vn.io.dxa.rulestone.core.DecisionTable;
import vn.io.dxa.rulestone.core.DecisionTableJson;
import vn.io.dxa.rulestone.core.JwsWriter;
import vn.io.dxa.rulestone.core.ParameterSet;
import vn.io.dxa.rulestone.core.ParameterSetJson;
import vn.io.dxa.rulestone.core.ReasonCodeCatalog;
import vn.io.dxa.rulestone.core.ReasonCodeCatalogJson;
import vn.io.dxa.rulestone.core.Release;
import vn.io.dxa.rulestone.core.ReleaseJson;
import vn.io.dxa.rulestone.core.VersionedDefinition;

/**
 * Issues signed approval statements and releases, for tests of engines built with
 * {@link vn.io.dxa.rulestone.core.RuleStone.Builder#requireApproved}, and for a CI job that approves
 * the content of a reviewed pull request before a governance service exists.
 *
 * <pre>{@code
 * ApprovalIssuer issuer = ApprovalIssuer.generate("test-key", ApprovalVerifier.EDDSA);
 * String jws = issuer.issue(ApprovalIssuer.statement("CHG-1")
 *         .covering(table).covering(parameterSet)
 *         .maker("u:nguyen.van.a")
 *         .approver("u:tran.thi.b", "RULE_CHECKER")
 *         .build());
 * RuleStoneEngine engine = RuleStone.builder()
 *         .decision(table).paramSet(parameterSet)
 *         .approval(jws)
 *         .requireApproved(ApprovalVerifier.builder().trustedKey("test-key", issuer.publicKey()).build())
 *         .build();
 * }</pre>
 *
 * <p>In production the private key belongs in the bank's HSM or KMS; a CI job that signs with a
 * key it holds should get it from the CI system's secret store, never from the repository.
 */
public final class ApprovalIssuer {

    private final String kid;
    private final String algorithm;
    private final PrivateKey privateKey;
    private final PublicKey publicKey;
    private final SecureRandom random;

    private ApprovalIssuer(String kid, PrivateKey privateKey, PublicKey publicKey, SecureRandom random) {
        this.kid = Objects.requireNonNull(kid, "kid");
        this.privateKey = Objects.requireNonNull(privateKey, "privateKey");
        this.publicKey = publicKey;
        this.random = random;
        this.algorithm = JwsWriter.algorithmOf(privateKey);
    }

    /**
     * An issuer that signs with {@code privateKey}, named {@code kid} in the header.
     *
     * @param privateKey an Ed25519 private key, which signs with {@code EdDSA}, or a P-256 EC private
     *                   key, which signs with {@code ES256}
     */
    public static ApprovalIssuer of(String kid, PrivateKey privateKey) {
        return new ApprovalIssuer(kid, privateKey, null, null);
    }

    /**
     * Like {@link #of(String, PrivateKey)}, with the source of the random nonces of {@code ES256}
     * signatures; a seeded generator makes them repeatable, for test fixtures. {@code EdDSA}
     * signatures are always repeatable.
     */
    public static ApprovalIssuer of(String kid, PrivateKey privateKey, SecureRandom random) {
        return new ApprovalIssuer(kid, privateKey, null, Objects.requireNonNull(random, "random"));
    }

    /**
     * An issuer with a new random key pair, for tests.
     *
     * @param algorithm {@link ApprovalVerifier#EDDSA} or {@link ApprovalVerifier#ES256}
     */
    public static ApprovalIssuer generate(String kid, String algorithm) {
        try {
            KeyPairGenerator generator;
            if (ApprovalVerifier.EDDSA.equals(algorithm)) {
                generator = KeyPairGenerator.getInstance("Ed25519");
            } else if (ApprovalVerifier.ES256.equals(algorithm)) {
                generator = KeyPairGenerator.getInstance("EC");
                generator.initialize(new ECGenParameterSpec("secp256r1"));
            } else {
                throw new IllegalArgumentException("algorithm must be EdDSA or ES256");
            }
            KeyPair pair = generator.generateKeyPair();
            return new ApprovalIssuer(kid, pair.getPrivate(), pair.getPublic(), null);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("EdDSA and ES256 are required by every Java 17 platform", e);
        }
    }

    public String kid() {
        return kid;
    }

    /** {@code EdDSA} or {@code ES256}. */
    public String algorithm() {
        return algorithm;
    }

    /**
     * The public key that verifies this issuer's statements.
     *
     * @throws IllegalStateException if the issuer was made from a private key alone
     */
    public PublicKey publicKey() {
        if (publicKey == null) {
            throw new IllegalStateException("the issuer was made from a private key alone");
        }
        return publicKey;
    }

    /** A verifier that trusts this issuer's key, with the default algorithms and one approver at least. */
    public ApprovalVerifier verifier() {
        return ApprovalVerifier.builder().trustedKey(kid, publicKey()).build();
    }

    /** Signs {@code statement}: JWS compact serialization of its canonical form. */
    public String issue(ApprovalStatement statement) {
        return sign(JwsWriter.header(algorithm, kid, ApprovalVerifier.TYPE), ApprovalStatementJson.write(statement));
    }

    /** Signs {@code release}: JWS compact serialization of its canonical form, with {@code typ} {@value ApprovalVerifier#RELEASE_TYPE}. */
    public String issue(Release release) {
        return sign(JwsWriter.header(algorithm, kid, ApprovalVerifier.RELEASE_TYPE), ReleaseJson.write(release));
    }

    /**
     * Signs any header and payload text as they are, without checking either: for tests of how an
     * engine refuses malformed statements, such as a forbidden header or a payload that is not
     * canonical.
     */
    public String sign(String header, String payload) {
        String input = JwsWriter.signingInput(header, payload);
        try {
            return JwsWriter.compact(input, JwsWriter.sign(algorithm, privateKey, random, input.getBytes(StandardCharsets.US_ASCII)));
        } catch (IllegalStateException e) {
            throw new IllegalStateException("cannot sign with key '" + kid + "'", e.getCause());
        }
    }

    /** Starts release {@code sequence} of {@code target}. */
    public static ReleaseBuilder release(String target, long sequence) {
        return new ReleaseBuilder(target, sequence);
    }

    /** The exact version of a table, flow or parameter set, and its kind. */
    private static ApprovalStatement.Subject subject(VersionedDefinition definition) {
        if (definition instanceof DecisionTable table) {
            return new ApprovalStatement.Subject(ArtifactKind.TABLE, table.id(), DecisionTableJson.exactVersion(table));
        }
        if (definition instanceof DecisionFlow flow) {
            return new ApprovalStatement.Subject(ArtifactKind.FLOW, flow.id(), DecisionFlowJson.exactVersion(flow));
        }
        ParameterSet set = (ParameterSet) definition;
        return new ApprovalStatement.Subject(ArtifactKind.PARAMS, set.id(), ParameterSetJson.exactVersion(set));
    }

    /** Starts a statement for change set {@code changeSet}. */
    public static StatementBuilder statement(String changeSet) {
        return new StatementBuilder(changeSet);
    }

    /**
     * Builds an {@link ApprovalStatement}. By default the approval id is a UUID derived from the change
     * set, the issuer is {@code rulestone-test}, and the statement and every approval are dated now,
     * to the second.
     */
    public static final class StatementBuilder {

        private String changeSet;
        private String approvalId;
        private final List<ApprovalStatement.Subject> subjects = new ArrayList<>();
        private final List<String> makers = new ArrayList<>();
        private final List<String[]> approvers = new ArrayList<>();
        private Optional<String> policy = Optional.empty();
        private boolean emergency;
        private Optional<String> postApprovalDue = Optional.empty();
        private String issuer = "rulestone-test";
        private String issuedAt = Instant.now().truncatedTo(ChronoUnit.SECONDS).toString();
        private Optional<String> logEntry = Optional.empty();

        private StatementBuilder(String changeSet) {
            this.changeSet = Objects.requireNonNull(changeSet, "changeSet");
        }

        public StatementBuilder changeSet(String changeSet) {
            this.changeSet = Objects.requireNonNull(changeSet, "changeSet");
            return this;
        }

        /** Approves the exact content of a table, flow or parameter set version. */
        public StatementBuilder covering(VersionedDefinition definition) {
            ApprovalStatement.Subject subject = ApprovalIssuer.subject(definition);
            return subject(subject.kind(), subject.id(), subject.version());
        }

        /** Approves the exact content of a reason-code catalog, named {@value ApprovalStatement#CATALOG_ID}. */
        public StatementBuilder covering(ReasonCodeCatalog catalog) {
            return subject(ArtifactKind.CATALOG, ApprovalStatement.CATALOG_ID, ReasonCodeCatalogJson.exactVersion(catalog));
        }

        /** Approves an exact version, {@code <label>@sha256:<hex>}, given as is. */
        public StatementBuilder subject(ArtifactKind kind, String id, String exactVersion) {
            subjects.add(new ApprovalStatement.Subject(kind, id, exactVersion));
            return this;
        }

        public StatementBuilder maker(String principal) {
            makers.add(Objects.requireNonNull(principal, "principal"));
            return this;
        }

        public StatementBuilder approver(String principal) {
            approvers.add(new String[] {Objects.requireNonNull(principal, "principal"), null});
            return this;
        }

        public StatementBuilder approver(String principal, String role) {
            approvers.add(new String[] {Objects.requireNonNull(principal, "principal"), Objects.requireNonNull(role, "role")});
            return this;
        }

        public StatementBuilder approvalId(String approvalId) {
            this.approvalId = Objects.requireNonNull(approvalId, "approvalId");
            return this;
        }

        public StatementBuilder policy(String policy) {
            this.policy = Optional.of(policy);
            return this;
        }

        /** Marks an emergency approval, which the full policy must post-approve by {@code postApprovalDue}. */
        public StatementBuilder emergency(String postApprovalDue) {
            this.emergency = true;
            this.postApprovalDue = Optional.of(postApprovalDue);
            return this;
        }

        public StatementBuilder issuer(String issuer) {
            this.issuer = Objects.requireNonNull(issuer, "issuer");
            return this;
        }

        /** When the statement and, unless given otherwise, every approval were made, e.g. {@code 2026-11-20T07:41:02Z}. */
        public StatementBuilder issuedAt(String issuedAt) {
            this.issuedAt = Objects.requireNonNull(issuedAt, "issuedAt");
            return this;
        }

        public StatementBuilder logEntry(String logEntry) {
            this.logEntry = Optional.of(logEntry);
            return this;
        }

        public ApprovalStatement build() {
            List<ApprovalStatement.Approver> list = approvers.stream()
                    .map(a -> new ApprovalStatement.Approver(a[0], Optional.ofNullable(a[1]), issuedAt))
                    .toList();
            String id = approvalId != null ? approvalId
                    : UUID.nameUUIDFromBytes(changeSet.getBytes(StandardCharsets.UTF_8)).toString();
            return new ApprovalStatement(id, changeSet, subjects, makers, list, policy, emergency,
                    postApprovalDue, issuer, issuedAt, logEntry);
        }
    }

    /**
     * Builds a {@link Release}. By default it is issued now, to the second; a release after the first
     * needs {@link #previous}.
     */
    public static final class ReleaseBuilder {

        private final String target;
        private final long sequence;
        private Optional<String> previous = Optional.empty();
        private final List<Release.Entry> artifacts = new ArrayList<>();
        private final List<Release.Route> routing = new ArrayList<>();
        private String issuedAt = Instant.now().truncatedTo(ChronoUnit.SECONDS).toString();

        private ReleaseBuilder(String target, long sequence) {
            this.target = Objects.requireNonNull(target, "target");
            this.sequence = sequence;
        }

        /** Chains to {@code previous}, which must be release {@code sequence - 1}. */
        public ReleaseBuilder previous(Release previous) {
            return previous(ReleaseJson.hash(previous));
        }

        /** Chains to the release with this hash, {@code sha256:<hex>}, given as is. */
        public ReleaseBuilder previous(String hash) {
            this.previous = Optional.of(Objects.requireNonNull(hash, "hash"));
            return this;
        }

        /** Lists the exact content of a table, flow or parameter set version as {@code ACTIVE}. */
        public ReleaseBuilder active(VersionedDefinition definition) {
            ApprovalStatement.Subject subject = subject(definition);
            return artifact(subject.kind(), subject.id(), subject.version(), Release.State.ACTIVE);
        }

        /** Lists the exact content of a table, flow or parameter set version as {@code RETIRED}, still loaded for replay. */
        public ReleaseBuilder retired(VersionedDefinition definition) {
            ApprovalStatement.Subject subject = subject(definition);
            return artifact(subject.kind(), subject.id(), subject.version(), Release.State.RETIRED);
        }

        /** Lists the exact content of a reason-code catalog as {@code ACTIVE}, named {@value ApprovalStatement#CATALOG_ID}. */
        public ReleaseBuilder active(ReasonCodeCatalog catalog) {
            return artifact(ArtifactKind.CATALOG, ApprovalStatement.CATALOG_ID, ReasonCodeCatalogJson.exactVersion(catalog),
                    Release.State.ACTIVE);
        }

        /** Lists an exact version, {@code <label>@sha256:<hex>}, given as is. */
        public ReleaseBuilder artifact(ArtifactKind kind, String id, String exactVersion, Release.State state) {
            artifacts.add(new Release.Entry(kind, id, exactVersion, state));
            return this;
        }

        /** Routes {@code share} of the traffic of a table or flow to this version, which must also be listed. */
        public ReleaseBuilder canary(VersionedDefinition definition, double share) {
            ApprovalStatement.Subject subject = subject(definition);
            return route(Release.Route.canary(subject.id(), subject.version(), share));
        }

        /** Runs this version of a table or flow in shadow, which must also be listed. */
        public ReleaseBuilder shadow(VersionedDefinition definition) {
            ApprovalStatement.Subject subject = subject(definition);
            return route(Release.Route.shadow(subject.id(), subject.version()));
        }

        public ReleaseBuilder route(Release.Route route) {
            routing.add(Objects.requireNonNull(route, "route"));
            return this;
        }

        /** When the release is issued, e.g. {@code 2026-11-20T07:45:00Z}. */
        public ReleaseBuilder issuedAt(String issuedAt) {
            this.issuedAt = Objects.requireNonNull(issuedAt, "issuedAt");
            return this;
        }

        public Release build() {
            return new Release(target, sequence, previous, artifacts, routing, issuedAt);
        }
    }
}
