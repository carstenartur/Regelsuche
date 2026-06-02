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
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.logging.Logger;

/**
 * Watches the plugins/ and rules/ directories for file system changes and triggers
 * a debounced reload on the provided {@link PluginRuntime}.
 */
public final class PluginDirectoryWatcher implements Closeable {
    private static final Logger LOGGER = Logger.getLogger(PluginDirectoryWatcher.class.getName());
    private static final Duration DEFAULT_DEBOUNCE = Duration.ofMillis(300);

    private final PluginRuntime runtime;
    private final Duration debounce;
    private final Consumer<PluginReloadResult> listener;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "plugin-dir-watcher");
        t.setDaemon(true);
        return t;
    });
    private WatchService watchService;
    private Thread watchThread;
    private volatile ScheduledFuture<?> pending;
    private volatile boolean running;

    public PluginDirectoryWatcher(PluginRuntime runtime) {
        this(runtime, DEFAULT_DEBOUNCE, result -> {});
    }

    public PluginDirectoryWatcher(PluginRuntime runtime, Duration debounce, Consumer<PluginReloadResult> listener) {
        this.runtime = runtime;
        this.debounce = debounce;
        this.listener = listener;
    }

    public void start() throws IOException {
        watchService = FileSystems.getDefault().newWatchService();
        registerIfExists(runtime.config().pluginsDirectory());
        registerIfExists(runtime.config().rulesDirectory());
        running = true;
        watchThread = new Thread(this::watchLoop, "plugin-dir-watch-loop");
        watchThread.setDaemon(true);
        watchThread.start();
    }

    private void registerIfExists(Path dir) throws IOException {
        if (dir != null && Files.isDirectory(dir)) {
            dir.register(watchService,
                StandardWatchEventKinds.ENTRY_CREATE,
                StandardWatchEventKinds.ENTRY_MODIFY,
                StandardWatchEventKinds.ENTRY_DELETE);
        }
    }

    private void watchLoop() {
        while (running) {
            WatchKey key;
            try {
                key = watchService.poll(500, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (ClosedWatchServiceException e) {
                break;
            }
            if (key == null) {
                continue;
            }
            key.pollEvents();
            key.reset();
            scheduleReload();
        }
    }

    private void scheduleReload() {
        ScheduledFuture<?> existing = pending;
        if (existing != null) {
            existing.cancel(false);
        }
        pending = scheduler.schedule(() -> {
            try {
                PluginReloadResult result = runtime.reloadWithResult();
                listener.accept(result);
            } catch (Exception ex) {
                LOGGER.warning("Hot reload failed: " + ex.getMessage());
            }
        }, debounce.toMillis(), TimeUnit.MILLISECONDS);
    }

    @Override
    public void close() throws IOException {
        running = false;
        scheduler.shutdownNow();
        if (watchService != null) {
            watchService.close();
        }
        if (watchThread != null) {
            watchThread.interrupt();
        }
    }
}
