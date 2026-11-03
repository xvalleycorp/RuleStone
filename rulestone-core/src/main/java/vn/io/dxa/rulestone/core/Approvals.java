//
// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.rulestone.core;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import vn.io.dxa.rulestone.api.ArtifactKind;

/**
 * The checks of {@link RuleStone.Builder#requireApproved}: every registered artifact is covered,
 * at its exact version, by a verified approval statement in which no approver is a maker and
 * enough approvers are named. Problems name versions, principals and change sets, never content.
 */
final class Approvals {

    private Approvals() {}

    /** A signed statement and the name problems give it: its file name, or its position. */
    record Named(String name, String jws) {}

    /** A registered artifact at the exact version the engine computed from its content. */
    record Artifact(ArtifactKind kind, String id, String label, String exactVersion) {

        /** How problems name it, e.g. {@code retail_credit@8 (parameter set)}. */
        String ref() {
            String ref = id + "@" + label;
            return switch (kind) {
                case TABLE -> ref;
                case FLOW -> ref + " (flow)";
                case PARAMS -> ref + " (parameter set)";
                case CATALOG -> ref + " (reason-code catalog)";
            };
        }
    }

    /**
     * The registered artifacts, in the order problems list them: parameter sets, tables, flows, the
     * catalog, named {@code catalogId}.
     */
    static List<Artifact> artifacts(Collection<ParameterSet> sets, Collection<DecisionTable> tables,
                                    Collection<DecisionFlow> flows, ReasonCodeCatalog catalog, String catalogId) {
        Set<Artifact> artifacts = new LinkedHashSet<>();
        sets.forEach(s -> artifacts.add(new Artifact(ArtifactKind.PARAMS, s.id(), s.version(),
                ParameterSetJson.exactVersion(s))));
        tables.forEach(t -> artifacts.add(new Artifact(ArtifactKind.TABLE, t.id(), t.version(),
                DecisionTableJson.exactVersion(t))));
        flows.forEach(f -> artifacts.add(new Artifact(ArtifactKind.FLOW, f.id(), f.version(),
                DecisionFlowJson.exactVersion(f))));
        if (catalog != null) {
            artifacts.add(new Artifact(ArtifactKind.CATALOG, catalogId, catalog.version(),
                    ReasonCodeCatalogJson.exactVersion(catalog)));
        }
        return List.copyOf(artifacts);
    }

    /**
     * Every problem: first those of statements that do not verify, in the order given, then those
     * of artifacts that no acceptable statement covers.
     */
    static List<String> problems(ApprovalVerifier verifier, List<Named> approvals, List<Artifact> artifacts) {
        List<String> problems = new ArrayList<>();
        List<ApprovalStatement> verified = new ArrayList<>();
        for (Named approval : approvals) {
            ApprovalVerifier.Checked<ApprovalStatement> checked = verifier.check(approval.jws());
            checked.value().ifPresent(verified::add);
            checked.problems().forEach(p -> problems.add("approval " + approval.name() + ": " + p));
        }
        for (Artifact artifact : artifacts) {
            ApprovalStatement.Subject subject =
                    new ApprovalStatement.Subject(artifact.kind(), artifact.id(), artifact.exactVersion());
            List<ApprovalStatement> covering = verified.stream().filter(s -> s.subjects().contains(subject)).toList();
            if (covering.isEmpty()) {
                Set<String> approved = new LinkedHashSet<>();
                verified.forEach(s -> s.subjects().stream()
                        .filter(o -> o.kind() == artifact.kind() && o.id().equals(artifact.id())
                                && o.version().startsWith(artifact.label() + "@sha256:"))
                        .forEach(o -> approved.add(o.version())));
                problems.add(artifact.ref() + ": no approval covers " + artifact.exactVersion()
                        + (approved.isEmpty() ? "" : "; the approved content is " + String.join(", ", approved)));
                continue;
            }
            List<String> rejected = new ArrayList<>();
            boolean accepted = false;
            for (ApprovalStatement statement : covering) {
                List<String> own = statementProblems(statement, verifier.minApprovers(), artifact.ref());
                accepted |= own.isEmpty();
                rejected.addAll(own);
            }
            if (!accepted) {
                problems.addAll(rejected);
            }
        }
        return problems;
    }

    /** Four-eyes and the minimum number of approvers, for one statement covering {@code ref}. */
    private static List<String> statementProblems(ApprovalStatement statement, int minApprovers, String ref) {
        List<String> problems = new ArrayList<>();
        String at = ref + ": approval " + statement.changeSet();
        for (ApprovalStatement.Approver approver : statement.approvers()) {
            if (statement.makers().contains(approver.principal())) {
                problems.add(at + " names approver " + approver.principal() + ", who is also a maker");
            }
        }
        int count = statement.approvers().size();
        if (count < minApprovers) {
            problems.add(at + " names " + count + (count == 1 ? " approver" : " approvers") + "; at least "
                    + minApprovers + " are required");
        }
        return problems;
    }
}
