package de.regelsuche.math.sympy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.regelsuche.python.runtime.ManagedPythonRuntime;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Engine;
import org.graalvm.polyglot.HostAccess;
import org.graalvm.polyglot.PolyglotAccess;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.Value;
import org.graalvm.python.embedding.GraalPyResources;
import org.graalvm.python.embedding.VirtualFileSystem;

/**
 * Native-enabled SymPy adapter over the shared serialized Python lifecycle.
 *
 * <p>Only queueing, deadlines and session retirement are shared. The engine, package extraction,
 * native-module settings, permissions, script and mathematical wire contract remain owned here.
 * GraalPy native extensions still execute on a dedicated platform thread. Retired workers own only
 * their context, never the shared engine or a successor's context.</p>
 */
final class GraalPySymPyRuntime implements AutoCloseable {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String RUNTIME_ID = "graalpy-embedded";
    private final Engine engine;
    private final Path externalResourcesDirectory;
    private final ManagedPythonRuntime runtime;
    private volatile boolean closed;

    GraalPySymPyRuntime() {
        Path extracted = extractResources();
        Engine createdEngine = null;
        try {
            createdEngine = Engine.newBuilder("python")
                    .option("engine.WarnInterpreterOnly", "false").build();
            Engine ownedEngine = createdEngine;
            // SymPyFactorizationPolicy already owns the configurable payload byte limits.
            // Do not introduce an undocumented, tighter limit in the shared lifecycle layer.
            runtime = new ManagedPythonRuntime(() -> new Worker(ownedEngine, extracted),
                    Integer.MAX_VALUE, Integer.MAX_VALUE, "regelsuche-graalpy-sympy-");
        } catch (RuntimeException exception) {
            if (createdEngine != null) {
                try { createdEngine.close(); }
                catch (RuntimeException cleanup) { exception.addSuppressed(cleanup); }
            }
            deleteResources(extracted, exception);
            throw exception;
        }
        externalResourcesDirectory = extracted;
        engine = createdEngine;
    }

    SymPyInvocation invoke(String input, Duration timeout) {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(timeout, "timeout");
        if (closed) {
            return SymPyInvocation.failure(SymPyInvocation.Status.UNAVAILABLE,
                    "GRAALPY_RUNTIME_CLOSED", RUNTIME_ID, 0);
        }
        long started = System.nanoTime();
        try {
            var result = runtime.invoke(input, timeout);
            return SymPyInvocation.completed(result.output(), RUNTIME_ID, result.runtimeVersion(),
                    result.coldStart(), result.initializationNanos(), result.invocationNanos());
        } catch (ManagedPythonRuntime.RuntimeFailure failure) {
            return switch (failure.failure()) {
                case TIMEOUT -> SymPyInvocation.failure(SymPyInvocation.Status.TIMEOUT,
                        "GRAALPY_FACTORIZATION_TIMEOUT", RUNTIME_ID, System.nanoTime() - started);
                case INTERRUPTED -> SymPyInvocation.failure(SymPyInvocation.Status.UNAVAILABLE,
                        "GRAALPY_FACTORIZATION_INTERRUPTED", RUNTIME_ID, System.nanoTime() - started, failure);
                case CLOSED -> SymPyInvocation.failure(SymPyInvocation.Status.UNAVAILABLE,
                        "GRAALPY_RUNTIME_CLOSED", RUNTIME_ID, 0);
                case EXECUTION, SIZE_LIMIT -> {
                    Throwable cause = failure.getCause() == null ? failure : failure.getCause();
                    yield SymPyInvocation.failure(SymPyInvocation.Status.TECHNICAL_FAILURE,
                            "GRAALPY_" + cause.getClass().getSimpleName().toUpperCase(Locale.ROOT),
                            RUNTIME_ID, System.nanoTime() - started, cause);
                }
            };
        }
    }

    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        RuntimeException failure = null;
        try { runtime.close(); }
        catch (RuntimeException exception) { failure = exception; }
        try { engine.close(); }
        catch (RuntimeException exception) {
            if (failure == null) failure = exception; else failure.addSuppressed(exception);
        }
        try { deleteResources(externalResourcesDirectory); }
        catch (RuntimeException exception) {
            if (failure == null) failure = exception; else failure.addSuppressed(exception);
        }
        if (failure != null) throw failure;
    }

    private static Path extractResources() {
        Path directory;
        try { directory = Files.createTempDirectory("regelsuche-graalpy-sympy-"); }
        catch (IOException exception) {
            throw new IllegalStateException("temporary GraalPy resource directory cannot be created", exception);
        }
        try (VirtualFileSystem fileSystem = VirtualFileSystem.newBuilder()
                .resourceDirectory(SymPyScript.RESOURCE_DIRECTORY)
                .resourceLoadingClass(SymPyScript.class).build()) {
            GraalPyResources.extractVirtualFileSystemResources(fileSystem, directory);
            return directory;
        } catch (IOException | RuntimeException exception) {
            deleteResources(directory, exception);
            throw new IllegalStateException("embedded GraalPy resources cannot be extracted", exception);
        }
    }

    private static void deleteResources(Path directory, Throwable authoritativeFailure) {
        try { deleteResources(directory); }
        catch (RuntimeException cleanupFailure) { authoritativeFailure.addSuppressed(cleanupFailure); }
    }

    private static void deleteResources(Path directory) {
        if (directory == null || !Files.exists(directory)) return;
        try {
            Files.walkFileTree(directory, new SimpleFileVisitor<>() {
                @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                    Files.deleteIfExists(file);
                    return FileVisitResult.CONTINUE;
                }
                @Override public FileVisitResult postVisitDirectory(Path visitedDirectory, IOException failure) throws IOException {
                    if (failure != null) throw failure;
                    Files.deleteIfExists(visitedDirectory);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException exception) {
            throw new IllegalStateException("temporary GraalPy resources cannot be deleted: " + directory, exception);
        }
    }

    private static String hostExecutablePath() {
        String path = System.getenv("PATH");
        if (path == null || path.isBlank()) {
            throw new IllegalStateException("PATH is required for GraalPy native-module isolation");
        }
        return path;
    }

    private static final class Worker implements ManagedPythonRuntime.Session {
        private final Engine engine;
        private final Path resources;
        private final AtomicReference<Context> active = new AtomicReference<>();
        private final AtomicBoolean retired = new AtomicBoolean();
        private Value factorFunction;

        Worker(Engine engine, Path resources) { this.engine = engine; this.resources = resources; }

        @Override public String initialize() {
            if (retired.get()) throw new IllegalStateException("retired SymPy startup");
            try {
                Context context = Context.newBuilder().engine(engine)
                        // Native-module isolation needs writable private copies, not the read-only VFS.
                        .apply(GraalPyResources.forExternalDirectory(resources))
                        .allowHostAccess(HostAccess.NONE)
                        // Only PATH, for GraalPy's provisioned patchelf; never the full environment.
                        .environment("PATH", hostExecutablePath())
                        // Trusted native-extension initialization owns its GC threads and patchelf.
                        .allowCreateThread(true).allowCreateProcess(true).allowNativeAccess(true)
                        .allowPolyglotAccess(PolyglotAccess.NONE)
                        .option("python.PosixModuleBackend", "java")
                        .allowExperimentalOptions(true).option("python.IsolateNativeModules", "true")
                        .option("python.DontWriteBytecodeFlag", "true").build();
                // Publish before imports/evaluation, so a timeout can cancel a hanging bootstrap.
                active.set(context);
                if (retired.get()) { closeActive(); throw new IllegalStateException("retired SymPy startup"); }
                Source source = Source.newBuilder("python", SymPyScript.source(), "<regelsuche-sympy-adapter>")
                        .internal(true).build();
                Value adapter = context.eval(source);
                factorFunction = adapter.getMember("factor_payload");
                Value runtimeInfo = adapter.getMember("runtime_info");
                if (!adapter.hasMembers() || factorFunction == null || !factorFunction.canExecute()
                        || runtimeInfo == null || !runtimeInfo.canExecute()) {
                    throw new IllegalStateException("embedded SymPy adapter export object is unavailable");
                }
                return runtimeVersion(runtimeInfo.execute().asString());
            } catch (IOException | RuntimeException exception) {
                close();
                throw new IllegalStateException("embedded GraalPy context initialization failed", exception);
            }
        }

        @Override public String invoke(String input) { return factorFunction.execute(input).asString(); }

        @Override public void close() {
            retired.set(true);
            closeActive();
        }

        private void closeActive() {
            Context context = active.getAndSet(null);
            if (context != null) {
                try { context.close(true); }
                catch (RuntimeException ignored) { /* Preserve the adapter's best-effort cancellation contract. */ }
            }
        }

        private static String runtimeVersion(String runtimeInfoJson) {
            try {
                JsonNode info = JSON.readTree(runtimeInfoJson);
                return requiredText(info, "pythonImplementation") + '-' + requiredText(info, "pythonVersion")
                        + "/sympy-" + requiredText(info, "sympyVersion");
            } catch (IOException exception) {
                throw new IllegalStateException("embedded GraalPy runtime metadata is invalid", exception);
            }
        }

        private static String requiredText(JsonNode node, String field) {
            JsonNode value = node == null ? null : node.get(field);
            if (value == null || !value.isTextual() || value.textValue().isBlank()) {
                throw new IllegalStateException("embedded GraalPy runtime field is missing: " + field);
            }
            return value.textValue();
        }
    }
}
