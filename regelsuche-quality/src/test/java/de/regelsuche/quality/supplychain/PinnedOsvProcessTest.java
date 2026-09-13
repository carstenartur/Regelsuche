package de.regelsuche.quality.supplychain;

import static org.junit.jupiter.api.Assertions.*;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PinnedOsvProcessTest {
    @TempDir Path root;

    @Test void outputLimitStopsTheActualProducerAndRetainsOnlyBoundedDiagnosticPrefixes() throws Exception {
        Path output = Files.createDirectory(root.resolve("limit"));
        Path completed = output.resolve("producer-completed");
        var result = PinnedOsvScanner.execute(root, output, command(SupplyChainJson.MAX_JSON_BYTES + 4 * 1024 * 1024,
            completed, true), Duration.ofSeconds(5)).receipt();
        assertEquals("OUTPUT_LIMIT", result.path("outcome").asText());
        for (String prefix : List.of("stdout", "stderr")) {
            Path retained = output.resolve(result.path(prefix + "Path").asText());
            assertTrue(Files.size(retained) <= SupplyChainJson.MAX_JSON_BYTES);
            assertEquals(Files.size(retained), result.path(prefix + "Bytes").longValue());
            assertEquals(SupplyChainJson.hash(retained), result.path(prefix + "Hash").asText());
            assertTrue(result.path(prefix + "ObservedBytes").longValue() <= SupplyChainJson.MAX_JSON_BYTES + 1);
        }
        assertTrue(result.path("stdoutTruncated").booleanValue() || result.path("stderrTruncated").booleanValue());
        assertFalse(Files.exists(completed), "the producer must be stopped before completing its over-limit writes");
        assertTrue(result.path("exitCode").isIntegralNumber());
        assertNotEquals(0, result.path("exitCode").intValue());
    }

    @Test void bothPipesAreConsumedConcurrentlyBeyondTheirBufferCapacity() throws Exception {
        Path output = Files.createDirectory(root.resolve("both-pipes"));
        long bytes = 2 * 1024 * 1024;
        var result = PinnedOsvScanner.execute(root, output, command(bytes, output.resolve("completed"), false), Duration.ofSeconds(5)).receipt();
        assertEquals("EXITED", result.path("outcome").asText());
        assertEquals(0, result.path("exitCode").intValue());
        assertEquals(bytes, Files.size(output.resolve("scanner.stdout.json")));
        assertEquals(bytes, Files.size(output.resolve("scanner.stderr.log")));
        assertEquals(SupplyChainJson.hash(output.resolve("scanner.stdout.json")), result.path("stdoutHash").asText());
        assertEquals(SupplyChainJson.hash(output.resolve("scanner.stderr.log")), result.path("stderrHash").asText());
    }

    @Test void exactlyTheLimitIsCompleteButTheFirstExtraByteTruncates() throws Exception {
        int limit = 32_768;
        Path output = Files.createDirectory(root.resolve("boundary"));
        for (int bytes : List.of(limit, limit + 1)) {
            var result = BoundedScannerProcess.run(output, command(bytes, output.resolve("completed-" + bytes), false),
                Duration.ofSeconds(5), limit);
            assertEquals(bytes == limit ? "EXITED" : "OUTPUT_LIMIT", result.outcome());
            if (bytes == limit) {
                assertEquals(0, result.exitCode());
                assertEquals(limit, result.stdout().observedBytes());
                assertEquals(limit, result.stderr().observedBytes());
                assertFalse(result.stdout().truncated());
                assertFalse(result.stderr().truncated());
            } else {
                assertTrue(result.stdout().truncated() || result.stderr().truncated());
            }
            assertTrue(result.stdout().bytes().length <= limit);
            assertTrue(result.stderr().bytes().length <= limit);
        }
    }

    @Test void startFailureRetainsEmptyStreamsAndAnActualFailureReceipt() throws Exception {
        Path output = Files.createDirectory(root.resolve("not-started"));
        var result = PinnedOsvScanner.execute(root, output, List.of(output.resolve("absent-executable").toString()),
            Duration.ofSeconds(5)).receipt();
        assertEquals("START_FAILURE", result.path("outcome").asText());
        assertTrue(result.path("exitCode").isNull());
        assertTrue(result.hasNonNull("error"));
        assertEquals(0, Files.size(output.resolve("scanner.stdout.json")));
        assertEquals(0, Files.size(output.resolve("scanner.stderr.log")));
        assertArrayEquals(SupplyChainJson.canonical(result), Files.readAllBytes(output.resolve("scanner-execution.json")));
    }

    @Test void interruptionStopsTheActualProcessAndRetainsDiagnosticsBeforeRestoringTheFlag() throws Exception {
        Path output = Files.createDirectory(root.resolve("interrupted"));
        Path ready = output.resolve("ready");
        var finished = new java.util.concurrent.CompletableFuture<com.fasterxml.jackson.databind.node.ObjectNode>();
        var restored = new java.util.concurrent.atomic.AtomicBoolean();
        List<String> command = command(16_384, ready, true);
        Thread caller = new Thread(() -> {
            try {
                var receipt = PinnedOsvScanner.execute(root, output, command, Duration.ofSeconds(30)).receipt();
                restored.set(Thread.currentThread().isInterrupted());
                finished.complete(receipt);
            } catch (Exception failure) { finished.completeExceptionally(failure); }
        });
        caller.start();
        try {
            long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
            while (!Files.exists(ready) && System.nanoTime() < deadline) Thread.sleep(10);
            assertTrue(Files.exists(ready), "the actual child must reach its waiting state");
            caller.interrupt();
            var receipt = finished.get(5, java.util.concurrent.TimeUnit.SECONDS);
            assertEquals("INTERRUPTED", receipt.path("outcome").asText());
            assertTrue(restored.get());
            assertTrue(receipt.path("exitCode").isIntegralNumber());
            assertNotEquals(0, receipt.path("exitCode").intValue());
            assertEquals(16_384, Files.size(output.resolve("scanner.stdout.json")));
            assertEquals(16_384, Files.size(output.resolve("scanner.stderr.log")));
            assertArrayEquals(SupplyChainJson.canonical(receipt), Files.readAllBytes(output.resolve("scanner-execution.json")));
        } finally {
            caller.interrupt(); caller.join(5_000);
        }
        assertFalse(caller.isAlive());
    }

    private static List<String> command(long bytes, Path completed, boolean wait) throws Exception {
        Path java = Path.of(System.getProperty("java.home"), "bin", "java");
        Path classes = Path.of(OutputFixture.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        return List.of(java.toString(), "-cp", classes.toString(), OutputFixture.class.getName(), Long.toString(bytes),
            completed.toString(), Boolean.toString(wait));
    }

    public static final class OutputFixture {
        public static void main(String[] arguments) throws Exception {
            long bytes = Long.parseLong(arguments[0]);
            var start = new CountDownLatch(1);
            Thread stdout = new Thread(() -> emit(FileDescriptor.out, bytes, start));
            Thread stderr = new Thread(() -> emit(FileDescriptor.err, bytes, start));
            stdout.start(); stderr.start(); start.countDown();
            stdout.join(); stderr.join();
            Files.writeString(Path.of(arguments[1]), "COMPLETED");
            if (Boolean.parseBoolean(arguments[2])) new CountDownLatch(1).await();
        }
        private static void emit(FileDescriptor descriptor, long count, CountDownLatch start) {
            try (var stream = new FileOutputStream(descriptor)) {
                start.await();
                byte[] buffer = new byte[16384];
                for (long written = 0; written < count; written += buffer.length)
                    stream.write(buffer, 0, (int) Math.min(buffer.length, count - written));
                stream.flush();
            } catch (Exception failure) {
                throw new IllegalStateException(failure);
            }
        }
    }
}
