package de.regelsuche.discovery.representation;

import static de.regelsuche.discovery.representation.ReferenceIndependentCandidateValidation.JSON;
import static de.regelsuche.discovery.representation.ReferenceIndependentCandidateValidation.hash;

import de.regelsuche.validation.OracleValidator;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** One bounded JVM oracle session. A failed session is never silently restarted. */
final class ReferenceIndependentOracleSession implements OracleValidator, AutoCloseable {
    static final String READY = "REFERENCE_INDEPENDENT_ORACLE_READY_V1";
    private final int timeoutMillis;
    private final List<String> command;
    private final ExecutorService io = Executors.newSingleThreadExecutor(
        Thread.ofPlatform().daemon().name("reference-independent-oracle-io").factory());
    private Process process;
    private BufferedReader output;
    private BufferedWriter input;
    private boolean ready;
    private boolean closed;

    ReferenceIndependentOracleSession(int timeoutMillis) {
        this(timeoutMillis, javaCommand(ReferenceIndependentOracleWorker.class.getName()));
    }

    ReferenceIndependentOracleSession(int timeoutMillis, List<String> command) {
        if (timeoutMillis < 1 || timeoutMillis > 60_000) {
            throw new IllegalArgumentException("invalid oracle timeout");
        }
        this.timeoutMillis = timeoutMillis;
        this.command = List.copyOf(command);
    }

    @Override
    public synchronized OracleValidation validateEquivalence(String left, String right) {
        if (closed) {
            return OracleValidation.unavailable("oracle session unavailable after termination");
        }
        if ((long) left.length() + right.length() > 8192) {
            return OracleValidation.unavailable("oracle input exceeds bounded protocol size");
        }
        long deadline = deadline();
        startUntil(deadline);
        String requestHash = hash(List.of(left, right));
        return await(() -> {
            input.write(JSON.writeValueAsString(new Request(left, right, requestHash)));
            input.newLine();
            input.flush();
            Response response = JSON.readValue(readBoundedLine(output), Response.class);
            if (!requestHash.equals(response.requestHash()) || response.status() == null
                    || response.evidence() == null) {
                throw new IOException("oracle response does not bind request");
            }
            return new OracleValidation(response.status(), response.evidence());
        }, deadline);
    }

    synchronized void start() {
        startUntil(deadline());
    }

    Process process() {
        if (process == null) {
            throw new IllegalStateException("oracle has not started");
        }
        return process;
    }

    private long deadline() {
        return System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
    }

    private void startUntil(long deadline) {
        if (ready) {
            return;
        }
        if (closed) {
            throw new OracleTransportException("oracle session is closed");
        }
        try {
            process = new ProcessBuilder(command)
                .redirectError(ProcessBuilder.Redirect.DISCARD).start();
            output = new BufferedReader(new InputStreamReader(
                process.getInputStream(), StandardCharsets.UTF_8));
            input = new BufferedWriter(new OutputStreamWriter(
                process.getOutputStream(), StandardCharsets.UTF_8));
            await(() -> {
                if (!READY.equals(readBoundedLine(output))) {
                    throw new IOException("oracle readiness protocol mismatch");
                }
                return true;
            }, deadline);
            ready = true;
        } catch (IOException exception) {
            close();
            throw new OracleTransportException("oracle process could not start");
        }
    }

    private <T> T await(Callable<T> action, long deadline) {
        var pending = io.submit(action);
        try {
            return pending.get(Math.max(0L, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
        } catch (TimeoutException exception) {
            pending.cancel(true);
            close();
            throw new OracleTimeoutException("oracle invocation exceeded configured deadline");
        } catch (InterruptedException exception) {
            pending.cancel(true);
            close();
            Thread.currentThread().interrupt();
            throw new OracleTransportException("oracle invocation interrupted");
        } catch (ExecutionException exception) {
            close();
            throw new OracleTransportException("oracle protocol or process failure");
        }
    }

    @Override
    public synchronized void close() {
        closed = true;
        if (process != null) {
            // This fixed local worker starts no children. Retain ownership via
            // Process rather than resolving unrelated operating-system PIDs.
            process.destroyForcibly();
            try {
                process.waitFor(5, TimeUnit.SECONDS);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        }
        closePipe(input);
        closePipe(output);
        if (process != null) {
            closePipe(process.getErrorStream());
        }
        io.shutdownNow();
    }

    private static void closePipe(Closeable pipe) {
        if (pipe != null) {
            try {
                pipe.close();
            } catch (IOException ignored) {
                // Broken pipes during cleanup must not replace retained failure evidence.
            }
        }
    }

    static String readBoundedLine(BufferedReader reader) throws IOException {
        StringBuilder line = new StringBuilder();
        for (int next; (next = reader.read()) != -1;) {
            if (next == '\n') {
                return line.toString();
            }
            if (line.length() >= 65_536) {
                throw new IOException("oracle protocol line exceeds limit");
            }
            line.append((char) next);
        }
        throw new IOException("oracle exited before completing a response");
    }

    static List<String> javaCommand(String mainClass, String... arguments) {
        var paths = new LinkedHashSet<String>(Arrays.asList(
            System.getProperty("java.class.path").split(File.pathSeparator)));
        for (ClassLoader loader = ReferenceIndependentOracleSession.class.getClassLoader();
                loader != null; loader = loader.getParent()) {
            if (loader instanceof URLClassLoader urls) {
                for (var url : urls.getURLs()) {
                    if (url.getProtocol().equals("file")) {
                        try {
                            paths.add(Path.of(url.toURI()).toString());
                        } catch (java.net.URISyntaxException exception) {
                            throw new IllegalStateException("invalid runtime classpath URL", exception);
                        }
                    }
                }
            }
        }
        String executable = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
        var result = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"),
            "bin", executable).toString(), "-Xmx192m", "-Dfile.encoding=UTF-8",
            "-cp", String.join(File.pathSeparator, paths), mainClass));
        result.addAll(List.of(arguments));
        return List.copyOf(result);
    }

    record Request(String source, String candidate, String requestHash) {
    }

    record Response(String requestHash, OracleValidationStatus status, String evidence) {
    }

    static final class OracleTimeoutException extends RuntimeException {
        OracleTimeoutException(String message) {
            super(message);
        }
    }

    static final class OracleTransportException extends RuntimeException {
        OracleTransportException(String message) {
            super(message);
        }
    }
}
