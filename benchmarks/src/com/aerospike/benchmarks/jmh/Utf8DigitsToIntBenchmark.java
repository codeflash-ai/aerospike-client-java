/*
 * JMH benchmark for Buffer.utf8DigitsToInt optimization.
 *
 * Compares the old backward-loop-with-multiplier implementation
 * against the new Horner's forward accumulation.
 *
 * Run:
 *   mvn package -pl benchmarks -am -DskipTests -q
 *   java -cp benchmarks/target/aerospike-benchmarks-*-jar-with-dependencies.jar \
 *        org.openjdk.jmh.Main Utf8DigitsToIntBenchmark
 */
package com.aerospike.benchmarks.jmh;

import com.aerospike.client.command.Buffer;

import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(2)
@State(Scope.Thread)
public class Utf8DigitsToIntBenchmark {

    @Param({"1", "5", "9"})
    private int digits;

    private byte[] buf;

    private static final byte[] DIGITS_1 = "7".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] DIGITS_5 = "12345".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] DIGITS_9 = "987654321".getBytes(StandardCharsets.US_ASCII);

    @Setup(Level.Trial)
    public void setup() {
        buf = switch (digits) {
            case 1 -> DIGITS_1;
            case 5 -> DIGITS_5;
            case 9 -> DIGITS_9;
            default -> throw new IllegalArgumentException();
        };
    }

    /**
     * OLD implementation: backward loop with multiplier.
     */
    @Benchmark
    public int old_utf8DigitsToInt() {
        return oldUtf8DigitsToInt(buf, 0, buf.length);
    }

    /**
     * NEW implementation: Horner's forward accumulation.
     */
    @Benchmark
    public int new_utf8DigitsToInt() {
        return Buffer.utf8DigitsToInt(buf, 0, buf.length);
    }

    static int oldUtf8DigitsToInt(byte[] buf, int begin, int end) {
        int val = 0;
        int mult = 1;
        for (int i = end - 1; i >= begin; i--) {
            val += (buf[i] - 48) * mult;
            mult *= 10;
        }
        return val;
    }

    public static void main(String[] args) throws RunnerException {
        Options opt = new OptionsBuilder()
                .include(Utf8DigitsToIntBenchmark.class.getSimpleName())
                .build();
        new Runner(opt).run();
    }
}
