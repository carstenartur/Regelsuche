package de.regelsuche.docs;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import de.regelsuche.sympyqa.SymPyQaHarness;
import de.regelsuche.transform.ConservativeCompleteSquareHypothesisOperator;
import de.regelsuche.transform.QuadraticFactorizationHypothesisOperator;
import de.regelsuche.util.AtomicJsonFile;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Runs Discovery Campaign 8 with quadratic-factorization and complete-square families:
 * monic quadratic factorization (hypothesis operator) and conservative complete-square
 * preparation — both families are genuinely new relative to Campaigns 1–7.
 * Produces candidate-mining reports and cross-campaign progress comparison (campaigns 5, 7 and 8).
 */
public final class DiscoveryCampaignEightRunner {
    private static final ObjectMapper JSON = new ObjectMapper()
        .findAndRegisterModules()
        .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

    private final DiscoveryBenchmarkScenarioLoader loader = new DiscoveryBenchmarkScenarioLoader();
    private final DiscoveryCandidateReportWriter candidateReportWriter = new DiscoveryCandidateReportWriter();

    public static void main(String[] args) {
        Path repoRoot = args.length == 0
            ? Path.of(".").toAbsolutePath().normalize()
            : Path.of(args[0]).toAbsolutePath().normalize();
        new DiscoveryCampaignEightRunner().writeReport(repoRoot.resolve("app/build/reports/discovery-campaign-8"));
    }

    public CampaignReport run() {
        List<CaseResult> results = cases().stream()
            .map(this::evaluate)
            .sorted(Comparator.comparing(CaseResult::id))
            .toList();
        List<String> blockers = results.stream()
            .filter(result -> !result.success())
            .map(result -> result.id() + ": "
                + (result.failureReason().isBlank() ? result.notes() : result.failureReason()))
            .toList();
        SymPyQaHarness.QaSummary qaSummary = qaSummary(results, null);
        return new CampaignReport(
            "discovery-campaign-8",
            results,
            blockers,
            progressSummaries(results),
            qaSummary
        );
    }

    public CampaignReport writeReport(Path outputDirectory) {
        return writeReport(outputDirectory, run());
    }

    CampaignReport writeReport(Path outputDirectory, CampaignReport report) {
        try {
            Files.createDirectories(outputDirectory);
            List<PromotionRecord> promotionRecords = report.results().stream()
                .map(result -> new PromotionDecider()
                    .decide(PromotionObservation.fromCampaignEight(result, report.id())))
                .toList();
            AtomicJsonFile.writeUtf8(
                outputDirectory.resolve("discovery-campaign-8.json"),
                JSON.writerWithDefaultPrettyPrinter().writeValueAsString(report)
            );
            Files.writeString(
                outputDirectory.resolve("campaign-progress.md"),
                renderProgress(report),
                StandardCharsets.UTF_8
            );
            candidateReportWriter.write(
                outputDirectory,
                report.id(),
                promotionRecords
            );
            Path qaDirectory = outputDirectory.resolve("sympy-qa");
            Files.createDirectories(qaDirectory);
            qaSummary(report.results(), qaDirectory);
            return report;
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private List<ProgressSummary> progressSummaries(List<CaseResult> currentResults) {
        DiscoveryCampaignFiveRunner.CampaignReport campaignFive = new DiscoveryCampaignFiveRunner().run();
        DiscoveryCampaignSevenRunner.CampaignReport campaignSeven = new DiscoveryCampaignSevenRunner().run();
        return List.of(
            summary("discovery-campaign-5", campaignFive.results().stream()
                .map(result -> new SummaryInput(
                    result.success(),
                    result.oracleStatus(),
                    result.ablationStatus()))
                .toList()),
            summary("discovery-campaign-7", campaignSeven.results().stream()
                .map(result -> new SummaryInput(
                    result.success(),
                    result.oracleStatus(),
                    result.ablationStatus()))
                .toList()),
            summary("discovery-campaign-8", currentResults.stream()
                .map(result -> new SummaryInput(
                    result.success(),
                    result.oracleStatus(),
                    result.ablationStatus()))
                .toList())
        );
    }

    private ProgressSummary summary(String campaignId, List<SummaryInput> results) {
        long successCount = results.stream().filter(SummaryInput::success).count();
        long promotionReadyCount = results.stream()
            .filter(SummaryInput::success)
            .filter(result -> !"DISAGREE".equals(result.oracleStatus()))
            .filter(result -> "DEGRADED".equals(result.ablationStatus()))
            .count();
        return new ProgressSummary(campaignId, results.size(), successCount, results.size() - successCount, promotionReadyCount);
    }

    private SymPyQaHarness.QaSummary qaSummary(List<CaseResult> results, Path outputDirectory) {
        Path targetDirectory = outputDirectory;
        try {
            if (targetDirectory == null) {
                targetDirectory = Files.createTempDirectory("discovery-campaign-8-qa-");
            }
            return new SymPyQaHarness().run(
                results.stream().map(CaseResult::inputExpression).toList(),
                targetDirectory
            );
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private CaseResult evaluate(CampaignCase campaignCase) {
        DiscoveryBenchmarkScenario scenario = campaignCase.scenario();
        DiscoveryBenchmarkEvidence enabled = new DiscoveryBenchmarkExecutor(loader).execute(scenario);
        AblationResult ablation = campaignCase.primaryOperatorId().isBlank()
            ? AblationResult.notApplicable()
            : runAblation(campaignCase, enabled);
        DiscoveryBenchmarkEvidence.EvidenceEdge shortcut = shortcutEdge(enabled, campaignCase).orElse(null);
        return new CaseResult(
            campaignCase.id(),
            campaignCase.family(),
            scenario.inputExpression(),
            scenario.targetExpression(),
            enabled.success(),
            enabled.failureReason(),
            enabled.oracleStatus(),
            enabled.oracleEvidence(),
            ablation.status(),
            shortcut == null ? "" : shortcut.source(),
            shortcut == null ? "" : shortcut.packId(),
            shortcut == null ? "" : shortcut.operatorId(),
            shortcut == null ? List.of() : shortcut.assumptions(),
            enabled.withoutMacroRun().appliedRuleIds(),
            campaignCase.notes(),
            enabled.smallGraphMessage()
        );
    }

    private AblationResult runAblation(CampaignCase campaignCase, DiscoveryBenchmarkEvidence enabled) {
        DiscoveryOperatorRegistry registry = new DiscoveryOperatorRegistry()
            .register(new DefaultDiscoveryOperatorProvider());
        registry.disable(campaignCase.primaryOperatorId());
        DiscoveryBenchmarkEvidence disabled = new DiscoveryBenchmarkExecutor(loader, registry)
            .execute(campaignCase.scenario());
        boolean worsePath = disabled.withoutMacroRun().path().size() > enabled.withoutMacroRun().path().size();
        boolean degraded = !disabled.success() || worsePath || shortcutEdge(disabled, campaignCase).isEmpty();
        String notes = disabled.success()
            ? disabled.failureReason().isBlank()
                ? "disabled path length " + disabled.withoutMacroRun().path().size()
                : disabled.failureReason()
            : disabled.failureReason();
        return new AblationResult(degraded ? "DEGRADED" : "UNCHANGED", notes);
    }

    private Optional<DiscoveryBenchmarkEvidence.EvidenceEdge> shortcutEdge(
        DiscoveryBenchmarkEvidence evidence,
        CampaignCase campaignCase
    ) {
        List<DiscoveryBenchmarkEvidence.EvidenceEdge> selected = evidence.edges().stream()
            .filter(edge -> edge.tags().contains("selected-path"))
            .toList();
        return selected.stream()
            .filter(edge -> !campaignCase.expectedRuleId().isBlank() && edge.ruleId().equals(campaignCase.expectedRuleId()))
            .findFirst()
            .or(() -> selected.stream()
                .filter(edge -> campaignCase.operatorIds().contains(edge.operatorId()))
                .findFirst())
            .or(() -> selected.stream()
                .filter(edge -> !edge.assumptions().isEmpty())
                .findFirst())
            .or(() -> selected.stream().reduce((left, right) -> right));
    }

    private String renderProgress(CampaignReport report) {
        StringBuilder out = new StringBuilder();
        out.append("# Discovery campaign progress (campaigns 5, 7, 8)\n\n");
        out.append("| Campaign | Total | Success | Blocked | Promotion-ready |\n");
        out.append("| --- | ---: | ---: | ---: | ---: |\n");
        for (ProgressSummary summary : report.progress()) {
            out.append("| ").append(escape(summary.campaignId()))
                .append(" | ").append(summary.totalCases())
                .append(" | ").append(summary.successCount())
                .append(" | ").append(summary.blockedCount())
                .append(" | ").append(summary.promotionReadyCount())
                .append(" |\n");
        }
        out.append("\n> **Promotion-ready** means: success=true, oracleStatus is not DISAGREE, and ablationStatus=DEGRADED.\n");
        return out.toString();
    }

    private String escape(String value) {
        return value == null ? "" : value.replace("|", "\\|").replace("\n", " ");
    }

    private List<CampaignCase> cases() {
        return Stream.of(
            quadraticFactorizationCases().stream(),
            completeSquareCases().stream())
            .flatMap(stream -> stream)
            .toList();
    }

    private List<CampaignCase> quadraticFactorizationCases() {
        return List.of(
            new CampaignCase(
                "qf-x2-6x-5",
                "quadratic-factorization",
                "x^2 + 6*x + 5",
                "(x + 1) * (x + 5)",
                List.of(QuadraticFactorizationHypothesisOperator.RULE_ID),
                QuadraticFactorizationHypothesisOperator.RULE_ID,
                List.of("sympy-polynomial-basic"),
                "quadratic factorization: x²+6x+5 = (x+1)(x+5)"
            ),
            new CampaignCase(
                "qf-x2-3x-2",
                "quadratic-factorization",
                "x^2 + 3*x + 2",
                "(x + 1) * (x + 2)",
                List.of(QuadraticFactorizationHypothesisOperator.RULE_ID),
                QuadraticFactorizationHypothesisOperator.RULE_ID,
                List.of("sympy-polynomial-basic"),
                "quadratic factorization: x²+3x+2 = (x+1)(x+2)"
            ),
            new CampaignCase(
                "qf-y2-y-6",
                "quadratic-factorization",
                "y^2 - y - 6",
                "(y - 3) * (y + 2)",
                List.of(QuadraticFactorizationHypothesisOperator.RULE_ID),
                QuadraticFactorizationHypothesisOperator.RULE_ID,
                List.of("sympy-polynomial-basic"),
                "quadratic factorization: y²-y-6 = (y-3)(y+2)"
            )
        );
    }

    private List<CampaignCase> completeSquareCases() {
        return List.of(
            new CampaignCase(
                "cs-x2-4x-4",
                "complete-square",
                "x^2 + 4*x + 4",
                "(x + 2)^2",
                List.of(ConservativeCompleteSquareHypothesisOperator.RULE_ID),
                ConservativeCompleteSquareHypothesisOperator.RULE_ID,
                List.of("sympy-polynomial-basic"),
                "complete the square: x²+4x+4 = (x+2)²"
            ),
            new CampaignCase(
                "cs-x2-2xy-y2",
                "complete-square",
                "x^2 + 2*x*y + y^2",
                "(x + y)^2",
                List.of(ConservativeCompleteSquareHypothesisOperator.RULE_ID),
                ConservativeCompleteSquareHypothesisOperator.RULE_ID,
                List.of("sympy-polynomial-basic"),
                "bivariate perfect square: x²+2xy+y² = (x+y)²"
            )
        );
    }

    public record CampaignReport(
        String id,
        List<CaseResult> results,
        List<String> blockers,
        List<ProgressSummary> progress,
        SymPyQaHarness.QaSummary qaSummary
    ) {
        public CampaignReport {
            results = results == null ? List.of() : List.copyOf(results);
            blockers = blockers == null ? List.of() : List.copyOf(blockers);
            progress = progress == null ? List.of() : List.copyOf(progress);
        }
    }

    public record CaseResult(
        String id,
        String family,
        String inputExpression,
        String targetExpression,
        boolean success,
        String failureReason,
        String oracleStatus,
        String oracleEvidence,
        String ablationStatus,
        String shortcutSource,
        String shortcutPackId,
        String shortcutOperatorId,
        List<String> shortcutAssumptions,
        List<String> rulePath,
        String notes,
        String smallGraphMessage
    ) {
        public CaseResult {
            failureReason = failureReason == null ? "" : failureReason;
            oracleStatus = oracleStatus == null ? "UNAVAILABLE" : oracleStatus;
            oracleEvidence = oracleEvidence == null ? "" : oracleEvidence;
            ablationStatus = ablationStatus == null ? "N/A" : ablationStatus;
            shortcutSource = shortcutSource == null ? "" : shortcutSource;
            shortcutPackId = shortcutPackId == null ? "" : shortcutPackId;
            shortcutOperatorId = shortcutOperatorId == null ? "" : shortcutOperatorId;
            shortcutAssumptions = shortcutAssumptions == null ? List.of() : List.copyOf(shortcutAssumptions);
            rulePath = rulePath == null ? List.of() : List.copyOf(rulePath);
            notes = notes == null ? "" : notes;
            smallGraphMessage = smallGraphMessage == null ? "" : smallGraphMessage;
        }
    }

    public record ProgressSummary(
        String campaignId,
        long totalCases,
        long successCount,
        long blockedCount,
        long promotionReadyCount
    ) {
    }

    private record SummaryInput(boolean success, String oracleStatus, String ablationStatus) {
        private SummaryInput {
            oracleStatus = oracleStatus == null ? "UNAVAILABLE" : oracleStatus;
            ablationStatus = ablationStatus == null ? "N/A" : ablationStatus;
        }
    }

    private record CampaignCase(
        String id,
        String family,
        String inputExpression,
        String targetExpression,
        List<String> operatorIds,
        String expectedRuleId,
        List<String> enabledRulePacks,
        String notes
    ) {
        private String primaryOperatorId() {
            return operatorIds().isEmpty() ? "" : operatorIds().getFirst();
        }

        private DiscoveryBenchmarkScenario scenario() {
            return new DiscoveryBenchmarkScenario(
                id,
                id,
                inputExpression,
                targetExpression,
                List.of(),
                operatorIds,
                enabledRulePacks,
                List.of(),
                List.of(),
                List.of(expectedRuleId),
                new DiscoveryBenchmarkScenario.MacroLearning(false, null, null),
                new DiscoveryBenchmarkScenario.Budgets(8, 240, 5000),
                new DiscoveryBenchmarkScenario.Gallery(false, 1, 2)
            );
        }
    }

    private record AblationResult(String status, String notes) {
        private static AblationResult notApplicable() {
            return new AblationResult("N/A", "");
        }
    }
}
