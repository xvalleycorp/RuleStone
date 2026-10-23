//
// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.rulestone.temporal.samples;

import io.temporal.worker.Worker;
import io.temporal.worker.WorkerFactory;
import java.time.Clock;
import java.time.ZoneId;
import java.util.List;
import vn.io.dxa.rulestone.api.RuleStoneEngine;
import vn.io.dxa.rulestone.core.DecisionTable;
import vn.io.dxa.rulestone.core.RuleStone;
import vn.io.dxa.rulestone.temporal.RuleStoneActivitiesImpl;

/** Worker setup for both styles. */
public final class LoanWorker {

    public static final String TASK_QUEUE = "loans";

    private LoanWorker() {
    }

    public static Worker register(WorkerFactory factory, List<DecisionTable> tables, Clock clock) {
        ZoneId zone = clock.getZone();   // the bank's business time zone
        RuleStoneEngine engine = RuleStone.builder()
                .clock(clock)
                .decisions(tables)       // every version that running workflows may replay
                .build();

        Worker worker = factory.newWorker(TASK_QUEUE);

        // Activity style: the activity reads facts with the input types of these tables.
        worker.registerActivitiesImplementations(new RuleStoneActivitiesImpl(engine, tables, clock));
        worker.registerWorkflowImplementationTypes(LoanActivityWorkflowImpl.class);

        // In-workflow style: the workflow needs the engine, so register a factory.
        worker.registerWorkflowImplementationFactory(LoanWorkflow.class,
                () -> new LoanWorkflowImpl(engine, zone));
        return worker;
    }
}
