//
// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.rulestone.governance;

import java.time.LocalDate;
import java.util.Optional;
import vn.io.dxa.rulestone.api.ArtifactKind;
import vn.io.dxa.rulestone.core.ApprovalStatement;
import vn.io.dxa.rulestone.core.DecisionFlow;
import vn.io.dxa.rulestone.core.DecisionFlowJson;
import vn.io.dxa.rulestone.core.DecisionTable;
import vn.io.dxa.rulestone.core.DecisionTableJson;
import vn.io.dxa.rulestone.core.InvalidDecisionException;
import vn.io.dxa.rulestone.core.ParameterSet;
import vn.io.dxa.rulestone.core.ParameterSetJson;
import vn.io.dxa.rulestone.core.ReasonCodeCatalog;
import vn.io.dxa.rulestone.core.ReasonCodeCatalogJson;

/** Reads the identity of an artifact from its content: id, version label, exact version, effective dates. */
record ArtifactContent(ArtifactKind kind, String id, String label, String exactVersion, Optional<LocalDate> effectiveFrom,
        Optional<LocalDate> effectiveTo) {

    /**
     * @param requestedId the id the caller expects, or null to take it from the content; a catalog has no
     *                    id of its own and is named {@value ApprovalStatement#CATALOG_ID} unless the caller names it
     * @throws GovernanceException if the content is not valid, or has another id than requested
     */
    static ArtifactContent parse(ArtifactKind kind, String requestedId, String content) {
        ArtifactContent parsed;
        try {
            switch (kind) {
                case TABLE -> {
                    DecisionTable table = DecisionTableJson.read(content);
                    parsed = new ArtifactContent(kind, table.id(), table.version(), DecisionTableJson.exactVersion(table),
                            table.effectiveFrom(), table.effectiveTo());
                }
                case FLOW -> {
                    DecisionFlow flow = DecisionFlowJson.read(content);
                    parsed = new ArtifactContent(kind, flow.id(), flow.version(), DecisionFlowJson.exactVersion(flow),
                            flow.effectiveFrom(), flow.effectiveTo());
                }
                case PARAMS -> {
                    ParameterSet set = ParameterSetJson.read(content);
                    parsed = new ArtifactContent(kind, set.id(), set.version(), ParameterSetJson.exactVersion(set),
                            set.effectiveFrom(), set.effectiveTo());
                }
                default -> {
                    ReasonCodeCatalog catalog = ReasonCodeCatalogJson.read(content);
                    parsed = new ArtifactContent(kind, requestedId != null ? requestedId : ApprovalStatement.CATALOG_ID,
                            catalog.version(), ReasonCodeCatalogJson.exactVersion(catalog), Optional.empty(), Optional.empty());
                }
            }
        } catch (InvalidDecisionException e) {
            throw new GovernanceException(GovernanceException.Code.INVALID_CONTENT,
                    "the " + kind.jsonName() + " is not valid: " + String.join("; ", e.problems()));
        }
        if (requestedId != null && !requestedId.equals(parsed.id())) {
            throw new GovernanceException(GovernanceException.Code.INVALID_REQUEST,
                    "the " + kind.jsonName() + " has id '" + parsed.id() + "', not '" + requestedId + "'");
        }
        return parsed;
    }

    ApprovalStatement.Subject subject() {
        return new ApprovalStatement.Subject(kind, id, exactVersion);
    }
}
