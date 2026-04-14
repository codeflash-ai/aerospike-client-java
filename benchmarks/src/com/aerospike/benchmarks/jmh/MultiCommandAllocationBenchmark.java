/*
 * JMH benchmark for MultiCommand protoBuf/header allocation hoisting.
 *
 * Compares allocating byte[8] and byte[12] inside the loop (old) vs
 * allocating once before the loop and reusing (new).
 *
 * Run:
 *   mvn package -pl benchmarks -am -DskipTests -q
 *   java -cp benchmarks/target/aerospike-benchmarks-*-jar-with-dependencies.jar \
 *        org.openjdk.jmh.Main MultiCommandAllocationBenchmark
 */
package com.aerospike.benchmarks.jmh;

import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;
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
public class MultiCommandAllocationBenchmark {

    /** Simulates the number of loop iterations (blocks) in a parseResult call. */
    @Param({"10", "50", "100"})
    private int blocks;

    /**
     * OLD pattern: allocate byte[8] protoBuf inside the loop on every iteration.
     * Also uses manual byte-by-byte copy for the ReadTimeout header.
     */
    @Benchmark
    public void old_allocatePerIteration(Blackhole bh) {
        for (int i = 0; i < blocks; i++) {
            byte[] protoBuf = new byte[8];
            protoBuf[0] = (byte) i;
            bh.consume(protoBuf);

            // Simulate the ReadTimeout path (rare, but allocated per-iteration in old code)
            if (i == -1) { // never true, but prevents dead-code elimination
                byte[] b = new byte[12];
                int count = 0;
                for (int j = 0; j < 8; j++) {
                    b[count++] = protoBuf[j];
                }
                bh.consume(b);
            }
        }
    }

    /**
     * NEW pattern: allocate byte[8] and byte[12] before the loop, reuse across iterations.
     * Uses System.arraycopy instead of manual loops.
     */
    @Benchmark
    public void new_allocateOnce(Blackhole bh) {
        byte[] protoBuf = new byte[8];
        byte[] b = new byte[12];
        for (int i = 0; i < blocks; i++) {
            protoBuf[0] = (byte) i;
            bh.consume(protoBuf);

            if (i == -1) {
                System.arraycopy(protoBuf, 0, b, 0, 8);
                bh.consume(b);
            }
        }
    }

    public static void main(String[] args) throws RunnerException {
        Options opt = new OptionsBuilder()
                .include(MultiCommandAllocationBenchmark.class.getSimpleName())
                .build();
        new Runner(opt).run();
    }
}
