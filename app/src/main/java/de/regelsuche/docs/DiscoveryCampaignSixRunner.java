package de.regelsuche.docs;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import de.regelsuche.canonical.ExpressionCanonicalizer;
import de.regelsuche.moves.MoveCandidateTransformationEngine;
import de.regelsuche.moves.enumerate.Depth1MoveEnumerator;
import de.regelsuche.scoring.ExpressionScorer;
import de.regelsuche.search.SearchHeuristic;
import de.regelsuche.search.strategy.BestFirstSearchStrategy;
import de.regelsuche.search.strategy.SearchProblem;
import de.regelsuche.search.strategy.SearchState;
import de.regelsuche.util.AtomicJsonFile;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Runs Discovery Campaign 6: Countable Move Enumeration Probe. */
public final class DiscoveryCampaignSixRunner {
    private static final ObjectMapper JSON = new ObjectMapper()
        .findAndRegisterModules()
        .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

    private final MoveCandidateTransformationEngine engine = new MoveCandidateTransformationEngine(
        MoveCandidateTransformationEngine.defaultClassicEngine(),
        new Depth1MoveEnumerator()
    );
    private final ExpressionCanonicalizer canonicalizer = new ExpressionCanonicalizer();

    public static void main(String[] args) {
        Path repoRoot = args.length == 0
            ? Path.of(".").toAbsolutePath().normalize()
            : Path.of(args[0]).toAbsolutePath().normalize();
        new DiscoveryCampaignSixRunner()
            .writeReport(repoRoot.resolve("app/build/reports/discovery-campaign-6"));
    }

    public CampaignReport run() {
        List<CaseResult> results = cases().stream().map(this::evaluate).toList();
        return new CampaignReport("discovery-campaign-6", results);
    }

    public CampaignReport writeReport(Path outputDirectory) {
        return writeReport(outputDirectory, run());
    }

    CampaignReport writeReport(Path outputDirectory, CampaignReport report) {
        try {
            Files.createDirectories(outputDirectory);
            AtomicJsonFile.writeUtf8(
                outputDirectory.resolve("discovery-campaign-6.json"),
                JSON.writerWithDefaultPrettyPrinter().writeValueAsString(report)
            );
            Files.writeString(
                outputDirectory.resolve("countable-move-enumeration-report.md"),
                renderMarkdown(report),
                StandardCharsets.UTF_8
            );
            return report;
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private CaseResult evaluate(ProbeCase probeCase) {
        MoveCandidateTransformationEngine.ComparisonReport comparison = engine.compare(probeCase.inputExpression());
        boolean expectedMovePresent = switch (probeCase.id()) {
            case "cancellation-plus-one" -> hasParameter(comparison.moveCandidates(), "cancel", "+1");
            case "complete-square" -> hasParameter(comparison.moveCandidates(), "shift", "3")
                && hasParameter(comparison.moveCandidates(), "residue", "-4");
            case "repeated-subexpression" -> hasParameter(comparison.moveCandidates(), "x + 1", "x + 1");
            case "common-subexpression" -> hasParameter(comparison.moveCandidates(), "y + 1", "y + 1");
            default -> false;
        };
        boolean depth1SearchObserved = depth1SearchExpressions(probeCase.inputExpression()).stream()
            .map(this::canonical)
            .anyMatch(expression -> comparison.moveCandidates().stream()
                .map(MoveCandidateTransformationEngine.CandidateSummary::transformedExpression)
                .map(this::canonical)
                .anyMatch(expression::equals));
        return new CaseResult(
            probeCase.id(),
            probeCase.inputExpression(),
            probeCase.expectation(),
            expectedMovePresent,
            depth1SearchObserved,
            comparison
        );
    }

    private boolean hasParameter(
        List<MoveCandidateTransformationEngine.CandidateSummary> candidates,
        String name,
        String value
    ) {
        return candidates.stream().anyMatch(candidate -> value.equals(candidate.parameters().get(name)));
    }

    private List<String> depth1SearchExpressions(String expression) {
        SearchProblem problem = new SearchProblem(
            expression,
            engine,
            new ExpressionScorer(),
            new ExpressionCanonicalizer(),
            new SearchHeuristic(1, 40, 1, 2, 20, 12)
        );
        return new BestFirstSearchStrategy().search(problem).stream()
            .filter(state -> state.depth() == 1)
            .map(SearchState::expression)
            .toList();
    }

    private String renderMarkdown(CampaignReport report) {
        StringBuilder out = new StringBuilder();
        out.append("# Discovery Campaign 6: Countable Move Enumeration Probe\n\n");
        out.append("Vergleicht klassische Depth-1-Kandidaten mit explizit enumerierten RewriteMoves ")
            .append("und prüft, ob diese Kandidaten im bestehenden BestFirst-Search-Raum auftauchen.\n\n");
        for (CaseResult result : report.cases()) {
            out.append("## ").append(result.id()).append("\n\n");
            out.append("- Input: `").append(result.inputExpression()).append("`\n");
            out.append("- Erwartung: ").append(result.expectation()).append('\n');
            out.append("- Erwarteter Move gefunden: ").append(result.expectedMovePresent() ? "ja" : "nein").append('\n');
            out.append("- In Depth-1-Suche sichtbar: ").append(result.depth1SearchObserved() ? "ja" : "nein").append("\n\n");
            appendCandidateTable(out, "Klassische Engine-Kandidaten", result.comparison().classicCandidates());
            appendCandidateTable(out, "Move-Enumerator-Kandidaten", result.comparison().moveCandidates());
            appendCandidateTable(out, "Überschneidungen", result.comparison().overlaps());
            appendCandidateTable(out, "Nur aus Move-Enumeration", result.comparison().moveOnlyCandidates());
            appendCandidateTable(out, "Nur aus alter Engine", result.comparison().classicOnlyCandidates());
        }
        return out.toString();
    }

    private void appendCandidateTable(
        StringBuilder out,
        String title,
        List<MoveCandidateTransformationEngine.CandidateSummary> candidates
    ) {
        out.append("### ").append(title).append("\n\n");
        if (candidates.isEmpty()) {
            out.append("_Keine Kandidaten._\n\n");
            return;
        }
        out.append("| Transformation | Rule | Operator | Move | Ordinal | Parameters |\n");
        out.append("| --- | --- | --- | --- | --- | --- |\n");
        for (MoveCandidateTransformationEngine.CandidateSummary candidate : candidates) {
            out.append("| ").append(escape(candidate.transformedExpression()))
                .append(" | ").append(escape(candidate.ruleId()))
                .append(" | ").append(escape(candidate.operatorId()))
                .append(" | ").append(escape(candidate.moveId()))
                .append(" | ").append(escape(candidate.ordinal()))
                .append(" | ").append(escape(renderParameters(candidate.parameters())))
                .append(" |\n");
        }
        out.append('\n');
    }

    private String renderParameters(Map<String, String> parameters) {
        if (parameters.isEmpty()) {
            return "—";
        }
        List<String> parts = new ArrayList<>();
        for (Map.Entry<String, String> entry : new LinkedHashMap<>(parameters).entrySet()) {
            parts.add(entry.getKey() + "=" + entry.getValue());
        }
        return String.join(", ", parts);
    }

    private String escape(String value) {
        if (value == null || value.isBlank()) {
            return "—";
        }
        return value.replace("|", "\\|").replace("\n", " ");
    }

    private String canonical(String expression) {
        try {
            return canonicalizer.canonicalize(expression);
        } catch (RuntimeException exception) {
            return expression == null ? "" : expression.replaceAll("\\s+", " ").trim();
        }
    }

    private List<ProbeCase> cases() {
        return List.of(
            new ProbeCase("cancellation-plus-one", "x - 1 = 0", "Kandidat +1"),
            new ProbeCase("complete-square", "x^2 + 6*x + 5", "shift=3, residue=-4"),
            new ProbeCase("repeated-subexpression", "(x+1)^2 - (x+1)", "repeated subexpression x+1"),
            new ProbeCase("common-subexpression", "x*(y+1)+z*(y+1)", "common subexpression y+1")
        );
    }

    record ProbeCase(String id, String inputExpression, String expectation) {
    }

    public record CaseResult(
        String id,
        String inputExpression,
        String expectation,
        boolean expectedMovePresent,
        boolean depth1SearchObserved,
        MoveCandidateTransformationEngine.ComparisonReport comparison
    ) {
    }

    public record CampaignReport(String campaignId, List<CaseResult> cases) {
        public CampaignReport {
            cases = cases == null ? List.of() : List.copyOf(cases);
        }
    }
}
