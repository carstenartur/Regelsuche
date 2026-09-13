package de.regelsuche.quality.supplychain;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Simultaneous bounded consumers for the pinned, child-free offline worker. */
final class BoundedScannerProcess {
    private static final Duration CLEANUP_LIMIT = Duration.ofSeconds(10);
    private BoundedScannerProcess() {}

    record Captured(byte[] bytes, long observedBytes, boolean truncated, String error) {}
    record Result(String outcome, Integer exitCode, String error, Captured stdout, Captured stderr) {}

    static Result run(Path directory, List<String> command, Duration timeout, long outputLimit) {
        var worker = new Worker(directory, command, timeout, outputLimit);
        return worker.run();
    }

    private static final class Worker {
        private final Path directory;
        private final List<String> command;
        private final long deadline;
        private final long outputLimit;
        private final AtomicReference<String> stopped = new AtomicReference<>();
        private Process process;
        private Consumer stdout, stderr;
        private String outcome = "NOT_STARTED", error = "";
        private boolean interrupted;

        Worker(Path directory, List<String> command, Duration timeout, long outputLimit) {
            if (timeout.isNegative() || timeout.isZero() || outputLimit < 1 || outputLimit > Integer.MAX_VALUE)
                throw new IllegalArgumentException("invalid scanner process bounds");
            this.directory = directory; this.command = List.copyOf(command); this.outputLimit = outputLimit;
            deadline = System.nanoTime() + timeout.toNanos();
        }

        Result run() {
            try {
                start();
                if (process.waitFor(remaining(deadline), TimeUnit.NANOSECONDS)) outcome = "EXITED";
                else stop("TIMED_OUT");
            } catch (IOException failure) {
                outcome = "START_FAILURE"; error = failure.toString();
            } catch (InterruptedException failure) {
                interrupted = true; stop("INTERRUPTED");
            } finally {
                cleanup();
            }
            Integer exit = process == null || process.isAlive() ? null : process.exitValue();
            if (stopped.get() != null) outcome = stopped.get();
            return new Result(outcome, exit, error, capture(stdout), capture(stderr));
        }

        private void start() throws IOException {
            var builder = new ProcessBuilder(command).directory(directory.toFile());
            builder.environment().clear();
            builder.environment().put("LANG", "C.UTF-8");
            process = builder.start();
            process.getOutputStream().close();
            stdout = new Consumer(process.getInputStream(), outputLimit, this::stop);
            stderr = new Consumer(process.getErrorStream(), outputLimit, this::stop);
            stdout.start(); stderr.start();
        }

        private void stop(String reason) {
            stopped.compareAndSet(null, reason);
            if (process != null && process.isAlive()) process.destroyForcibly();
        }

        private void cleanup() {
            interrupted |= Thread.interrupted();
            long cleanupDeadline = System.nanoTime() + CLEANUP_LIMIT.toNanos();
            if (process != null && process.isAlive()) process.destroyForcibly();
            awaitProcess(cleanupDeadline);
            // Normal success must include complete capture inside the original execution deadline.
            if (!awaitConsumers(deadline)) {
                stop("TIMED_OUT");
                closeConsumers();
                if (!awaitConsumers(cleanupDeadline)) {
                    error = "scanner output consumers did not finish after stream closure";
                    stopped.set("OUTPUT_FAILURE");
                }
            }
            if (interrupted) Thread.currentThread().interrupt();
        }

        private void awaitProcess(long until) {
            if (process == null) return;
            try {
                if (!process.waitFor(remaining(until), TimeUnit.NANOSECONDS)) {
                    stopped.set("TERMINATION_FAILURE"); error = "scanner did not terminate after forced destruction";
                }
            } catch (InterruptedException failure) {
                interrupted = true; stopped.compareAndSet(null, "INTERRUPTED");
            }
        }

        private boolean awaitConsumers(long until) {
            try {
                return await(stdout, until) && await(stderr, until);
            } catch (InterruptedException failure) {
                interrupted = true; stopped.compareAndSet(null, "INTERRUPTED"); return false;
            }
        }
        private static boolean await(Consumer consumer, long until) throws InterruptedException {
            return consumer == null || consumer.finished.await(remaining(until), TimeUnit.NANOSECONDS);
        }
        private void closeConsumers() {
            if (stdout != null) stdout.close();
            if (stderr != null) stderr.close();
        }
        private static Captured capture(Consumer consumer) {
            return consumer == null ? new Captured(new byte[0], 0, false, "") : consumer.capture();
        }
    }

    private static long remaining(long deadline) { return Math.max(0, deadline - System.nanoTime()); }

    private static final class Consumer implements Runnable {
        private final InputStream input;
        private final long limit;
        private final java.util.function.Consumer<String> stop;
        private final ByteArrayOutputStream retained = new ByteArrayOutputStream();
        private final CountDownLatch finished = new CountDownLatch(1);
        private long observed;
        private boolean truncated, sealed;
        private String error = "";

        Consumer(InputStream input, long limit, java.util.function.Consumer<String> stop) {
            this.input = input; this.limit = limit; this.stop = stop;
        }
        void start() { Thread.ofVirtual().name("bounded-osv-output").start(this); }

        @Override public void run() {
            try (input) {
                byte[] block = new byte[16384];
                while (true) {
                    int maximum;
                    synchronized (this) {
                        if (sealed) return;
                        maximum = (int) Math.min(block.length, limit - retained.size() + 1);
                    }
                    int count = input.read(block, 0, maximum);
                    if (count == -1) return;
                    if (!retain(block, count)) { stop.accept("OUTPUT_LIMIT"); return; }
                }
            } catch (IOException failure) {
                synchronized (this) { error = failure.toString(); }
                stop.accept("OUTPUT_FAILURE");
            } finally {
                finished.countDown();
            }
        }

        private synchronized boolean retain(byte[] block, int count) {
            if (sealed) return true;
            observed += count;
            int keep = (int) Math.min(count, limit - retained.size());
            retained.write(block, 0, keep);
            truncated = count > keep;
            return !truncated;
        }
        synchronized Captured capture() {
            sealed = true;
            return new Captured(retained.toByteArray(), observed, truncated, error);
        }
        void close() {
            try { input.close(); }
            catch (IOException failure) { synchronized (this) { error = failure.toString(); } }
        }
    }
}
