package de.regelsuche.docs;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import de.regelsuche.moves.report.MoveTreeReport;
import de.regelsuche.moves.report.MoveTreeReportAssembler;
import de.regelsuche.moves.report.MoveTreeReportWriter;
import de.regelsuche.scoring.ExpressionScorer;
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
import java.util.ArrayList;
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
        return writeReport(outputDirectory, run());
    }

    CampaignReport writeReport(Path outputDirectory, CampaignReport report) {
        try {
            Files.createDirectories(outputDirectory);
            AtomicJsonFile.writeUtf8(
                outputDirectory.resolve("discovery-campaign-5.json"),
                JSON.writerWithDefaultPrettyPrinter().writeValueAsString(report)
            );
            Files.writeString(
                outputDirectory.resolve("hidden-structure-report.md"),
                renderMarkdown(report),
                StandardCharsets.UTF_8
            );
            writeMoveTreeReport(outputDirectory);
            return report;
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    /**
     * Builds and writes the {@code move-tree-report.json} / {@code .md} artefacts
     * from the first successful campaign path, modelling every applied step as a
     * countable {@link de.regelsuche.moves.RewriteMove}.
     */
    MoveTreeReport writeMoveTreeReport(Path outputDirectory) {
        MoveTreeReport moveTree = buildMoveTreeReport();
        return new MoveTreeReportWriter().write(outputDirectory, moveTree);
    }

    MoveTreeReport buildMoveTreeReport() {
        ExpressionScorer scorer = new ExpressionScorer();
        MoveTreeReportAssembler assembler = new MoveTreeReportAssembler();
        for (CampaignCase campaignCase : cases()) {
            DiscoveryBenchmarkScenario scenario = campaignCase.scenario();
            DiscoveryBenchmarkEvidence evidence = new DiscoveryBenchmarkExecutor(loader).execute(scenario);
            List<String> path = evidence.withoutMacroRun().path();
            List<String> rules = evidence.withoutMacroRun().appliedRuleIds();
            if (path.size() < 2 || rules.isEmpty()) {
                continue;
            }
            List<MoveTreeReportAssembler.PathStep> steps = pathSteps(evidence, path, rules);
            return assembler.assemble(
                campaignCase.id(),
                steps,
                List.of(),
                expression -> scorer.score(expression).weightedTotal());
        }
        return assembler.assemble("discovery-campaign-5", List.of(), List.of(), null);
    }

    private List<MoveTreeReportAssembler.PathStep> pathSteps(
        DiscoveryBenchmarkEvidence evidence, List<String> path, List<String> rules) {
        java.util.Map<String, DiscoveryBenchmarkEvidence.EvidenceEdge> edgesByKey = new java.util.LinkedHashMap<>();
        for (DiscoveryBenchmarkEvidence.EvidenceEdge edge : evidence.edges()) {
            edgesByKey.putIfAbsent(edgeKey(edge.from(), edge.to(), edge.ruleId()), edge);
        }
        List<MoveTreeReportAssembler.PathStep> steps = new ArrayList<>();
        int stepCount = Math.min(rules.size(), path.size() - 1);
        for (int i = 0; i < stepCount; i++) {
            String before = path.get(i);
            String after = path.get(i + 1);
            String ruleId = rules.get(i);
            String key = edgeKey(before, after, ruleId);
            DiscoveryBenchmarkEvidence.EvidenceEdge edge = edgesByKey.get(key);
            steps.add(new MoveTreeReportAssembler.PathStep(
                before,
                after,
                ruleId,
                edge == null ? "" : edge.operatorId(),
                edge == null ? List.of() : edge.assumptions(),
                edge == null ? "" : edge.source()));
        }
        return steps;
    }

    private String edgeKey(String from, String to, String ruleId) {
        return stripWhitespace(from) + "->" + stripWhitespace(to) + "|" + (ruleId == null ? "" : ruleId);
    }

    private String stripWhitespace(String value) {
        return value == null ? "" : value.replaceAll("\\s+", "");
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
        List<String> shortcutAssumptions = ensureSubstitutionEvidence(shortcut == null ? List.of() : shortcut.assumptions());
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
        if ("substitution".equals(campaignCase.family())) {
            List<DiscoveryBenchmarkEvidence.EvidenceEdge> allEdges = evidence.edges();
            Comparator<DiscoveryBenchmarkEvidence.EvidenceEdge> substitutionOrdering = substitutionEdgeOrdering(campaignCase);
            Optional<DiscoveryBenchmarkEvidence.EvidenceEdge> substitutionEdge = firstMatching(
                allEdges,
                edge -> SubstitutionIntroductionOperator.RULE_ID.equals(edge.ruleId()) && hasSubstitutionEvidence(edge),
                substitutionOrdering
            );
            if (substitutionEdge.isPresent()) {
                return substitutionEdge;
            }
            Optional<DiscoveryBenchmarkEvidence.EvidenceEdge> substitutionOperatorEdge = firstMatching(
                allEdges,
                edge -> campaignCase.operatorIds().contains(edge.operatorId()) && hasSubstitutionEvidence(edge),
                substitutionOrdering
            );
            if (substitutionOperatorEdge.isPresent()) {
                return substitutionOperatorEdge;
            }
            Optional<DiscoveryBenchmarkEvidence.EvidenceEdge> substitutionEvidenceEdge = firstMatching(
                allEdges,
                this::hasSubstitutionEvidence,
                substitutionOrdering
            );
            if (substitutionEvidenceEdge.isPresent()) {
                return substitutionEvidenceEdge;
            }
            Optional<DiscoveryBenchmarkEvidence.EvidenceEdge> partialSubstitutionEvidenceEdge = firstMatching(
                allEdges,
                edge -> campaignCase.operatorIds().contains(edge.operatorId()) && hasAnySubstitutionEvidence(edge),
                substitutionOrdering
            );
            if (partialSubstitutionEvidenceEdge.isPresent()) {
                return partialSubstitutionEvidenceEdge;
            }
        }
        return firstMatching(selected, edge -> !campaignCase.expectedRuleId().isBlank() && edge.ruleId().equals(campaignCase.expectedRuleId()))
            .or(() -> firstMatching(selected, edge -> campaignCase.operatorIds().contains(edge.operatorId())))
            .or(() -> firstMatching(selected, edge -> !edge.assumptions().isEmpty()))
            .or(() -> firstMatching(selected, edge -> true));
    }

    private Optional<DiscoveryBenchmarkEvidence.EvidenceEdge> firstMatching(
        List<DiscoveryBenchmarkEvidence.EvidenceEdge> edges,
        java.util.function.Predicate<DiscoveryBenchmarkEvidence.EvidenceEdge> predicate
    ) {
        return firstMatching(edges, predicate, edgeOrdering());
    }

    private Optional<DiscoveryBenchmarkEvidence.EvidenceEdge> firstMatching(
        List<DiscoveryBenchmarkEvidence.EvidenceEdge> edges,
        java.util.function.Predicate<DiscoveryBenchmarkEvidence.EvidenceEdge> predicate,
        Comparator<DiscoveryBenchmarkEvidence.EvidenceEdge> ordering
    ) {
        return edges.stream()
            .filter(predicate)
            .sorted(ordering)
            .findFirst();
    }

    private Comparator<DiscoveryBenchmarkEvidence.EvidenceEdge> substitutionEdgeOrdering(CampaignCase campaignCase) {
        return Comparator
            .comparing((DiscoveryBenchmarkEvidence.EvidenceEdge edge) -> !edge.tags().contains("selected-path"))
            .thenComparing(edge -> !SubstitutionIntroductionOperator.RULE_ID.equals(edge.ruleId()))
            .thenComparing(edge -> !campaignCase.operatorIds().contains(edge.operatorId()))
            .thenComparingInt(edge -> edge.assumptions().size())
            .thenComparing(edgeOrdering());
    }

    private Comparator<DiscoveryBenchmarkEvidence.EvidenceEdge> edgeOrdering() {
        return Comparator
            .comparing(DiscoveryBenchmarkEvidence.EvidenceEdge::from)
            .thenComparing(DiscoveryBenchmarkEvidence.EvidenceEdge::to)
            .thenComparing(DiscoveryBenchmarkEvidence.EvidenceEdge::ruleId)
            .thenComparing(DiscoveryBenchmarkEvidence.EvidenceEdge::operatorId)
            .thenComparing(DiscoveryBenchmarkEvidence.EvidenceEdge::source)
            .thenComparing(DiscoveryBenchmarkEvidence.EvidenceEdge::packId)
            .thenComparing(edge -> String.join("|", sortedCopy(edge.assumptions())));
    }

    private List<String> sortedCopy(List<String> values) {
        ArrayList<String> copy = new ArrayList<>(values == null ? List.of() : values);
        copy.sort(String::compareTo);
        return copy;
    }

    private boolean hasSubstitutionEvidence(DiscoveryBenchmarkEvidence.EvidenceEdge edge) {
        List<String> assumptions = edge.assumptions();
        return assumptions.stream().anyMatch(value -> value.startsWith("substitution.placeholder."))
            && assumptions.stream().anyMatch(value -> value.startsWith("substitution.occurrences."))
            && assumptions.stream().anyMatch(value -> value.startsWith("substitution.substituted"));
    }

    private boolean hasAnySubstitutionEvidence(DiscoveryBenchmarkEvidence.EvidenceEdge edge) {
        return edge.assumptions().stream().anyMatch(value -> value.startsWith("substitution."));
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

    private List<String> ensureSubstitutionEvidence(List<String> assumptions) {
        return assumptions == null ? List.of() : List.copyOf(assumptions);
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
                "new: multi-step complete-square with residue"));
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
                new DiscoveryBenchmarkScenario.Budgets(8, 220, 2000),
                new DiscoveryBenchmarkScenario.Gallery(false, 1, 3)
            );
        }
    }

    private record AblationResult(String status, String notes) {
    }
}
