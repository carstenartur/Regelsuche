package de.regelsuche.docs;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import de.regelsuche.transform.CommonSubexpressionDiscoveryOperator;
import de.regelsuche.transform.CompleteSquareBridgeOperator;
import de.regelsuche.transform.DifferenceOfSquaresPreparationOperator;
import de.regelsuche.transform.SubstitutionExpansionOperator;
import de.regelsuche.transform.SubstitutionIntroductionOperator;
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
import java.util.stream.Collectors;

/** Runs Discovery Campaign 5 with hidden-structure stress cases. */
public final class DiscoveryCampaignFiveRunner {
    private static final ObjectMapper JSON = new ObjectMapper()
        .findAndRegisterModules()
        .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

    private final DiscoveryBenchmarkScenarioLoader loader = new DiscoveryBenchmarkScenarioLoader();
    private final SymPyDiscoveryOracleAdapter oracle = new SymPyDiscoveryOracleAdapter();
    private final PromotionDecider decider = new PromotionDecider();

    public static void main(String[] args) {
        Path repoRoot = args.length == 0
            ? Path.of(".").toAbsolutePath().normalize()
            : Path.of(args[0]).toAbsolutePath().normalize();
        new DiscoveryCampaignFiveRunner().writeReport(repoRoot.resolve("app/build/reports/discovery-campaign-5"));
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
        return new CampaignReport("discovery-campaign-5", results, blockers);
    }

    public CampaignReport writeReport(Path outputDirectory) {
        try {
            Files.createDirectories(outputDirectory);
            CampaignReport report = run();
            AtomicJsonFile.writeUtf8(
                outputDirectory.resolve("discovery-campaign-5.json"),
                JSON.writerWithDefaultPrettyPrinter().writeValueAsString(report)
            );
            Files.writeString(
                outputDirectory.resolve("hidden-structure-report.md"),
                renderMarkdown(report),
                StandardCharsets.UTF_8
            );
            return report;
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private CaseResult evaluate(CampaignCase campaignCase) {
        DiscoveryBenchmarkScenario scenario = campaignCase.scenario();
        DiscoveryBenchmarkEvidence enabled = new DiscoveryBenchmarkExecutor(loader).execute(scenario);
        SymPyDiscoveryOracleAdapter.OracleResult oracleResult =
            oracle.equivalence(scenario.inputExpression(), scenario.targetExpression());
        AblationResult ablation = runAblation(campaignCase, enabled);
        DiscoveryBenchmarkEvidence.EvidenceEdge shortcut = shortcutEdge(enabled, campaignCase).orElse(null);

        String shortcutSource = shortcut == null ? "" : shortcut.source();
        String shortcutPack = shortcut == null ? "" : shortcut.packId();
        String shortcutOperator = shortcut == null ? "" : shortcut.operatorId();
        List<String> shortcutAssumptions = shortcut == null ? List.of() : shortcut.assumptions();
        List<String> rulePath = enabled.withoutMacroRun().appliedRuleIds();
        List<String> operatorsUsed = rulePath.stream()
            .map(ruleId -> ruleId == null ? "" : ruleId)
            .filter(ruleId -> !ruleId.isBlank())
            .distinct()
            .toList();

        PromotionObservation observation = new PromotionObservation(
            campaignCase.id(),
            "discovery-campaign-5",
            PromotionObservation.discoveryDateFor("discovery-campaign-5"),
            campaignCase.family(),
            scenario.inputExpression(),
            scenario.targetExpression(),
            enabled.success(),
            oracleResult.status().name(),
            oracleResult.evidence(),
            ablation.status(),
            shortcutOperator,
            shortcutPack,
            shortcutAssumptions,
            campaignCase.notes(),
            rulePath,
            !rulePath.isEmpty(),
            curatedPathPresent(shortcutSource),
            fallbackUsed(rulePath),
            "substitution".equals(campaignCase.family()) || rulePath.size() >= 2
        );
        PromotionRecord promotion = decider.decide(observation);

        return new CaseResult(
            campaignCase.id(),
            campaignCase.family(),
            campaignCase.ahaCategory(),
            campaignCase.noveltyAssessment(),
            scenario.inputExpression(),
            scenario.targetExpression(),
            enabled.success(),
            enabled.failureReason(),
            oracleResult.status().name(),
            oracleResult.evidence(),
            ablation.status(),
            shortcutSource,
            shortcutPack,
            shortcutOperator,
            shortcutAssumptions,
            rulePath,
            operatorsUsed,
            renderSubstitutionEvidence(shortcutAssumptions),
            promotion.stage(),
            promotion.promotionEligible(),
            !promotion.reusedMacroIds().isEmpty(),
            campaignCase.notes()
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

    private String renderSubstitutionEvidence(List<String> assumptions) {
        List<String> substitution = assumptions.stream()
            .filter(assumption -> assumption != null && assumption.startsWith("substitution."))
            .toList();
        if (substitution.isEmpty()) {
            return "—";
        }
        return substitution.stream().collect(Collectors.joining("; "));
    }

    private String renderMarkdown(CampaignReport report) {
        StringBuilder out = new StringBuilder();
        out.append("# Discovery Campaign 5: Hidden Structure Stress Cases\n\n");
        out.append("| Case | Aha category | New/similar | Input | Target | Rule path | Used operators | Placeholder/substitution evidence | Oracle | Ablation | Promotion stage | Macro reused |\n");
        out.append("| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |\n");
        for (CaseResult result : report.results()) {
            out.append("| ").append(escape(result.id()))
                .append(" | ").append(escape(result.ahaCategory()))
                .append(" | ").append(escape(result.noveltyAssessment()))
                .append(" | ").append(escape(result.inputExpression()))
                .append(" | ").append(escape(result.targetExpression()))
                .append(" | ").append(escape(result.rulePath().isEmpty() ? "—" : String.join(" -> ", result.rulePath())))
                .append(" | ").append(escape(result.operatorsUsed().isEmpty() ? "—" : String.join(", ", result.operatorsUsed())))
                .append(" | ").append(escape(orDash(result.substitutionEvidence())))
                .append(" | ").append(escape(result.oracleStatus().toLowerCase(Locale.ROOT)))
                .append(" | ").append(escape(result.ablationStatus().toLowerCase(Locale.ROOT)))
                .append(" | ").append(escape(result.promotionStage().name().toLowerCase(Locale.ROOT)))
                .append(" | ").append(result.reusedExistingMacro() ? "yes" : "no")
                .append(" |\n");
        }
        if (!report.blockers().isEmpty()) {
            out.append("\n## Blockers\n\n");
            for (String blocker : report.blockers()) {
                out.append("- ").append(escape(blocker)).append('\n');
            }
        }
        return out.toString();
    }

    private boolean curatedPathPresent(String source) {
        if (source == null || source.isBlank()) {
            return false;
        }
        String normalized = source.toLowerCase(Locale.ROOT);
        return normalized.equals("scenario")
            || normalized.equals("scenario-generic")
            || normalized.equals("hardcoded")
            || normalized.contains("scenario-exact-path");
    }

    private boolean fallbackUsed(List<String> rulePath) {
        return rulePath != null && rulePath.stream()
            .filter(ruleId -> ruleId != null)
            .map(ruleId -> ruleId.toLowerCase(Locale.ROOT))
            .anyMatch(ruleId -> ruleId.contains("fallback"));
    }

    private String orDash(String value) {
        return value == null || value.isBlank() ? "—" : value;
    }

    private String escape(String value) {
        return value == null ? "" : value.replace("|", "\\|").replace("\n", " ");
    }

    private List<CampaignCase> cases() {
        return List.of(
            new CampaignCase("a-hidden-binomial-sin", "polynomial", "A", "sin(x)^2 + 2*sin(x) + 1", "(sin(x) + 1)^2",
                List.of("complete_square_bridge"), CompleteSquareBridgeOperator.RULE_ID,
                List.of("sympy-polynomial-basic"), "new: trig-subexpression disguised as quadratic"),
            new CampaignCase("a-hidden-binomial-composite", "substitution", "A", "(a+b)^2 + 2*(a+b)*c + c^2", "(a + b + c)^2",
                List.of("substitution_introduction", "substitution_expansion", "complete_square_bridge"),
                CompleteSquareBridgeOperator.RULE_ID, List.of("sympy-polynomial-basic", "core"),
                "new: nested sum compressed into a single square"),
            new CampaignCase("a-hidden-binomial-cos", "polynomial", "A", "cos(t)^2 - 2*cos(t) + 1", "(cos(t) - 1)^2",
                List.of("complete_square_bridge"), CompleteSquareBridgeOperator.RULE_ID,
                List.of("sympy-polynomial-basic"), "new: negative middle term with function term"),
            new CampaignCase("a-hidden-binomial-shared-u-v", "substitution", "A", "(u+v)^2 + 2*(u+v) + 1", "((u + v) + 1)^2",
                List.of("substitution_introduction", "complete_square_bridge", "substitution_expansion"),
                CompleteSquareBridgeOperator.RULE_ID, List.of("sympy-polynomial-basic", "core"),
                "new: substitution depth differs from campaign 2/3"),
            new CampaignCase("a-hidden-binomial-p-q-r", "substitution", "A", "(p-q)^2 + 2*(p-q)*r + r^2", "(p - q + r)^2",
                List.of("substitution_introduction", "substitution_expansion", "complete_square_bridge"),
                CompleteSquareBridgeOperator.RULE_ID, List.of("sympy-polynomial-basic", "core"),
                "new: sign-sensitive hidden square"),

            new CampaignCase("b-complete-square-plus-rest", "substitution", "B", "x^2 + 6*x + 5", "(x + 3)^2 - 4",
                List.of("substitution_introduction", "complete_square_bridge"), CompleteSquareBridgeOperator.RULE_ID,
                List.of("sympy-polynomial-basic", "core"), "new: same family but target keeps residue"),
            new CampaignCase("b-complete-square-minus-rest", "substitution", "B", "x^2 - 10*x + 3", "(x - 5)^2 - 22",
                List.of("substitution_introduction", "complete_square_bridge"), CompleteSquareBridgeOperator.RULE_ID,
                List.of("sympy-polynomial-basic", "core"), "new: negative linear coefficient + residue"),
            new CampaignCase("b-complete-square-symbolic", "substitution", "B", "y^2 + 14*y + 20", "(y + 7)^2 - 29",
                List.of("substitution_introduction", "complete_square_bridge"), CompleteSquareBridgeOperator.RULE_ID,
                List.of("sympy-polynomial-basic", "core"), "new: symbolic variable variant with residue"),
            new CampaignCase("b-complete-square-nested", "substitution", "B", "(a+b)^2 + 4*(a+b) + 1", "((a + b) + 2)^2 - 3",
                List.of("substitution_introduction", "substitution_expansion", "complete_square_bridge"),
                CompleteSquareBridgeOperator.RULE_ID, List.of("sympy-polynomial-basic", "core"),
                "new: nested substitution + residue"),
            new CampaignCase("b-complete-square-offset", "substitution", "B", "m^2 - 2*m - 8", "(m - 1)^2 - 9",
                List.of("substitution_introduction", "complete_square_bridge"), CompleteSquareBridgeOperator.RULE_ID,
                List.of("sympy-polynomial-basic", "core"), "new: offset with negative constant"),

            new CampaignCase("c-common-subexpr-three-terms", "factorization", "C", "x*(y+1) + z*(y+1) + w*(y+1)", "(y+1)*(x+z+w)",
                List.of("common_subexpression_discovery"), CommonSubexpressionDiscoveryOperator.RULE_ID,
                List.of("sympy-polynomial-basic", "sympy-rational-basic"), "new: extends existing 2-term common factor case"),
            new CampaignCase("c-common-subexpr-a-b", "factorization", "C", "(a+b)*x + (a+b)*y + (a+b)*z", "(a+b)*(x+y+z)",
                List.of("common_subexpression_discovery"), CommonSubexpressionDiscoveryOperator.RULE_ID,
                List.of("sympy-polynomial-basic", "sympy-rational-basic"), "new: full three-term symbolic chain"),
            new CampaignCase("c-common-subexpr-trig", "factorization", "C", "sin(t)*(k+1) + cos(t)*(k+1)", "(k+1)*(sin(t)+cos(t))",
                List.of("common_subexpression_discovery"), CommonSubexpressionDiscoveryOperator.RULE_ID,
                List.of("sympy-polynomial-basic", "sympy-rational-basic"), "new: mixed trig factors with shared affine term"),
            new CampaignCase("c-common-subexpr-power", "factorization", "C", "(r^2+1)*m + (r^2+1)*n", "(r^2+1)*(m+n)",
                List.of("common_subexpression_discovery"), CommonSubexpressionDiscoveryOperator.RULE_ID,
                List.of("sympy-polynomial-basic", "sympy-rational-basic"), "new: nonlinear common subexpression"),
            new CampaignCase("c-common-subexpr-plus-one", "factorization", "C", "(x+1)*y + (x+1)*z + (x+1)", "(x+1)*(y+z+1)",
                List.of("common_subexpression_discovery"), CommonSubexpressionDiscoveryOperator.RULE_ID,
                List.of("sympy-polynomial-basic", "sympy-rational-basic"), "new: includes scalar tail term"),

            new CampaignCase("d-sophie-germain-xy", "polynomial", "D", "x^4 + 4*y^4",
                "(x^2 - 2*x*y + 2*y^2)*(x^2 + 2*x*y + 2*y^2)",
                List.of("sophie_germain_bridge"), DifferenceOfSquaresPreparationOperator.RULE_ID,
                List.of("sympy-polynomial-basic"), "new: canonical Sophie-Germain with two symbols"),
            new CampaignCase("d-sophie-germain-a-b", "polynomial", "D", "a^4 + 4*b^4",
                "(a^2 - 2*a*b + 2*b^2)*(a^2 + 2*a*b + 2*b^2)",
                List.of("sophie_germain_bridge"), DifferenceOfSquaresPreparationOperator.RULE_ID,
                List.of("sympy-polynomial-basic"), "new: symbolic analogue of xy case"),
            new CampaignCase("d-sophie-germain-u-v-w", "substitution", "D", "(u+v)^4 + 4*w^4",
                "((u+v)^2 - 2*(u+v)*w + 2*w^2)*((u+v)^2 + 2*(u+v)*w + 2*w^2)",
                List.of("substitution_introduction", "sophie_germain_bridge", "substitution_expansion"),
                DifferenceOfSquaresPreparationOperator.RULE_ID, List.of("sympy-polynomial-basic", "core"),
                "new: nested-substitution Sophie-Germain"),
            new CampaignCase("d-sophie-germain-p-qr", "substitution", "D", "p^4 + 4*(q+r)^4",
                "(p^2 - 2*p*(q+r) + 2*(q+r)^2)*(p^2 + 2*p*(q+r) + 2*(q+r)^2)",
                List.of("substitution_introduction", "sophie_germain_bridge", "substitution_expansion"),
                DifferenceOfSquaresPreparationOperator.RULE_ID, List.of("sympy-polynomial-basic", "core"),
                "new: hidden inner-sum fourth power"),
            new CampaignCase("d-sophie-germain-mn", "substitution", "D", "(m-n)^4 + 4*n^4",
                "((m-n)^2 - 2*(m-n)*n + 2*n^2)*((m-n)^2 + 2*(m-n)*n + 2*n^2)",
                List.of("substitution_introduction", "sophie_germain_bridge", "substitution_expansion"),
                DifferenceOfSquaresPreparationOperator.RULE_ID, List.of("sympy-polynomial-basic", "core"),
                "new: sign-sensitive Sophie-Germain variant"),

            new CampaignCase("e-multipath-shared-prefix", "substitution", "E", "(t+1)*(x+z) + (t+1)*(y+z)", "(t+1)*(x+y+2*z)",
                List.of("substitution_introduction", "common_subexpression_discovery", "substitution_expansion"),
                CommonSubexpressionDiscoveryOperator.RULE_ID, List.of("sympy-polynomial-basic", "core"),
                "new: competing paths via distribution or direct factoring"),
            new CampaignCase("e-multipath-factor-after-substitute", "substitution", "E", "(x+1)^2 - (x+1)", "(x+1)*x",
                List.of("substitution_introduction", "common_subexpression_discovery", "substitution_expansion"),
                CommonSubexpressionDiscoveryOperator.RULE_ID, List.of("sympy-polynomial-basic", "core"),
                "new: substitution then factoring of repeated atom"),
            new CampaignCase("e-multipath-complete-square", "substitution", "E", "(c+d)^2 + 12*(c+d) + 35", "((c+d)+6)^2 - 1",
                List.of("substitution_introduction", "complete_square_bridge", "substitution_expansion"),
                CompleteSquareBridgeOperator.RULE_ID, List.of("sympy-polynomial-basic", "core"),
                "new: multi-step complete-square with residue"),
            new CampaignCase("e-multipath-trig-square", "substitution", "E", "sin(x)^2 + 2*sin(x)*cos(x) + cos(x)^2", "(sin(x)+cos(x))^2",
                List.of("substitution_introduction", "complete_square_bridge", "substitution_expansion"),
                CompleteSquareBridgeOperator.RULE_ID, List.of("sympy-polynomial-basic", "core"),
                "new: hidden trig square via rearrangement"),
            new CampaignCase("e-multipath-two-sums", "substitution", "E", "(k+2)^2 + 2*(k+2)*(m+1) + (m+1)^2", "(k+m+3)^2",
                List.of("substitution_introduction", "substitution_expansion", "complete_square_bridge"),
                CompleteSquareBridgeOperator.RULE_ID, List.of("sympy-polynomial-basic", "core"),
                "new: two-level substitution chain"));
    }

    public record CampaignReport(
        String id,
        List<CaseResult> results,
        List<String> blockers
    ) {
        public CampaignReport {
            results = results == null ? List.of() : List.copyOf(results);
            blockers = blockers == null ? List.of() : List.copyOf(blockers);
        }
    }

    public record CaseResult(
        String id,
        String family,
        String ahaCategory,
        String noveltyAssessment,
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
        List<String> operatorsUsed,
        String substitutionEvidence,
        PromotionStage promotionStage,
        boolean promotionEligible,
        boolean reusedExistingMacro,
        String notes
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
            operatorsUsed = operatorsUsed == null ? List.of() : List.copyOf(operatorsUsed);
            substitutionEvidence = substitutionEvidence == null ? "" : substitutionEvidence;
            promotionStage = promotionStage == null ? PromotionStage.OBSERVED : promotionStage;
            notes = notes == null ? "" : notes;
        }
    }

    private record CampaignCase(
        String id,
        String family,
        String ahaCategory,
        String inputExpression,
        String targetExpression,
        List<String> operatorIds,
        String expectedRuleId,
        List<String> enabledRulePacks,
        String noveltyAssessment
    ) {
        private String primaryOperatorId() {
            return operatorIds().isEmpty() ? "" : operatorIds().getFirst();
        }

        private String notes() {
            return ahaCategory + " | " + noveltyAssessment;
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
                new DiscoveryBenchmarkScenario.Budgets(8, 260, 5000),
                new DiscoveryBenchmarkScenario.Gallery(false, 1, 3)
            );
        }
    }

    private record AblationResult(String status, String notes) {
    }
}
