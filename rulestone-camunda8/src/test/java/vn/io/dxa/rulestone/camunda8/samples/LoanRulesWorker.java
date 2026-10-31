//
// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.rulestone.camunda8.samples;

import io.camunda.client.CamundaClient;
import io.camunda.client.api.worker.JobWorker;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.ZoneId;
import java.util.List;
import java.util.stream.Stream;
import vn.io.dxa.rulestone.api.RuleStoneEngine;
import vn.io.dxa.rulestone.camunda8.RuleStoneJobWorker;
import vn.io.dxa.rulestone.camunda8.RuleStoneWorkerOptions;
import vn.io.dxa.rulestone.core.DecisionTable;
import vn.io.dxa.rulestone.core.DecisionTableJson;
import vn.io.dxa.rulestone.core.ParameterSet;
import vn.io.dxa.rulestone.core.ParameterSetJson;
import vn.io.dxa.rulestone.core.RuleStone;

/** The worker setup shown in docs/camunda.md. */
public final class LoanRulesWorker {

    private LoanRulesWorker() {
    }

    /** One worker for every RuleStone task. */
    public static JobWorker start(CamundaClient client, Path rulesDirectory) throws IOException {
        List<DecisionTable> tables;
        List<ParameterSet> paramSets;
        try (Stream<Path> files = Files.list(rulesDirectory)) {
            tables = files.filter(f -> f.toString().endsWith(".stone.json"))
                    .map(DecisionTableJson::read)
                    .toList();
        }
        try (Stream<Path> files = Files.list(rulesDirectory)) {
            paramSets = files.filter(f -> f.toString().endsWith(ParameterSetJson.FILE_SUFFIX))
                    .map(ParameterSetJson::read)
                    .toList();
        }
        Clock clock = Clock.system(ZoneId.of("Asia/Ho_Chi_Minh"));
        RuleStoneEngine engine = RuleStone.builder().clock(clock).decisions(tables).paramSets(paramSets).build();

        return RuleStoneJobWorker.register(client, engine, tables,
                RuleStoneWorkerOptions.builder().clock(clock).build());
    }

    /** A worker for one decision that fetches only that decision's variables. */
    public static JobWorker startLoanEligibility(CamundaClient client, RuleStoneEngine engine,
            List<DecisionTable> tables, Clock clock) {
        return RuleStoneJobWorker.register(client, engine, tables,
                RuleStoneWorkerOptions.builder()
                        .jobType("rulestone:loan_eligibility")
                        .fetchVariables("applicant", "amount", "applicationDate")
                        .clock(clock)
                        .build());
    }
}
