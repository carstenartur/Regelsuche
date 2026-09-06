package de.regelsuche.python.runtime;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Serialized lifecycle for trusted embedded Python adapters, without GraalPy or mathematical dependencies.
 *
 * <p>The adapter owns its fixed program, packages, capability policy and result verification. This class
 * never evaluates source, grants guest permissions, interprets a mathematical status, or certifies output.
 * A completed invocation is still untrusted data. One daemon platform thread enters each session.
 * Queueing, initialization and invocation share one monotonic deadline; best-effort cancellation and
 * resource cleanup are not a hard real-time or process-isolation guarantee.</p>
 */
public final class ManagedPythonRuntime implements AutoCloseable {
    public enum Failure { TIMEOUT, INTERRUPTED, CLOSED, EXECUTION, SIZE_LIMIT }

    /** A technical transport failure, never a mathematical counterexample. */
    public static final class RuntimeFailure extends RuntimeException {
        private static final long serialVersionUID = 1L;
        private final Failure failure;

        private RuntimeFailure(Failure failure, String message, Throwable cause) {
            super(message, cause);
            this.failure = failure;
        }

        public Failure failure() { return failure; }
    }

    /**
     * Factory for a fresh, non-shared session holder. Allocate no guest resources in this method:
     * put context construction and imports in {@link Session#initialize()}. This permits the owner to
     * publish the holder for cancellation before initialization starts. A factory completing after
     * retirement has its returned holder closed and can never replace a newer generation.
     */
    @FunctionalInterface
    public interface SessionFactory {
        Session create();
    }

    /**
     * Backend-specific session. initialize/invoke run only on its platform worker. close must be
     * thread-safe and tolerate concurrent or not-yet-started initialization and execution. In particular,
     * resources created after cancellation must be closed, not published for reuse. Close owns only this
     * session: it must not retire the runtime, close a shared engine or delete another session's resources.
     */
    public interface Session {
        String initialize();
        String invoke(String input);
        void close();
    }

    /** Timings are diagnostics, excluded from any mathematical certificate by the adapter. */
    public record Invocation(String output, String runtimeVersion, boolean coldStart,
                             long initializationNanos, long invocationNanos) {
        public Invocation {
            Objects.requireNonNull(output, "output");
            Objects.requireNonNull(runtimeVersion, "runtimeVersion");
            if (runtimeVersion.isBlank() || initializationNanos < 0 || invocationNanos < 0) {
                throw new IllegalArgumentException("invalid invocation metadata");
            }
        }
    }

    private final SessionFactory factory;
    private final int maximumInputBytes;
    private final int maximumOutputBytes;
    private final String threadName;
    private final ReentrantLock gate = new ReentrantLock();
    // Only the caller holding gate may change these two fields.
    private Generation generation;
    private boolean closed;

    /** Integer.MAX_VALUE delegates that data limit to a separately bounded adapter policy. */
    public ManagedPythonRuntime(SessionFactory factory, int maximumInputBytes, int maximumOutputBytes,
                                String threadName) {
        this.factory = Objects.requireNonNull(factory, "factory");
        if (maximumInputBytes < 1 || maximumOutputBytes < 1) {
            throw new IllegalArgumentException("positive input/output byte limits required");
        }
        if (threadName == null || !threadName.matches("[A-Za-z][A-Za-z0-9-]{0,95}")) {
            throw new IllegalArgumentException("invalid platform-thread prefix");
        }
        this.maximumInputBytes = maximumInputBytes;
        this.maximumOutputBytes = maximumOutputBytes;
        this.threadName = threadName;
    }

    public Invocation invoke(String input, Duration timeout) {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(timeout, "timeout");
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("positive timeout required");
        }
        final long budget;
        try { budget = timeout.toNanos(); }
        catch (ArithmeticException exception) {
            throw new IllegalArgumentException("timeout exceeds monotonic clock range", exception);
        }
        checkSize(input, maximumInputBytes);
        long started = System.nanoTime();
        boolean acquired = false;
        Generation selected = null;
        Future<Invocation> task = null;
        try {
            acquired = gate.tryLock(budget, TimeUnit.NANOSECONDS);
            if (!acquired) throw failure(Failure.TIMEOUT, "Python runtime queue deadline exceeded", null);
            if (closed) throw failure(Failure.CLOSED, "Python runtime is closed", null);
            if (remaining(started, budget) <= 0) {
                // We now own the idle generation. Preserve cancellation semantics even when the
                // deadline expired just before submission. A waiter without the gate never retires it.
                selected = generation;
                throw new TimeoutException();
            }
            if (generation == null) generation = new Generation();
            selected = generation;
            Generation owned = selected;
            task = owned.executor.submit(() -> owned.execute(input));
            // Recompute after submission, so thread creation/submission do not extend the deadline.
            long left = remaining(started, budget);
            if (left <= 0) throw new TimeoutException();
            return task.get(left, TimeUnit.NANOSECONDS);
        } catch (TimeoutException exception) {
            RuntimeFailure result = failure(Failure.TIMEOUT, "Python runtime deadline exceeded; session retired", exception);
            retire(selected, task, result);
            throw result;
        } catch (InterruptedException exception) {
            RuntimeFailure result = failure(Failure.INTERRUPTED, "Python runtime invocation interrupted", exception);
            if (acquired) retire(selected, task, result);
            Thread.currentThread().interrupt();
            throw result;
        } catch (ExecutionException exception) {
            RuntimeFailure result = exception.getCause() instanceof RuntimeFailure known ? known
                    : failure(Failure.EXECUTION, "Python runtime execution failed; session retired", exception.getCause());
            retire(selected, task, result);
            throw result;
        } finally {
            if (acquired) gate.unlock();
        }
    }

    private static long remaining(long started, long budget) {
        return budget - (System.nanoTime() - started);
    }

    private static RuntimeFailure failure(Failure failure, String message, Throwable cause) {
        return new RuntimeFailure(failure, message, cause);
    }

    private static void checkSize(String data, int limit) {
        if (limit != Integer.MAX_VALUE && (data.length() > limit || data.getBytes(StandardCharsets.UTF_8).length > limit)) {
            throw failure(Failure.SIZE_LIMIT, "Python runtime data byte limit exceeded", null);
        }
    }

    private void retire(Generation selected, Future<?> task, RuntimeFailure authoritative) {
        if (task != null) task.cancel(true);
        if (selected == null) return;
        if (generation == selected) generation = null;
        try { selected.close(); }
        catch (RuntimeException cleanup) { authoritative.addSuppressed(cleanup); }
    }

    /** Idempotent; waits for the serialized caller and then releases its session. */
    @Override public void close() {
        gate.lock();
        try {
            if (closed) return;
            closed = true;
            Generation owned = generation;
            generation = null;
            if (owned != null) owned.close();
        } finally { gate.unlock(); }
    }

    private final class Generation {
        final ExecutorService executor = Executors.newSingleThreadExecutor(
                Thread.ofPlatform().daemon(true).name(threadName, 0).factory());
        final AtomicReference<Session> active = new AtomicReference<>();
        final AtomicBoolean retired = new AtomicBoolean();
        // Worker-confined metadata; caller holds gate across every invocation.
        String version;

        Invocation execute(String input) {
            if (retired.get()) throw new IllegalStateException("retired Python session");
            boolean cold = version == null;
            long initialization = 0;
            Session session = active.get();
            if (cold) {
                long started = System.nanoTime();
                session = Objects.requireNonNull(factory.create(), "session factory returned null");
                active.set(session);
                if (retired.get()) {
                    closeActive();
                    throw new IllegalStateException("retired Python startup");
                }
                version = Objects.requireNonNull(session.initialize(), "missing runtime version");
                if (version.isBlank()) throw new IllegalStateException("blank runtime version");
                initialization = System.nanoTime() - started;
            }
            if (retired.get()) throw new IllegalStateException("retired Python execution");
            long started = System.nanoTime();
            String output = Objects.requireNonNull(session.invoke(input), "missing Python output");
            checkSize(output, maximumOutputBytes);
            return new Invocation(output, version, cold, initialization, System.nanoTime() - started);
        }

        void closeActive() {
            Session session = active.getAndSet(null);
            if (session != null) session.close();
        }

        void close() {
            retired.set(true);
            try { closeActive(); }
            finally { executor.shutdownNow(); }
        }
    }
}
