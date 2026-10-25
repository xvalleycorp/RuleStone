# RuleStone benchmarks

[JMH](https://github.com/openjdk/jmh) benchmarks of `RuleStoneEngine.evaluate`, so that you can size a deployment and so that regressions show up between releases. This module is built and tested with the rest of the project, so the benchmarks keep compiling, but it is never deployed: JMH is GPL-2.0 with the Classpath Exception.

## What is measured

`EvaluateBenchmark` measures one call to `evaluate`, everything included: version selection, input conversion and type checking, the facts hash, the rules, and the decision record. All threads share one engine, as applications do. There is no audit sink, so the figures exclude what your sink costs.

The tables (see `Tables`) have 10, 100 or 1,000 rules, each comparing one input with a threshold, followed by a catch-all rule. The facts lie halfway between the thresholds, so under `FIRST` half the rules are examined before one matches, and under `COLLECT` every rule is examined and half match:

| `input` | Each rule |
|---|---|
| `MONEY` | `payment.amount >= money(…, 'VND')`: a money field of a struct |
| `DECIMAL` | `payment.riskScore >= decimal('…')`: a decimal field of a struct |
| `LIST` | `payment.history.exists(t, t.amount >= money(…, 'VND'))`: a list of 20 structs, read to the end |

`TablesTest` checks that the tables examine and match the number of rules they claim to.

## Running

```sh
mvn -pl rulestone-benchmark -am package -DskipTests
java -jar rulestone-benchmark/target/benchmarks.jar -prof gc              # 1 thread, every table
java -jar rulestone-benchmark/target/benchmarks.jar -prof gc -t 4         # 4 threads on one engine
java -jar rulestone-benchmark/target/benchmarks.jar -p rules=10 -p hitPolicy=COLLECT   # a subset
```

The benchmark runs in sample-time mode, which records the latency distribution from p0 to p100; `-prof gc` adds the bytes allocated per evaluation (`gc.alloc.rate.norm`). Each table is warmed up for 15 seconds, because the expressions need several seconds of JIT compilation to reach their steady state, then measured for 10 seconds, in a forked JVM with a 2 GB heap. Add `-rf json` to keep the results for comparison.

Run on a quiet machine, with as many cores as threads, and report the hardware, JDK and garbage collector with the results.

## Results

See [Threading and performance](../docs/java-api.md#threading-and-performance) in the Java API guide.
