package de.regelsuche.docs;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import de.regelsuche.canonical.ExpressionCanonicalizer;
import de.regelsuche.moves.MoveCandidateTransformationEngine;
import de.regelsuche.moves.RewriteMove;
import de.regelsuche.moves.search.CountableMoveSearchEngine;
import de.regelsuche.moves.search.CountableMoveSearchEngine.CountableMoveSearchResult;
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
import java.util.Set;

/** Runs Discovery Campaign 6: Countable Move Search Probe. */
public final class DiscoveryCampaignSixRunner {
    private static final ObjectMapper JSON = new ObjectMapper()
        .findAndRegisterModules()
        .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

    private final MoveCandidateTransformationEngine moveAdapter = new MoveCandidateTransformationEngine();
    private final CountableMoveSearchEngine moveSearchEngine = new CountableMoveSearchEngine();
    private final ExpressionCanonicalizer canonicalizer = new ExpressionCanonicalizer();

    /** Explored-state count at or below which a successful search space counts as "ausreichend klein". */
    private static final int SMALL_SEARCH_SPACE_THRESHOLD = 64;

    public static void main(String[] args) {
        Path repoRoot = args.length == 0
            ? Path.of(".").toAbsolutePath().normalize()
            : Path.of(args[0]).toAbsolutePath().normalize();
        new DiscoveryCampaignSixRunner()
            .writeReport(repoRoot.resolve("app/build/reports/discovery-campaign-6"));
    }

    public CampaignReport run() {
        List<CaseResult> results = cases().stream().map(this::evaluate).toList();
        return new CampaignReport(
            "discovery-campaign-6",
            results,
            summarize(results),
            relatedFollowUpIssues(),
            builtInArchitectureNote()
        );
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
        MoveCandidateTransformationEngine.ComparisonReport comparison = moveAdapter.compare(probeCase.inputExpression());
        boolean expectedMovePresent = switch (probeCase.id()) {
            case "cancellation-plus-one" -> hasParameter(comparison.moveCandidates(), "cancel", "+1");
            case "complete-square" -> hasParameter(comparison.moveCandidates(), "shift", "3")
                && hasParameter(comparison.moveCandidates(), "residue", "-4");
            case "repeated-subexpression" -> hasParameter(comparison.moveCandidates(), "x + 1", "x + 1");
            case "common-subexpression" -> hasParameter(comparison.moveCandidates(), "y + 1", "y + 1");
            default -> false;
        };
        String expectedMoveCoverage = expectedCoverage(comparison, probeCase.id());

        CountableMoveSearchResult searchResult = moveSearchEngine.search(
            probeCase.inputExpression(),
            probeCase.targetExpression(),
            4,
            120
        );

        List<PathStep> pathSteps = toPathSteps(searchResult);
        String architectureNote = architectureNote(probeCase, expectedMovePresent, searchResult);
        Interpretation interpretation = interpretation(probeCase, comparison, searchResult, architectureNote);
        String searchSpaceAssessment = assessSearchSpace(searchResult, architectureNote);

        return new CaseResult(
            probeCase.id(),
            probeCase.inputExpression(),
            probeCase.targetExpression(),
            probeCase.expectation(),
            new Depth1CandidateProbe(
                expectedMovePresent,
                expectedMoveCoverage,
                comparison.classicCandidates().size(),
                comparison.moveCandidates().size(),
                comparison.overlaps().size(),
                comparison.moveOnlyCandidates().size(),
                comparison.classicOnlyCandidates().size()
            ),
            new MultiStepCountableMoveSearch(
                searchResult.success(),
                searchResult.pathLength(),
                searchResult.pathExpressions(),
                searchResult.appliedMoves(),
                searchResult.appliedRuleIds(),
                ordinalPath(searchResult.appliedMoves()),
                searchResult.exploredStateCount(),
                searchResult.uniqueCanonicalStateCount(),
                searchResult.failureReason().name(),
                pathSteps
            ),
            searchResult.searchSpaceMetrics(),
            searchSpaceAssessment,
            interpretation,
            comparison,
            architectureNote
        );
    }

    /**
     * Classifies the search space of a single case into one of the actionable verdicts requested
     * by Issue #103: sufficiently small, needs stronger heuristic, normalizer, parameter limiting,
     * or a new realizer.
     */
    private String assessSearchSpace(CountableMoveSearchResult searchResult, String architectureNote) {
        CountableMoveSearchEngine.SearchSpaceMetrics metrics = searchResult.searchSpaceMetrics();
        if (searchResult.success()
            && metrics.prunedByStateBudgetCount() == 0
            && metrics.exploredStateCount() <= SMALL_SEARCH_SPACE_THRESHOLD) {
            return "ausreichend klein";
        }
        if (searchResult.failureReason() == CountableMoveSearchEngine.FailureReason.MAX_STATES_REACHED
            || metrics.prunedByStateBudgetCount() > 0) {
            return "braucht stärkere Heuristik";
        }
        if ("Missing normalizer".equals(architectureNote)) {
            return "braucht Normalizer";
        }
        if ("Missing realizer".equals(architectureNote) || "Missing parameter enumerator".equals(architectureNote)) {
            return "braucht neuen Realizer";
        }
        if (metrics.unresolvedParameterMoveCount() > 0) {
            return "braucht Parameterbegrenzung";
        }
        if (!searchResult.success()) {
            return "braucht stärkere Heuristik";
        }
        return "ausreichend klein";
    }

    private SearchSpaceSummary summarize(List<CaseResult> results) {
        int successful = 0;
        int totalExplored = 0;
        int totalUnique = 0;
        int totalGenerated = 0;
        int totalDuplicates = 0;
        int totalPrunedByDepth = 0;
        int totalPrunedByBudget = 0;
        int classicFallback = 0;
        int unknown = 0;
        int unresolved = 0;
        double maxBranching = 0.0;
        Map<String, Integer> moveKinds = new java.util.TreeMap<>();
        Map<String, Integer> enumerators = new java.util.TreeMap<>();
        Map<String, Integer> assessments = new java.util.TreeMap<>();
        for (CaseResult result : results) {
            CountableMoveSearchEngine.SearchSpaceMetrics metrics = result.searchSpace();
            if (result.multiStepSearch().success()) {
                successful++;
            }
            totalExplored += metrics.exploredStateCount();
            totalUnique += metrics.uniqueCanonicalStateCount();
            totalGenerated += metrics.generatedMoveCount();
            totalDuplicates += metrics.duplicateStateCount();
            totalPrunedByDepth += metrics.prunedByDepthCount();
            totalPrunedByBudget += metrics.prunedByStateBudgetCount();
            classicFallback += metrics.classicFallbackMoveCount();
            unknown += metrics.unknownMoveCount();
            unresolved += metrics.unresolvedParameterMoveCount();
            for (var branching : metrics.branchingFactorByDepth()) {
                maxBranching = Math.max(maxBranching, branching.branchingFactor());
            }
            metrics.moveKindHistogram().forEach((key, value) -> moveKinds.merge(key, value, Integer::sum));
            metrics.enumeratorHistogram().forEach((key, value) -> enumerators.merge(key, value, Integer::sum));
            assessments.merge(result.searchSpaceAssessment(), 1, Integer::sum);
        }
        return new SearchSpaceSummary(
            results.size(),
            successful,
            totalExplored,
            totalUnique,
            totalGenerated,
            totalDuplicates,
            totalPrunedByDepth,
            totalPrunedByBudget,
            maxBranching,
            new LinkedHashMap<>(moveKinds),
            new LinkedHashMap<>(enumerators),
            new LinkedHashMap<>(assessments),
            classicFallback,
            unknown,
            unresolved
        );
    }

    private List<PathStep> toPathSteps(CountableMoveSearchResult searchResult) {
        if (!searchResult.success() || searchResult.appliedMoves().isEmpty()) {
            return List.of();
        }
        List<PathStep> steps = new ArrayList<>();
        List<String> path = searchResult.pathExpressions();
        for (int index = 0; index < searchResult.appliedMoves().size(); index++) {
            RewriteMove move = searchResult.appliedMoves().get(index);
            String before = index < path.size() ? path.get(index) : "";
            String after = index + 1 < path.size() ? path.get(index + 1) : move.targetExpression();
            steps.add(new PathStep(
                index + 1,
                before,
                move.kind().name(),
                move.ruleId(),
                ordinalText(move),
                renderParameters(move),
                after
            ));
        }
        return List.copyOf(steps);
    }

    private String architectureNote(ProbeCase probeCase, boolean expectedMovePresent, CountableMoveSearchResult searchResult) {
        if (!expectedMovePresent) {
            return "Missing parameter enumerator";
        }
        if (!searchResult.success() && "cancellation-plus-one".equals(probeCase.id())) {
            return "Missing normalizer";
        }
        if (!searchResult.appliedMoves().isEmpty() && searchResult.appliedMoves().stream().anyMatch(move ->
            move.kind().name().equals("UNKNOWN") || move.hasUnresolvedParameters())) {
            return "Move enumeration needs classic fallback";
        }
        if (!searchResult.success()) {
            return "Missing realizer";
        }
        return "Move enumeration is sufficient";
    }

    private Interpretation interpretation(
        ProbeCase probeCase,
        MoveCandidateTransformationEngine.ComparisonReport comparison,
        CountableMoveSearchResult searchResult,
        String architectureNote
    ) {
        String suitability = searchResult.success()
            ? "Mehrstufiger Pfad erreichbar innerhalb des Budgets."
            : "Ziel im aktuellen Budget nicht erreicht.";
        String missingFamily = switch (architectureNote) {
            case "Missing parameter enumerator" -> "Parameter-Enumerator für den erwarteten Move erweitern.";
            case "Missing normalizer" -> "Nachgelagerte Normalisierung (z. B. cancellation -> solve) fehlt.";
            case "Missing realizer" -> "Move-Realizer für diesen Fall vervollständigen.";
            case "Move enumeration needs classic fallback" -> "Move-Metadatenableitung für klassische Kandidaten verbessern.";
            default -> "Keine zusätzliche Move-Familie zwingend.";
        };
        String comparisonText = comparison.moveOnlyCandidates().size() > comparison.classicOnlyCandidates().size()
            ? "Move-Enumeration ergänzt die klassische Engine sichtbar."
            : comparison.moveOnlyCandidates().size() < comparison.classicOnlyCandidates().size()
                ? "Klassische Engine deckt aktuell noch mehr Kandidaten ab."
                : "Move-Enumeration und Classic sind in diesem Fall ähnlich stark.";
        if ("cancellation-plus-one".equals(probeCase.id()) && !searchResult.success()) {
            comparisonText = "Cancellation-Move ist da, aber Normalisierungsfolge bis x = 1 fehlt noch.";
        }
        return new Interpretation(suitability, missingFamily, comparisonText);
    }

    private String expectedCoverage(MoveCandidateTransformationEngine.ComparisonReport comparison, String caseId) {
        Set<String> expectedCanonicalTargets = switch (caseId) {
            case "cancellation-plus-one" -> Set.of(canonical("x - 1 + 1 = 0 + 1"));
            case "complete-square" -> Set.of(canonical("(x + 3)^2 - 4"));
            case "repeated-subexpression" -> Set.of(canonical("(x + 1) * x"));
            case "common-subexpression" -> Set.of(canonical("(y + 1) * (x + z)"));
            default -> Set.of();
        };
        boolean inMove = comparison.moveCandidates().stream()
            .map(MoveCandidateTransformationEngine.CandidateSummary::transformedExpression)
            .map(this::canonical)
            .anyMatch(expectedCanonicalTargets::contains);
        boolean inClassic = comparison.classicCandidates().stream()
            .map(MoveCandidateTransformationEngine.CandidateSummary::transformedExpression)
            .map(this::canonical)
            .anyMatch(expectedCanonicalTargets::contains);
        if (inMove && inClassic) {
            return "Overlap";
        }
        if (inMove) {
            return "Move-only";
        }
        if (inClassic) {
            return "Classic-only";
        }
        return "Not-found";
    }

    private String ordinalText(RewriteMove move) {
        return move.ordinal().ruleOrdinal() + ":" + move.ordinal().occurrenceOrdinal() + ":" + move.ordinal().parameterOrdinals();
    }

    private List<String> ordinalPath(List<RewriteMove> appliedMoves) {
        return appliedMoves.stream().map(this::ordinalText).toList();
    }

    private String renderParameters(RewriteMove move) {
        if (move.parameters().isEmpty()) {
            return move.hasUnresolvedParameters() ? "parameters-unresolved" : "—";
        }
        List<String> parts = new ArrayList<>();
        for (var parameter : move.parameters()) {
            parts.add(parameter.name() + "=" + parameter.value());
        }
        return String.join(", ", parts);
    }

    private boolean hasParameter(
        List<MoveCandidateTransformationEngine.CandidateSummary> candidates,
        String name,
        String value
    ) {
        return candidates.stream().anyMatch(candidate -> value.equals(candidate.parameters().get(name)));
    }

    private String renderMarkdown(CampaignReport report) {
        StringBuilder out = new StringBuilder();
        out.append("# Discovery Campaign 6: Countable Move Search Probe\n\n");
        out.append("Diese Probe vergleicht Depth-1-Kandidaten und bewertet eine begrenzte mehrstufige Countable-Move-Suche (Depth<=4).\n\n");
        out.append("## Built-in vs. nachladbare Module\n\n");
        out.append(report.builtInArchitectureNote()).append("\n\n");

        for (CaseResult result : report.cases()) {
            out.append("## ").append(result.id()).append("\n\n");
            out.append("- Input: `").append(result.inputExpression()).append("`\n");
            out.append("- Target: `").append(result.targetExpression()).append("`\n");
            out.append("- Erwartung: ").append(result.expectation()).append("\n\n");

            out.append("### Depth-1 Candidate Summary\n\n");
            out.append("- Erwarteter Move vorhanden: ").append(result.depth1CandidateProbe().expectedMovePresent() ? "ja" : "nein").append("\n");
            out.append("- Expected coverage: ").append(result.depth1CandidateProbe().expectedMoveCoverage()).append("\n");
            appendClassicVsMoveSummary(out, result.depth1CandidateProbe());

            out.append("### Multi-step Search Result\n\n");
            out.append("- Ziel erreichbar: ").append(result.multiStepSearch().success() ? "ja" : "nein").append("\n");
            out.append("- Pfadlänge: ").append(result.multiStepSearch().pathLength()).append("\n");
            out.append("- appliedMoves: ").append(result.multiStepSearch().appliedMoves().size()).append("\n");
            out.append("- ordinalPath: ").append(escape(String.join(" -> ", result.multiStepSearch().ordinalPath()))).append("\n");
            out.append("- explored states: ").append(result.multiStepSearch().exploredStateCount()).append("\n");
            out.append("- unique canonical states: ").append(result.multiStepSearch().uniqueCanonicalStateCount()).append("\n");
            out.append("- failure reason: ").append(result.multiStepSearch().failureReason()).append("\n\n");

            appendSearchSpaceTable(out, result.searchSpace(), result.searchSpaceAssessment());

            out.append("### Successful path\n\n");
            if (result.multiStepSearch().pathSteps().isEmpty()) {
                out.append("_Kein erfolgreicher Pfad._\n\n");
            } else {
                out.append("| step | before | moveKind | ruleId | ordinal | parameters | after |\n");
                out.append("| --- | --- | --- | --- | --- | --- | --- |\n");
                for (PathStep step : result.multiStepSearch().pathSteps()) {
                    out.append("| ").append(step.step())
                        .append(" | ").append(escape(step.before()))
                        .append(" | ").append(escape(step.moveKind()))
                        .append(" | ").append(escape(step.ruleId()))
                        .append(" | ").append(escape(step.ordinal()))
                        .append(" | ").append(escape(step.parameters()))
                        .append(" | ").append(escape(step.after()))
                        .append(" |\n");
                }
                out.append('\n');
            }

            out.append("### Classic-vs-Move Vergleich\n\n");
            appendClassicVsMoveSummary(out, result.depth1CandidateProbe());

            out.append("### Interpretation\n\n");
            out.append("- Tauglichkeit: ").append(result.interpretation().suitability()).append("\n");
            out.append("- Fehlende Move-Familie: ").append(result.interpretation().missingMoveFamily()).append("\n");
            out.append("- Enumeration im Vergleich: ").append(result.interpretation().enumerationComparison()).append("\n\n");

            out.append("### Architecture note\n\n");
            out.append("- ").append(result.architectureNote()).append("\n\n");
        }

        appendSearchSpaceSummary(out, report.searchSpaceSummary());

        out.append("## Related follow-up issues\n\n");
        for (String issue : report.relatedFollowUpIssues()) {
            out.append("- ").append(issue).append("\n");
        }
        out.append('\n');
        return out.toString();
    }

    private void appendSearchSpaceTable(
        StringBuilder out,
        CountableMoveSearchEngine.SearchSpaceMetrics metrics,
        String assessment
    ) {
        out.append("### Search Space Intelligence\n\n");
        out.append("| metric | value |\n");
        out.append("| --- | --- |\n");
        out.append("| exploredStateCount | ").append(metrics.exploredStateCount()).append(" |\n");
        out.append("| uniqueCanonicalStateCount | ").append(metrics.uniqueCanonicalStateCount()).append(" |\n");
        out.append("| generatedMoveCount | ").append(metrics.generatedMoveCount()).append(" |\n");
        out.append("| duplicateStateCount | ").append(metrics.duplicateStateCount()).append(" |\n");
        out.append("| prunedByDepthCount | ").append(metrics.prunedByDepthCount()).append(" |\n");
        out.append("| prunedByStateBudgetCount | ").append(metrics.prunedByStateBudgetCount()).append(" |\n");
        out.append("| classicFallbackMoveCount | ").append(metrics.classicFallbackMoveCount()).append(" |\n");
        out.append("| unknownMoveCount | ").append(metrics.unknownMoveCount()).append(" |\n");
        out.append("| unresolvedParameterMoveCount | ").append(metrics.unresolvedParameterMoveCount()).append(" |\n\n");

        out.append("- branchingFactor pro Tiefe: ").append(renderBranchingFactor(metrics.branchingFactorByDepth())).append("\n");
        out.append("- MoveKind-Histogramm: ").append(renderHistogram(metrics.moveKindHistogram())).append("\n");
        out.append("- Enumerator-Histogramm: ").append(renderHistogram(metrics.enumeratorHistogram())).append("\n");
        out.append("- successfulPathMoveKinds: ")
            .append(metrics.successfulPathMoveKinds().isEmpty()
                ? "—" : escape(String.join(" -> ", metrics.successfulPathMoveKinds())))
            .append("\n");
        out.append("- Bewertung: ").append(escape(assessment)).append("\n\n");
    }

    private void appendSearchSpaceSummary(StringBuilder out, SearchSpaceSummary summary) {
        out.append("## Search Space Intelligence Summary\n\n");
        out.append("| metric | value |\n");
        out.append("| --- | --- |\n");
        out.append("| caseCount | ").append(summary.caseCount()).append(" |\n");
        out.append("| successfulCaseCount | ").append(summary.successfulCaseCount()).append(" |\n");
        out.append("| totalExploredStates | ").append(summary.totalExploredStates()).append(" |\n");
        out.append("| totalUniqueCanonicalStates | ").append(summary.totalUniqueCanonicalStates()).append(" |\n");
        out.append("| totalGeneratedMoves | ").append(summary.totalGeneratedMoves()).append(" |\n");
        out.append("| totalDuplicateStates | ").append(summary.totalDuplicateStates()).append(" |\n");
        out.append("| totalPrunedByDepth | ").append(summary.totalPrunedByDepth()).append(" |\n");
        out.append("| totalPrunedByStateBudget | ").append(summary.totalPrunedByStateBudget()).append(" |\n");
        out.append("| maxBranchingFactor | ").append(formatFactor(summary.maxBranchingFactor())).append(" |\n");
        out.append("| classicFallbackMoveCount | ").append(summary.classicFallbackMoveCount()).append(" |\n");
        out.append("| unknownMoveCount | ").append(summary.unknownMoveCount()).append(" |\n");
        out.append("| unresolvedParameterMoveCount | ").append(summary.unresolvedParameterMoveCount()).append(" |\n\n");

        out.append("- MoveKind-Histogramm (gesamt): ").append(renderHistogram(summary.aggregatedMoveKindHistogram())).append("\n");
        out.append("- Enumerator-Histogramm (gesamt): ").append(renderHistogram(summary.aggregatedEnumeratorHistogram())).append("\n");
        out.append("- Bewertungen: ").append(renderHistogram(summary.assessmentHistogram())).append("\n\n");
    }

    private String renderBranchingFactor(List<CountableMoveSearchEngine.DepthBranchingFactor> branching) {
        if (branching.isEmpty()) {
            return "—";
        }
        List<String> parts = new ArrayList<>();
        for (CountableMoveSearchEngine.DepthBranchingFactor entry : branching) {
            parts.add("d" + entry.depth() + "=" + formatFactor(entry.branchingFactor())
                + " (" + entry.generatedMoveCount() + "/" + entry.expandedNodeCount() + ")");
        }
        return escape(String.join(", ", parts));
    }

    private String renderHistogram(Map<String, Integer> histogram) {
        if (histogram.isEmpty()) {
            return "—";
        }
        List<String> parts = new ArrayList<>();
        histogram.entrySet().stream()
            .sorted(Map.Entry.comparingByKey())
            .forEach(entry -> parts.add(entry.getKey() + "=" + entry.getValue()));
        return escape(String.join(", ", parts));
    }

    private String formatFactor(double value) {
        return String.format(java.util.Locale.ROOT, "%.2f", value);
    }

    private void appendClassicVsMoveSummary(StringBuilder out, Depth1CandidateProbe probe) {
        out.append("- classic count: ").append(probe.classicCount()).append("\n");
        out.append("- move count: ").append(probe.moveCount()).append("\n");
        out.append("- overlap count: ").append(probe.overlapCount()).append("\n");
        out.append("- move-only count: ").append(probe.moveOnlyCount()).append("\n");
        out.append("- classic-only count: ").append(probe.classicOnlyCount()).append("\n\n");
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
            new ProbeCase(
                "cancellation-plus-one",
                "x - 1 = 0",
                "x = 1",
                "+1 auf beiden Seiten muss als Move vorkommen; bei Fehlschlag: cancellation vorhanden, normalization follow-up fehlt"
            ),
            new ProbeCase(
                "complete-square",
                "x^2 + 6*x + 5",
                "(x + 3)^2 - 4",
                "complete-square Move mit shift=3 und residue=-4"
            ),
            new ProbeCase(
                "repeated-subexpression",
                "(x+1)^2 - (x+1)",
                "(x+1)*x",
                "repeated-subexpression/factor Move"
            ),
            new ProbeCase(
                "common-subexpression",
                "x*(y+1)+z*(y+1)",
                "(y+1)*(x+z)",
                "common-subexpression Move"
            )
        );
    }

    private List<String> relatedFollowUpIssues() {
        return List.of(
            "#102 Discovery Engine Roadmap: durch diesen PR teilweise adressiert (Countable Move Search Probe in Campaign 6).",
            "#103 Search Space Intelligence: Priorität steigt, weil explored/unique state Metriken jetzt systematisch vorliegen.",
            "#105 Discovery Visualization & Explainability: Priorität bleibt hoch, da Pfad-/Move-Tabellen jetzt reichhaltigere Erklärsignale liefern.",
            "#106 Rule Authoring IDE: sollte auf Countable Move Ordinals/Parameter aufbauen statt ältere ruleId-only Sicht.",
            "#104 Plugin Ecosystem: für Move-Enumeratoren/Realizer aktuell bewusst zurückgestellt, bis API stabil ist."
        );
    }

    private String builtInArchitectureNote() {
        return "Aktuell built-in: Cancellation-, Complete-Square-, Repeated- und Common-Subexpression-"
            + "Move-Familien inkl. ParameterEnumeratoren und MoveRealizer. Spätere Modul-Kandidaten:"
            + " zusätzliche mathematische Rule Packs und domänenspezifische Move-Familien. Stabil bleiben sollte"
            + " primär die API aus ParameterEnumerator, MoveRealizer und MoveSearchEngine."
            + " Jetzt bleibt alles built-in, weil Move-Modell/Realizer noch eng mit AST/Parser/Canonicalizer gekoppelt sind"
            + " und eine frühe Plugin-Festlegung unnötig verengen würde.";
    }

    record ProbeCase(String id, String inputExpression, String targetExpression, String expectation) {
    }

    public record Depth1CandidateProbe(
        boolean expectedMovePresent,
        String expectedMoveCoverage,
        int classicCount,
        int moveCount,
        int overlapCount,
        int moveOnlyCount,
        int classicOnlyCount
    ) {
    }

    public record MultiStepCountableMoveSearch(
        boolean success,
        int pathLength,
        List<String> pathExpressions,
        List<RewriteMove> appliedMoves,
        List<String> appliedRuleIds,
        List<String> ordinalPath,
        int exploredStateCount,
        int uniqueCanonicalStateCount,
        String failureReason,
        List<PathStep> pathSteps
    ) {
        public MultiStepCountableMoveSearch {
            pathExpressions = pathExpressions == null ? List.of() : List.copyOf(pathExpressions);
            appliedMoves = appliedMoves == null ? List.of() : List.copyOf(appliedMoves);
            appliedRuleIds = appliedRuleIds == null ? List.of() : List.copyOf(appliedRuleIds);
            ordinalPath = ordinalPath == null ? List.of() : List.copyOf(ordinalPath);
            pathSteps = pathSteps == null ? List.of() : List.copyOf(pathSteps);
            failureReason = failureReason == null ? "NONE" : failureReason;
        }
    }

    public record PathStep(
        int step,
        String before,
        String moveKind,
        String ruleId,
        String ordinal,
        String parameters,
        String after
    ) {
    }

    public record Interpretation(String suitability, String missingMoveFamily, String enumerationComparison) {
    }

    public record CaseResult(
        String id,
        String inputExpression,
        String targetExpression,
        String expectation,
        Depth1CandidateProbe depth1CandidateProbe,
        MultiStepCountableMoveSearch multiStepSearch,
        CountableMoveSearchEngine.SearchSpaceMetrics searchSpace,
        String searchSpaceAssessment,
        Interpretation interpretation,
        MoveCandidateTransformationEngine.ComparisonReport comparison,
        String architectureNote
    ) {
        public CaseResult {
            searchSpace = searchSpace == null
                ? CountableMoveSearchEngine.SearchSpaceMetrics.empty()
                : searchSpace;
            searchSpaceAssessment = searchSpaceAssessment == null ? "" : searchSpaceAssessment;
        }
    }

    public record SearchSpaceSummary(
        int caseCount,
        int successfulCaseCount,
        int totalExploredStates,
        int totalUniqueCanonicalStates,
        int totalGeneratedMoves,
        int totalDuplicateStates,
        int totalPrunedByDepth,
        int totalPrunedByStateBudget,
        double maxBranchingFactor,
        Map<String, Integer> aggregatedMoveKindHistogram,
        Map<String, Integer> aggregatedEnumeratorHistogram,
        Map<String, Integer> assessmentHistogram,
        int classicFallbackMoveCount,
        int unknownMoveCount,
        int unresolvedParameterMoveCount
    ) {
        public SearchSpaceSummary {
            aggregatedMoveKindHistogram = aggregatedMoveKindHistogram == null
                ? Map.of() : Map.copyOf(aggregatedMoveKindHistogram);
            aggregatedEnumeratorHistogram = aggregatedEnumeratorHistogram == null
                ? Map.of() : Map.copyOf(aggregatedEnumeratorHistogram);
            assessmentHistogram = assessmentHistogram == null ? Map.of() : Map.copyOf(assessmentHistogram);
        }
    }

    public record CampaignReport(
        String campaignId,
        List<CaseResult> cases,
        SearchSpaceSummary searchSpaceSummary,
        List<String> relatedFollowUpIssues,
        String builtInArchitectureNote
    ) {
        public CampaignReport {
            cases = cases == null ? List.of() : List.copyOf(cases);
            relatedFollowUpIssues = relatedFollowUpIssues == null ? List.of() : List.copyOf(relatedFollowUpIssues);
            builtInArchitectureNote = builtInArchitectureNote == null ? "" : builtInArchitectureNote;
        }
    }
}
