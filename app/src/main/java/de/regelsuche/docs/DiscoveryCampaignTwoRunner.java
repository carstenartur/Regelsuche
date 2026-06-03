package de.regelsuche.docs;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import de.regelsuche.benchmark.DiscoveryExpectation;
import de.regelsuche.sympyqa.SymPyQaHarness;
import de.regelsuche.transform.ExpLogInverseOperator;
import de.regelsuche.transform.LogProductAssumptionOperator;
import de.regelsuche.transform.PowerRootAssumptionRules;
import de.regelsuche.transform.SubstitutionExpansionOperator;
import de.regelsuche.transform.SubstitutionIntroductionOperator;
import de.regelsuche.transform.TrigPowerReductionOperator;
import de.regelsuche.transform.TrigPythagoreanIdentityOperator;
import de.regelsuche.util.AtomicJsonFile;
import de.regelsuche.validation.SymPyDiscoveryOracleAdapter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/** Runs Discovery Campaign 2 and compares resolved blockers against campaign 1. */
public final class DiscoveryCampaignTwoRunner {
    private static final ObjectMapper JSON = new ObjectMapper()
        .findAndRegisterModules()
        .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

    private final DiscoveryBenchmarkScenarioLoader loader = new DiscoveryBenchmarkScenarioLoader();
    private final SymPyDiscoveryOracleAdapter oracle = new SymPyDiscoveryOracleAdapter();

    public static void main(String[] args) {
        Path repoRoot = args.length == 0
            ? Path.of(".").toAbsolutePath().normalize()
            : Path.of(args[0]).toAbsolutePath().normalize();
        new DiscoveryCampaignTwoRunner().writeReport(repoRoot.resolve("app/build/reports/discovery-campaign-2"));
    }

    public CampaignReport run() {
        Map<String, String> before = campaignOneStatuses();
        List<CaseResult> results = cases().stream()
            .map(campaignCase -> evaluate(campaignCase, before.getOrDefault(campaignCase.id(), "N/A")))
            .sorted(Comparator.comparing(CaseResult::id))
            .toList();
        List<String> blockers = results.stream()
            .filter(result -> !result.success())
            .map(result -> result.id() + ": "
                + (result.failureReason().isBlank() ? result.notes() : result.failureReason()))
            .toList();
        List<ComparisonRow> comparison = results.stream()
            .map(result -> new ComparisonRow(
                result.id(),
                result.beforeStatus(),
                result.success() ? "success" : "blocked",
                result.shortcutOperatorId(),
                result.ablationStatus(),
                result.shortcutAssumptions()))
            .toList();
        SymPyQaHarness.QaSummary qaSummary = qaSummary(results, null);
        return new CampaignReport("discovery-campaign-2", results, blockers, comparison, qaSummary);
    }

    public CampaignReport writeReport(Path outputDirectory) {
        try {
            Files.createDirectories(outputDirectory);
            CampaignReport report = run();
            AtomicJsonFile.writeUtf8(
                outputDirectory.resolve("discovery-campaign-2.json"),
                JSON.writerWithDefaultPrettyPrinter().writeValueAsString(report)
            );
            Files.writeString(
                outputDirectory.resolve("regression-comparison.md"),
                renderComparison(report),
                StandardCharsets.UTF_8
            );
            Files.writeString(
                outputDirectory.resolve("blockers.md"),
                renderBlockers(report),
                StandardCharsets.UTF_8
            );
            Files.writeString(
                outputDirectory.resolve("promoted-candidates.md"),
                renderPromotedCandidates(report),
                StandardCharsets.UTF_8
            );
            Files.writeString(
                outputDirectory.resolve("new-operator-suggestions.md"),
                renderOperatorSuggestions(report),
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

    private Map<String, String> campaignOneStatuses() {
        DiscoveryCampaignOneRunner.CampaignReport campaignOne = new DiscoveryCampaignOneRunner().run();
        Map<String, String> statuses = new LinkedHashMap<>();
        for (DiscoveryCampaignOneRunner.CaseResult result : campaignOne.results()) {
            statuses.put(result.id(), result.success() ? "success" : "blocked");
        }
        return Map.copyOf(statuses);
    }

    private SymPyQaHarness.QaSummary qaSummary(List<CaseResult> results, Path outputDirectory) {
        Path targetDirectory = outputDirectory;
        try {
            if (targetDirectory == null) {
                targetDirectory = Files.createTempDirectory("discovery-campaign-2-qa-");
            }
            return new SymPyQaHarness().run(
                results.stream().map(CaseResult::inputExpression).toList(),
                targetDirectory
            );
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private CaseResult evaluate(CampaignCase campaignCase, String beforeStatus) {
        DiscoveryBenchmarkScenario scenario = campaignCase.scenario();
        DiscoveryBenchmarkEvidence enabled = new DiscoveryBenchmarkExecutor(loader).execute(scenario);
        SymPyDiscoveryOracleAdapter.OracleResult oracleResult =
            oracle.equivalence(scenario.inputExpression(), scenario.targetExpression());
        AblationResult ablation = campaignCase.primaryOperatorId().isBlank()
            ? AblationResult.notApplicable()
            : runAblation(campaignCase, enabled);
        DiscoveryBenchmarkEvidence.EvidenceEdge shortcut = shortcutEdge(enabled, campaignCase).orElse(null);
        return new CaseResult(
            campaignCase.id(),
            campaignCase.family(),
            scenario.inputExpression(),
            scenario.targetExpression(),
            beforeStatus,
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

    private String renderComparison(CampaignReport report) {
        StringBuilder out = new StringBuilder();
        out.append("# Campaign 1 vs Campaign 2\n\n");
        out.append("| case | beforeStatus | afterStatus | newShortcutOperator | ablation | assumptions |\n");
        out.append("| --- | --- | --- | --- | --- | --- |\n");
        for (ComparisonRow row : report.comparison()) {
            out.append("| ").append(escape(row.caseId()))
                .append(" | ").append(escape(row.beforeStatus()))
                .append(" | ").append(escape(row.afterStatus()))
                .append(" | ").append(escape(orDash(row.newShortcutOperator())))
                .append(" | ").append(escape(orDash(row.ablation())))
                .append(" | ").append(escape(row.assumptions().isEmpty() ? "—" : String.join(", ", row.assumptions())))
                .append(" |\n");
        }
        return out.toString();
    }

    private String renderBlockers(CampaignReport report) {
        StringBuilder out = new StringBuilder("# Remaining blockers\n\n");
        if (report.blockers().isEmpty()) {
            out.append("- none\n");
            return out.toString();
        }
        for (String blocker : report.blockers()) {
            out.append("- ").append(escape(blocker)).append('\n');
        }
        return out.toString();
    }

    private String renderPromotedCandidates(CampaignReport report) {
        StringBuilder out = new StringBuilder("# Promoted candidates from Campaign 1 blockers\n\n");
        List<CaseResult> promoted = report.results().stream()
            .filter(result -> "blocked".equals(result.beforeStatus()) && result.success())
            .toList();
        if (promoted.isEmpty()) {
            out.append("- none\n");
            return out.toString();
        }
        for (CaseResult result : promoted) {
            out.append("- ").append(escape(result.id()))
                .append(": blocked -> success")
                .append(" (operator=").append(escape(orDash(result.shortcutOperatorId())))
                .append(", pack=").append(escape(orDash(result.shortcutPackId())))
                .append(")\n");
        }
        return out.toString();
    }

    private String renderOperatorSuggestions(CampaignReport report) {
        StringBuilder out = new StringBuilder("# New operator suggestions\n\n");
        Map<String, List<CaseResult>> byFamily = report.results().stream()
            .filter(result -> !result.success())
            .collect(Collectors.groupingBy(CaseResult::family, LinkedHashMap::new, Collectors.toList()));
        if (byFamily.isEmpty()) {
            out.append("- none\n");
            return out.toString();
        }
        for (Map.Entry<String, List<CaseResult>> entry : byFamily.entrySet()) {
            out.append("## ").append(escape(entry.getKey())).append("\n\n");
            for (CaseResult result : entry.getValue()) {
                out.append("- ").append(escape(result.id()))
                    .append(": add/refine operator for ")
                    .append(escape(result.inputExpression()))
                    .append(" -> ")
                    .append(escape(result.targetExpression()))
                    .append("\n");
            }
            out.append('\n');
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
                "trig-pythagorean",
                "trigonometric",
                "sin(x)^2 + cos(x)^2",
                "1",
                List.of("trig_pythagorean_identity"),
                TrigPythagoreanIdentityOperator.RULE_ID,
                List.of("sympy-trig-basic"),
                "pythagorean identity promoted from Campaign 1 blocker"
            ),
            new CampaignCase(
                "trig-sin-complement",
                "trigonometric",
                "1 - sin(x)^2",
                "cos(x)^2",
                List.of("trig_power_reduction"),
                TrigPowerReductionOperator.RULE_ID,
                List.of("sympy-trig-basic"),
                "power reduction identity"
            ),
            new CampaignCase(
                "trig-cos-complement",
                "trigonometric",
                "1 - cos(x)^2",
                "sin(x)^2",
                List.of("trig_power_reduction"),
                TrigPowerReductionOperator.RULE_ID,
                List.of("sympy-trig-basic"),
                "power reduction identity"
            ),
            new CampaignCase(
                "trig-tan-secant",
                "trigonometric",
                "tan(x)^2 + 1",
                "sec(x)^2",
                List.of("trig_pythagorean_identity"),
                TrigPythagoreanIdentityOperator.RULE_ID,
                List.of("sympy-trig-basic"),
                "tan-secant identity"
            ),
            new CampaignCase(
                "log-product-assumptions",
                "logarithmic",
                "log(a * b)",
                "log(a) + log(b)",
                List.of("log_product_assumption"),
                LogProductAssumptionOperator.RULE_ID,
                List.of("sympy-log-basic"),
                "assumption-carrying log product rule"
            ),
            new CampaignCase(
                "exp-log-inverse",
                "logarithmic",
                "exp(log(x))",
                "x",
                List.of("exp_log_inverse"),
                ExpLogInverseOperator.RULE_ID,
                List.of("sympy-log-basic"),
                "assumption-carrying exp/log inverse"
            ),
            new CampaignCase(
                "sqrt-square-assumptions",
                "root",
                "sqrt(x^2)",
                "x",
                List.of("power_root_assumptions"),
                PowerRootAssumptionRules.RULE_ID,
                List.of("sympy-polynomial-basic"),
                "assumption-carrying root/power simplification"
            ),
            new CampaignCase(
                "substitution-hidden-structure",
                "substitution",
                "(a+b)^2 + 6*(a+b) + 5",
                "(a+b+1) * (a+b+5)",
                List.of("substitution_introduction", "factor_candidate", "substitution_expansion"),
                SubstitutionExpansionOperator.RULE_ID,
                List.of("sympy-polynomial-basic"),
                "discover hidden structure via substitution and expand back"
            )
        );
    }

    public record CampaignReport(
        String id,
        List<CaseResult> results,
        List<String> blockers,
        List<ComparisonRow> comparison,
        SymPyQaHarness.QaSummary qaSummary
    ) {
        public CampaignReport {
            results = results == null ? List.of() : List.copyOf(results);
            blockers = blockers == null ? List.of() : List.copyOf(blockers);
            comparison = comparison == null ? List.of() : List.copyOf(comparison);
        }
    }

    public record CaseResult(
        String id,
        String family,
        String inputExpression,
        String targetExpression,
        String beforeStatus,
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
            beforeStatus = beforeStatus == null || beforeStatus.isBlank() ? "N/A" : beforeStatus;
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

    public record ComparisonRow(
        String caseId,
        String beforeStatus,
        String afterStatus,
        String newShortcutOperator,
        String ablation,
        List<String> assumptions
    ) {
        public ComparisonRow {
            assumptions = assumptions == null ? List.of() : List.copyOf(assumptions);
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
                List.of(DiscoveryExpectation.BRIDGE_REQUIRED),
                operatorIds,
                enabledRulePacks,
                List.of(),
                List.of(),
                List.of(expectedRuleId),
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
