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
 * Separates raw process execution from checked mathematical evidence for
 * Lean and Z3. Custom constructors are transport-only, even with a success predicate.
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
    private final CheckedKind checkedKind;
    private final Path leanProject;
    private final Path evidenceRoot;
    private enum CheckedKind { NONE, LEAN, Z3 }


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
        this.checkedKind = CheckedKind.NONE;
        this.leanProject = null;
        this.evidenceRoot = null;
        this.command = List.copyOf(command);
        this.toolName = toolName;
        this.artifactSuffix = artifactSuffix;
        this.timeoutMillis = Math.max(100L, timeout.toMillis());
        this.successPredicate = successPredicate;
    }

    private ProverExecutor(CheckedKind kind, Path project, Path evidence) {
        this.checkedKind = kind;
        this.leanProject = project;
        this.evidenceRoot = Objects.requireNonNull(evidence).toAbsolutePath().normalize();
        this.toolName = kind == CheckedKind.LEAN ? "lean4" : "smtlib2";
        this.command = List.of();
        this.artifactSuffix = "";
        this.timeoutMillis = 20_000L;
        this.successPredicate = defaultSuccess();
    }

    /** Only factory-created, semantically checked backends can grant proof status. */
    boolean checksMathematicalEvidence() { return checkedKind != CheckedKind.NONE; }

    public String toolName() {
        return toolName;
    }

    public ProverExecutionResult execute(String artifact) {
        if (checksMathematicalEvidence()) return checkedExecute(artifact, null);
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
            ok ? ProverExecutionResult.Status.PROCESS_SUCCEEDED : ProverExecutionResult.Status.PROVER_FAILED,
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

    /** Uses a configured, pinned Lean/mathlib project; never falls back to a raw exit check. */
    public static ProverExecutor lean() {
        String project = System.getProperty("regelsuche.lean.project", System.getenv("REGELSUCHE_LEAN_PROJECT"));
        return new ProverExecutor(CheckedKind.LEAN,
            project == null || project.isBlank() ? null : Path.of(project), defaultEvidence());
    }

    public static ProverExecutor lean(Path project, Path evidenceRoot) {
        return new ProverExecutor(CheckedKind.LEAN, Objects.requireNonNull(project), evidenceRoot);
    }

    public static ProverExecutor z3() { return z3(defaultEvidence()); }
    public static ProverExecutor z3(Path evidenceRoot) {
        return new ProverExecutor(CheckedKind.Z3, null, evidenceRoot);
    }

    /** Raw cvc5 transport. It does not grant proof status without a checked artifact adapter. */
    public static ProverExecutor cvc5() {
        return new ProverExecutor(List.of("cvc5", "--lang=smt2"), "smtlib2", ".smt2",
            Duration.ofSeconds(20), (exit, out, err) -> exit == 0);
    }

    private static Path defaultEvidence() {
        return Path.of(System.getProperty("regelsuche.proof.evidence", "proof-evidence"));
    }

    ProverExecutionResult executeBound(String artifact, de.regelsuche.solver.ir.SolverIr.Obligation expected) {
        return checksMathematicalEvidence() ? checkedExecute(artifact, Objects.requireNonNull(expected)) : execute(artifact);
    }

    private ProverExecutionResult checkedExecute(String artifact,
            de.regelsuche.solver.ir.SolverIr.Obligation expected) {
        long start = System.currentTimeMillis();
        try {
            var request = checkedKind == CheckedKind.LEAN
                ? de.regelsuche.solver.portfolio.LeanSolverBackend.readArtifact(artifact)
                : de.regelsuche.solver.portfolio.SmtProofArtifacts.readArtifact(artifact);
            if (expected != null && !request.contentHash().equals(expected.contentHash()))
                throw new IllegalArgumentException("artifact does not match the current goal and premises");
            de.regelsuche.solver.ir.SolverExecution execution;
            Path directory;
            if (checkedKind == CheckedKind.LEAN) {
                if (leanProject == null) return result(ProverExecutionResult.Status.PROVER_NOT_AVAILABLE,
                    -1, "", "Configure a pinned Lean/mathlib project", start);
                var attempt = new de.regelsuche.solver.portfolio.LeanSolverBackend(leanProject, evidenceRoot)
                    .executeWithEvidence(request);
                execution = attempt.execution(); directory = attempt.directory();
            } else {
                var detection = de.regelsuche.solver.portfolio.Z3SmtSolverBackend.detectSystemZ3();
                if (detection.availability() != de.regelsuche.solver.portfolio.BackendAvailability.AVAILABLE)
                    return result(ProverExecutionResult.Status.PROVER_NOT_AVAILABLE, -1, "", detection.detail(), start);
                var attempt = detection.backend().executeWithEvidence(request, evidenceRoot);
                execution = attempt.execution(); directory = attempt.directory();
            }
            var answer = execution.result();
            boolean bound = execution.obligationHash().equals(request.contentHash())
                && answer.goalHash().equals(request.goalHash())
                && answer.assumptionsHash().equals(request.assumptionsHash());
            boolean proved = bound && answer.status() == de.regelsuche.solver.ir.SolverIr.ResultStatus.CONFIRMED
                && answer.translationStatus() == de.regelsuche.solver.ir.SolverIr.TranslationStatus.LOSSLESS
                && answer.translationIssues().isEmpty() && !answer.certificateHash().isEmpty();
            ProverExecutionResult.Status status = proved ? ProverExecutionResult.Status.PROVER_CONFIRMED
                : answer.status() == de.regelsuche.solver.ir.SolverIr.ResultStatus.TIMEOUT
                    ? ProverExecutionResult.Status.PROVER_TIMEOUT : ProverExecutionResult.Status.PROVER_FAILED;
            return result(status, proved ? 0 : -1,
                answer.toCanonicalJson() + "\nEvidence: " + directory,
                proved ? "" : answer.message(), start);
        } catch (Exception failure) {
            if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
            return result(ProverExecutionResult.Status.PROVER_FAILED, -1, "", failure.toString(), start);
        }
    }

    private ProverExecutionResult result(ProverExecutionResult.Status status, int exit,
                                         String out, String err, long start) {
        return new ProverExecutionResult(status, exit, out, err, System.currentTimeMillis() - start, toolName);
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
