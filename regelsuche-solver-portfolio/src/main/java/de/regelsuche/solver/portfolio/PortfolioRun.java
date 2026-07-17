package de.regelsuche.solver.portfolio;

import de.regelsuche.solver.ir.SolverExecution;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Runtime bundle retaining and canonically writing every backend execution. */
public record PortfolioRun(
    PortfolioReport report,
    List<SolverExecution> executions,
    SolverExecution selectedExecution
) {
    public PortfolioRun {
        Objects.requireNonNull(report, "report");
        executions = executions == null ? List.of() : List.copyOf(executions);
        Set<String> executionHashes = new HashSet<>();
        for (SolverExecution execution : executions) {
            Objects.requireNonNull(execution, "execution");
            if (!executionHashes.add(execution.contentHash())) {
                throw new IllegalArgumentException(
                    "portfolio executions must have unique content hashes");
            }
            boolean traced = report.attempts().stream().anyMatch(attempt ->
                execution.contentHash().equals(attempt.executionHash()));
            if (!traced) {
                throw new IllegalArgumentException(
                    "every retained execution must appear in the portfolio trace");
            }
        }
        long tracedExecutions = report.attempts().stream()
            .filter(attempt -> !attempt.executionHash().isEmpty())
            .map(PortfolioAttempt::executionHash)
            .distinct()
            .count();
        if (tracedExecutions != executions.size()) {
            throw new IllegalArgumentException(
                "every traced execution must be retained by the portfolio run");
        }
        boolean reportSelected = !report.selectedExecutionHash().isEmpty();
        if (reportSelected != (selectedExecution != null)) {
            throw new IllegalArgumentException(
                "selected execution presence must match the portfolio report");
        }
        if (selectedExecution != null
                && !report.selectedExecutionHash().equals(selectedExecution.contentHash())) {
            throw new IllegalArgumentException(
                "selected execution must match portfolio report");
        }
    }

    /**
     * Writes one authoritative evidence layout for the exact request:
     * obligation, request, report, and every concrete translation/result/execution.
     * Filtered and skipped attempts remain in the report and deliberately have no
     * fabricated backend artifact.
     */
    public void write(Path outputDirectory, PortfolioRequest request) {
        Objects.requireNonNull(outputDirectory, "outputDirectory");
        Objects.requireNonNull(request, "request");
        validateRequest(request);
        try {
            clearDirectory(outputDirectory);
            Files.createDirectories(outputDirectory);
            Files.writeString(
                outputDirectory.resolve("obligation.json"),
                request.obligation().toCanonicalJson(),
                StandardCharsets.UTF_8);
            Files.writeString(
                outputDirectory.resolve("request.json"),
                request.toCanonicalJson(),
                StandardCharsets.UTF_8);
            Files.writeString(
                outputDirectory.resolve("report.json"),
                report.toCanonicalJson(),
                StandardCharsets.UTF_8);

            Path executionsDirectory = outputDirectory.resolve("executions");
            Files.createDirectories(executionsDirectory);
            for (SolverExecution execution : executions) {
                PortfolioAttempt attempt = report.attempts().stream()
                    .filter(item -> execution.contentHash().equals(item.executionHash()))
                    .findFirst()
                    .orElseThrow();
                Path attemptDirectory = executionsDirectory.resolve(String.format(
                    "%03d-%s",
                    attempt.sequence(),
                    safeFilename(attempt.backendId())));
                Files.createDirectories(attemptDirectory);
                Files.writeString(
                    attemptDirectory.resolve("translation.json"),
                    execution.translation().toCanonicalJson(),
                    StandardCharsets.UTF_8);
                Files.writeString(
                    attemptDirectory.resolve("result.json"),
                    execution.result().toCanonicalJson(),
                    StandardCharsets.UTF_8);
                Files.writeString(
                    attemptDirectory.resolve("execution.json"),
                    execution.toCanonicalJson(),
                    StandardCharsets.UTF_8);
            }
        } catch (IOException exception) {
            throw new UncheckedIOException(
                "Could not write solver portfolio evidence", exception);
        }
    }

    private void validateRequest(PortfolioRequest request) {
        if (!report.requestHash().equals(request.contentHash())) {
            throw new IllegalArgumentException(
                "portfolio request must match the report request hash");
        }
        if (!report.obligationHash().equals(request.obligation().contentHash())) {
            throw new IllegalArgumentException(
                "portfolio request obligation must match the report");
        }
        for (SolverExecution execution : executions) {
            if (!execution.obligationHash().equals(request.obligation().contentHash())) {
                throw new IllegalArgumentException(
                    "portfolio execution belongs to another obligation");
            }
        }
    }

    private static void clearDirectory(Path directory) throws IOException {
        if (!Files.exists(directory)) {
            return;
        }
        try (var paths = Files.walk(directory)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(path);
            }
        }
    }

    private static String safeFilename(String value) {
        return value.replaceAll("[^A-Za-z0-9._-]", "_");
    }
}
