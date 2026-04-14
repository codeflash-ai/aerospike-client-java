/*
 * Benchmark for the MultiCommand Inflater reuse optimization.
 *
 * Two measurements:
 *  A) Isolated Inflater pattern benchmark (no server required):
 *     Compares "new Inflater + inflate + end" per block
 *     vs "reuse Inflater with reset()" across N blocks.
 *
 *  B) Live scan benchmark (requires Aerospike on localhost:3000):
 *     Inserts records with compressible values, then times full scans
 *     with compress=true (exercises the patched Inflater reuse path)
 *     vs compress=false (baseline, no Inflater path at all).
 *
 * Build:
 *   mvn package -pl benchmarks -am -DskipTests -q
 *
 * Run:
 *   java -cp benchmarks/target/aerospike-benchmarks-*-jar-with-dependencies.jar \
 *        com.aerospike.benchmarks.InflaterBenchmark
 */
package com.aerospike.benchmarks;

import com.aerospike.client.AerospikeClient;
import com.aerospike.client.Bin;
import com.aerospike.client.Key;
import com.aerospike.client.policy.ClientPolicy;
import com.aerospike.client.policy.ScanPolicy;
import com.aerospike.client.policy.WritePolicy;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

public class InflaterBenchmark {

    // ── tuning ────────────────────────────────────────────────────────
    static final String HOST      = "127.0.0.1";
    static final int    PORT      = 3000;
    static final String NAMESPACE = "test";
    static final String SET_NAME  = "inflater_bench";
    static final int    NUM_KEYS  = 20_000;   // records to insert
    // Repeated phrase produces very high compression ratio (~10:1)
    static final String BIN_VALUE = "Aerospike is a high-performance NoSQL database. ".repeat(30);

    // Isolated Inflater benchmark
    static final int WARMUP_BLOCKS   = 10_000;
    static final int TIMED_BLOCKS    = 500_000;  // block decompressions per arm
    static final int BLOCKS_PER_ITER = 10;       // simulate N compressed blocks per "message"

    // ── pre-compute a representative compressed block ─────────────────

    static byte[] makeCompressedBlock(int uncompressedSize) {
        byte[] src = BIN_VALUE.substring(0, Math.min(BIN_VALUE.length(), uncompressedSize))
                              .getBytes(StandardCharsets.UTF_8);
        byte[] out = new byte[src.length + 64];
        Deflater def = new Deflater();
        def.setInput(src);
        def.finish();
        int cLen = def.deflate(out);
        def.end();
        return Arrays.copyOf(out, cLen);
    }

    // ─────────────────────────────────────────────────────────────────
    // Part A: isolated Inflater pattern benchmark
    // ─────────────────────────────────────────────────────────────────

    static void runIsolatedBenchmark() {
        System.out.println("╔══════════════════════════════════════════════════════════════════╗");
        System.out.println("║  Part A: Isolated Inflater pattern  (no server required)         ║");
        System.out.println("╚══════════════════════════════════════════════════════════════════╝");

        // Three representative uncompressed payload sizes
        int[] sizes = {512, 4096, 32768};

        for (int uSize : sizes) {
            byte[] compressed   = makeCompressedBlock(uSize);
            byte[] decompressed = new byte[uSize + 64];

            // ── warm up both patterns ─────────────────────────────────
            runOldPattern(compressed, decompressed, WARMUP_BLOCKS, BLOCKS_PER_ITER);
            runNewPattern(compressed, decompressed, WARMUP_BLOCKS, BLOCKS_PER_ITER);

            System.gc();
            long oldNs = runOldPattern(compressed, decompressed, TIMED_BLOCKS, BLOCKS_PER_ITER);
            System.gc();
            long newNs = runNewPattern(compressed, decompressed, TIMED_BLOCKS, BLOCKS_PER_ITER);

            double oldNsPerBlock = (double) oldNs / TIMED_BLOCKS;
            double newNsPerBlock = (double) newNs / TIMED_BLOCKS;
            double speedup       = oldNsPerBlock / newNsPerBlock;

            System.out.printf("  uncompressed=%6d B  compressed=%5d B  "
                + "old=%7.1f ns/block  new=%7.1f ns/block  speedup=%.2fx%n",
                uSize, compressed.length, oldNsPerBlock, newNsPerBlock, speedup);
        }
        System.out.println();
    }

    /** OLD pattern: new Inflater() + setInput + inflate + end() per block. */
    static long runOldPattern(byte[] compressed, byte[] out, int totalBlocks, int blocksPerIter) {
        int iters = totalBlocks / blocksPerIter;
        long t = System.nanoTime();
        try {
            for (int i = 0; i < iters; i++) {
                for (int b = 0; b < blocksPerIter; b++) {
                    Inflater inf = new Inflater();        // ← allocation each time
                    try {
                        inf.setInput(compressed);
                        inf.inflate(out);
                    } catch (DataFormatException e) {
                        throw new RuntimeException(e);
                    } finally {
                        inf.end();                        // ← native teardown each time
                    }
                }
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return System.nanoTime() - t;
    }

    /** NEW pattern: single Inflater, reset() between blocks. */
    static long runNewPattern(byte[] compressed, byte[] out, int totalBlocks, int blocksPerIter) {
        int iters = totalBlocks / blocksPerIter;
        long t = System.nanoTime();
        Inflater inf = new Inflater();
        try {
            for (int i = 0; i < iters; i++) {
                for (int b = 0; b < blocksPerIter; b++) {
                    inf.setInput(compressed);
                    try {
                        inf.inflate(out);
                    } catch (DataFormatException e) {
                        throw new RuntimeException(e);
                    }
                    inf.reset();                         // ← cheap state reset, no native alloc
                }
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            inf.end();                                   // ← native teardown once at end
        }
        return System.nanoTime() - t;
    }

    // ─────────────────────────────────────────────────────────────────
    // Part B: live scan benchmark
    // ─────────────────────────────────────────────────────────────────

    static void runLiveBenchmark() {
        System.out.println("╔══════════════════════════════════════════════════════════════════╗");
        System.out.println("║  Part B: Live scan benchmark  (Aerospike on " + HOST + ":" + PORT + ")      ║");
        System.out.println("╚══════════════════════════════════════════════════════════════════╝");

        ClientPolicy cp = new ClientPolicy();
        cp.timeout = 5000;

        try (AerospikeClient client = new AerospikeClient(cp, HOST, PORT)) {

            // ── 1. Seed data ────────────────────────────────────────
            System.out.println("  Inserting " + NUM_KEYS + " records …");
            WritePolicy wp = new WritePolicy();
            wp.socketTimeout = 3000;
            wp.totalTimeout  = 5000;

            for (int i = 0; i < NUM_KEYS; i++) {
                Key key = new Key(NAMESPACE, SET_NAME, i);
                Bin bin = new Bin("val", BIN_VALUE);
                client.put(wp, key, bin);
            }
            System.out.println("  Insert done.");
            System.out.println();

            // ── 2. Warm-up scans (discard) ──────────────────────────
            System.out.println("  Warming up (3 scans each) …");
            for (int i = 0; i < 3; i++) scanOnce(client, false);
            for (int i = 0; i < 3; i++) scanOnce(client, true);
            System.out.println("  Warm-up done.");
            System.out.println();

            // ── 3. Timed scans ──────────────────────────────────────
            int SCAN_RUNS = 8;
            System.out.printf("  Running %d timed scans each (compress=false and compress=true) …%n", SCAN_RUNS);
            System.out.println();

            long[] noCompressMs  = new long[SCAN_RUNS];
            long[] yesCompressMs = new long[SCAN_RUNS];

            for (int i = 0; i < SCAN_RUNS; i++) {
                noCompressMs[i]  = scanOnce(client, false);
                yesCompressMs[i] = scanOnce(client, true);
            }

            // ── 4. Report ───────────────────────────────────────────
            System.out.printf("  %-40s  %-40s%n", "compress=false (baseline)", "compress=true (Inflater reuse)");
            System.out.printf("  %-40s  %-40s%n", "-".repeat(38), "-".repeat(38));
            for (int i = 0; i < SCAN_RUNS; i++) {
                System.out.printf("  run %d: %5d ms  (%,d rec/s)%18s run %d: %5d ms  (%,d rec/s)%n",
                    i+1, noCompressMs[i],  recPerSec(NUM_KEYS, noCompressMs[i]),  "",
                    i+1, yesCompressMs[i], recPerSec(NUM_KEYS, yesCompressMs[i]));
            }

            long avgNo  = average(noCompressMs);
            long avgYes = average(yesCompressMs);
            double ratio = (double) avgNo / avgYes;

            System.out.println();
            System.out.printf("  avg compress=false : %5d ms  (%,d rec/s)%n", avgNo,  recPerSec(NUM_KEYS, avgNo));
            System.out.printf("  avg compress=true  : %5d ms  (%,d rec/s)%n", avgYes, recPerSec(NUM_KEYS, avgYes));
            System.out.printf("  compress=true overhead vs baseline: %.2fx  (%.1f%%%s)%n",
                ratio,
                Math.abs((ratio - 1.0) * 100),
                ratio >= 1.0 ? " faster" : " slower");
            System.out.println();

            // ── 5. Cleanup ──────────────────────────────────────────
            System.out.println("  Cleaning up test data …");
            client.truncate(null, NAMESPACE, SET_NAME, null);
            System.out.println("  Done.");

        } catch (Exception e) {
            System.err.println("ERROR: " + e.getMessage());
            e.printStackTrace();
        }
    }

    static long scanOnce(AerospikeClient client, boolean compress) {
        ScanPolicy sp = new ScanPolicy();
        sp.compress      = compress;
        sp.socketTimeout = 10_000;
        sp.totalTimeout  = 30_000;

        long[] count = {0};
        long t = System.nanoTime();
        client.scanAll(sp, NAMESPACE, SET_NAME, (key, record) -> count[0]++);
        long elapsed = System.nanoTime() - t;
        return elapsed / 1_000_000;  // → ms
    }

    static long recPerSec(int keys, long ms) {
        return ms == 0 ? 0 : (keys * 1000L) / ms;
    }

    static long average(long[] vals) {
        long sum = 0;
        for (long v : vals) sum += v;
        return sum / vals.length;
    }

    // ─────────────────────────────────────────────────────────────────

    public static void main(String[] args) {
        System.out.println();
        System.out.println("═══════════════════════════════════════════════════════════════════");
        System.out.println("  InflaterBenchmark  —  MultiCommand Inflater reuse optimization  ");
        System.out.println("═══════════════════════════════════════════════════════════════════");
        System.out.println();

        runIsolatedBenchmark();
        runLiveBenchmark();

        System.out.println("═══════════════════════════════════════════════════════════════════");
        System.out.println("  Done.");
        System.out.println("═══════════════════════════════════════════════════════════════════");
    }
}
