//
// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.rulestone.benchmark;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import vn.io.dxa.rulestone.api.DecisionResult;
import vn.io.dxa.rulestone.core.HitPolicy;

/** The benchmarks measure what they claim: the number of rules examined and matched. */
class TablesTest {

    @ParameterizedTest
    @EnumSource(Tables.Input.class)
    void firstExaminesHalfTheRulesAndCollectAllOfThem(Tables.Input input) {
        for (int rules : List.of(10, 100, 1000)) {
            DecisionResult first = evaluate(HitPolicy.FIRST, rules, input);
            assertThat(first.trace()).hasSize(rules / 2 + 1);
            assertThat(first.matchedRules()).containsExactly("R" + rules / 2);

            DecisionResult collect = evaluate(HitPolicy.COLLECT, rules, input);
            assertThat(collect.trace()).hasSize(rules + 1);
            assertThat(collect.matchedRules()).hasSize(rules / 2 + 1);
        }
    }

    private static DecisionResult evaluate(HitPolicy hitPolicy, int rules, Tables.Input input) {
        EvaluateBenchmark benchmark = new EvaluateBenchmark();
        benchmark.hitPolicy = hitPolicy;
        benchmark.rules = rules;
        benchmark.input = input;
        benchmark.setUp();
        return benchmark.evaluate();
    }
}
