/*
 * JMH benchmark for ThreadLocalData.resizeBuffer optimization.
 *
 * Compares the old pattern (ThreadLocal.set + ThreadLocal.get) against the
 * new pattern (set + return the local reference directly).
 *
 * Run:
 *   mvn package -pl benchmarks -am -DskipTests -q
 *   java -cp benchmarks/target/aerospike-benchmarks-*-jar-with-dependencies.jar \
 *        org.openjdk.jmh.Main ResizeBufferBenchmark
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
public class ResizeBufferBenchmark {

    @Param({"1024", "4096", "16384", "65536"})
    private int size;

    private ThreadLocal<byte[]> threadLocal;

    @Setup(Level.Trial)
    public void setup() {
        threadLocal = ThreadLocal.withInitial(() -> new byte[8192]);
        // Prime the thread-local so get() is a fast-path lookup
        threadLocal.get();
    }

    /**
     * OLD pattern: set() then get() -- pays for two ThreadLocal hash lookups.
     */
    @Benchmark
    public byte[] old_setThenGet() {
        threadLocal.set(new byte[size]);
        return threadLocal.get();
    }

    /**
     * NEW pattern: set() and return the local reference directly -- one hash lookup.
     */
    @Benchmark
    public byte[] new_setAndReturnDirect() {
        byte[] buffer = new byte[size];
        threadLocal.set(buffer);
        return buffer;
    }

    public static void main(String[] args) throws RunnerException {
        Options opt = new OptionsBuilder()
                .include(ResizeBufferBenchmark.class.getSimpleName())
                .build();
        new Runner(opt).run();
    }
}
