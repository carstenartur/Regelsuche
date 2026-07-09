package de.regelsuche.docs;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import de.regelsuche.transform.RationalNormalizationHypothesisOperator;
import de.regelsuche.transform.RepeatedSubexpressionFactorizationHypothesisOperator;
import de.regelsuche.transform.TelescopingFractionHypothesisOperator;
import de.regelsuche.util.AtomicJsonFile;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Validates mined generalized pattern hypotheses on generated holdout cases.
 *
 * <p>This closes the first automated discovery loop after pattern mining: support examples are no longer
 * enough for promotion. A generalized hypothesis must also succeed on generated holdout cases, survive
 * oracle validation, show useful ablation, and pass the public evidence gate.</p>
 */
final class GeneralizedHypothesisValidationRunner {
    static final String SOURCE_CAMPAIGN = "pattern-hypothesis-validation";

    private static final ObjectMapper JSON = new ObjectMapper()
        .findAndRegisterModules()
        .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

    private final DiscoveryBenchmarkScenarioLoader loader = new DiscoveryBenchmarkScenarioLoader();
    private final PromotionDecider decider = new PromotionDecider();
    private final PublicEvidenceGate publicEvidenceGate = new PublicEvidenceGate();

    ValidationReport run(PatternHypothesisMiner.PatternHypothesisReport patternReport) {
        List<PatternHypothesisMiner.GeneralizedHypothesis> hypotheses = patternReport == null
            ? List.of()
            : patternReport.hypotheses();
        List<ValidatedHypothesis> validated = new ArrayList<>();
        List<RejectedHypothesis> rejected = new ArrayList<>();
        List<PromotionRecord> promotionRecords = new ArrayList<>();

        for (PatternHypothesisMiner.GeneralizedHypothesis hypothesis : hypotheses) {
            List<HoldoutCase> holdouts = holdoutCases(hypothesis);
            if (holdouts.isEmpty()) {
                rejected.add(new RejectedHypothesis(hypothesis.hypothesisId(), hypothesis.family(), hypothesis.operatorId(),
                    "no-holdout-generator"));
                continue;
            }
            ValidatedHypothesis result = validate(hypothesis, holdouts);
            validated.add(result);
            promotionRecords.add(result.promotionRecord());
        }
        long publicAccepted = validated.stream().filter(ValidatedHypothesis::publicEvidenceAccepted).count();
        return new ValidationReport(
            validated.stream().sorted(Comparator.comparing(ValidatedHypothesis::hypothesisId)).toList(),
            rejected.stream().sorted(Comparator.comparing(RejectedHypothesis::hypothesisId)).toList(),
            promotionRecords.stream().sorted(Comparator.comparing(PromotionRecord::candidateId)).toList(),
            publicAccepted
        );
    }

    ValidationReport write(Path outputDirectory, PatternHypothesisMiner.PatternHypothesisReport patternReport) {
        try {
            Files.createDirectories(outputDirectory);
            ValidationReport report = run(patternReport);
            AtomicJsonFile.writeUtf8(
                outputDirectory.resolve("validated-hypotheses.json"),
                JSON.writerWithDefaultPrettyPrinter().writeValueAsString(report)
            );
            Files.writeString(
                outputDirectory.resolve("validated-hypotheses.md"),
                renderMarkdown(report),
                StandardCharsets.UTF_8
            );
            return report;
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private ValidatedHypothesis validate(
        PatternHypothesisMiner.GeneralizedHypothesis hypothesis,
        List<HoldoutCase> holdouts
    ) {
        List<HoldoutResult> results = holdouts.stream()
            .map(holdout -> validateHoldout(hypothesis, holdout))
            .toList();
        AblationEvidence ablation = aggregateAblation(hypothesis, results);
        PromotionRecord record = promotionRecord(hypothesis, results, ablation);
        PublicEvidenceGate.GateDecision gateDecision = publicEvidenceGate.evaluate(record, NoveltyStatus.NEW);
        return new ValidatedHypothesis(
            hypothesis.hypothesisId(),
            hypothesis.family(),
            hypothesis.operatorId(),
            hypothesis.leftPattern(),
            hypothesis.rightPattern(),
            hypothesis.supportCount(),
            hypothesis.supportingExampleIds(),
            results,
            ablation,
            record,
            gateDecision.accepted(),
            gateDecision.rejectionReasons()
        );
    }

    private HoldoutResult validateHoldout(
        PatternHypothesisMiner.GeneralizedHypothesis hypothesis,
        HoldoutCase holdout
    ) {
        DiscoveryBenchmarkScenario scenario = new DiscoveryBenchmarkScenario(
            holdout.id(),
            holdout.id(),
            holdout.inputExpression(),
            holdout.targetExpression(),
            List.of(),
            List.of(hypothesis.operatorId()),
            holdout.enabledRulePacks(),
            List.of(),
            List.of(),
            List.of(hypothesis.operatorId()),
            new DiscoveryBenchmarkScenario.MacroLearning(false, null, null),
            new DiscoveryBenchmarkScenario.Budgets(8, 240, 5000),
            new DiscoveryBenchmarkScenario.Gallery(false, 1, 2)
        );
        DiscoveryBenchmarkEvidence withCandidate = new DiscoveryBenchmarkExecutor(loader).execute(scenario);
        DiscoveryOperatorRegistry disabledRegistry = new DiscoveryOperatorRegistry()
            .register(new DefaultDiscoveryOperatorProvider());
        disabledRegistry.disable(hypothesis.operatorId());
        DiscoveryBenchmarkEvidence withoutCandidate = new DiscoveryBenchmarkExecutor(loader, disabledRegistry).execute(scenario);
        return new HoldoutResult(
            holdout.id(),
            holdout.inputExpression(),
            holdout.targetExpression(),
            withCandidate.success(),
            pathLength(withCandidate),
            statesExplored(withCandidate),
            withoutCandidate.success(),
            pathLength(withoutCandidate),
            statesExplored(withoutCandidate),
            withCandidate.oracleStatus(),
            withCandidate.oracleEvidence(),
            aggregateRulePath(withCandidate, hypothesis.operatorId())
        );
    }

    private AblationEvidence aggregateAblation(
        PatternHypothesisMiner.GeneralizedHypothesis hypothesis,
        List<HoldoutResult> results
    ) {
        boolean withSuccess = results.stream().allMatch(HoldoutResult::withSuccess);
        boolean withoutSuccess = results.stream().allMatch(HoldoutResult::withoutSuccess);
        int withPathLength = results.stream().mapToInt(HoldoutResult::withPathLength).sum();
        int withoutPathLength = results.stream().mapToInt(HoldoutResult::withoutPathLength).sum();
        long withStatesExplored = results.stream().mapToLong(HoldoutResult::withStatesExplored).sum();
        long withoutStatesExplored = results.stream().mapToLong(HoldoutResult::withoutStatesExplored).sum();
        return AblationEvidence.compare(
            withSuccess,
            withPathLength,
            withStatesExplored,
            withoutSuccess,
            withoutPathLength,
            withoutStatesExplored,
            "holdout validation for " + hypothesis.hypothesisId() + " on " + results.size() + " generated cases"
        );
    }

    private PromotionRecord promotionRecord(
        PatternHypothesisMiner.GeneralizedHypothesis hypothesis,
        List<HoldoutResult> results,
        AblationEvidence ablation
    ) {
        HoldoutResult representative = results.getFirst();
        List<String> rulePath = results.stream()
            .flatMap(result -> result.rulePath().stream())
            .filter(rule -> rule != null && !rule.isBlank())
            .distinct()
            .toList();
        List<String> assumptions = assumptions(hypothesis, results);
        PromotionObservation observation = new PromotionObservation(
            hypothesis.hypothesisId() + "-holdout",
            SOURCE_CAMPAIGN,
            LocalDate.of(2026, 12, 1).toString(),
            hypothesis.family(),
            representative.inputExpression(),
            representative.targetExpression(),
            results.stream().allMatch(HoldoutResult::withSuccess),
            oracleStatus(results),
            oracleEvidence(results),
            ablation.ablationStatus(),
            hypothesis.operatorId(),
            sourcePack(hypothesis.operatorId()),
            assumptions,
            "generalized from " + hypothesis.supportCount() + " support examples and validated on generated holdouts",
            rulePath.isEmpty() ? List.of(hypothesis.operatorId()) : rulePath,
            results.stream().allMatch(HoldoutResult::withSuccess),
            false,
            false,
            true
        );
        return decider.decide(observation, ablation);
    }

    private List<String> assumptions(PatternHypothesisMiner.GeneralizedHypothesis hypothesis, List<HoldoutResult> results) {
        List<String> assumptions = new ArrayList<>();
        assumptions.add("generatedHypothesis.id=" + hypothesis.hypothesisId());
        assumptions.add("generatedHypothesis.supportCount=" + hypothesis.supportCount());
        assumptions.add("generatedHypothesis.leftPattern=" + hypothesis.leftPattern());
        assumptions.add("generatedHypothesis.rightPattern=" + hypothesis.rightPattern());
        assumptions.addAll(hypothesis.parameterRelations().stream()
            .map(relation -> "generatedHypothesis.parameterRelation=" + relation)
            .toList());
        assumptions.addAll(results.stream()
            .map(result -> "generatedHoldout." + result.holdoutId() + "="
                + result.inputExpression() + " -> " + result.targetExpression())
            .toList());
        return List.copyOf(assumptions);
    }

    private List<HoldoutCase> holdoutCases(PatternHypothesisMiner.GeneralizedHypothesis hypothesis) {
        String operator = hypothesis.operatorId();
        if (RepeatedSubexpressionFactorizationHypothesisOperator.RULE_ID.equals(operator)) {
            return List.of(
                new HoldoutCase("holdout-rsf-explicit-uv", "u * v + u * w", "u * (v + w)", List.of()),
                new HoldoutCase("holdout-rsf-explicit-mn", "m * n - m * p", "m * (n - p)", List.of())
            );
        }
        if (TelescopingFractionHypothesisOperator.RULE_ID.equals(operator)) {
            return List.of(
                new HoldoutCase("holdout-tel-q56", "1 / ((q + 5) * (q + 6))", "1 / (q + 5) - 1 / (q + 6)", List.of()),
                new HoldoutCase("holdout-tel-r78", "1 / ((r + 7) * (r + 8))", "1 / (r + 7) - 1 / (r + 8)", List.of())
            );
        }
        if (RationalNormalizationHypothesisOperator.RULE_ID.equals(operator)) {
            return List.of(
                new HoldoutCase("holdout-rn-shared-denom-add", "alpha / gamma + beta / gamma", "(alpha + beta) / gamma", List.of()),
                new HoldoutCase("holdout-rn-shared-denom-sub", "alpha / gamma - beta / gamma", "(alpha - beta) / gamma", List.of())
            );
        }
        return List.of();
    }

    private String sourcePack(String operatorId) {
        if (RepeatedSubexpressionFactorizationHypothesisOperator.RULE_ID.equals(operatorId)) {
            return "sympy-polynomial-basic";
        }
        if (TelescopingFractionHypothesisOperator.RULE_ID.equals(operatorId)) {
            return "sympy-rational-basic";
        }
        if (RationalNormalizationHypothesisOperator.RULE_ID.equals(operatorId)) {
            return "rational-basic";
        }
        return "generated-holdout";
    }

    private int pathLength(DiscoveryBenchmarkEvidence evidence) {
        return Math.max(0, evidence.withoutMacroRun().path().size() - 1);
    }

    private long statesExplored(DiscoveryBenchmarkEvidence evidence) {
        return evidence.withoutMacroRun().analytics().statesExplored();
    }

    private List<String> aggregateRulePath(DiscoveryBenchmarkEvidence evidence, String fallbackOperatorId) {
        List<String> path = evidence.withoutMacroRun().appliedRuleIds();
        if (path == null || path.isEmpty()) {
            return List.of(fallbackOperatorId);
        }
        return List.copyOf(path);
    }

    private String oracleStatus(List<HoldoutResult> results) {
        if (results.stream().anyMatch(result -> "DISAGREE".equalsIgnoreCase(result.oracleStatus()))) {
            return "DISAGREE";
        }
        if (results.stream().anyMatch(result -> "AGREE".equalsIgnoreCase(result.oracleStatus()))) {
            return "AGREE";
        }
        if (results.stream().anyMatch(result -> "UNKNOWN".equalsIgnoreCase(result.oracleStatus()))) {
            return "UNKNOWN";
        }
        return "UNAVAILABLE";
    }

    private String oracleEvidence(List<HoldoutResult> results) {
        Set<String> evidence = new LinkedHashSet<>();
        for (HoldoutResult result : results) {
            if (!result.oracleEvidence().isBlank()) {
                evidence.add(result.holdoutId() + ": " + result.oracleEvidence());
            }
        }
        return String.join("; ", evidence);
    }

    String renderMarkdown(ValidationReport report) {
        StringBuilder out = new StringBuilder("# Validated generalized hypotheses\n\n");
        out.append("| Hypothesis | Family | Operator | Support | Holdouts | Ablation | Public evidence |\n");
        out.append("| --- | --- | --- | ---: | ---: | --- | --- |\n");
        for (ValidatedHypothesis hypothesis : report.validatedHypotheses()) {
            out.append("| ").append(escape(hypothesis.hypothesisId()))
                .append(" | ").append(escape(hypothesis.family()))
                .append(" | ").append(escape(hypothesis.operatorId()))
                .append(" | ").append(hypothesis.supportCount())
                .append(" | ").append(hypothesis.holdoutResults().size())
                .append(" | ").append(escape(hypothesis.ablationEvidence().ablationStatus()))
                .append(" | ").append(hypothesis.publicEvidenceAccepted() ? "accepted" : escape(String.join(", ", hypothesis.publicEvidenceRejectionReasons())))
                .append(" |\n");
        }
        if (!report.rejectedHypotheses().isEmpty()) {
            out.append("\n## Rejected hypotheses\n\n");
            for (RejectedHypothesis rejected : report.rejectedHypotheses()) {
                out.append("- ").append(escape(rejected.hypothesisId()))
                    .append(": ").append(escape(rejected.reason()))
                    .append("\n");
            }
        }
        return out.toString();
    }

    private String escape(String value) {
        return value == null ? "" : value.replace("|", "\\|").replace("\n", " ");
    }

    record ValidationReport(
        List<ValidatedHypothesis> validatedHypotheses,
        List<RejectedHypothesis> rejectedHypotheses,
        List<PromotionRecord> promotionRecords,
        long publicAcceptedCount
    ) {
        ValidationReport {
            validatedHypotheses = validatedHypotheses == null ? List.of() : List.copyOf(validatedHypotheses);
            rejectedHypotheses = rejectedHypotheses == null ? List.of() : List.copyOf(rejectedHypotheses);
            promotionRecords = promotionRecords == null ? List.of() : List.copyOf(promotionRecords);
        }
    }

    record ValidatedHypothesis(
        String hypothesisId,
        String family,
        String operatorId,
        String leftPattern,
        String rightPattern,
        int supportCount,
        List<String> supportingExampleIds,
        List<HoldoutResult> holdoutResults,
        AblationEvidence ablationEvidence,
        PromotionRecord promotionRecord,
        boolean publicEvidenceAccepted,
        List<String> publicEvidenceRejectionReasons
    ) {
        ValidatedHypothesis {
            supportingExampleIds = supportingExampleIds == null ? List.of() : List.copyOf(supportingExampleIds);
            holdoutResults = holdoutResults == null ? List.of() : List.copyOf(holdoutResults);
            publicEvidenceRejectionReasons = publicEvidenceRejectionReasons == null ? List.of() : List.copyOf(publicEvidenceRejectionReasons);
        }
    }

    record RejectedHypothesis(String hypothesisId, String family, String operatorId, String reason) {
        RejectedHypothesis {
            reason = reason == null ? "" : reason;
        }
    }

    record HoldoutResult(
        String holdoutId,
        String inputExpression,
        String targetExpression,
        boolean withSuccess,
        int withPathLength,
        long withStatesExplored,
        boolean withoutSuccess,
        int withoutPathLength,
        long withoutStatesExplored,
        String oracleStatus,
        String oracleEvidence,
        List<String> rulePath
    ) {
        HoldoutResult {
            oracleStatus = oracleStatus == null || oracleStatus.isBlank() ? "UNAVAILABLE" : oracleStatus;
            oracleEvidence = oracleEvidence == null ? "" : oracleEvidence;
            rulePath = rulePath == null ? List.of() : List.copyOf(rulePath);
        }
    }

    private record HoldoutCase(String id, String inputExpression, String targetExpression, List<String> enabledRulePacks) {
        private HoldoutCase {
            enabledRulePacks = enabledRulePacks == null ? List.of() : List.copyOf(enabledRulePacks);
        }
    }
}
