package de.regelsuche.proof;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * Executes a generated prover artifact against a real external tool such as
 * {@code lean} or {@code z3}.
 *
 * <p>The executor writes the script to a temporary file, invokes the tool
 * with a hard timeout, captures {@code stdout}/{@code stderr} and reports
 * the resulting {@link ProverExecutionResult.Status}. A missing executable
 * is reported as {@link ProverExecutionResult.Status#PROVER_NOT_AVAILABLE}
 * rather than thrown so that callers can degrade gracefully.</p>
 *
 * <p>Security note: the executor invokes a configured command verbatim. It
 * is intended for trusted developer-side use; callers must not expose it
 * to untrusted input on the command-line level.</p>
 */
public final class ProverExecutor {
    private static final long DEFAULT_TIMEOUT_MILLIS = 15_000L;

    private final List<String> command;
    private final String toolName;
    private final String artifactSuffix;
    private final long timeoutMillis;
    private final SuccessPredicate successPredicate;

    public ProverExecutor(List<String> command, String toolName, String artifactSuffix) {
        this(command, toolName, artifactSuffix, Duration.ofMillis(DEFAULT_TIMEOUT_MILLIS), defaultSuccess());
    }

    public ProverExecutor(
        List<String> command,
        String toolName,
        String artifactSuffix,
        Duration timeout,
        SuccessPredicate successPredicate
    ) {
        Objects.requireNonNull(command, "command");
        if (command.isEmpty()) {
            throw new IllegalArgumentException("command must not be empty");
        }
        Objects.requireNonNull(toolName, "toolName");
        Objects.requireNonNull(artifactSuffix, "artifactSuffix");
        Objects.requireNonNull(timeout, "timeout");
        Objects.requireNonNull(successPredicate, "successPredicate");
        this.command = List.copyOf(command);
        this.toolName = toolName;
        this.artifactSuffix = artifactSuffix;
        this.timeoutMillis = Math.max(100L, timeout.toMillis());
        this.successPredicate = successPredicate;
    }

    public String toolName() {
        return toolName;
    }

    public ProverExecutionResult execute(String artifact) {
        Path scriptFile;
        try {
            scriptFile = Files.createTempFile("regelsuche_prover_", artifactSuffix);
            Files.writeString(scriptFile, artifact, StandardCharsets.UTF_8);
        } catch (IOException ex) {
            return new ProverExecutionResult(
                ProverExecutionResult.Status.PROVER_FAILED,
                -1,
                "",
                "Failed to write prover script: " + ex.getMessage(),
                0L,
                toolName
            );
        }

        try {
            return runProcess(scriptFile);
        } finally {
            try {
                Files.deleteIfExists(scriptFile);
            } catch (IOException ignored) {
                // best-effort cleanup
            }
        }
    }

    private ProverExecutionResult runProcess(Path scriptFile) {
        java.util.List<String> fullCommand = new java.util.ArrayList<>(command.size() + 1);
        fullCommand.addAll(command);
        fullCommand.add(scriptFile.toAbsolutePath().toString());

        ProcessBuilder builder = new ProcessBuilder(fullCommand)
            .redirectErrorStream(false);
        long start = System.currentTimeMillis();
        Process process;
        try {
            process = builder.start();
        } catch (IOException ex) {
            return new ProverExecutionResult(
                ProverExecutionResult.Status.PROVER_NOT_AVAILABLE,
                -1,
                "",
                "Could not start '" + command.get(0) + "': " + ex.getMessage(),
                System.currentTimeMillis() - start,
                toolName
            );
        }
        try {
            process.getOutputStream().close();
        } catch (IOException ignored) {
            // process may not accept stdin; ignore
        }

        boolean finished;
        try {
            finished = process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            return new ProverExecutionResult(
                ProverExecutionResult.Status.PROVER_FAILED,
                -1,
                "",
                "Interrupted while waiting for prover",
                System.currentTimeMillis() - start,
                toolName
            );
        }
        if (!finished) {
            process.destroyForcibly();
            try {
                process.waitFor(2, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            return new ProverExecutionResult(
                ProverExecutionResult.Status.PROVER_TIMEOUT,
                -1,
                readQuietly(process.getInputStream()),
                readQuietly(process.getErrorStream()),
                System.currentTimeMillis() - start,
                toolName
            );
        }

        int exitCode = process.exitValue();
        String stdout = readQuietly(process.getInputStream());
        String stderr = readQuietly(process.getErrorStream());
        long duration = System.currentTimeMillis() - start;
        boolean ok = successPredicate.isSuccess(exitCode, stdout, stderr);
        return new ProverExecutionResult(
            ok ? ProverExecutionResult.Status.PROVER_CONFIRMED : ProverExecutionResult.Status.PROVER_FAILED,
            exitCode,
            stdout,
            stderr,
            duration,
            toolName
        );
    }

    private static String readQuietly(java.io.InputStream stream) {
        try (stream) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            return "";
        }
    }

    /**
     * @return a {@link ProverExecutor} for the Lean 4 elaborator
     *         ({@code lean}). Success is exit code 0 with no error/warning
     *         containing {@code "sorry"} (so a {@code sorry}-only skeleton is
     *         not counted as proved).
     */
    public static ProverExecutor lean() {
        return new ProverExecutor(
            List.of("lean"),
            "lean4",
            ".lean",
            Duration.ofSeconds(20),
            (exit, out, err) -> exit == 0 && !(out + err).toLowerCase().contains("sorry")
        );
    }

    /** @return a {@link ProverExecutor} for {@code z3 -smt2 <file>}. */
    public static ProverExecutor z3() {
        return new ProverExecutor(
            List.of("z3", "-smt2"),
            "smtlib2",
            ".smt2",
            Duration.ofSeconds(20),
            (exit, out, err) -> exit == 0 && out.toLowerCase().contains("unsat")
        );
    }

    /** @return a {@link ProverExecutor} for {@code cvc5 --lang=smt2 <file>}. */
    public static ProverExecutor cvc5() {
        return new ProverExecutor(
            List.of("cvc5", "--lang=smt2"),
            "smtlib2",
            ".smt2",
            Duration.ofSeconds(20),
            (exit, out, err) -> exit == 0 && out.toLowerCase().contains("unsat")
        );
    }

    private static SuccessPredicate defaultSuccess() {
        return (exit, out, err) -> exit == 0;
    }

    /** Pluggable predicate for deciding whether the prover output indicates success. */
    @FunctionalInterface
    public interface SuccessPredicate {
        boolean isSuccess(int exitCode, String stdout, String stderr);
    }
}
