package de.regelsuche.math.sympy;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/** Package-local bounded external-process transport for the control backend. */
final class SymPyProcessRunner {
    private SymPyProcessRunner() {
    }

    static Output run(
        List<String> command,
        byte[] input,
        Duration timeout,
        int maxStdoutBytes,
        int maxStderrBytes
    ) {
        long started = System.nanoTime();
        Process process;
        try {
            process = new ProcessBuilder(command).start();
        } catch (IOException exception) {
            return Output.unavailable(
                exception.getClass().getSimpleName() + ": "
                    + message(exception),
                System.nanoTime() - started);
        }

        try (ExecutorService executor =
                Executors.newVirtualThreadPerTaskExecutor()) {
            Future<BoundedBytes> stdout = executor.submit(() ->
                readBounded(process.getInputStream(), maxStdoutBytes));
            Future<BoundedBytes> stderr = executor.submit(() ->
                readBounded(process.getErrorStream(), maxStderrBytes));
            Future<Void> writer = executor.submit(() -> {
                try (var target = process.getOutputStream()) {
                    target.write(input);
                }
                return null;
            });

            boolean finished = process.waitFor(
                timeout.toMillis(),
                TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
            }
            try {
                writer.get();
            } catch (ExecutionException exception) {
                if (finished) {
                    throw exception;
                }
            }
            BoundedBytes capturedStdout = stdout.get();
            BoundedBytes capturedStderr = stderr.get();
            return new Output(
                true,
                !finished,
                finished ? process.exitValue() : -1,
                capturedStdout.text(),
                capturedStderr.text(),
                capturedStdout.totalBytes(),
                capturedStderr.totalBytes(),
                capturedStdout.truncated() || capturedStderr.truncated(),
                System.nanoTime() - started);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            return Output.unavailable(
                "InterruptedException",
                System.nanoTime() - started);
        } catch (ExecutionException exception) {
            process.destroyForcibly();
            Throwable cause = exception.getCause() != null
                ? exception.getCause()
                : exception;
            return Output.unavailable(
                cause.getClass().getSimpleName() + ": " + message(cause),
                System.nanoTime() - started);
        }
    }

    private static BoundedBytes readBounded(
        InputStream source,
        int maximum
    ) throws IOException {
        ByteArrayOutputStream retained = new ByteArrayOutputStream(
            Math.min(maximum, 16 * 1024));
        byte[] buffer = new byte[8 * 1024];
        int total = 0;
        boolean truncated = false;
        int read;
        while ((read = source.read(buffer)) >= 0) {
            total = Math.addExact(total, read);
            int remaining = maximum - retained.size();
            if (remaining > 0) {
                retained.write(buffer, 0, Math.min(remaining, read));
            }
            if (total > maximum) {
                truncated = true;
            }
        }
        return new BoundedBytes(
            retained.toString(StandardCharsets.UTF_8),
            total,
            truncated);
    }

    private static String message(Throwable throwable) {
        String value = throwable.getMessage();
        return value == null ? "" : value.replaceAll("\\s+", " ").strip();
    }

    record Output(
        boolean available,
        boolean timedOut,
        int exitCode,
        String stdout,
        String stderr,
        int stdoutBytes,
        int stderrBytes,
        boolean outputLimitExceeded,
        long endToEndNanos
    ) {
        Output {
            stdout = stdout == null ? "" : stdout;
            stderr = stderr == null ? "" : stderr;
            if (exitCode < -1
                    || stdoutBytes < 0
                    || stderrBytes < 0
                    || endToEndNanos < 0) {
                throw new IllegalArgumentException(
                    "SymPy process output is invalid");
            }
        }

        static Output unavailable(String detail, long elapsed) {
            String checked = detail == null ? "" : detail;
            return new Output(
                false,
                false,
                -1,
                "",
                checked,
                0,
                checked.getBytes(StandardCharsets.UTF_8).length,
                false,
                Math.max(0, elapsed));
        }
    }

    private record BoundedBytes(
        String text,
        int totalBytes,
        boolean truncated
    ) {
    }
}
