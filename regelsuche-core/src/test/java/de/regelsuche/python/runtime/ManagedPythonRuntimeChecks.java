package de.regelsuche.python.runtime;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import de.regelsuche.python.runtime.ManagedPythonRuntime.Failure;
import de.regelsuche.python.runtime.ManagedPythonRuntime.RuntimeFailure;
import de.regelsuche.python.runtime.ManagedPythonRuntime.Session;

/** Dependency-free deterministic lifecycle checks, also invoked by the JUnit test class. */
public final class ManagedPythonRuntimeChecks {
    private static final Duration NORMAL = Duration.ofSeconds(10);
    private static final Duration SHORT = Duration.ofMillis(80);
    private ManagedPythonRuntimeChecks() { }

    static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
    static void await(CountDownLatch latch) {
        try { check(latch.await(10, TimeUnit.SECONDS), "latch deadline"); }
        catch (InterruptedException exception) { throw new AssertionError(exception); }
    }
    static RuntimeFailure fails(Failure kind, Runnable operation) {
        try { operation.run(); }
        catch (RuntimeFailure result) {
            check(result.failure() == kind, "wrong failure kind: " + result.failure());
            return result;
        }
        throw new AssertionError("expected " + kind);
    }
    static ManagedPythonRuntime runtime(ManagedPythonRuntime.SessionFactory factory) {
        return new ManagedPythonRuntime(factory, 128, 128, "runtime-check-");
    }
    static class Echo implements Session {
        final AtomicInteger initialized = new AtomicInteger();
        final AtomicInteger closed = new AtomicInteger();
        Thread worker;
        @Override public String initialize() {
            check(closed.get() == 0, "initializing a closed session");
            initialized.incrementAndGet(); worker = Thread.currentThread();
            check(!worker.isVirtual(), "Python initialization must use a platform thread");
            return "test-runtime/1";
        }
        @Override public String invoke(String input) {
            check(Thread.currentThread() == worker, "context changed worker");
            check(closed.get() == 0, "invoking closed session");
            return input;
        }
        @Override public void close() { closed.incrementAndGet(); }
    }

    public static void warmSessionAndClose() {
        Echo echo = new Echo();
        ManagedPythonRuntime runtime = runtime(() -> echo);
        var cold = runtime.invoke("one", NORMAL);
        var warm = runtime.invoke("two", NORMAL);
        check(cold.coldStart() && !warm.coldStart(), "one initialization, warm reuse");
        check(cold.output().equals("one") && warm.output().equals("two"), "exact outputs");
        check(cold.runtimeVersion().equals("test-runtime/1"), "runtime metadata");
        check(warm.initializationNanos() == 0, "warm initialization time");
        check(echo.initialized.get() == 1, "repeated initialization");
        runtime.close(); runtime.close();
        check(echo.closed.get() == 1, "close exactly once");
        fails(Failure.CLOSED, () -> runtime.invoke("three", NORMAL));
    }

    public static void unicodeAndInputLimits() {
        AtomicInteger created = new AtomicInteger();
        try (var runtime = new ManagedPythonRuntime(() -> { created.incrementAndGet(); return new Echo(); }, 4, 4, "byte-check-")) {
            check(runtime.invoke("éé", NORMAL).output().equals("éé"), "UTF-8 equality");
            fails(Failure.SIZE_LIMIT, () -> runtime.invoke("ééé", NORMAL));
            check(!runtime.invoke("ok", NORMAL).coldStart(), "invalid input must not retire warm context");
            check(created.get() == 1, "input rejection created a context");
        }
    }

    public static void outputLimitRetiresSession() {
        Echo first = new Echo() { @Override public String invoke(String input) { return "é".repeat(65); } };
        AtomicInteger created = new AtomicInteger();
        try (var runtime = runtime(() -> created.getAndIncrement() == 0 ? first : new Echo())) {
            fails(Failure.SIZE_LIMIT, () -> runtime.invoke("ok", NORMAL));
            check(first.closed.get() == 1, "overlarge output session not retired");
            check(runtime.invoke("recovered", NORMAL).coldStart(), "output recovery not fresh");
        }
    }

    public static void executionFailureRetiresSession() {
        Echo first = new Echo() { @Override public String invoke(String input) { throw new IllegalStateException("private-data"); } };
        AtomicInteger created = new AtomicInteger();
        try (var runtime = runtime(() -> created.getAndIncrement() == 0 ? first : new Echo())) {
            var error = fails(Failure.EXECUTION, () -> runtime.invoke("ok", NORMAL));
            check(!error.getMessage().contains("private-data"), "guest data in public message");
            check(error.getCause() instanceof IllegalStateException, "technical cause lost");
            check(first.closed.get() == 1, "failing session not closed");
            check(runtime.invoke("recovered", NORMAL).coldStart(), "execution recovery not fresh");
        }
    }

    public static void initializationFailureRetiresSession() {
        Echo first = new Echo() { @Override public String initialize() { throw new IllegalStateException("bootstrap"); } };
        AtomicInteger created = new AtomicInteger();
        try (var runtime = runtime(() -> created.getAndIncrement() == 0 ? first : new Echo())) {
            fails(Failure.EXECUTION, () -> runtime.invoke("x", NORMAL));
            check(first.closed.get() == 1, "failed bootstrap leaked");
            check(runtime.invoke("recovered", NORMAL).coldStart(), "bootstrap recovery not fresh");
        }
    }

    public static void blockedInvocationIsCancelled() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        Echo first = new Echo() {
            @Override public String invoke(String input) { entered.countDown(); awaitIgnoringInterrupt(release); return "late"; }
            @Override public void close() { super.close(); release.countDown(); }
        };
        AtomicInteger created = new AtomicInteger();
        try (var runtime = runtime(() -> created.getAndIncrement() == 0 ? first : new Echo());
                var caller = Executors.newSingleThreadExecutor()) {
            Future<?> timed = caller.submit(() -> fails(Failure.TIMEOUT, () -> runtime.invoke("x", Duration.ofSeconds(1))));
            await(entered); timed.get(10, TimeUnit.SECONDS);
            check(first.closed.get() == 1, "timed out context not closed");
            check(runtime.invoke("recovered", NORMAL).coldStart(), "timeout recovery not fresh");
            check(!runtime.invoke("warm", NORMAL).coldStart(), "old task retired replacement");
        }
    }

    public static void cancelledStartupCannotReplaceNewGeneration() throws Exception {
        CountDownLatch factoryEntered = new CountDownLatch(1), releaseFactory = new CountDownLatch(1), lateClosed = new CountDownLatch(1);
        Echo first = new Echo() { @Override public void close() { super.close(); lateClosed.countDown(); } };
        Echo replacement = new Echo();
        AtomicInteger created = new AtomicInteger();
        try (var runtime = runtime(() -> {
            if (created.getAndIncrement() != 0) return replacement;
            factoryEntered.countDown(); awaitIgnoringInterrupt(releaseFactory); return first;
        }); var caller = Executors.newSingleThreadExecutor()) {
            Future<?> timed = caller.submit(() -> fails(Failure.TIMEOUT, () -> runtime.invoke("x", Duration.ofSeconds(1))));
            await(factoryEntered); timed.get(10, TimeUnit.SECONDS);
            check(runtime.invoke("replacement", NORMAL).coldStart(), "no replacement after delayed factory");
            releaseFactory.countDown(); await(lateClosed);
            check(first.initialized.get() == 0 && first.closed.get() == 1, "retired factory was initialized or leaked");
            check(replacement.closed.get() == 0, "late startup closed replacement");
            check(!runtime.invoke("warm", NORMAL).coldStart(), "late startup replaced warm generation");
        } finally { releaseFactory.countDown(); }
    }

    public static void queueTimeoutDoesNotCancelOwner() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        Echo echo = new Echo() {
            @Override public String invoke(String input) {
                if (input.equals("owner")) { entered.countDown(); awaitIgnoringInterrupt(release); }
                return super.invoke(input);
            }
            @Override public void close() { super.close(); release.countDown(); }
        };
        try (var runtime = runtime(() -> echo); var caller = Executors.newSingleThreadExecutor()) {
            Future<?> owner = caller.submit(() -> runtime.invoke("owner", NORMAL));
            await(entered);
            fails(Failure.TIMEOUT, () -> runtime.invoke("queued", SHORT));
            check(echo.closed.get() == 0, "queue waiter cancelled owner");
            release.countDown(); owner.get(10, TimeUnit.SECONDS);
            check(!runtime.invoke("warm", NORMAL).coldStart(), "queue deadline retired owner");
        } finally { release.countDown(); }
    }

    public static void interruptRestoresFlagAndRetiresOwner() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1), done = new CountDownLatch(1);
        AtomicReference<Throwable> error = new AtomicReference<>();
        AtomicBoolean interruptRestored = new AtomicBoolean();
        Echo echo = new Echo() {
            @Override public String invoke(String input) { entered.countDown(); awaitIgnoringInterrupt(release); return input; }
            @Override public void close() { super.close(); release.countDown(); }
        };
        try (var runtime = runtime(() -> echo)) {
            Thread caller = Thread.ofPlatform().start(() -> {
                try {
                    fails(Failure.INTERRUPTED, () -> runtime.invoke("owner", NORMAL));
                    interruptRestored.set(Thread.currentThread().isInterrupted());
                } catch (Throwable failure) { error.set(failure); }
                finally { done.countDown(); }
            });
            await(entered); caller.interrupt(); await(done); caller.join();
            check(error.get() == null, "interrupt caller failed: " + error.get());
            check(interruptRestored.get() && echo.closed.get() == 1, "interrupt cleanup/flag");
        } finally { release.countDown(); }
    }

    public static void concurrentCallsUseOneWorker() throws Exception {
        Echo echo = new Echo();
        try (var runtime = runtime(() -> echo); ExecutorService callers = Executors.newFixedThreadPool(8)) {
            List<Future<ManagedPythonRuntime.Invocation>> results = new ArrayList<>();
            for (int i = 0; i < 40; i++) {
                String input = Integer.toString(i);
                results.add(callers.submit(() -> runtime.invoke(input, NORMAL)));
            }
            int cold = 0;
            for (int i = 0; i < results.size(); i++) {
                var result = results.get(i).get(10, TimeUnit.SECONDS);
                check(result.output().equals(Integer.toString(i)), "mixed caller outputs");
                if (result.coldStart()) cold++;
            }
            check(cold == 1 && echo.initialized.get() == 1, "multiple concurrent contexts");
        }
    }

    public static void cleanupCannotMaskExecutionFailure() {
        Echo echo = new Echo() {
            @Override public String invoke(String input) { throw new IllegalArgumentException("execution"); }
            @Override public void close() { super.close(); throw new IllegalStateException("cleanup"); }
        };
        try (var runtime = runtime(() -> echo)) {
            var error = fails(Failure.EXECUTION, () -> runtime.invoke("x", NORMAL));
            check(error.getSuppressed().length == 1, "cleanup failure lost or replaced original");
            check(echo.closed.get() == 1, "cleanup attempted more than once");
        }
    }

    public static void closeStopsNewAndQueuedAdmissions() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicInteger invocations = new AtomicInteger();
        Echo echo = new Echo() {
            @Override public String invoke(String input) {
                invocations.incrementAndGet();
                entered.countDown(); awaitIgnoringInterrupt(release);
                return super.invoke(input);
            }
        };
        ManagedPythonRuntime runtime = runtime(() -> echo);
        AtomicReference<Throwable> error = new AtomicReference<>();
        Thread waiter = Thread.ofPlatform().unstarted(() -> {
            try { fails(Failure.CLOSED, () -> runtime.invoke("queued", NORMAL)); }
            catch (Throwable failure) { error.set(failure); }
        });
        Thread closer = Thread.ofPlatform().unstarted(runtime::close);
        try (var caller = Executors.newSingleThreadExecutor()) {
            Future<?> owner = caller.submit(() -> runtime.invoke("owner", NORMAL));
            try {
                await(entered);
                waiter.start();
                long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (waiter.getState() != Thread.State.TIMED_WAITING && System.nanoTime() < until) Thread.onSpinWait();
                check(waiter.getState() == Thread.State.TIMED_WAITING, "waiter did not queue behind owner");
                closer.start();
                until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (!runtime.isClosed() && System.nanoTime() < until) Thread.onSpinWait();
                check(runtime.isClosed(), "close did not close admission before waiting");
                // The current owner remains blocked. A new call must fail CLOSED, not wait and time out.
                fails(Failure.CLOSED, () -> runtime.invoke("new", SHORT));
                check(echo.closed.get() == 0, "close must drain the active owner");
            } finally { release.countDown(); }
            owner.get(10, TimeUnit.SECONDS);
            waiter.join(10_000); closer.join(10_000);
            check(!waiter.isAlive() && !closer.isAlive(), "closing threads stuck");
            check(error.get() == null, "queued caller failed: " + error.get());
            check(invocations.get() == 1 && echo.closed.get() == 1, "closed runtime admitted queued work");
        } finally { release.countDown(); runtime.close(); }
    }

    public static void invalidConfigurationAndDeadline() {
        rejects(() -> runtime(null));
        rejects(() -> new ManagedPythonRuntime(Echo::new, 0, 1, "runtime-"));
        rejects(() -> new ManagedPythonRuntime(Echo::new, 1, 0, "runtime-"));
        rejects(() -> new ManagedPythonRuntime(Echo::new, 1, 1, "bad/name"));
        try (var runtime = runtime(Echo::new)) {
            rejects(() -> runtime.invoke("x", Duration.ZERO));
            rejects(() -> runtime.invoke("x", Duration.ofNanos(-1)));
            rejects(() -> runtime.invoke("x", Duration.ofSeconds(Long.MAX_VALUE)));
            rejects(() -> runtime.invoke(null, NORMAL));
        }
        try (var runtime = runtime(() -> null)) {
            fails(Failure.EXECUTION, () -> runtime.invoke("x", NORMAL));
        }
    }
    private static void rejects(Runnable runnable) {
        try { runnable.run(); }
        catch (IllegalArgumentException | NullPointerException expected) { return; }
        throw new AssertionError("invalid input accepted");
    }
    private static void awaitIgnoringInterrupt(CountDownLatch latch) {
        boolean interrupted = false;
        for (;;) {
            try { if (!latch.await(10, TimeUnit.SECONDS)) throw new AssertionError("release deadline"); break; }
            catch (InterruptedException exception) { interrupted = true; }
        }
        if (interrupted) Thread.currentThread().interrupt();
    }
    public static void main(String[] arguments) throws Exception {
        warmSessionAndClose(); unicodeAndInputLimits(); outputLimitRetiresSession();
        executionFailureRetiresSession(); initializationFailureRetiresSession(); blockedInvocationIsCancelled();
        cancelledStartupCannotReplaceNewGeneration(); queueTimeoutDoesNotCancelOwner();
        interruptRestoresFlagAndRetiresOwner(); concurrentCallsUseOneWorker();
        cleanupCannotMaskExecutionFailure(); closeStopsNewAndQueuedAdmissions(); invalidConfigurationAndDeadline();
        System.out.println("13 shared lifecycle scenarios passed");
    }
}
