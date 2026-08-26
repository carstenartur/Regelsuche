package de.regelsuche.math.sympy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
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
 * One reusable GraalPy engine and one serialized platform-thread worker.
 *
 * <p>The context imports SymPy once and is reused across requests. GraalPy
 * native extensions cannot execute on Java virtual threads, so the worker is
 * deliberately backed by one dedicated daemon platform thread. A timeout
 * force-closes the active context and advances the runtime generation before
 * another request is accepted. Tasks from an older generation cannot mutate
 * or close a newer worker.</p>
 *
 * <p>The build keeps Python packages inside a module-specific virtual
 * filesystem. At runtime they are extracted once into a private temporary
 * directory. Native-module isolation creates, patches and deletes context-local
 * library copies, which cannot be done inside GraalPy's read-only virtual
 * filesystem. The extracted tree belongs to this runtime and is removed by
 * {@link #close()}.</p>
 */
final class GraalPySymPyRuntime implements AutoCloseable {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String RUNTIME_ID = "graalpy-embedded";

    private final Engine engine;
    private final Path externalResourcesDirectory;
    private final Object invocationLock = new Object();
    private final Object stateLock = new Object();
    private ExecutorService executor;
    private Worker worker;
    private long generation;
    private boolean closed;

    GraalPySymPyRuntime() {
        Path extracted = extractResources();
        Engine createdEngine = null;
        try {
            createdEngine = Engine.newBuilder("python")
                .option("engine.WarnInterpreterOnly", "false")
                .build();
            executor = newExecutor();
        } catch (RuntimeException exception) {
            if (createdEngine != null) {
                try {
                    createdEngine.close();
                } catch (RuntimeException closeFailure) {
                    exception.addSuppressed(closeFailure);
                }
            }
            deleteResources(extracted, exception);
            throw exception;
        }
        externalResourcesDirectory = extracted;
        engine = createdEngine;
    }

    SymPyInvocation invoke(
        String input,
        Duration timeout
    ) {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(timeout, "timeout");
        synchronized (invocationLock) {
            InvocationAuthority authority = invocationAuthority();
            if (authority == null) {
                return SymPyInvocation.failure(
                    SymPyInvocation.Status.UNAVAILABLE,
                    "GRAALPY_RUNTIME_CLOSED",
                    RUNTIME_ID,
                    0);
            }

            long started = System.nanoTime();
            Future<SymPyInvocation> future = authority.executor().submit(() ->
                execute(input, authority.generation()));
            try {
                return future.get(
                    timeout.toMillis(),
                    TimeUnit.MILLISECONDS);
            } catch (TimeoutException exception) {
                future.cancel(true);
                resetGeneration(authority.generation(), true);
                return SymPyInvocation.failure(
                    SymPyInvocation.Status.TIMEOUT,
                    "GRAALPY_FACTORIZATION_TIMEOUT",
                    RUNTIME_ID,
                    System.nanoTime() - started);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                future.cancel(true);
                resetGeneration(authority.generation(), true);
                return SymPyInvocation.failure(
                    SymPyInvocation.Status.UNAVAILABLE,
                    "GRAALPY_FACTORIZATION_INTERRUPTED",
                    RUNTIME_ID,
                    System.nanoTime() - started,
                    exception);
            } catch (ExecutionException exception) {
                resetGeneration(authority.generation(), true);
                Throwable cause = exception.getCause() == null
                    ? exception
                    : exception.getCause();
                return SymPyInvocation.failure(
                    SymPyInvocation.Status.TECHNICAL_FAILURE,
                    "GRAALPY_" + cause.getClass().getSimpleName()
                        .toUpperCase(java.util.Locale.ROOT),
                    RUNTIME_ID,
                    System.nanoTime() - started,
                    cause);
            }
        }
    }

    private InvocationAuthority invocationAuthority() {
        synchronized (stateLock) {
            return closed
                ? null
                : new InvocationAuthority(executor, generation);
        }
    }

    private SymPyInvocation execute(
        String input,
        long expectedGeneration
    ) {
        WorkerSnapshot snapshot = null;
        long invocationStarted = 0;
        try {
            snapshot = workerFor(expectedGeneration);
            if (snapshot == null) {
                return SymPyInvocation.failure(
                    SymPyInvocation.Status.UNAVAILABLE,
                    "GRAALPY_INVOCATION_GENERATION_RETIRED",
                    RUNTIME_ID,
                    0);
            }
            invocationStarted = System.nanoTime();
            String output = snapshot.worker().factor(input);
            long invocationNanos =
                System.nanoTime() - invocationStarted;
            return SymPyInvocation.completed(
                output,
                RUNTIME_ID,
                snapshot.worker().runtimeVersion(),
                snapshot.coldStart(),
                snapshot.initializationNanos(),
                invocationNanos);
        } catch (PolyglotException | IllegalStateException exception) {
            resetGeneration(expectedGeneration, false);
            long invocationNanos = invocationStarted == 0
                ? 0
                : System.nanoTime() - invocationStarted;
            return SymPyInvocation.failure(
                SymPyInvocation.Status.TECHNICAL_FAILURE,
                "GRAALPY_" + exception.getClass().getSimpleName()
                    .toUpperCase(java.util.Locale.ROOT),
                RUNTIME_ID,
                invocationNanos,
                exception);
        }
    }

    private WorkerSnapshot workerFor(long expectedGeneration) {
        synchronized (stateLock) {
            if (closed || generation != expectedGeneration) {
                return null;
            }
            if (worker != null) {
                return new WorkerSnapshot(worker, false, 0);
            }
        }

        long initializationStarted = System.nanoTime();
        Worker created = Worker.create(
            engine,
            externalResourcesDirectory);
        long initializationNanos =
            System.nanoTime() - initializationStarted;
        synchronized (stateLock) {
            if (closed || generation != expectedGeneration) {
                created.close(true);
                return null;
            }
            if (worker == null) {
                worker = created;
                return new WorkerSnapshot(
                    created,
                    true,
                    initializationNanos);
            }
            Worker current = worker;
            created.close(true);
            return new WorkerSnapshot(current, false, 0);
        }
    }

    private void resetGeneration(
        long expectedGeneration,
        boolean replaceExecutor
    ) {
        Worker retiredWorker;
        ExecutorService retiredExecutor = null;
        synchronized (stateLock) {
            if (generation != expectedGeneration) {
                return;
            }
            generation = Math.incrementExact(generation);
            retiredWorker = worker;
            worker = null;
            if (replaceExecutor && !closed) {
                retiredExecutor = executor;
                executor = newExecutor();
            }
        }
        if (retiredWorker != null) {
            retiredWorker.close(true);
        }
        if (retiredExecutor != null) {
            retiredExecutor.shutdownNow();
        }
    }

    @Override
    public void close() {
        synchronized (invocationLock) {
            Worker retiredWorker;
            ExecutorService retiredExecutor;
            synchronized (stateLock) {
                if (closed) {
                    return;
                }
                closed = true;
                generation = Math.incrementExact(generation);
                retiredWorker = worker;
                worker = null;
                retiredExecutor = executor;
            }
            if (retiredWorker != null) {
                retiredWorker.close(true);
            }
            retiredExecutor.shutdownNow();

            RuntimeException failure = null;
            try {
                engine.close();
            } catch (RuntimeException exception) {
                failure = exception;
            }
            try {
                deleteResources(externalResourcesDirectory);
            } catch (RuntimeException exception) {
                if (failure == null) {
                    failure = exception;
                } else {
                    failure.addSuppressed(exception);
                }
            }
            if (failure != null) {
                throw failure;
            }
        }
    }

    private static ExecutorService newExecutor() {
        return Executors.newSingleThreadExecutor(
            Thread.ofPlatform()
                .daemon(true)
                .name("regelsuche-graalpy-sympy-", 0)
                .factory());
    }

    private static Path extractResources() {
        Path directory;
        try {
            directory = Files.createTempDirectory(
                "regelsuche-graalpy-sympy-");
        } catch (IOException exception) {
            throw new IllegalStateException(
                "temporary GraalPy resource directory cannot be created",
                exception);
        }

        try (VirtualFileSystem fileSystem =
                VirtualFileSystem.newBuilder()
                    .resourceDirectory(SymPyScript.RESOURCE_DIRECTORY)
                    .resourceLoadingClass(SymPyScript.class)
                    .build()) {
            GraalPyResources.extractVirtualFileSystemResources(
                fileSystem,
                directory);
            return directory;
        } catch (IOException | RuntimeException exception) {
            deleteResources(directory, exception);
            throw new IllegalStateException(
                "embedded GraalPy resources cannot be extracted",
                exception);
        }
    }

    private static void deleteResources(
        Path directory,
        Throwable authoritativeFailure
    ) {
        try {
            deleteResources(directory);
        } catch (RuntimeException cleanupFailure) {
            authoritativeFailure.addSuppressed(cleanupFailure);
        }
    }

    private static void deleteResources(Path directory) {
        if (directory == null || !Files.exists(directory)) {
            return;
        }
        try {
            Files.walkFileTree(
                directory,
                new SimpleFileVisitor<>() {
                    @Override
                    public FileVisitResult visitFile(
                        Path file,
                        BasicFileAttributes attributes
                    ) throws IOException {
                        Files.deleteIfExists(file);
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult postVisitDirectory(
                        Path visitedDirectory,
                        IOException failure
                    ) throws IOException {
                        if (failure != null) {
                            throw failure;
                        }
                        Files.deleteIfExists(visitedDirectory);
                        return FileVisitResult.CONTINUE;
                    }
                });
        } catch (IOException exception) {
            throw new IllegalStateException(
                "temporary GraalPy resources cannot be deleted: "
                    + directory,
                exception);
        }
    }

    private static String hostExecutablePath() {
        String path = System.getenv("PATH");
        if (path == null || path.isBlank()) {
            throw new IllegalStateException(
                "PATH is required for GraalPy native-module isolation");
        }
        return path;
    }

    private record InvocationAuthority(
        ExecutorService executor,
        long generation
    ) {
        private InvocationAuthority {
            Objects.requireNonNull(executor, "executor");
        }
    }

    private record WorkerSnapshot(
        Worker worker,
        boolean coldStart,
        long initializationNanos
    ) {
        private WorkerSnapshot {
            Objects.requireNonNull(worker, "worker");
            if (initializationNanos < 0) {
                throw new IllegalArgumentException(
                    "GraalPy initialization duration must not be negative");
            }
        }
    }

    private static final class Worker {
        private final Context context;
        private final Value factorFunction;
        private final String runtimeVersion;

        private Worker(
            Context context,
            Value factorFunction,
            String runtimeVersion
        ) {
            this.context = context;
            this.factorFunction = factorFunction;
            this.runtimeVersion = runtimeVersion;
        }

        static Worker create(
            Engine engine,
            Path externalResourcesDirectory
        ) {
            Context context = null;
            try {
                context = Context.newBuilder()
                    .engine(engine)
                    // IsolateNativeModules must create and delete context-local
                    // copies next to the native libraries. GraalPy's embedded
                    // VFS is read-only, so use the private extracted resource
                    // tree through the supported external-directory adapter.
                    .apply(GraalPyResources.forExternalDirectory(
                        externalResourcesDirectory))
                    .allowHostAccess(HostAccess.NONE)
                    // The Polyglot default exposes no process environment.
                    // GraalPy searches for patchelf through PATH, so provide
                    // only this one host variable rather than inheriting the
                    // complete environment.
                    .environment("PATH", hostExecutablePath())
                    // The checked-in adapter is the only evaluated Python
                    // code. Permit GraalPy's internal background-GC daemon
                    // required by its native-extension runtime; application
                    // requests remain serialized on the dedicated worker.
                    .allowCreateThread(true)
                    // IsolateNativeModules relocates ELF libraries by invoking
                    // the pinned host patchelf executable. The structured
                    // request cannot select commands or paths; this process
                    // authority is reserved for the trusted GraalPy path.
                    .allowCreateProcess(true)
                    // GraalPy 25.1.3 loads the native _ctypes module while
                    // importing the pinned SymPy environment. Native access is
                    // required even though no user-supplied Python is evaluated.
                    .allowNativeAccess(true)
                    .allowPolyglotAccess(PolyglotAccess.NONE)
                    // Preserve the Java POSIX backend used by the embedded VFS
                    // configuration. Only native extension loading and
                    // isolation require native/process authority.
                    .option("python.PosixModuleBackend", "java")
                    // Timeout recovery and cold-start measurements replace a
                    // context inside the same JVM. Every context in that
                    // process must isolate native modules before _ctypes can be
                    // loaded again.
                    .allowExperimentalOptions(true)
                    .option("python.IsolateNativeModules", "true")
                    .option("python.DontWriteBytecodeFlag", "true")
                    .build();
                Source source = Source.newBuilder(
                    "python",
                    SymPyScript.source(),
                    "<regelsuche-sympy-adapter>")
                    .internal(true)
                    .build();
                Value adapter = context.eval(source);
                Value factorFunction = adapter.getMember("factor_payload");
                Value runtimeInfo = adapter.getMember("runtime_info");
                if (!adapter.hasMembers()
                        || factorFunction == null
                        || !factorFunction.canExecute()
                        || runtimeInfo == null
                        || !runtimeInfo.canExecute()) {
                    throw new IllegalStateException(
                        "embedded SymPy adapter export object is unavailable");
                }
                String version = runtimeVersion(
                    runtimeInfo.execute().asString());
                return new Worker(
                    context,
                    factorFunction,
                    version);
            } catch (IOException | RuntimeException exception) {
                if (context != null) {
                    try {
                        context.close(true);
                    } catch (RuntimeException closeFailure) {
                        exception.addSuppressed(closeFailure);
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
