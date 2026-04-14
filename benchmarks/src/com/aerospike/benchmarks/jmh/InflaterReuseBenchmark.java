/*
 * JMH benchmark for MultiCommand Inflater reuse optimization.
 *
 * Compares the old pattern (new Inflater + inflate + end per block)
 * against the new pattern (single Inflater with reset() between blocks).
 *
 * No Aerospike server required -- uses pre-compressed synthetic payloads.
 *
 * Run:
 *   mvn package -pl benchmarks -am -DskipTests -q
 *   java -cp benchmarks/target/aerospike-benchmarks-*-jar-with-dependencies.jar \
 *        org.openjdk.jmh.Main InflaterReuseBenchmark
 */
package com.aerospike.benchmarks.jmh;

import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;

import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(2)
@State(Scope.Thread)
public class InflaterReuseBenchmark {

    @Param({"512", "4096", "32768"})
    private int uncompressedSize;

    /** Number of compressed blocks to decompress per benchmark invocation,
     *  simulating a multi-block server response. */
    @Param({"10"})
    private int blocksPerInvocation;

    private byte[] compressed;
    private byte[] decompressed;

    private static final String COMPRESSIBLE_TEXT =
        "Aerospike is a high-performance NoSQL database. ".repeat(700);

    @Setup(Level.Trial)
    public void setup() {
        byte[] src = COMPRESSIBLE_TEXT.substring(0, Math.min(COMPRESSIBLE_TEXT.length(), uncompressedSize))
                                      .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        if (src.length < uncompressedSize) {
            src = Arrays.copyOf(src, uncompressedSize);
        }

        byte[] out = new byte[src.length + 256];
        Deflater def = new Deflater();
        def.setInput(src);
        def.finish();
        int cLen = def.deflate(out);
        def.end();
        compressed = Arrays.copyOf(out, cLen);
        decompressed = new byte[uncompressedSize + 64];
    }

    /**
     * OLD pattern: allocate a new Inflater per block, call end() each time.
     */
    @Benchmark
    public int old_newInflaterPerBlock() throws DataFormatException {
        int totalBytes = 0;
        for (int b = 0; b < blocksPerInvocation; b++) {
            Inflater inf = new Inflater();
            try {
                inf.setInput(compressed);
                totalBytes += inf.inflate(decompressed);
            } finally {
                inf.end();
            }
        }
        return totalBytes;
    }

    /**
     * NEW pattern: single Inflater, reset() between blocks, end() once at the end.
     */
    @Benchmark
    public int new_reuseInflater() throws DataFormatException {
        int totalBytes = 0;
        Inflater inf = new Inflater();
        try {
            for (int b = 0; b < blocksPerInvocation; b++) {
                inf.setInput(compressed);
                totalBytes += inf.inflate(decompressed);
                inf.reset();
            }
        } finally {
            inf.end();
        }
        return totalBytes;
    }

    public static void main(String[] args) throws RunnerException {
        Options opt = new OptionsBuilder()
                .include(InflaterReuseBenchmark.class.getSimpleName())
                .build();
        new Runner(opt).run();
    }
}
