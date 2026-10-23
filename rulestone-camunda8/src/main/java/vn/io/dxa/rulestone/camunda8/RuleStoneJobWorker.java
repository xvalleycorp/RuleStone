//
// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.rulestone.camunda8;

import io.camunda.client.CamundaClient;
import io.camunda.client.api.worker.JobWorker;
import io.camunda.client.api.worker.JobWorkerBuilderStep1.JobWorkerBuilderStep3;
import java.util.Collection;
import vn.io.dxa.rulestone.api.RuleStoneEngine;
import vn.io.dxa.rulestone.core.DecisionTable;

/**
 * Opens a Camunda 8 job worker that evaluates RuleStone decisions.
 *
 * <pre>{@code
 * JobWorker worker = RuleStoneJobWorker.register(client, engine, tables,
 *         RuleStoneWorkerOptions.builder().clock(clock).build());
 * }</pre>
 *
 * <p>For settings these options do not cover, build the worker yourself with a
 * {@link RuleStoneJobHandler}.
 */
public final class RuleStoneJobWorker {

    private RuleStoneJobWorker() {
    }

    /**
     * Opens a worker with default options for job type {@value RuleStoneWorkerOptions#DEFAULT_JOB_TYPE}.
     *
     * @param client the Camunda client
     * @param engine the engine
     * @param tables every decision table registered with the engine
     * @return the open worker; close it on shutdown
     */
    public static JobWorker register(CamundaClient client, RuleStoneEngine engine,
            Collection<DecisionTable> tables) {
        return register(client, engine, tables, RuleStoneWorkerOptions.defaults());
    }

    /**
     * Opens a worker.
     *
     * @param client  the Camunda client
     * @param engine  the engine
     * @param tables  every decision table registered with the engine
     * @param options the job type, fetched variables, error code, backoff, clock and worker name
     * @return the open worker; close it on shutdown
     */
    public static JobWorker register(CamundaClient client, RuleStoneEngine engine,
            Collection<DecisionTable> tables, RuleStoneWorkerOptions options) {
        JobWorkerBuilderStep3 builder = client.newWorker()
                .jobType(options.jobType())
                .handler(new RuleStoneJobHandler(engine, tables, options));
        if (!options.fetchVariables().isEmpty()) {
            builder = builder.fetchVariables(options.fetchVariables());
        }
        if (options.workerName().isPresent()) {
            builder = builder.name(options.workerName().get());
        }
        return builder.open();
    }
}
