package de.regelsuche.benchmarks;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/** Small trusted-process adapter shared by external benchmark baselines. */
final class ExternalCommand {
    private ExternalCommand() {
    }

    static Output run(List<String> command, Duration timeout) {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(timeout, "timeout");
        if (command.isEmpty()) {
            throw new IllegalArgumentException("command must not be empty");
        }
        long timeoutMillis = Math.max(100L, timeout.toMillis());
        Process process;
        try {
            process = new ProcessBuilder(command)
                .redirectErrorStream(false)
                .start();
        } catch (IOException exception) {
            return new Output(false, false, -1, "", exception.getMessage());
        }
        try {
            process.getOutputStream().close();
        } catch (IOException ignored) {
            // The benchmark commands do not use stdin.
        }

        boolean finished;
        try {
            finished = process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            return new Output(
                true, false, -1, "", "Interrupted while waiting for external command");
        }
        if (!finished) {
            process.destroyForcibly();
            try {
                process.waitFor(2, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            return new Output(
                true,
                true,
                -1,
                read(process.getInputStream()),
                read(process.getErrorStream()));
        }
        return new Output(
            true,
            false,
            process.exitValue(),
            read(process.getInputStream()),
            read(process.getErrorStream()));
    }

    private static String read(java.io.InputStream stream) {
        try (stream) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            return "";
        }
    }

    record Output(
        boolean available,
        boolean timedOut,
        int exitCode,
        String stdout,
        String stderr
    ) {
        Output {
            stdout = stdout == null ? "" : stdout;
            stderr = stderr == null ? "" : stderr;
        }
    }
}
