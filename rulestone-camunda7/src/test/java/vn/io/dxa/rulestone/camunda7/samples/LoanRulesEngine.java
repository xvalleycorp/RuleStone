//
// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.rulestone.camunda7.samples;

import java.time.Clock;
import java.util.List;
import org.camunda.bpm.engine.ProcessEngine;
import org.camunda.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import vn.io.dxa.rulestone.api.RuleStoneEngine;
import vn.io.dxa.rulestone.camunda7.RuleStoneDecisionService;
import vn.io.dxa.rulestone.camunda7.RuleStoneProcessEnginePlugin;
import vn.io.dxa.rulestone.core.DecisionTable;
import vn.io.dxa.rulestone.core.RuleStone;

/** The process engine setup shown in docs/camunda.md. */
public final class LoanRulesEngine {

    private LoanRulesEngine() {
    }

    /** Adds RuleStone to a process engine configuration and builds the engine. */
    public static ProcessEngine build(ProcessEngineConfigurationImpl configuration,
            List<DecisionTable> tables, Clock clock) {
        RuleStoneEngine rules = RuleStone.builder().clock(clock).decisions(tables).build();
        RuleStoneDecisionService decisions = new RuleStoneDecisionService(rules, tables, clock);

        configuration.getProcessEnginePlugins().add(new RuleStoneProcessEnginePlugin(decisions));
        return configuration.buildProcessEngine();
    }
}
