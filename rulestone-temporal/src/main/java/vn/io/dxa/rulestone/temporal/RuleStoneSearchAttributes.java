//
// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.rulestone.temporal;

import com.fasterxml.jackson.databind.JsonNode;
import io.temporal.common.SearchAttributeKey;
import io.temporal.common.SearchAttributeUpdate;
import io.temporal.workflow.Workflow;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Typed search attributes that let operators find workflows by decision outcome, for example
 * {@code RuleStoneDecision = 'loan_eligibility' AND RuleStoneOutcome = 'REVIEW'}.
 *
 * <p>Register the attributes in each namespace before use, for example:
 *
 * <pre>
 * temporal operator search-attribute create --namespace bank \
 *     --name RuleStoneDecision --type Keyword \
 *     --name RuleStoneRuleVersion --type Keyword \
 *     --name RuleStoneOutcome --type Keyword \
 *     --name RuleStoneReasonCodes --type KeywordList
 * </pre>
 *
 * <p>Upserting an unregistered attribute fails the workflow task. A workflow holds one value per
 * attribute: when it makes several decisions, upsert the one operators search by. Search attributes
 * are not encrypted by payload codecs and are visible to anyone with visibility access, so the
 * outcome must be a categorical output such as {@code decision}, never personal data or an amount.
 */
public final class RuleStoneSearchAttributes {

    /** {@code RuleStoneDecision} (Keyword): the decision id. */
    public static final SearchAttributeKey<String> DECISION = SearchAttributeKey.forKeyword("RuleStoneDecision");

    /** {@code RuleStoneRuleVersion} (Keyword): the exact rule version, {@code <version>@sha256:<hash>}. */
    public static final SearchAttributeKey<String> RULE_VERSION =
            SearchAttributeKey.forKeyword("RuleStoneRuleVersion");

    /** {@code RuleStoneOutcome} (Keyword): the value of the chosen output, e.g. {@code APPROVE}. */
    public static final SearchAttributeKey<String> OUTCOME = SearchAttributeKey.forKeyword("RuleStoneOutcome");

    /** {@code RuleStoneReasonCodes} (KeywordList): the reason codes of the matching rules. */
    public static final SearchAttributeKey<List<String>> REASON_CODES =
            SearchAttributeKey.forKeywordList("RuleStoneReasonCodes");

    private RuleStoneSearchAttributes() {
    }

    /**
     * Upserts the decision, rule version, outcome and reason codes of {@code outcome} on the current
     * workflow. Call only from workflow code.
     *
     * @param outcome       the outcome
     * @param outcomeOutput the output whose value becomes {@code RuleStoneOutcome}, e.g. {@code "decision"};
     *                      the attribute is removed when the decision did not set that output
     * @throws IllegalArgumentException if the output is a list
     */
    public static void upsert(DecisionOutcome outcome, String outcomeOutput) {
        List<SearchAttributeUpdate<?>> updates = updates(outcome, outcomeOutput);
        Workflow.upsertTypedSearchAttributes(updates.toArray(new SearchAttributeUpdate<?>[0]));
    }

    /**
     * The updates {@link #upsert} applies, for workflows that combine them with their own.
     *
     * @param outcome       the outcome
     * @param outcomeOutput the output whose value becomes {@code RuleStoneOutcome}
     * @return the updates
     * @throws IllegalArgumentException if the output is a list
     */
    public static List<SearchAttributeUpdate<?>> updates(DecisionOutcome outcome, String outcomeOutput) {
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(outcomeOutput, "outcomeOutput");
        List<SearchAttributeUpdate<?>> updates = new ArrayList<>();
        updates.add(SearchAttributeUpdate.valueSet(DECISION, outcome.decisionId()));
        updates.add(SearchAttributeUpdate.valueSet(RULE_VERSION, outcome.ruleVersion()));
        JsonNode value = outcome.output().get(outcomeOutput);
        if (value == null || value.isNull()) {
            updates.add(SearchAttributeUpdate.valueUnset(OUTCOME));
        } else if (value.isContainerNode()) {
            throw new IllegalArgumentException("output '" + outcomeOutput + "' is not a single value");
        } else {
            // The JSON form: text as is, numbers and booleans as written.
            updates.add(SearchAttributeUpdate.valueSet(OUTCOME, value.asText()));
        }
        updates.add(outcome.reasonCodes().isEmpty()
                ? SearchAttributeUpdate.valueUnset(REASON_CODES)
                : SearchAttributeUpdate.valueSet(REASON_CODES, outcome.reasonCodes()));
        return updates;
    }
}
