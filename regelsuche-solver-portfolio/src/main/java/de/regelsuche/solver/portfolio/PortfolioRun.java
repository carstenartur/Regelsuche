package de.regelsuche.solver.portfolio;

import de.regelsuche.solver.ir.SolverExecution;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
        if (selectedExecution != null
                && !report.selectedExecutionHash().equals(selectedExecution.contentHash())) {
            throw new IllegalArgumentException(
                "selected execution must match portfolio report");
        }
    }

    /**
     * Writes one authoritative layout: `report.json` plus every concrete
     * execution under `executions/`. Filtered and skipped attempts remain in
     * the report and deliberately have no fabricated execution artifact.
     */
    public void write(Path outputDirectory) {
        Objects.requireNonNull(outputDirectory, "outputDirectory");
        try {
            Path executionsDirectory = outputDirectory.resolve("executions");
            Files.createDirectories(executionsDirectory);
            Files.writeString(
                outputDirectory.resolve("report.json"),
                report.toCanonicalJson(),
                StandardCharsets.UTF_8);
            for (SolverExecution execution : executions) {
                PortfolioAttempt attempt = report.attempts().stream()
                    .filter(item -> execution.contentHash().equals(item.executionHash()))
                    .findFirst()
                    .orElseThrow();
                String filename = String.format(
                    "%03d-%s.json",
                    attempt.sequence(),
                    safeFilename(attempt.backendId()));
                Files.writeString(
                    executionsDirectory.resolve(filename),
                    execution.toCanonicalJson(),
                    StandardCharsets.UTF_8);
            }
        } catch (IOException exception) {
            throw new UncheckedIOException(
                "Could not write solver portfolio evidence", exception);
        }
    }

    private static String safeFilename(String value) {
        return value.replaceAll("[^A-Za-z0-9._-]", "_");
    }
}
