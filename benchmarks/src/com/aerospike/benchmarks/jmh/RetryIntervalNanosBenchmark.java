/*
 * JMH benchmark for SyncCommand retryIntervalNanos optimization.
 *
 * Compares calling TimeUnit.MILLISECONDS.toNanos() on every retry iteration
 * against pre-computing the nanos value once before the loop.
 *
 * Run:
 *   mvn package -pl benchmarks -am -DskipTests -q
 *   java -cp benchmarks/target/aerospike-benchmarks-*-jar-with-dependencies.jar \
 *        org.openjdk.jmh.Main RetryIntervalNanosBenchmark
 */
package com.aerospike.benchmarks.jmh;

import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;

import java.util.concurrent.TimeUnit;

@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(2)
@State(Scope.Thread)
public class RetryIntervalNanosBenchmark {

    @Param({"500"})
    private int retryIntervalMs;

    private long deadline;

    @Setup(Level.Invocation)
    public void setup() {
        deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    }

    /**
     * OLD: compute nanos conversion on each call (as done inside the retry loop).
     */
    @Benchmark
    public long old_convertPerCall() {
        return deadline - System.nanoTime() - TimeUnit.MILLISECONDS.toNanos(retryIntervalMs);
    }

    /**
     * NEW: use a pre-computed nanos value.
     */
    @Benchmark
    public long new_precomputedNanos() {
        long retryIntervalNanos = TimeUnit.MILLISECONDS.toNanos(retryIntervalMs);
        return deadline - System.nanoTime() - retryIntervalNanos;
    }

    public static void main(String[] args) throws RunnerException {
        Options opt = new OptionsBuilder()
                .include(RetryIntervalNanosBenchmark.class.getSimpleName())
                .build();
        new Runner(opt).run();
    }
}
