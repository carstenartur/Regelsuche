package de.regelsuche.benchmark;

import de.regelsuche.validation.DiscoveryEvidenceKind;
import de.regelsuche.validation.DiscoveryResultKind;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** Aggregates discovery corpus/replay rows into an operator benchmark dashboard. */
public final class DiscoveryBenchmarkDashboard {
    public List<Row> aggregate(List<DeterministicDiscoveryExperimentRunner.SeedRunReport> reports) {
        Map<String, Accumulator> byOperator = new TreeMap<>();
        for (DeterministicDiscoveryExperimentRunner.SeedRunReport report : reports == null
            ? List.<DeterministicDiscoveryExperimentRunner.SeedRunReport>of()
            : reports) {
            String operator = operator(report);
            byOperator.computeIfAbsent(operator, Accumulator::new).add(report);
        }
        return byOperator.values().stream().map(Accumulator::toRow).toList();
    }

    public String renderMarkdown(List<Row> rows) {
        StringBuilder out = new StringBuilder();
        out.append("| Operator | Cases | Candidates | Bridge | Transformed | Macro learned | Macro reused | False positives | Avg time | Notes |\n");
        out.append("| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |\n");
        for (Row row : rows == null ? List.<Row>of() : rows) {
            out.append("| ")
                .append(escape(row.operator()))
                .append(" | ").append(row.cases())
                .append(" | ").append(row.candidates())
                .append(" | ").append(row.bridge())
                .append(" | ").append(row.transformed())
                .append(" | ").append(row.macroLearned())
                .append(" | ").append(row.macroReused())
                .append(" | ").append(row.falsePositives())
                .append(" | ").append(String.format(Locale.ROOT, "%.1f ms", row.avgTimeMillis()))
                .append(" | ").append(escape(row.notes()))
                .append(" |\n");
        }
        return out.toString();
    }

    private String operator(DeterministicDiscoveryExperimentRunner.SeedRunReport report) {
        return report.seed().tags().stream()
            .filter(tag -> tag.startsWith("operator:"))
            .map(tag -> tag.substring("operator:".length()))
            .findFirst()
            .orElse(report.seed().category());
    }

    private String escape(String value) {
        return value == null ? "" : value.replace("|", "\\|").replace("\n", " ");
    }

    public record Row(
        String operator,
        int cases,
        int candidates,
        int bridge,
        int transformed,
        int macroLearned,
        int macroReused,
        int falsePositives,
        double avgTimeMillis,
        String notes
    ) {
    }

    private static final class Accumulator {
        private final String operator;
        private int cases;
        private int candidates;
        private int bridge;
        private int transformed;
        private int macroLearned;
        private int macroReused;
        private int falsePositives;
        private long elapsedMillis;
        private final Set<String> notes = new java.util.TreeSet<>();

        private Accumulator(String operator) {
            this.operator = operator;
        }

        private void add(DeterministicDiscoveryExperimentRunner.SeedRunReport report) {
            cases++;
            candidates += report.hypotheses().size();
            if (rank(report.resultKind()) >= rank(DiscoveryResultKind.BRIDGE_FOUND)) {
                bridge++;
            }
            if (report.resultKind() == DiscoveryResultKind.TRANSFORMED) {
                transformed++;
            }
            if (report.evidence().contains(DiscoveryEvidenceKind.MACRO_LEARNED)) {
                macroLearned++;
            }
            if (report.evidence().contains(DiscoveryEvidenceKind.MACRO_REUSED)) {
                macroReused++;
            }
            if (report.resultKind() == DiscoveryResultKind.FALSE_POSITIVE) {
                falsePositives++;
            }
            elapsedMillis += report.elapsedMillis();
            if (!report.summary().isBlank()) {
                notes.add(report.summary());
            }
        }

        private Row toRow() {
            return new Row(operator, cases, candidates, bridge, transformed, macroLearned, macroReused, falsePositives,
                cases == 0 ? 0.0 : (double) elapsedMillis / cases,
                notes.isEmpty() ? "actual corpus/replay rows" : String.join("; ", notes));
        }

        private int rank(DiscoveryResultKind kind) {
            return switch (kind == null ? DiscoveryResultKind.NO_CANDIDATE : kind) {
                case NO_CANDIDATE -> 0;
                case HYPOTHESIS_ONLY -> 1;
                case BRIDGE_FOUND -> 2;
                case TRANSFORMED -> 3;
                case FALSE_POSITIVE -> -1;
            };
        }
    }
}
