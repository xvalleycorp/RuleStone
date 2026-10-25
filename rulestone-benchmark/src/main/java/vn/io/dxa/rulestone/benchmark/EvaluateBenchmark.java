//
// Copyright 2026 The RuleStone Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.rulestone.benchmark;

import java.time.LocalDate;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import vn.io.dxa.rulestone.api.DecisionRef;
import vn.io.dxa.rulestone.api.DecisionResult;
import vn.io.dxa.rulestone.api.Facts;
import vn.io.dxa.rulestone.api.RuleStoneEngine;
import vn.io.dxa.rulestone.core.HitPolicy;
import vn.io.dxa.rulestone.core.RuleStone;

/**
 * Latency of one {@link RuleStoneEngine#evaluate} call, everything included: version
 * selection, input conversion and checking, the facts hash, the rules and the decision record.
 * All threads share one engine, as applications do. The tables are described in {@link Tables}.
 *
 * <p>Sample-time mode records the latency distribution (p50 to p99.99); add {@code -prof gc}
 * for the bytes allocated per evaluation, and {@code -t 4} for four threads on one engine.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.SampleTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 5, time = 3)
@Measurement(iterations = 5, time = 2)
@Fork(value = 1, jvmArgsAppend = {"-Xms2g", "-Xmx2g"})
public class EvaluateBenchmark {

    @Param({"FIRST", "COLLECT"})
    public HitPolicy hitPolicy;

    @Param({"10", "100", "1000"})
    public int rules;

    @Param({"MONEY", "DECIMAL", "LIST"})
    public Tables.Input input;

    private RuleStoneEngine engine;
    private DecisionRef ref;
    private Facts facts;

    @Setup
    public void setUp() {
        engine = RuleStone.builder().decision(Tables.table(hitPolicy, rules, input)).build();
        ref = DecisionRef.of(Tables.DECISION).asOf(LocalDate.of(2026, 11, 1)).correlationId("PAY-2026-000042");
        facts = Tables.facts(rules);
    }

    @Benchmark
    public DecisionResult evaluate() {
        return engine.evaluate(ref, facts);
    }
}
