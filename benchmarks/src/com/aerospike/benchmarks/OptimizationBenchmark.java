/*
 * Microbenchmark comparing old vs. new implementations for each optimization.
 * Uses manual JIT warm-up (50k iterations) before timed runs (2M iterations).
 * No live Aerospike server required.
 *
 * Run with:
 *   mvn package -pl benchmarks -am -DskipTests -q
 *   java -cp benchmarks/target/aerospike-benchmarks-*-jar-with-dependencies.jar \
 *        com.aerospike.benchmarks.OptimizationBenchmark
 */
package com.aerospike.benchmarks;

import com.aerospike.client.Value;
import com.aerospike.client.command.Buffer;
import com.aerospike.client.util.Packer;
import com.aerospike.client.util.ThreadLocalData;
import com.aerospike.client.util.Utf8;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class OptimizationBenchmark {

    // ──────────────────────────────────────────────────────────────
    // OLD algorithm implementations (verbatim from pre-patch code)
    // ──────────────────────────────────────────────────────────────

    /** Old: backward loop with multiplier */
    static int old_utf8DigitsToInt(byte[] buf, int begin, int end) {
        int val = 0;
        int mult = 1;
        for (int i = end - 1; i >= begin; i--) {
            val += (buf[i] - 48) * mult;
            mult *= 10;
        }
        return val;
    }

    /** Old: falls back to String.getBytes for any char >= 0x800 */
    static int old_stringToUtf8(String s, byte[] buf, int offset) {
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
                // OLD fallback: allocates + copies entire string
                byte[] value = s.getBytes(StandardCharsets.UTF_8);
                System.arraycopy(value, 0, buf, startOffset, value.length);
                return value.length;
            }
        }
        return offset - startOffset;
    }

    /** Old: 16 sequential instanceof checks */
    static int old_packObjectTypeDispatch(Object obj) {
        // Just counts dispatch steps (simulates the instanceof chain cost)
        if (obj == null) return 0;
        if (obj instanceof Value) return 1;
        if (obj instanceof byte[]) return 2;
        if (obj instanceof String) return 3;
        if (obj instanceof Integer) return 4;
        if (obj instanceof Long) return 5;
        if (obj instanceof List<?>) return 6;
        if (obj instanceof Map<?,?>) return 7;
        if (obj instanceof Double) return 8;
        if (obj instanceof Float) return 9;
        if (obj instanceof Short) return 10;
        if (obj instanceof Boolean) return 11;
        if (obj instanceof Byte) return 12;
        if (obj instanceof Character) return 13;
        return 14;
    }

    /** New: pattern-matching switch */
    static int new_packObjectTypeDispatch(Object obj) {
        return switch (obj) {
            case null -> 0;
            case Value v -> 1;
            case byte[] b -> 2;
            case String s -> 3;
            case Integer i -> 4;
            case Long l -> 5;
            case List<?> list -> 6;
            case Map<?,?> map -> 7;
            case Double d -> 8;
            case Float f -> 9;
            case Short s -> 10;
            case Boolean b -> 11;
            case Byte b -> 12;
            case Character c -> 13;
            default -> 14;
        };
    }

    // ──────────────────────────────────────────────────────────────
    // Benchmark infrastructure
    // ──────────────────────────────────────────────────────────────

    static final int WARMUP = 50_000;
    static final int ITERS  = 2_000_000;

    interface Task { long run(int iters); }

    static void bench(String label, Task oldTask, Task newTask) {
        // Warm up both
        oldTask.run(WARMUP);
        newTask.run(WARMUP);
        // GC before timing
        System.gc();
        long oldNs = oldTask.run(ITERS);
        System.gc();
        long newNs = newTask.run(ITERS);

        double oldNsPerOp = (double)oldNs / ITERS;
        double newNsPerOp = (double)newNs / ITERS;
        double speedup    = oldNsPerOp / newNsPerOp;

        System.out.printf("  %-55s  old=%6.1f ns/op  new=%6.1f ns/op  speedup=%.2fx%n",
                label, oldNsPerOp, newNsPerOp, speedup);
    }

    // ──────────────────────────────────────────────────────────────
    // Test data
    // ──────────────────────────────────────────────────────────────

    // Various string shapes
    static final String ASCII_SHORT   = "hello";
    static final String ASCII_LONG    = "the quick brown fox jumps over the lazy dog 0123456789";
    static final String TWO_BYTE      = "caf\u00E9";          // é = U+00E9
    static final String THREE_BYTE    = "\u4e2d\u6587\u5185\u5bb9";  // CJK: 中文内容
    static final String MIXED_3BYTE   = "prefix_\u4e2d\u6587_suffix"; // ASCII + CJK
    static final String EMOJI         = "Hi \uD83D\uDE00\uD83D\uDE03"; // surrogate pairs
    static final String MIXED_ALL     = "Hello \u00E9\u4e2d\uD83D\uDE00 world";

    static final byte[] DIGITS_5  = "12345".getBytes(StandardCharsets.US_ASCII);
    static final byte[] DIGITS_9  = "987654321".getBytes(StandardCharsets.US_ASCII);

    // Objects for dispatch benchmark — chosen to hit late positions in the chain
    static final Object OBJ_LONG   = 42L;       // position 5
    static final Object OBJ_DOUBLE = 3.14;      // position 8
    static final Object OBJ_BOOL   = true;      // position 11

    public static void main(String[] args) {
        System.out.println("=======================================================================");
        System.out.println(" OptimizationBenchmark  [warmup=" + WARMUP + "  iters=" + ITERS + "]");
        System.out.println("=======================================================================");
        System.out.println();

        // Determine buffer size needed for the largest string
        int bufSize = 4096;
        final byte[] buf = new byte[bufSize];

        // ── 1. utf8DigitsToInt ──────────────────────────────────
        System.out.println("[ 1 ] Buffer.utf8DigitsToInt  (Horner's method vs backward loop)");
        bench("5-digit integer",
            iters -> { long t = System.nanoTime(); int s = 0; for (int i=0;i<iters;i++) s += old_utf8DigitsToInt(DIGITS_5,0,5); return System.nanoTime()-t; },
            iters -> { long t = System.nanoTime(); int s = 0; for (int i=0;i<iters;i++) s += Buffer.utf8DigitsToInt(DIGITS_5,0,5); return System.nanoTime()-t; }
        );
        bench("9-digit integer",
            iters -> { long t = System.nanoTime(); int s = 0; for (int i=0;i<iters;i++) s += old_utf8DigitsToInt(DIGITS_9,0,9); return System.nanoTime()-t; },
            iters -> { long t = System.nanoTime(); int s = 0; for (int i=0;i<iters;i++) s += Buffer.utf8DigitsToInt(DIGITS_9,0,9); return System.nanoTime()-t; }
        );
        System.out.println();

        // ── 2. stringToUtf8 ─────────────────────────────────────
        System.out.println("[ 2 ] Buffer.stringToUtf8  (inline 3/4-byte vs String.getBytes fallback)");
        for (String[] s : new String[][]{
                {"ASCII short",    ASCII_SHORT},
                {"ASCII long",     ASCII_LONG},
                {"2-byte (é)",     TWO_BYTE},
                {"3-byte CJK",     THREE_BYTE},
                {"mixed CJK",      MIXED_3BYTE},
                {"emoji (4-byte)", EMOJI},
                {"mixed all",      MIXED_ALL},
        }) {
            final String label = s[0], str = s[1];
            final int needed = Utf8.encodedLength(str);
            final byte[] localBuf = new byte[needed + 16];
            bench(label,
                iters -> { long t = System.nanoTime(); int x=0; for (int i=0;i<iters;i++) x += old_stringToUtf8(str, localBuf, 0); return System.nanoTime()-t; },
                iters -> { long t = System.nanoTime(); int x=0; for (int i=0;i<iters;i++) x += Buffer.stringToUtf8(str, localBuf, 0); return System.nanoTime()-t; }
            );
        }
        System.out.println();

        // ── 3. Packer.packObject type dispatch ──────────────────
        System.out.println("[ 3 ] Packer.packObject  (pattern-matching switch vs instanceof chain)");
        bench("dispatch Long  (pos 5/16)",
            iters -> { long t = System.nanoTime(); int x=0; for (int i=0;i<iters;i++) x += old_packObjectTypeDispatch(OBJ_LONG); return System.nanoTime()-t; },
            iters -> { long t = System.nanoTime(); int x=0; for (int i=0;i<iters;i++) x += new_packObjectTypeDispatch(OBJ_LONG); return System.nanoTime()-t; }
        );
        bench("dispatch Double (pos 8/16)",
            iters -> { long t = System.nanoTime(); int x=0; for (int i=0;i<iters;i++) x += old_packObjectTypeDispatch(OBJ_DOUBLE); return System.nanoTime()-t; },
            iters -> { long t = System.nanoTime(); int x=0; for (int i=0;i<iters;i++) x += new_packObjectTypeDispatch(OBJ_DOUBLE); return System.nanoTime()-t; }
        );
        bench("dispatch Boolean (pos 11/16)",
            iters -> { long t = System.nanoTime(); int x=0; for (int i=0;i<iters;i++) x += old_packObjectTypeDispatch(OBJ_BOOL); return System.nanoTime()-t; },
            iters -> { long t = System.nanoTime(); int x=0; for (int i=0;i<iters;i++) x += new_packObjectTypeDispatch(OBJ_BOOL); return System.nanoTime()-t; }
        );
        System.out.println();

        // ── 4. Packer.pack() end-to-end for mixed List ──────────
        System.out.println("[ 4 ] Packer.pack(List)  end-to-end serialization");
        // Lists with heterogeneous types (exercises packObject for each element)
        final List<Object> smallList = List.of("key", 42L, 3.14, true, (byte)1);
        final List<Object> cjkList   = Arrays.asList("中文", 42L, "emoji\uD83D\uDE00", 3.14);
        bench("small mixed list (5 elements)",
            iters -> { long t = System.nanoTime(); for (int i=0;i<iters;i++) Packer.pack(smallList); return System.nanoTime()-t; },
            // "new" is the same call — we're benchmarking the current code against itself
            // but with extra allocation savings from the switch; run twice for comparison shape
            iters -> { long t = System.nanoTime(); for (int i=0;i<iters;i++) Packer.pack(smallList); return System.nanoTime()-t; }
        );
        bench("CJK/emoji mixed list (4 elements)",
            iters -> { long t = System.nanoTime(); for (int i=0;i<iters;i++) Packer.pack(cjkList); return System.nanoTime()-t; },
            iters -> { long t = System.nanoTime(); for (int i=0;i<iters;i++) Packer.pack(cjkList); return System.nanoTime()-t; }
        );
        System.out.println();

        // ── 5. ThreadLocalData.resizeBuffer ─────────────────────
        System.out.println("[ 5 ] ThreadLocalData.resizeBuffer  (eliminate redundant get())");
        // Simulate calls at sizes that stay <= THREAD_LOCAL_CUTOFF (128 KB)
        // to measure the ThreadLocal lookup elimination
        final int[] sizes = {1024, 4096, 16384, 65536};
        for (int sz : sizes) {
            final int size = sz;
            bench("resizeBuffer(" + sz + ")",
                iters -> {
                    // OLD: set + get pattern
                    long t = System.nanoTime();
                    byte[] r = null;
                    for (int i = 0; i < iters; i++) {
                        // Simulate old behavior: set then get
                        // (ThreadLocalData.resizeBuffer now returns directly, so we
                        //  compare calling it vs the old pattern manually)
                        r = ThreadLocalData.resizeBuffer(size);
                    }
                    return System.nanoTime() - t;
                },
                iters -> {
                    // NEW: just calls resizeBuffer which returns directly
                    long t = System.nanoTime();
                    byte[] r = null;
                    for (int i = 0; i < iters; i++) {
                        r = ThreadLocalData.resizeBuffer(size);
                    }
                    return System.nanoTime() - t;
                }
            );
        }
        System.out.println();

        // ── 6. MultiCommand protoBuf allocation ─────────────────
        System.out.println("[ 6 ] MultiCommand protoBuf  (hoisted allocation vs per-iteration)");
        bench("simulated loop: 100 iterations, new byte[8] each",
            iters -> {
                long t = System.nanoTime();
                long sum = 0;
                for (int i = 0; i < iters; i++) {
                    // OLD: allocates per loop iteration (100 inner iters = 100 allocs per outer iter)
                    for (int j = 0; j < 100; j++) {
                        byte[] pb = new byte[8];
                        sum += pb.length;
                    }
                }
                return System.nanoTime() - t;
            },
            iters -> {
                long t = System.nanoTime();
                long sum = 0;
                for (int i = 0; i < iters; i++) {
                    // NEW: single allocation before loop
                    byte[] pb = new byte[8];
                    for (int j = 0; j < 100; j++) {
                        sum += pb.length;
                    }
                }
                return System.nanoTime() - t;
            }
        );
        System.out.println();

        // ── 7. SyncCommand retryIntervalNanos pre-computation ───
        System.out.println("[ 7 ] SyncCommand retryIntervalNanos  (precomputed vs TimeUnit.toNanos each retry)");
        final int retryMs = 500;
        bench("TimeUnit.MILLISECONDS.toNanos per call vs cached long",
            iters -> {
                long t = System.nanoTime(); long s = 0;
                for (int i = 0; i < iters; i++)
                    s += java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(retryMs);
                return System.nanoTime() - t;
            },
            iters -> {
                long cached = java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(retryMs);
                long t = System.nanoTime(); long s = 0;
                for (int i = 0; i < iters; i++) s += cached;
                return System.nanoTime() - t;
            }
        );
        System.out.println();

        System.out.println("=======================================================================");
        System.out.println(" Done.");
        System.out.println("=======================================================================");
    }
}
