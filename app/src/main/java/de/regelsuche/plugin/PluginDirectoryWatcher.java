package de.regelsuche.plugin;

import java.io.Closeable;
import java.io.IOException;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.logging.Logger;

/**
 * Watches the plugins/ and rules/ directories and reloads the runtime after a
 * complete trailing-edge quiet period.
 *
 * <p>Filesystem observation, debounce and reload execution deliberately share
 * one watch-loop authority. A later event resets the quiet period instead of
 * racing a separately scheduled reload task. Content-neutral duplicate events
 * complete a debounce cycle but do not emit a user-visible reload callback.</p>
 */
public final class PluginDirectoryWatcher implements Closeable {
    private static final Logger LOGGER = Logger.getLogger(
        PluginDirectoryWatcher.class.getName());
    private static final Duration DEFAULT_DEBOUNCE =
        Duration.ofMillis(300);

    private final PluginRuntime runtime;
    private final long debounceNanos;
    private final Consumer<PluginReloadResult> listener;
    private final Consumer<Boolean> cycleListener;
    // Package-private test seam invoked after each WatchKey is drained;
    // every production constructor installs a no-op.
    private final Runnable eventObserver;
    private volatile boolean running;
    private boolean started;
    private WatchService watchService;
    private Thread watchThread;
    private ReportedState reportedState;

    public PluginDirectoryWatcher(PluginRuntime runtime) {
        this(runtime, DEFAULT_DEBOUNCE, result -> { });
    }

    public PluginDirectoryWatcher(
        PluginRuntime runtime,
        Duration debounce,
        Consumer<PluginReloadResult> listener
    ) {
        this(runtime, debounce, listener, changed -> { });
    }

    PluginDirectoryWatcher(
        PluginRuntime runtime,
        Duration debounce,
        Consumer<PluginReloadResult> listener,
        Consumer<Boolean> cycleListener
    ) {
        this(
            runtime,
            debounce,
            listener,
            cycleListener,
            () -> { });
    }

    PluginDirectoryWatcher(
        PluginRuntime runtime,
        Duration debounce,
        Consumer<PluginReloadResult> listener,
        Consumer<Boolean> cycleListener,
        Runnable eventObserver
    ) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.debounceNanos = debounceNanos(debounce);
        this.listener = Objects.requireNonNull(listener, "listener");
        this.cycleListener = Objects.requireNonNull(
            cycleListener,
            "cycleListener");
        this.eventObserver = Objects.requireNonNull(
            eventObserver,
            "eventObserver");
        this.reportedState = ReportedState.from(runtime);
    }

    public synchronized void start() throws IOException {
        if (started) {
            throw new IllegalStateException(
                "plugin directory watcher has already been started");
        }

        WatchService service = FileSystems.getDefault().newWatchService();
        try {
            registerIfExists(
                service,
                runtime.config().pluginsDirectory());
            registerIfExists(service, runtime.config().rulesDirectory());
        } catch (IOException exception) {
            service.close();
            throw exception;
        }

        reportedState = ReportedState.from(runtime);
        watchService = service;
        running = true;
        started = true;
        watchThread = new Thread(
            () -> watchLoop(service),
            "plugin-dir-watch-loop");
        watchThread.setDaemon(true);
        watchThread.start();
    }

    private static long debounceNanos(Duration debounce) {
        Duration required = Objects.requireNonNull(debounce, "debounce");
        if (required.isNegative()) {
            throw new IllegalArgumentException(
                "plugin directory debounce must not be negative");
        }
        try {
            return required.toNanos();
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException(
                "plugin directory debounce is too large",
                exception);
        }
    }

    private static void registerIfExists(
        WatchService service,
        Path directory
    ) throws IOException {
        if (directory != null && Files.isDirectory(directory)) {
            directory.register(
                service,
                StandardWatchEventKinds.ENTRY_CREATE,
                StandardWatchEventKinds.ENTRY_MODIFY,
                StandardWatchEventKinds.ENTRY_DELETE);
        }
    }

    private void watchLoop(WatchService service) {
        try {
            while (running) {
                WatchKey key = service.take();
                observe(key);
                if (!running || !awaitQuietPeriod(service)) {
                    break;
                }
                reloadAndReportCycle();
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        } catch (ClosedWatchServiceException exception) {
            // Closing the WatchService is the normal shutdown signal.
        } finally {
            running = false;
        }
    }

    private boolean awaitQuietPeriod(
        WatchService service
    ) throws InterruptedException {
        while (running) {
            WatchKey next = service.poll(
                debounceNanos,
                TimeUnit.NANOSECONDS);
            if (next == null) {
                return running;
            }
            observe(next);
        }
        return false;
    }

    private void observe(WatchKey key) {
        drain(key);
        eventObserver.run();
    }

    private static void drain(WatchKey key) {
        key.pollEvents();
        key.reset();
    }

    private void reloadAndReportCycle() {
        try {
            PluginReloadResult result = runtime.reloadWithResult();
            ReportedState current = ReportedState.from(result);
            boolean changed = !result.pluginChanges().isEmpty()
                || !result.ruleFileChanges().isEmpty()
                || !current.equals(reportedState);
            reportedState = current;
            if (changed) {
                listener.accept(result);
            }
            cycleListener.accept(changed);
        } catch (Exception exception) {
            LOGGER.warning(
                "Hot reload failed: " + exception.getMessage());
        }
    }

    @Override
    public void close() throws IOException {
        WatchService service;
        Thread thread;
        synchronized (this) {
            running = false;
            service = watchService;
            thread = watchThread;
            watchService = null;
            watchThread = null;
        }

        IOException closeFailure = null;
        if (service != null) {
            try {
                service.close();
            } catch (IOException exception) {
                closeFailure = exception;
            }
        }
        if (thread != null && thread != Thread.currentThread()) {
            thread.interrupt();
            try {
                thread.join();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                IOException interrupted = new IOException(
                    "interrupted while stopping plugin directory watcher",
                    exception);
                if (closeFailure == null) {
                    closeFailure = interrupted;
                } else {
                    closeFailure.addSuppressed(interrupted);
                }
            }
        }
        if (closeFailure != null) {
            throw closeFailure;
        }
    }

    private record ReportedState(
        List<PluginRuntime.RuntimeDiagnostic> diagnostics,
        List<RuleConflictDetector.RuleConflict> conflicts,
        List<RuleConflictDetector.CyclicConflict> cyclicConflicts
    ) {
        private ReportedState {
            diagnostics = List.copyOf(diagnostics);
            conflicts = List.copyOf(conflicts);
            cyclicConflicts = List.copyOf(cyclicConflicts);
        }

        private static ReportedState from(PluginRuntime runtime) {
            return new ReportedState(
                runtime.diagnostics(),
                runtime.conflicts(),
                runtime.cyclicConflicts());
        }

        private static ReportedState from(PluginReloadResult result) {
            return new ReportedState(
                result.diagnostics(),
                result.conflicts(),
                result.cyclicConflicts());
        }
    }
}
