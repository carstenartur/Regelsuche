package de.regelsuche.math.sympy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Engine;
import org.graalvm.polyglot.HostAccess;
import org.graalvm.polyglot.PolyglotAccess;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.Value;
import org.graalvm.python.embedding.GraalPyResources;
import org.graalvm.python.embedding.VirtualFileSystem;

/**
 * One reusable GraalPy engine and one serialized worker context.
 *
 * <p>The context imports SymPy once and is reused across requests. A timeout
 * force-closes the active context and replaces the worker before another
 * request is accepted.</p>
 */
final class GraalPySymPyRuntime implements AutoCloseable {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String RUNTIME_ID = "graalpy-embedded";

    private final Engine engine;
    private ExecutorService executor;
    private volatile Worker worker;
    private boolean closed;

    GraalPySymPyRuntime() {
        engine = Engine.newBuilder("python")
            .option("engine.WarnInterpreterOnly", "false")
            .build();
        executor = newExecutor();
    }

    synchronized SymPyInvocation invoke(
        String input,
        Duration timeout
    ) {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(timeout, "timeout");
        if (closed) {
            return SymPyInvocation.failure(
                SymPyInvocation.Status.UNAVAILABLE,
                "GRAALPY_RUNTIME_CLOSED",
                RUNTIME_ID,
                0);
        }

        long started = System.nanoTime();
        Future<SymPyInvocation> future = executor.submit(() ->
            execute(input));
        try {
            return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException exception) {
            future.cancel(true);
            resetWorker(true);
            return SymPyInvocation.failure(
                SymPyInvocation.Status.TIMEOUT,
                "GRAALPY_FACTORIZATION_TIMEOUT",
                RUNTIME_ID,
                System.nanoTime() - started);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            future.cancel(true);
            resetWorker(true);
            return SymPyInvocation.failure(
                SymPyInvocation.Status.UNAVAILABLE,
                "GRAALPY_FACTORIZATION_INTERRUPTED",
                RUNTIME_ID,
                System.nanoTime() - started);
        } catch (ExecutionException exception) {
            resetWorker(true);
            Throwable cause = exception.getCause() == null
                ? exception
                : exception.getCause();
            return SymPyInvocation.failure(
                SymPyInvocation.Status.TECHNICAL_FAILURE,
                "GRAALPY_" + cause.getClass().getSimpleName()
                    .toUpperCase(java.util.Locale.ROOT),
                RUNTIME_ID,
                System.nanoTime() - started);
        }
    }

    private SymPyInvocation execute(String input) {
        boolean coldStart = worker == null;
        long initializationNanos = 0;
        try {
            if (worker == null) {
                long initializationStarted = System.nanoTime();
                worker = Worker.create(engine);
                initializationNanos =
                    System.nanoTime() - initializationStarted;
            }
            long invocationStarted = System.nanoTime();
            String output = worker.factor(input);
            long invocationNanos =
                System.nanoTime() - invocationStarted;
            return SymPyInvocation.completed(
                output,
                RUNTIME_ID,
                worker.runtimeVersion(),
                coldStart,
                initializationNanos,
                invocationNanos);
        } catch (PolyglotException | IllegalStateException exception) {
            resetWorker(false);
            return SymPyInvocation.failure(
                SymPyInvocation.Status.TECHNICAL_FAILURE,
                "GRAALPY_" + exception.getClass().getSimpleName()
                    .toUpperCase(java.util.Locale.ROOT),
                RUNTIME_ID,
                initializationNanos);
        }
    }

    private void resetWorker(boolean replaceExecutor) {
        Worker current = worker;
        worker = null;
        if (current != null) {
            current.close(true);
        }
        if (replaceExecutor && !closed) {
            executor.shutdownNow();
            executor = newExecutor();
        }
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        resetWorker(false);
        executor.shutdownNow();
        engine.close();
    }

    private static ExecutorService newExecutor() {
        return Executors.newSingleThreadExecutor(
            Thread.ofVirtual()
                .name("regelsuche-graalpy-sympy-", 0)
                .factory());
    }

    private static final class Worker {
        private final VirtualFileSystem fileSystem;
        private final Context context;
        private final Value factorFunction;
        private final String runtimeVersion;

        private Worker(
            VirtualFileSystem fileSystem,
            Context context,
            Value factorFunction,
            String runtimeVersion
        ) {
            this.fileSystem = fileSystem;
            this.context = context;
            this.factorFunction = factorFunction;
            this.runtimeVersion = runtimeVersion;
        }

        static Worker create(Engine engine) {
            VirtualFileSystem fileSystem = VirtualFileSystem.newBuilder()
                .resourceDirectory(SymPyScript.RESOURCE_DIRECTORY)
                .resourceLoadingClass(SymPyScript.class)
                .allowHostIO(VirtualFileSystem.HostIO.NONE)
                .build();
            Context context = GraalPyResources.contextBuilder(fileSystem)
                .engine(engine)
                .allowHostAccess(HostAccess.NONE)
                .allowCreateThread(false)
                .allowNativeAccess(false)
                .allowPolyglotAccess(PolyglotAccess.NONE)
                .option("python.DontWriteBytecodeFlag", "true")
                .build();
            try {
                Source source = Source.newBuilder(
                    "python",
                    SymPyScript.source(),
                    "<regelsuche-sympy-adapter>")
                    .internal(true)
                    .build();
                context.eval(source);
                Value bindings = context.getBindings("python");
                Value factorFunction = bindings.getMember("factor_payload");
                Value runtimeInfo = bindings.getMember("runtime_info");
                if (factorFunction == null
                        || !factorFunction.canExecute()
                        || runtimeInfo == null
                        || !runtimeInfo.canExecute()) {
                    throw new IllegalStateException(
                        "embedded SymPy adapter functions are unavailable");
                }
                String version = runtimeVersion(
                    runtimeInfo.execute().asString());
                return new Worker(
                    fileSystem,
                    context,
                    factorFunction,
                    version);
            } catch (IOException | RuntimeException exception) {
                try {
                    context.close(true);
                } finally {
                    try {
                        fileSystem.close();
                    } catch (IOException ignored) {
                        // The original initialization failure is authoritative.
                    }
                }
                throw new IllegalStateException(
                    "embedded GraalPy context initialization failed",
                    exception);
            }
        }

        String factor(String input) {
            return factorFunction.execute(input).asString();
        }

        String runtimeVersion() {
            return runtimeVersion;
        }

        void close(boolean cancel) {
            try {
                context.close(cancel);
            } catch (RuntimeException ignored) {
                // Closing a timed-out context is best-effort cleanup.
            }
            try {
                fileSystem.close();
            } catch (IOException ignored) {
                // The runtime result is already terminal at this point.
            }
        }

        private static String runtimeVersion(String runtimeInfoJson) {
            try {
                JsonNode info = JSON.readTree(runtimeInfoJson);
                String implementation = requiredText(
                    info,
                    "pythonImplementation");
                String pythonVersion = requiredText(
                    info,
                    "pythonVersion");
                String symPyVersion = requiredText(
                    info,
                    "sympyVersion");
                return implementation + '-' + pythonVersion
                    + "/sympy-" + symPyVersion;
            } catch (IOException exception) {
                throw new IllegalStateException(
                    "embedded GraalPy runtime metadata is invalid",
                    exception);
            }
        }

        private static String requiredText(
            JsonNode node,
            String field
        ) {
            JsonNode value = node == null ? null : node.get(field);
            if (value == null
                    || !value.isTextual()
                    || value.textValue().isBlank()) {
                throw new IllegalStateException(
                    "embedded GraalPy runtime field is missing: "
                        + field);
            }
            return value.textValue();
        }
    }
}
