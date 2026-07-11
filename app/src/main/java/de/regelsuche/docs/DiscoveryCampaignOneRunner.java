package de.regelsuche.docs;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import de.regelsuche.benchmark.DiscoveryExpectation;
import de.regelsuche.sympyqa.SymPyQaHarness;
import de.regelsuche.transform.CompleteSquareBridgeOperator;
import de.regelsuche.transform.DifferenceOfSquaresPreparationOperator;
import de.regelsuche.transform.FactorCandidateOperator;
import de.regelsuche.transform.RationalNormalizationHypothesisOperator;
import de.regelsuche.transform.RationalizationHypothesisOperator;
import de.regelsuche.transform.TelescopingFractionHypothesisOperator;
import de.regelsuche.util.AtomicJsonFile;
import de.regelsuche.validation.SymPyDiscoveryOracleAdapter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/** Runs the first curated discovery campaign and writes a compact report bundle. */
public final class DiscoveryCampaignOneRunner {
    private static final ObjectMapper JSON = new ObjectMapper()
        .findAndRegisterModules()
        .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

    private final DiscoveryBenchmarkScenarioLoader loader = new DiscoveryBenchmarkScenarioLoader();
    private final SymPyDiscoveryOracleAdapter oracle = new SymPyDiscoveryOracleAdapter();

    public static void main(String[] args) {
        Path repoRoot = args.length == 0
            ? Path.of(".").toAbsolutePath().normalize()
            : Path.of(args[0]).toAbsolutePath().normalize();
        new DiscoveryCampaignOneRunner().writeReport(repoRoot.resolve("app/build/reports/discovery-campaign-1"));
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
        return new CampaignReport("discovery-campaign-1", results, blockers, qaSummary);
    }

    public CampaignReport writeReport(Path outputDirectory) {
        try {
            Files.createDirectories(outputDirectory);
            CampaignReport report = run();
            AtomicJsonFile.writeUtf8(
                outputDirectory.resolve("discovery-campaign-1.json"),
                JSON.writerWithDefaultPrettyPrinter().writeValueAsString(report)
            );
            Files.writeString(
                outputDirectory.resolve("discovery-campaign-1.md"),
                renderMarkdown(report),
                StandardCharsets.UTF_8
            );
            Path qaDirectory = outputDirectory.resolve("sympy-qa");
            Files.createDirectories(qaDirectory);
            qaSummary(report.results(), qaDirectory);
            return report;
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private SymPyQaHarness.QaSummary qaSummary(List<CaseResult> results, Path outputDirectory) {
        Path targetDirectory = outputDirectory;
        try {
            if (targetDirectory == null) {
                targetDirectory = Files.createTempDirectory("discovery-campaign-qa-");
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
        SymPyDiscoveryOracleAdapter.OracleResult oracleResult =
            oracle.equivalence(scenario.inputExpression(), scenario.targetExpression());
        AblationResult ablation = campaignCase.operatorId().isBlank()
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
            oracleResult.status().name(),
            oracleResult.evidence(),
            ablation.status(),
            shortcut == null ? "" : shortcut.source(),
            shortcut == null ? "" : shortcut.packId(),
            shortcut == null ? "" : shortcut.operatorId(),
            shortcut == null ? List.of() : shortcut.assumptions(),
            enabled.withoutMacroRun().appliedRuleIds(),
            campaignCase.notes()
        );
    }

    private AblationResult runAblation(CampaignCase campaignCase, DiscoveryBenchmarkEvidence enabled) {
        DiscoveryOperatorRegistry registry = new DiscoveryOperatorRegistry()
            .register(new DefaultDiscoveryOperatorProvider());
        registry.disable(campaignCase.operatorId());
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
                .filter(edge -> !campaignCase.operatorId().isBlank() && edge.operatorId().equals(campaignCase.operatorId()))
                .findFirst())
            .or(() -> selected.stream()
                .filter(edge -> !edge.assumptions().isEmpty())
                .findFirst())
            .or(() -> selected.stream().reduce((left, right) -> right));
    }

    private String renderMarkdown(CampaignReport report) {
        StringBuilder out = new StringBuilder();
        out.append("# Discovery Campaign 1\n\n");
        out.append("| Case | Family | Success | Oracle | Ablation | Source | Pack | Operator | Assumptions |\n");
        out.append("| --- | --- | --- | --- | --- | --- | --- | --- | --- |\n");
        for (CaseResult result : report.results()) {
            out.append("| ").append(escape(result.id()))
                .append(" | ").append(escape(result.family()))
                .append(" | ").append(result.success() ? "yes" : "no")
                .append(" | ").append(escape(result.oracleStatus().toLowerCase(Locale.ROOT)))
                .append(" | ").append(escape(result.ablationStatus().toLowerCase(Locale.ROOT)))
                .append(" | ").append(escape(orDash(result.shortcutSource())))
                .append(" | ").append(escape(orDash(result.shortcutPackId())))
                .append(" | ").append(escape(orDash(result.shortcutOperatorId())))
                .append(" | ").append(escape(result.shortcutAssumptions().isEmpty()
                    ? "—"
                    : String.join(", ", result.shortcutAssumptions())))
                .append(" |\n");
        }
        out.append("\n## QA summary\n\n");
        out.append("- totalCases: ").append(report.qaSummary().totalCases()).append('\n');
        out.append("- sympyAvailableCases: ").append(report.qaSummary().sympyAvailableCases()).append('\n');
        out.append("- disagreements: ").append(report.qaSummary().disagreements()).append('\n');
        out.append("- regelsucheNoPath: ").append(report.qaSummary().regelsucheNoPath()).append('\n');
        if (!report.blockers().isEmpty()) {
            out.append("\n## Blockers\n\n");
            for (String blocker : report.blockers()) {
                out.append("- ").append(escape(blocker)).append('\n');
            }
        }
        return out.toString();
    }

    private String orDash(String value) {
        return value == null || value.isBlank() ? "—" : value;
    }

    private String escape(String value) {
        return value == null ? "" : value.replace("|", "\\|").replace("\n", " ");
    }

    private List<CampaignCase> cases() {
        return List.of(
            new CampaignCase(
                "complete-square-family",
                "polynomial",
                "x^2 + 10*x + 21",
                "(x + 3) * (x + 7)",
                "complete_square_bridge",
                CompleteSquareBridgeOperator.RULE_ID,
                List.of("sympy-polynomial-basic"),
                "quadratic completion shortcut"
            ),
            new CampaignCase(
                "sophie-germain-variant",
                "polynomial",
                "x^4 + 64",
                "(x^2 - 4*x + 8) * (x^2 + 4*x + 8)",
                "sophie_germain_bridge",
                DifferenceOfSquaresPreparationOperator.RULE_ID,
                List.of("sympy-polynomial-basic"),
                "hidden-structure Sophie-Germain shortcut"
            ),
            new CampaignCase(
                "factor-candidate",
                "polynomial",
                "2*x^2 + 4*x",
                "2 * (x^2 + 2*x)",
                "factor_candidate",
                FactorCandidateOperator.RULE_ID,
                List.of("sympy-polynomial-basic"),
                "factor candidate shortcut"
            ),
            new CampaignCase(
                "telescoping-rational",
                "rational",
                "1 / ((n + 1) * (n + 2))",
                "1 / (n + 1) - 1 / (n + 2)",
                "telescoping_fraction",
                TelescopingFractionHypothesisOperator.RULE_ID,
                List.of("sympy-rational-basic"),
                "telescoping partial-fraction shortcut"
            ),
            new CampaignCase(
                "rational-normalization",
                "rational",
                "x / y + z / y",
                "(x + z) / y",
                "rational_normalization",
                RationalNormalizationHypothesisOperator.RULE_ID,
                List.of("rational-basic"),
                "cancel/together style normalization"
            ),
            new CampaignCase(
                "rationalization-assumptions",
                "rational",
                "1 / (sqrt(x) + 1)",
                "(sqrt(x) - 1) / (x - 1)",
                "rationalization",
                RationalizationHypothesisOperator.RULE_ID,
                List.of("sympy-rational-basic"),
                "assumption-carrying rationalization shortcut"
            ),
            new CampaignCase(
                "trig-pythagorean",
                "trigonometric",
                "sin(x)^2 + cos(x)^2",
                "1",
                "",
                "sympy.trig.basic.pythagorean",
                List.of("sympy-trig-basic"),
                "pythagorean identity via SymPy-derived pack"
            ),
            new CampaignCase(
                "log-product-assumptions",
                "logarithmic",
                "log(a * b)",
                "log(a) + log(b)",
                "",
                "sympy.log.basic.product",
                List.of("sympy-log-basic"),
                "assumption-carrying log product rule"
            )
        );
    }

    public record CampaignReport(
        String id,
        List<CaseResult> results,
        List<String> blockers,
        SymPyQaHarness.QaSummary qaSummary
    ) {
        public CampaignReport {
            results = results == null ? List.of() : List.copyOf(results);
            blockers = blockers == null ? List.of() : List.copyOf(blockers);
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
        String notes
    ) implements CampaignCaseResult {
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
        }
    }

    private record CampaignCase(
        String id,
        String family,
        String inputExpression,
        String targetExpression,
        String operatorId,
        String expectedRuleId,
        List<String> enabledRulePacks,
        String notes
    ) {
        private DiscoveryBenchmarkScenario scenario() {
            return new DiscoveryBenchmarkScenario(
                id,
                id,
                inputExpression,
                targetExpression,
                operatorId.isBlank() ? List.of() : List.of(DiscoveryExpectation.BRIDGE_REQUIRED),
                operatorId.isBlank() ? List.of() : List.of(operatorId),
                enabledRulePacks,
                List.of(),
                List.of(),
                operatorId.isBlank() ? List.of() : List.of(expectedRuleId),
                new DiscoveryBenchmarkScenario.MacroLearning(false, null, null),
                new DiscoveryBenchmarkScenario.Budgets(8, 240, 5000),
                new DiscoveryBenchmarkScenario.Gallery(false, 1, 3)
            );
        }
    }

    private record AblationResult(String status, String notes) {
        private static AblationResult notApplicable() {
            return new AblationResult("N/A", "");
        }
    }
}
