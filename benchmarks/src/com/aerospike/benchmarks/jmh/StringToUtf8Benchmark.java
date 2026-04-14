/*
 * JMH benchmark for Buffer.stringToUtf8 optimization.
 *
 * Compares the old implementation (falls back to String.getBytes for chars >= U+0800)
 * against the new inline 3-byte BMP and 4-byte surrogate-pair encoding.
 *
 * Run:
 *   mvn package -pl benchmarks -am -DskipTests -q
 *   java -cp benchmarks/target/aerospike-benchmarks-*-jar-with-dependencies.jar \
 *        org.openjdk.jmh.Main StringToUtf8Benchmark
 */
package com.aerospike.benchmarks.jmh;

import com.aerospike.client.command.Buffer;
import com.aerospike.client.util.Utf8;

import org.openjdk.jmh.annotations.*;
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
public class StringToUtf8Benchmark {

    @Param({
        "ASCII_SHORT",
        "ASCII_LONG",
        "TWO_BYTE",
        "THREE_BYTE_CJK",
        "MIXED_CJK",
        "EMOJI",
        "MIXED_ALL"
    })
    private String inputName;

    private String input;
    private byte[] buf;

    private static final String ASCII_SHORT   = "hello";
    private static final String ASCII_LONG    = "the quick brown fox jumps over the lazy dog 0123456789";
    private static final String TWO_BYTE      = "caf\u00E9";
    private static final String THREE_BYTE_CJK = "\u4e2d\u6587\u5185\u5bb9";
    private static final String MIXED_CJK     = "prefix_\u4e2d\u6587_suffix";
    private static final String EMOJI         = "Hi \uD83D\uDE00\uD83D\uDE03";
    private static final String MIXED_ALL     = "Hello \u00E9\u4e2d\uD83D\uDE00 world";

    @Setup(Level.Trial)
    public void setup() {
        input = switch (inputName) {
            case "ASCII_SHORT"     -> ASCII_SHORT;
            case "ASCII_LONG"      -> ASCII_LONG;
            case "TWO_BYTE"        -> TWO_BYTE;
            case "THREE_BYTE_CJK"  -> THREE_BYTE_CJK;
            case "MIXED_CJK"       -> MIXED_CJK;
            case "EMOJI"           -> EMOJI;
            case "MIXED_ALL"       -> MIXED_ALL;
            default -> throw new IllegalArgumentException("Unknown input: " + inputName);
        };
        int needed = Utf8.encodedLength(input);
        buf = new byte[needed + 16];
    }

    /**
     * OLD implementation: falls back to String.getBytes(UTF_8) for any char >= U+0800.
     */
    @Benchmark
    public int old_stringToUtf8() {
        return oldStringToUtf8(input, buf, 0);
    }

    /**
     * NEW implementation: inline 3-byte BMP and 4-byte surrogate encoding.
     */
    @Benchmark
    public int new_stringToUtf8() {
        return Buffer.stringToUtf8(input, buf, 0);
    }

    static int oldStringToUtf8(String s, byte[] buf, int offset) {
        if (s == null) return 0;
        int length = s.length();
        int startOffset = offset;
        for (int i = 0; i < length; i++) {
            int c = s.charAt(i);
            if (c < 0x80) {
                buf[offset++] = (byte) c;
            } else if (c < 0x800) {
                buf[offset++] = (byte)(0xc0 | (c >> 6));
                buf[offset++] = (byte)(0x80 | (c & 0x3f));
            } else {
                byte[] value = s.getBytes(StandardCharsets.UTF_8);
                System.arraycopy(value, 0, buf, startOffset, value.length);
                return value.length;
            }
        }
        return offset - startOffset;
    }

    public static void main(String[] args) throws RunnerException {
        Options opt = new OptionsBuilder()
                .include(StringToUtf8Benchmark.class.getSimpleName())
                .build();
        new Runner(opt).run();
    }
}
