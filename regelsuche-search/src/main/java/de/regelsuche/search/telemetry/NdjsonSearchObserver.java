package de.regelsuche.search.telemetry;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Objects;

/** Writes each runtime search event as one deterministic NDJSON line. */
public final class NdjsonSearchObserver implements SearchObserver, AutoCloseable {
    private final BufferedWriter writer;
    private boolean closed;

    public NdjsonSearchObserver(Path outputFile) {
        Objects.requireNonNull(outputFile, "outputFile");
        try {
            Path parent = outputFile.toAbsolutePath().normalize().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            this.writer = Files.newBufferedWriter(
                outputFile,
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE
            );
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    @Override
    public synchronized void onEvent(SearchEvent event) {
        if (closed) {
            throw new IllegalStateException("observer is already closed");
        }
        try {
            writer.write(SearchEventJson.toJson(event));
            writer.newLine();
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        try {
            writer.flush();
            writer.close();
            closed = true;
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }
}
