//
// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.rulestone.camunda8;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

import io.camunda.client.api.CamundaFuture;
import io.camunda.client.api.command.CompleteJobCommandStep1;
import io.camunda.client.api.command.FailJobCommandStep1;
import io.camunda.client.api.command.FailJobCommandStep1.FailJobCommandStep2;
import io.camunda.client.api.command.ThrowErrorCommandStep1;
import io.camunda.client.api.command.ThrowErrorCommandStep1.ThrowErrorCommandStep2;
import io.camunda.client.api.response.ActivatedJob;
import io.camunda.client.api.worker.JobClient;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import org.mockito.Answers;
import vn.io.dxa.rulestone.api.RuleStoneEngine;
import vn.io.dxa.rulestone.core.DecisionTable;
import vn.io.dxa.rulestone.core.DecisionTableJson;
import vn.io.dxa.rulestone.core.RuleStone;

/** Rules, engines and mocked Camunda clients shared by the tests. */
final class TestSupport {

    static final ZoneId HCM = ZoneId.of("Asia/Ho_Chi_Minh");

    /** 5 November 2026 in Ho Chi Minh City: version 3 of loan_eligibility is in force. */
    static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-05T03:00:00Z"), HCM);

    /** Values that must never appear in a message. */
    static final String SECRET_INCOME = "25750123";
    static final String SECRET_DEBT = "98765432.17";

    static final String CARD_FEE = """
            {
              "id": "card_fee",
              "version": "1",
              "hitPolicy": "FIRST",
              "inputs": { "customer": { "birthDate": "date" }, "spend": "money", "rate": "decimal" },
              "outputs": { "fee": "money", "nextReview": "date", "tier": "string" },
              "rules": [
                { "id": "YOUTH", "when": "yearsBetween(customer.birthDate, businessDate) < 25",
                  "then": { "fee": "money(0, spend.currency())", "nextReview": "businessDate.plusYears(1)",
                            "tier": "'YOUTH'" },
                  "reasonCode": "YOUTH_WAIVER" },
                { "id": "STANDARD", "when": "true",
                  "then": { "fee": "spend * rate", "nextReview": "businessDate.plusMonths(6)",
                            "tier": "'STANDARD'" } }
              ]
            }
            """;

    /** A table without a catch-all rule, so that some inputs match nothing. */
    static final String RISK_BAND = """
            {
              "id": "risk_band",
              "version": "1",
              "hitPolicy": "FIRST",
              "inputs": { "score": "int" },
              "outputs": { "band": "string" },
              "rules": [ { "id": "HIGH", "when": "score < 500", "then": { "band": "'HIGH'" }, "reasonCode": "LOW_SCORE" } ]
            }
            """;

    private TestSupport() {
    }

    static List<DecisionTable> tables() {
        return List.of(
                DecisionTableJson.read(Path.of("..", "examples", "loan_eligibility.v2.stone.json")),
                DecisionTableJson.read(Path.of("..", "examples", "loan_eligibility.v3.stone.json")),
                DecisionTableJson.read(CARD_FEE),
                DecisionTableJson.read(RISK_BAND));
    }

    static RuleStoneEngine engine() {
        return RuleStone.builder().clock(CLOCK).decisions(tables()).build();
    }

    static ActivatedJob job(Map<String, String> headers, String variables) {
        ActivatedJob job = mock(ActivatedJob.class);
        when(job.getKey()).thenReturn(2251799813685249L);
        when(job.getCustomHeaders()).thenReturn(headers);
        when(job.getVariables()).thenReturn(variables);
        when(job.getRetries()).thenReturn(3);
        when(job.getBpmnProcessId()).thenReturn("loan-approval");
        when(job.getElementId()).thenReturn("CheckEligibility");
        return job;
    }

    /** A job client whose commands record their arguments; every command's future completes. */
    static final class Client {

        final JobClient client = mock(JobClient.class);
        final CompleteJobCommandStep1 complete = mock(CompleteJobCommandStep1.class, withSettings().defaultAnswer(Answers.RETURNS_SELF));
        final FailJobCommandStep1 fail = mock(FailJobCommandStep1.class, withSettings().defaultAnswer(Answers.RETURNS_SELF));
        final FailJobCommandStep2 failWithRetries = mock(FailJobCommandStep2.class, withSettings().defaultAnswer(Answers.RETURNS_SELF));
        final ThrowErrorCommandStep1 error = mock(ThrowErrorCommandStep1.class, withSettings().defaultAnswer(Answers.RETURNS_SELF));
        final ThrowErrorCommandStep2 errorWithCode = mock(ThrowErrorCommandStep2.class, withSettings().defaultAnswer(Answers.RETURNS_SELF));

        @SuppressWarnings("unchecked")
        Client() {
            when(client.newCompleteCommand(any(ActivatedJob.class))).thenReturn(complete);
            when(client.newFailCommand(any(ActivatedJob.class))).thenReturn(fail);
            when(client.newThrowErrorCommand(any(ActivatedJob.class))).thenReturn(error);
            when(fail.retries(anyInt())).thenReturn(failWithRetries);
            when(error.errorCode(anyString())).thenReturn(errorWithCode);
            when(complete.send()).thenReturn(mock(CamundaFuture.class));
            when(failWithRetries.send()).thenReturn(mock(CamundaFuture.class));
            when(errorWithCode.send()).thenReturn(mock(CamundaFuture.class));
        }
    }
}
