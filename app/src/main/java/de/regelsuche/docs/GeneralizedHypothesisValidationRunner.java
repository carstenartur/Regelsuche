package de.regelsuche.docs;

import de.regelsuche.proof.ProofPolicy;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.FunctionExpr;
import de.regelsuche.ast.NumberExpr;
import de.regelsuche.ast.VariableExpr;
import de.regelsuche.canonical.ExpressionCanonicalizer;
import de.regelsuche.input.InputRequest;
import de.regelsuche.input.InputType;
import de.regelsuche.mining.DynamicOperatorCompiler;
import de.regelsuche.mining.DynamicPatternOperator;
import de.regelsuche.mining.RulePatternInstantiator;
import de.regelsuche.mining.RulePatternParser;
import de.regelsuche.parse.ExpressionFormatter;
import de.regelsuche.parse.ExpressionParser;
import de.regelsuche.transform.HypothesisOperator;
import de.regelsuche.transform.RationalNormalizationHypothesisOperator;
import de.regelsuche.transform.RepeatedSubexpressionFactorizationHypothesisOperator;
import de.regelsuche.transform.TelescopingFractionHypothesisOperator;
import de.regelsuche.transform.Transformation;
import de.regelsuche.util.AtomicJsonFile;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Validates mined generalized pattern hypotheses on generated holdout cases.
 *
 * <p>The generator deliberately separates support examples from validation by using deterministic
 * structural templates per operator family, filtering exact / canonical / alpha-equivalent /
 * inverse leakage against the mined support examples, and reporting coverage separately from the
 * benchmark/oracle evidence gathered on a representative positive sample.</p>
 */
final class GeneralizedHypothesisValidationRunner {
    static final String SOURCE_CAMPAIGN = "pattern-hypothesis-validation";

    private static final ObjectMapper JSON = new ObjectMapper()
        .findAndRegisterModules()
        .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

    private static final String GENERATOR_VERSION = "holdout-generator-v2";
    private static final long GENERATOR_SEED = 214L;
    private static final int MIN_POSITIVE_HOLDOUTS = 100;
    private static final int MIN_NEGATIVE_HOLDOUTS = 100;
    private static final int BENCHMARK_SAMPLE_SIZE = 8;
    private static final String COVERAGE_NOTE =
        "Generator coverage counts deterministic holdouts; oracle/ablation evidence only covers the benchmarked positive sample.";

    private final DiscoveryBenchmarkScenarioLoader loader = new DiscoveryBenchmarkScenarioLoader();
    private final PromotionDecider decider = new PromotionDecider();
    private final PublicEvidenceGate publicEvidenceGate = new PublicEvidenceGate();
    private final HoldoutLeakageChecker leakageChecker = new HoldoutLeakageChecker();
    private final ExpressionCanonicalizer canonicalizer = new ExpressionCanonicalizer();
    private final DynamicOperatorCompiler dynamicCompiler = new DynamicOperatorCompiler();
    private final RulePatternParser rulePatternParser = new RulePatternParser();
    private final RulePatternInstantiator rulePatternInstantiator = new RulePatternInstantiator();

    ValidationReport run(PatternHypothesisMiner.PatternHypothesisReport patternReport) {
        List<PatternHypothesisMiner.GeneralizedHypothesis> hypotheses = patternReport == null
            ? List.of()
            : patternReport.hypotheses();
        List<ValidatedHypothesis> validated = new ArrayList<>();
        List<RejectedHypothesis> rejected = new ArrayList<>();
        List<PromotionRecord> promotionRecords = new ArrayList<>();

        for (PatternHypothesisMiner.GeneralizedHypothesis hypothesis : hypotheses) {
            GeneratedHoldoutSuite holdouts = holdoutCases(hypothesis);
            if (holdouts.positiveHoldouts().size() < MIN_POSITIVE_HOLDOUTS) {
                rejected.add(new RejectedHypothesis(hypothesis.hypothesisId(), hypothesis.family(), hypothesis.operatorId(),
                    "insufficient-positive-holdouts:" + holdouts.positiveHoldouts().size()));
                continue;
            }
            if (holdouts.negativeHoldouts().size() < MIN_NEGATIVE_HOLDOUTS) {
                rejected.add(new RejectedHypothesis(hypothesis.hypothesisId(), hypothesis.family(), hypothesis.operatorId(),
                    "insufficient-negative-holdouts:" + holdouts.negativeHoldouts().size()));
                continue;
            }
            List<LeakageFinding> remainingLeakage = leakageChecker.findLeakage(hypothesis.supportExamples(),
                holdouts.allHoldouts());
            if (!remainingLeakage.isEmpty()) {
                throw new IllegalStateException("holdout leakage detected for " + hypothesis.hypothesisId() + ": " + remainingLeakage);
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
            publicAccepted,
            aggregateCoverage(validated)
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
        GeneratedHoldoutSuite holdouts
    ) {
        List<HoldoutResult> results = holdouts.positiveHoldouts().stream()
            .limit(BENCHMARK_SAMPLE_SIZE)
            .map(holdout -> validatePositiveHoldout(hypothesis, holdout))
            .toList();
        List<NegativeHoldoutResult> negativeResults = holdouts.negativeHoldouts().stream()
            .map(holdout -> validateNegativeHoldout(hypothesis, holdout))
            .toList();
        AblationEvidence ablation = aggregateAblation(hypothesis, results);
        PromotionRecord record = promotionRecord(hypothesis, results, negativeResults, ablation, holdouts.generatorCoverage());
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
            negativeResults,
            ablation,
            record,
            gateDecision.accepted(),
            gateDecision.rejectionReasons(),
            holdouts.generatorCoverage()
        );
    }

    private HoldoutResult validatePositiveHoldout(
        PatternHypothesisMiner.GeneralizedHypothesis hypothesis,
        HoldoutCase holdout
    ) {
        // For dynamic operators, we need to register them in the operator registry so the benchmark
        // executor can find them. We resolve the effective operator ID used in the scenario.
        HypothesisOperator compiledOperator = operatorFor(hypothesis);
        String effectiveOperatorId = (compiledOperator instanceof DynamicPatternOperator dynOp)
            ? dynOp.ruleId()
            : hypothesis.operatorId();

        DiscoveryBenchmarkScenario scenario = new DiscoveryBenchmarkScenario(
            holdout.id(),
            holdout.id(),
            holdout.inputExpression(),
            holdout.targetExpression(),
            List.of(),
            List.of(effectiveOperatorId),
            holdout.enabledRulePacks(),
            List.of(),
            List.of(),
            List.of(effectiveOperatorId),
            new DiscoveryBenchmarkScenario.MacroLearning(false, null, null),
            new DiscoveryBenchmarkScenario.Budgets(8, 240, 5000),
            new DiscoveryBenchmarkScenario.Gallery(false, 1, 2)
        );
        DiscoveryOperatorRegistry withRegistry = buildRegistryWithDynamic(compiledOperator, effectiveOperatorId);
        DiscoveryBenchmarkEvidence withCandidate = new DiscoveryBenchmarkExecutor(loader, withRegistry).execute(scenario);
        DiscoveryOperatorRegistry withoutRegistry = buildRegistryWithDynamic(compiledOperator, effectiveOperatorId);
        withoutRegistry.disable(effectiveOperatorId);
        DiscoveryBenchmarkEvidence withoutCandidate = new DiscoveryBenchmarkExecutor(loader, withoutRegistry).execute(scenario);
        return new HoldoutResult(
            holdout.id(),
            holdout.inputExpression(),
            holdout.targetExpression(),
            holdout.templateId(),
            holdout.structureClass(),
            holdout.domainClass(),
            holdout.assumptionClass(),
            holdout.seed(),
            holdout.generatorVersion(),
            holdout.provenance(),
            withCandidate.success(),
            pathLength(withCandidate),
            statesExplored(withCandidate),
            withoutCandidate.success(),
            pathLength(withoutCandidate),
            statesExplored(withoutCandidate),
            withCandidate.oracleStatus(),
            withCandidate.oracleEvidence(),
            aggregateRulePath(withCandidate)
        );
    }

    /**
     * Builds an operator registry that includes both the default operators and, if the
     * compiled operator is a {@link DynamicPatternOperator}, also registers it under its
     * effective rule ID so the benchmark executor can find it.
     */
    private DiscoveryOperatorRegistry buildRegistryWithDynamic(
        HypothesisOperator compiledOperator,
        String effectiveOperatorId
    ) {
        DiscoveryOperatorRegistry registry = new DiscoveryOperatorRegistry()
            .register(new DefaultDiscoveryOperatorProvider());
        if (compiledOperator instanceof DynamicPatternOperator dynOp) {
            HypothesisOperator capturedOp = dynOp; // effectively final for lambda
            registry.register(new DiscoveryOperatorProvider() {
                @Override
                public String id() {
                    return "dynamic-operator-provider:" + effectiveOperatorId;
                }

                @Override
                public List<DiscoveryOperatorProvider.DiscoveryOperatorDefinition> operators() {
                    return List.of(new DiscoveryOperatorProvider.DiscoveryOperatorDefinition(
                        effectiveOperatorId,
                        () -> capturedOp,
                        List.of(effectiveOperatorId)
                    ));
                }
            });
        }
        return registry;
    }

    private NegativeHoldoutResult validateNegativeHoldout(
        PatternHypothesisMiner.GeneralizedHypothesis hypothesis,
        HoldoutCase holdout
    ) {
        HypothesisOperator operator = operatorFor(hypothesis);
        List<Transformation> candidates = operator == null ? List.of() : operator.generateCandidates(holdout.inputExpression());
        // For dynamic operators the rule ID is the dynamic rule ID, not the original operatorId.
        // Check both the original operatorId and any dynamic rule that starts with the dynamic prefix.
        boolean operatorFired = candidates.stream().anyMatch(candidate ->
            hypothesis.operatorId().equals(candidate.rule())
            || candidate.rule().startsWith(DynamicPatternOperator.RULE_ID_PREFIX));
        boolean rewroteToForbiddenTarget = candidates.stream().anyMatch(candidate ->
            comparableExpressionKey(candidate.transformedExpression()).equals(comparableExpressionKey(holdout.targetExpression())));
        return new NegativeHoldoutResult(
            holdout.id(),
            holdout.inputExpression(),
            holdout.targetExpression(),
            holdout.templateId(),
            holdout.structureClass(),
            holdout.domainClass(),
            holdout.assumptionClass(),
            holdout.seed(),
            holdout.generatorVersion(),
            holdout.provenance(),
            !operatorFired && !rewroteToForbiddenTarget,
            operatorFired,
            rewroteToForbiddenTarget,
            candidates.stream().map(Transformation::transformedExpression).limit(5).toList()
        );
    }

    private AblationEvidence aggregateAblation(
        PatternHypothesisMiner.GeneralizedHypothesis hypothesis,
        List<HoldoutResult> results
    ) {
        boolean withSuccess = !results.isEmpty() && results.stream().allMatch(HoldoutResult::withSuccess);
        boolean withoutSuccess = !results.isEmpty() && results.stream().allMatch(HoldoutResult::withoutSuccess);
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
            "holdout validation for " + hypothesis.hypothesisId() + " on " + results.size() + " benchmarked positive cases"
        );
    }

    private PromotionRecord promotionRecord(
        PatternHypothesisMiner.GeneralizedHypothesis hypothesis,
        List<HoldoutResult> results,
        List<NegativeHoldoutResult> negativeResults,
        AblationEvidence ablation,
        GeneratorCoverage coverage
    ) {
        HoldoutResult representative = results.getFirst();
        List<String> rulePath = results.stream()
            .flatMap(result -> result.rulePath().stream())
            .filter(rule -> rule != null && !rule.isBlank())
            .distinct()
            .toList();
        List<String> assumptions = assumptions(hypothesis, results, negativeResults, coverage);
        PromotionObservation observation = new PromotionObservation(
            hypothesis.hypothesisId() + "-holdout",
            SOURCE_CAMPAIGN,
            LocalDate.of(2026, 12, 1).toString(),
            hypothesis.family(),
            representative.inputExpression(),
            representative.targetExpression(),
            results.stream().allMatch(HoldoutResult::withSuccess) && negativeResults.stream().allMatch(NegativeHoldoutResult::blocked),
            oracleStatus(results),
            oracleEvidence(results),
            ablation.ablationStatus(),
            hypothesis.operatorId(),
            sourcePack(hypothesis.operatorId()),
            assumptions,
            "generalized from " + hypothesis.supportCount()
                + " support examples; generated " + coverage.generatedPositiveCount()
                + " positive and " + coverage.generatedNegativeCount()
                + " negative holdouts; benchmarked " + results.size() + " positive cases",
            rulePath,
            !rulePath.isEmpty(),
            false,
            fallbackUsed(rulePath),
            macroOpportunity(hypothesis.family(), rulePath),
            ProofPolicy.PROOF_OPTIONAL,
            ""
        );
        return decider.decide(observation, ablation);
    }

    private List<String> assumptions(
        PatternHypothesisMiner.GeneralizedHypothesis hypothesis,
        List<HoldoutResult> results,
        List<NegativeHoldoutResult> negativeResults,
        GeneratorCoverage coverage
    ) {
        List<String> assumptions = new ArrayList<>();
        assumptions.add("generatedHypothesis.id=" + hypothesis.hypothesisId());
        assumptions.add("generatedHypothesis.supportCount=" + hypothesis.supportCount());
        assumptions.add("generatedHypothesis.leftPattern=" + hypothesis.leftPattern());
        assumptions.add("generatedHypothesis.rightPattern=" + hypothesis.rightPattern());
        assumptions.add("generatedHoldout.seed=" + GENERATOR_SEED);
        assumptions.add("generatedHoldout.version=" + GENERATOR_VERSION);
        assumptions.add("generatedHoldout.positiveCount=" + coverage.generatedPositiveCount());
        assumptions.add("generatedHoldout.negativeCount=" + coverage.generatedNegativeCount());
        assumptions.add("generatedHoldout.filteredLeakageCount=" + coverage.filteredLeakageCount());
        assumptions.addAll(hypothesis.parameterRelations().stream()
            .map(relation -> "generatedHypothesis.parameterRelation=" + relation)
            .toList());
        assumptions.addAll(results.stream()
            .map(result -> "generatedHoldout." + result.holdoutId() + "="
                + result.inputExpression() + " -> " + result.targetExpression()
                + " [" + result.templateId() + "]")
            .toList());
        assumptions.addAll(negativeResults.stream()
            .limit(5)
            .map(result -> "negativeHoldout." + result.holdoutId() + "="
                + result.inputExpression() + " !-> " + result.targetExpression()
                + " blocked=" + result.blocked())
            .toList());
        return List.copyOf(assumptions);
    }

    private GeneratedHoldoutSuite holdoutCases(PatternHypothesisMiner.GeneralizedHypothesis hypothesis) {
        HypothesisOperator operator = operatorFor(hypothesis);
        if (operator == null) {
            return GeneratedHoldoutSuite.empty();
        }
        List<HoldoutCase> generated = switch (hypothesis.operatorId()) {
            case RepeatedSubexpressionFactorizationHypothesisOperator.RULE_ID -> repeatedSubexpressionHoldouts(operator);
            case TelescopingFractionHypothesisOperator.RULE_ID -> telescopingHoldouts(operator);
            case RationalNormalizationHypothesisOperator.RULE_ID -> rationalNormalizationHoldouts(operator);
            default -> (operator instanceof DynamicPatternOperator dynOp)
                ? dynamicHoldouts(hypothesis, dynOp)
                : List.of();
        };
        if (generated.isEmpty()) {
            return GeneratedHoldoutSuite.empty();
        }
        List<LeakageFinding> leakage = leakageChecker.findLeakage(hypothesis.supportExamples(), generated);
        Set<String> leakingIds = leakage.stream().map(LeakageFinding::holdoutId).collect(Collectors.toCollection(LinkedHashSet::new));
        List<HoldoutCase> filtered = generated.stream()
            .filter(holdout -> !leakingIds.contains(holdout.id()))
            .toList();
        List<HoldoutCase> positives = filtered.stream()
            .filter(holdout -> holdout.expectation() == HoldoutExpectation.POSITIVE)
            .toList();
        List<HoldoutCase> negatives = filtered.stream()
            .filter(holdout -> holdout.expectation() == HoldoutExpectation.NEGATIVE)
            .toList();
        return new GeneratedHoldoutSuite(
            positives,
            negatives,
            coverage(filtered, leakage)
        );
    }

    /**
     * Generic holdout generator for dynamically compiled operators.
     *
     * <p>Instantiates the left-hand pattern with systematic variable combinations to build
     * positive holdouts, and constructs negative holdouts from structurally non-matching
     * expressions. The set is deterministic for reproducibility.</p>
     */
    private List<HoldoutCase> dynamicHoldouts(
        PatternHypothesisMiner.GeneralizedHypothesis hypothesis,
        DynamicPatternOperator op
    ) {
        List<HoldoutCase> holdouts = new ArrayList<>();
        // Variable pools for substituting placeholders (deterministic order)
        String[] pool1 = {"u", "v", "w", "m", "n", "p", "q", "r", "s", "t"};
        String[] pool2 = {"a", "b", "c", "d", "e", "f", "g", "h", "i", "j"};
        String[] pool3 = {"x", "y", "z", "k", "l"};
        addDynamicPositiveHoldouts(holdouts, op, pool1, pool2, pool3);
        addDynamicNegativeHoldouts(holdouts, op, pool1, pool2);
        return List.copyOf(holdouts);
    }

    private void addDynamicPositiveHoldouts(
        List<HoldoutCase> holdouts,
        DynamicPatternOperator op,
        String[] pool1,
        String[] pool2,
        String[] pool3
    ) {
        List<String> placeholders = extractUppercasePlaceholders(op.leftPatternText());
        if (placeholders.isEmpty()) {
            return;
        }
        de.regelsuche.mining.RulePatternNode leftNode;
        try {
            leftNode = rulePatternParser.parse(op.leftPatternText());
        } catch (IllegalArgumentException ignored) {
            return;
        }
        int idx = 0;
        // Simple variable bindings: each placeholder gets a fresh variable name
        for (int i = 0; i < pool1.length && countExpectation(holdouts, HoldoutExpectation.POSITIVE) < 120; i++) {
            for (int j = 0; j < pool2.length; j++) {
                if (pool1[i].equals(pool2[j])) {
                    continue;
                }
                Map<String, Expr> bindings = buildVariableBindings(placeholders, pool1, pool2, pool3, i, j, idx);
                idx++;
                String input = instantiateToString(leftNode, bindings);
                if (input == null) {
                    continue;
                }
                positiveHoldout(op, "dyn-pos-" + idx, input, "dynamic-positive", "dynamic-operator", "general", "none")
                    .ifPresent(holdouts::add);
            }
        }
        // Composite bindings: first placeholder gets (pool1[i] + offset) for diversity
        for (int i = 0; i < pool1.length && countExpectation(holdouts, HoldoutExpectation.POSITIVE) < 120; i++) {
            for (int offset = 1; offset <= 3; offset++) {
                Map<String, Expr> bindings = buildCompositeBindings(placeholders, pool1, pool2, pool3, i, offset, idx);
                idx++;
                String input = instantiateToString(leftNode, bindings);
                if (input == null) {
                    continue;
                }
                positiveHoldout(op, "dyn-pos-comp-" + idx, input, "dynamic-positive-composite", "dynamic-operator", "general", "none")
                    .ifPresent(holdouts::add);
            }
        }
    }

    private void addDynamicNegativeHoldouts(
        List<HoldoutCase> holdouts,
        DynamicPatternOperator op,
        String[] pool1,
        String[] pool2
    ) {
        // Negative holdouts: simple expressions that shouldn't match the dynamic pattern.
        // These are deliberately simple so they structurally differ from the pattern.
        String[] simpleNegatives = {
            "u", "u + v", "u * v", "u^2", "u - v", "u / v",
            "a + b + c", "a * b * c", "a + b - c", "a^2 + b^2",
            "2 * u + 3 * v", "u * (v + 1)", "1 / (u + 1)",
            "u + 1", "u - 1", "u * 2", "u / 2", "u^3",
            "a + b + c + d", "a * b + c", "u^2 + v^2", "(u + v)^2",
            "u * v + w", "u + v * w", "u - v + w", "u * v - w"
        };
        int idx = 5000;
        for (String neg : simpleNegatives) {
            if (countExpectation(holdouts, HoldoutExpectation.NEGATIVE) >= 120) {
                break;
            }
            List<Transformation> candidates = op.generateCandidates(neg);
            if (!candidates.isEmpty()) {
                continue; // Operator fires – not a negative example for this pattern
            }
            holdouts.add(negativeHoldout(
                "dyn-neg-" + (idx++), neg, neg + " (no-rewrite)",
                "dynamic-negative", "dynamic-operator", "general", "none"
            ));
        }
        // Also vary with different variables in both pools
        for (int i = 0; i < pool1.length && countExpectation(holdouts, HoldoutExpectation.NEGATIVE) < 120; i++) {
            for (int j = 0; j < pool2.length; j++) {
                if (pool1[i].equals(pool2[j])) {
                    continue;
                }
                // Pair of different-variable simple expressions that don't satisfy repeated-placeholder constraints
                String neg1 = pool1[i] + " + " + pool2[j];
                String neg2 = pool1[i] + " * " + pool2[j] + " + " + pool1[(i + 1) % pool1.length] + " * " + pool2[(j + 1) % pool2.length];
                for (String neg : List.of(neg1, neg2)) {
                    if (countExpectation(holdouts, HoldoutExpectation.NEGATIVE) >= 120) {
                        break;
                    }
                    List<Transformation> candidates = op.generateCandidates(neg);
                    if (!candidates.isEmpty()) {
                        continue;
                    }
                    holdouts.add(negativeHoldout(
                        "dyn-neg-v-" + (idx++), neg, neg + " (no-rewrite)",
                        "dynamic-negative-vars", "dynamic-operator", "general", "none"
                    ));
                }
            }
        }
    }

    private Map<String, Expr> buildVariableBindings(
        List<String> placeholders,
        String[] pool1,
        String[] pool2,
        String[] pool3,
        int i,
        int j,
        int idx
    ) {
        Map<String, Expr> bindings = new java.util.LinkedHashMap<>();
        for (int k = 0; k < placeholders.size(); k++) {
            String placeholder = placeholders.get(k);
            if (k == 0) {
                bindings.put(placeholder, new VariableExpr(pool1[i]));
            } else if (k == 1) {
                bindings.put(placeholder, new VariableExpr(pool2[j]));
            } else {
                bindings.put(placeholder, new VariableExpr(pool3[(i + j + k) % pool3.length]));
            }
        }
        return bindings;
    }

    private Map<String, Expr> buildCompositeBindings(
        List<String> placeholders,
        String[] pool1,
        String[] pool2,
        String[] pool3,
        int i,
        int offset,
        int idx
    ) {
        Map<String, Expr> bindings = new java.util.LinkedHashMap<>();
        for (int k = 0; k < placeholders.size(); k++) {
            String placeholder = placeholders.get(k);
            if (k == 0) {
                // First placeholder gets a composite expression (variable + offset)
                bindings.put(placeholder, new BinaryExpr(
                    new VariableExpr(pool1[i]),
                    de.regelsuche.ast.BinaryOperator.ADD,
                    new NumberExpr(offset)
                ));
            } else if (k == 1) {
                bindings.put(placeholder, new VariableExpr(pool2[k % pool2.length]));
            } else {
                bindings.put(placeholder, new VariableExpr(pool3[(i + k) % pool3.length]));
            }
        }
        return bindings;
    }

    private String instantiateToString(de.regelsuche.mining.RulePatternNode leftNode, Map<String, Expr> bindings) {
        try {
            Expr expr = rulePatternInstantiator.instantiate(leftNode, bindings);
            return ExpressionFormatter.format(expr);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private long countExpectation(List<HoldoutCase> holdouts, HoldoutExpectation expectation) {
        return holdouts.stream().filter(h -> h.expectation() == expectation).count();
    }

    private List<String> extractUppercasePlaceholders(String pattern) {
        List<String> result = new ArrayList<>();
        java.util.regex.Matcher m = Pattern.compile("\\b([A-Z])\\b").matcher(pattern);
        while (m.find()) {
            String name = m.group(1);
            if (!result.contains(name)) {
                result.add(name);
            }
        }
        return result;
    }


    private List<HoldoutCase> repeatedSubexpressionHoldouts(HypothesisOperator operator) {
        List<HoldoutCase> holdouts = new ArrayList<>();
        String[] factors = {"u", "v", "w", "m", "n"};
        String[] leftTerms = {"a", "b", "c", "r", "s"};
        String[] rightTerms = {"d", "e", "f", "t", "z"};
        addGeneratedFactorizationPositives(holdouts, operator, "rsf-plus-offset", "factorization-offset-plus", "+", factors, leftTerms, rightTerms);
        addGeneratedFactorizationPositives(holdouts, operator, "rsf-minus-offset", "factorization-offset-minus", "-", factors, leftTerms, rightTerms);
        addGeneratedFactorizationSumPositives(holdouts, operator, "rsf-plus-symbolic", "factorization-symbolic-plus", "+", factors, leftTerms, rightTerms);
        addGeneratedFactorizationNegatives(holdouts, "rsf-negative-mismatch", "factorization-boundary", factors, leftTerms, rightTerms);
        addGeneratedFactorizationNegativeDifferences(holdouts, "rsf-negative-symbolic", "factorization-boundary-symbolic", factors, leftTerms, rightTerms);
        return holdouts;
    }

    private List<HoldoutCase> telescopingHoldouts(HypothesisOperator operator) {
        List<HoldoutCase> holdouts = new ArrayList<>();
        String[] bases = {"x", "q", "r", "t", "u"};
        String[] extras = {"a", "b", "c", "m", "n"};
        addGeneratedTelescopingBasePositives(holdouts, operator, "tel-base", "adjacent-base", bases);
        addGeneratedTelescopingCompositePositives(holdouts, operator, "tel-composite", "adjacent-composite", bases, extras);
        addGeneratedTelescopingScaledPositives(holdouts, operator, "tel-scaled", "adjacent-scaled", bases);
        addGeneratedTelescopingGapNegatives(holdouts, "tel-gap", "non-adjacent-gap", bases);
        addGeneratedTelescopingSymbolNegatives(holdouts, "tel-symbolic-numerator", "non-numeric-numerator", bases, extras);
        addGeneratedTelescopingMixedNegatives(holdouts, "tel-mixed-base", "non-adjacent-symbolic", bases, extras);
        return holdouts;
    }

    private List<HoldoutCase> rationalNormalizationHoldouts(HypothesisOperator operator) {
        List<HoldoutCase> holdouts = new ArrayList<>();
        String[] numerators = {"a", "b", "c", "m", "n"};
        String[] denominators = {"d", "g", "h", "q", "r"};
        addGeneratedRationalPositives(holdouts, operator, "rn-add-shared", "shared-denominator-add", "+", numerators, denominators);
        addGeneratedRationalPositives(holdouts, operator, "rn-sub-shared", "shared-denominator-sub", "-", numerators, denominators);
        addGeneratedRationalCompositePositives(holdouts, operator, "rn-add-composite", "shared-denominator-composite", numerators, denominators);
        addGeneratedRationalNegativeDenominatorCases(holdouts, "rn-negative-different", "mismatched-denominator", numerators, denominators);
        addGeneratedRationalNegativeCompositeCases(holdouts, "rn-negative-offset", "near-miss-denominator", numerators, denominators);
        addGeneratedRationalNegativeProductCases(holdouts, "rn-negative-product", "non-additive-boundary", numerators, denominators);
        return holdouts;
    }

    private void addGeneratedFactorizationPositives(
        List<HoldoutCase> holdouts,
        HypothesisOperator operator,
        String templateId,
        String structureClass,
        String sign,
        String[] factors,
        String[] leftTerms,
        String[] rightTerms
    ) {
        int index = 0;
        for (int offset = 1; offset <= 4; offset++) {
            for (String factor : factors) {
                for (String left : leftTerms) {
                    String right = rightTerms[index % rightTerms.length];
                    String common = "(" + factor + " + " + offset + ")";
                    String input = common + " * " + left + " " + sign + " " + common + " * " + right;
                    String id = templateId + "-" + factor + "-" + left + "-" + right + "-" + offset;
                    positiveHoldout(operator, id, input, templateId, structureClass, "polynomial", "none")
                        .ifPresent(holdouts::add);
                    index++;
                }
            }
        }
    }

    private void addGeneratedFactorizationSumPositives(
        List<HoldoutCase> holdouts,
        HypothesisOperator operator,
        String templateId,
        String structureClass,
        String sign,
        String[] factors,
        String[] leftTerms,
        String[] rightTerms
    ) {
        int index = 0;
        for (String factor : factors) {
            for (String extra : leftTerms) {
                if (factor.equals(extra)) {
                    continue;
                }
                for (String left : rightTerms) {
                    String right = leftTerms[index % leftTerms.length];
                    String common = "(" + factor + " + " + extra + ")";
                    String input = common + " * " + left + " " + sign + " " + common + " * " + right;
                    String id = templateId + "-" + factor + "-" + extra + "-" + left + "-" + right;
                    positiveHoldout(operator, id, input, templateId, structureClass, "polynomial", "none")
                        .ifPresent(holdouts::add);
                    index++;
                }
            }
        }
    }

    private void addGeneratedFactorizationNegatives(
        List<HoldoutCase> holdouts,
        String templateId,
        String structureClass,
        String[] factors,
        String[] leftTerms,
        String[] rightTerms
    ) {
        for (int offset = 1; offset <= 4; offset++) {
            for (String factor : factors) {
                for (int index = 0; index < leftTerms.length; index++) {
                    String left = leftTerms[index];
                    String right = rightTerms[index];
                    String input = "(" + factor + " + " + offset + ") * " + left + " + " + factor + " * " + right;
                    String target = "(" + factor + " + " + offset + ") * (" + left + " + " + right + ")";
                    holdouts.add(negativeHoldout(
                        templateId + "-" + factor + "-" + left + "-" + right + "-" + offset,
                        input,
                        target,
                        templateId,
                        structureClass,
                        "polynomial",
                        "none"
                    ));
                }
            }
        }
    }

    private void addGeneratedFactorizationNegativeDifferences(
        List<HoldoutCase> holdouts,
        String templateId,
        String structureClass,
        String[] factors,
        String[] leftTerms,
        String[] rightTerms
    ) {
        for (String factor : factors) {
            for (int index = 0; index < leftTerms.length; index++) {
                String extra = rightTerms[index];
                String left = leftTerms[index];
                String right = rightTerms[(index + 1) % rightTerms.length];
                String input = "(" + factor + " + " + extra + ") * " + left + " - (" + factor + " + " + right + ") * " + extra;
                String target = "(" + factor + " + " + extra + ") * (" + left + " - " + extra + ")";
                holdouts.add(negativeHoldout(
                    templateId + "-" + factor + "-" + left + "-" + extra + "-" + right,
                    input,
                    target,
                    templateId,
                    structureClass,
                    "polynomial",
                    "none"
                ));
            }
        }
    }

    private void addGeneratedTelescopingBasePositives(
        List<HoldoutCase> holdouts,
        HypothesisOperator operator,
        String templateId,
        String structureClass,
        String[] bases
    ) {
        for (int numerator = 1; numerator <= 4; numerator++) {
            for (String base : bases) {
                for (int offset = 1; offset <= 6; offset++) {
                    String lower = "(" + base + " + " + offset + ")";
                    String upper = "(" + base + " + " + (offset + 1) + ")";
                    String input = numerator + " / (" + lower + " * " + upper + ")";
                    String id = templateId + "-" + numerator + "-" + base + "-" + offset;
                    positiveHoldout(operator, id, input, templateId, structureClass, "rational", "adjacent-factors")
                        .ifPresent(holdouts::add);
                }
            }
        }
    }

    private void addGeneratedTelescopingCompositePositives(
        List<HoldoutCase> holdouts,
        HypothesisOperator operator,
        String templateId,
        String structureClass,
        String[] bases,
        String[] extras
    ) {
        for (int numerator = 1; numerator <= 4; numerator++) {
            for (String base : bases) {
                for (String extra : extras) {
                    if (base.equals(extra)) {
                        continue;
                    }
                    String symbolic = "((" + base + " + " + extra + ") + 2)";
                    String upper = "((" + base + " + " + extra + ") + 3)";
                    String input = numerator + " / (" + symbolic + " * " + upper + ")";
                    String id = templateId + "-" + numerator + "-" + base + "-" + extra;
                    positiveHoldout(operator, id, input, templateId, structureClass, "rational", "adjacent-factors")
                        .ifPresent(holdouts::add);
                }
            }
        }
    }

    private void addGeneratedTelescopingScaledPositives(
        List<HoldoutCase> holdouts,
        HypothesisOperator operator,
        String templateId,
        String structureClass,
        String[] bases
    ) {
        for (String base : bases) {
            for (int offset = 2; offset <= 6; offset++) {
                String lower = "((" + base + " + 1) + " + offset + ")";
                String upper = "((" + base + " + 1) + " + (offset + 1) + ")";
                String input = "5 / (" + lower + " * " + upper + ")";
                String id = templateId + "-" + base + "-" + offset;
                positiveHoldout(operator, id, input, templateId, structureClass, "rational", "adjacent-factors")
                    .ifPresent(holdouts::add);
            }
        }
    }

    private void addGeneratedTelescopingGapNegatives(
        List<HoldoutCase> holdouts,
        String templateId,
        String structureClass,
        String[] bases
    ) {
        for (int numerator = 1; numerator <= 4; numerator++) {
            for (String base : bases) {
                for (int offset = 1; offset <= 6; offset++) {
                    String lower = "(" + base + " + " + offset + ")";
                    String upper = "(" + base + " + " + (offset + 2) + ")";
                    String input = numerator + " / (" + lower + " * " + upper + ")";
                    String target = numerator + " / " + lower + " - " + numerator + " / " + upper;
                    holdouts.add(negativeHoldout(
                        templateId + "-" + numerator + "-" + base + "-" + offset,
                        input,
                        target,
                        templateId,
                        structureClass,
                        "rational",
                        "non-adjacent-factors"
                    ));
                }
            }
        }
    }

    private void addGeneratedTelescopingSymbolNegatives(
        List<HoldoutCase> holdouts,
        String templateId,
        String structureClass,
        String[] bases,
        String[] extras
    ) {
        for (String base : bases) {
            for (String extra : extras) {
                if (base.equals(extra)) {
                    continue;
                }
                String lower = "(" + base + " + 1)";
                String upper = "(" + base + " + 2)";
                String input = extra + " / (" + lower + " * " + upper + ")";
                String target = extra + " / " + lower + " - " + extra + " / " + upper;
                holdouts.add(negativeHoldout(
                    templateId + "-" + base + "-" + extra,
                    input,
                    target,
                    templateId,
                    structureClass,
                    "rational",
                    "non-numeric-numerator"
                ));
            }
        }
    }

    private void addGeneratedTelescopingMixedNegatives(
        List<HoldoutCase> holdouts,
        String templateId,
        String structureClass,
        String[] bases,
        String[] extras
    ) {
        for (String base : bases) {
            for (String extra : extras) {
                if (base.equals(extra)) {
                    continue;
                }
                String lower = "(" + base + " + 2)";
                String upper = "(" + extra + " + 3)";
                String input = "3 / (" + lower + " * " + upper + ")";
                String target = "3 / " + lower + " - 3 / " + upper;
                holdouts.add(negativeHoldout(
                    templateId + "-" + base + "-" + extra,
                    input,
                    target,
                    templateId,
                    structureClass,
                    "rational",
                    "mismatched-symbolic-base"
                ));
            }
        }
    }

    private void addGeneratedRationalPositives(
        List<HoldoutCase> holdouts,
        HypothesisOperator operator,
        String templateId,
        String structureClass,
        String sign,
        String[] numerators,
        String[] denominators
    ) {
        for (String denominator : denominators) {
            for (String left : numerators) {
                for (String right : numerators) {
                    if (left.equals(right)) {
                        continue;
                    }
                    String input = left + " / " + denominator + " " + sign + " " + right + " / " + denominator;
                    String id = templateId + "-" + denominator + "-" + left + "-" + right;
                    positiveHoldout(operator, id, input, templateId, structureClass, "rational", "shared-denominator")
                        .ifPresent(holdouts::add);
                }
            }
        }
    }

    private void addGeneratedRationalCompositePositives(
        List<HoldoutCase> holdouts,
        HypothesisOperator operator,
        String templateId,
        String structureClass,
        String[] numerators,
        String[] denominators
    ) {
        for (String denominator : denominators) {
            for (String left : numerators) {
                for (String right : numerators) {
                    if (left.equals(right)) {
                        continue;
                    }
                    String shared = "(" + denominator + " + 1)";
                    String input = "(" + left + " + " + right + ") / " + shared + " + " + left + " / " + shared;
                    String id = templateId + "-" + denominator + "-" + left + "-" + right;
                    positiveHoldout(operator, id, input, templateId, structureClass, "rational", "shared-denominator")
                        .ifPresent(holdouts::add);
                }
            }
        }
    }

    private void addGeneratedRationalNegativeDenominatorCases(
        List<HoldoutCase> holdouts,
        String templateId,
        String structureClass,
        String[] numerators,
        String[] denominators
    ) {
        for (int index = 0; index < denominators.length; index++) {
            String leftDenominator = denominators[index];
            String rightDenominator = denominators[(index + 1) % denominators.length];
            for (String left : numerators) {
                for (String right : numerators) {
                    if (left.equals(right)) {
                        continue;
                    }
                    String input = left + " / " + leftDenominator + " + " + right + " / " + rightDenominator;
                    String target = "(" + left + " + " + right + ") / " + leftDenominator;
                    holdouts.add(negativeHoldout(
                        templateId + "-" + leftDenominator + "-" + rightDenominator + "-" + left + "-" + right,
                        input,
                        target,
                        templateId,
                        structureClass,
                        "rational",
                        "mismatched-denominator"
                    ));
                }
            }
        }
    }

    private void addGeneratedRationalNegativeCompositeCases(
        List<HoldoutCase> holdouts,
        String templateId,
        String structureClass,
        String[] numerators,
        String[] denominators
    ) {
        for (String denominator : denominators) {
            for (String left : numerators) {
                for (String right : numerators) {
                    if (left.equals(right)) {
                        continue;
                    }
                    String input = left + " / (" + denominator + " + 1) - " + right + " / (" + denominator + " + 2)";
                    String target = "(" + left + " - " + right + ") / (" + denominator + " + 1)";
                    holdouts.add(negativeHoldout(
                        templateId + "-" + denominator + "-" + left + "-" + right,
                        input,
                        target,
                        templateId,
                        structureClass,
                        "rational",
                        "near-miss-denominator"
                    ));
                }
            }
        }
    }

    private void addGeneratedRationalNegativeProductCases(
        List<HoldoutCase> holdouts,
        String templateId,
        String structureClass,
        String[] numerators,
        String[] denominators
    ) {
        for (String denominator : denominators) {
            for (String left : numerators) {
                for (String right : numerators) {
                    if (left.equals(right)) {
                        continue;
                    }
                    String input = "(" + left + " / " + denominator + ") * (" + right + " / " + denominator + ")";
                    String target = "(" + left + " * " + right + ") / " + denominator;
                    holdouts.add(negativeHoldout(
                        templateId + "-" + denominator + "-" + left + "-" + right,
                        input,
                        target,
                        templateId,
                        structureClass,
                        "rational",
                        "non-additive-boundary"
                    ));
                }
            }
        }
    }

    private java.util.Optional<HoldoutCase> positiveHoldout(
        HypothesisOperator operator,
        String id,
        String input,
        String templateId,
        String structureClass,
        String domainClass,
        String assumptionClass
    ) {
        return operator.generateCandidates(input).stream()
            .findFirst()
            .map(transformation -> new HoldoutCase(
                id,
                input,
                transformation.transformedExpression(),
                HoldoutExpectation.POSITIVE,
                templateId,
                structureClass,
                domainClass,
                assumptionClass,
                GENERATOR_SEED,
                GENERATOR_VERSION,
                "deterministic:" + templateId,
                List.of()
            ));
    }

    private HoldoutCase negativeHoldout(
        String id,
        String input,
        String target,
        String templateId,
        String structureClass,
        String domainClass,
        String assumptionClass
    ) {
        return new HoldoutCase(
            id,
            input,
            target,
            HoldoutExpectation.NEGATIVE,
            templateId,
            structureClass,
            domainClass,
            assumptionClass,
            GENERATOR_SEED,
            GENERATOR_VERSION,
            "deterministic:" + templateId,
            List.of()
        );
    }

    private GeneratorCoverage coverage(List<HoldoutCase> holdouts, List<LeakageFinding> filteredLeakage) {
        return new GeneratorCoverage(
            holdouts.stream().filter(holdout -> holdout.expectation() == HoldoutExpectation.POSITIVE).count(),
            holdouts.stream().filter(holdout -> holdout.expectation() == HoldoutExpectation.NEGATIVE).count(),
            countBy(holdouts, HoldoutCase::templateId),
            countBy(holdouts, HoldoutCase::structureClass),
            countBy(holdouts, HoldoutCase::domainClass),
            countBy(holdouts, HoldoutCase::assumptionClass),
            filteredLeakage.size(),
            countLeakageByKind(filteredLeakage),
            COVERAGE_NOTE
        );
    }

    private GeneratorCoverage aggregateCoverage(List<ValidatedHypothesis> validatedHypotheses) {
        long positives = validatedHypotheses.stream()
            .map(ValidatedHypothesis::generatorCoverage)
            .mapToLong(GeneratorCoverage::generatedPositiveCount)
            .sum();
        long negatives = validatedHypotheses.stream()
            .map(ValidatedHypothesis::generatorCoverage)
            .mapToLong(GeneratorCoverage::generatedNegativeCount)
            .sum();
        long filteredLeakage = validatedHypotheses.stream()
            .map(ValidatedHypothesis::generatorCoverage)
            .mapToLong(GeneratorCoverage::filteredLeakageCount)
            .sum();
        return new GeneratorCoverage(
            positives,
            negatives,
            mergeMaps(validatedHypotheses, coverage -> coverage.byTemplate()),
            mergeMaps(validatedHypotheses, coverage -> coverage.byStructureClass()),
            mergeMaps(validatedHypotheses, coverage -> coverage.byDomain()),
            mergeMaps(validatedHypotheses, coverage -> coverage.byAssumptionClass()),
            filteredLeakage,
            mergeMaps(validatedHypotheses, coverage -> coverage.filteredLeakageByKind()),
            COVERAGE_NOTE
        );
    }

    private Map<String, Long> countBy(List<HoldoutCase> holdouts, java.util.function.Function<HoldoutCase, String> keyExtractor) {
        return holdouts.stream()
            .collect(Collectors.groupingBy(
                holdout -> keyExtractor.apply(holdout),
                LinkedHashMap::new,
                Collectors.counting()
            ));
    }

    private Map<String, Long> countLeakageByKind(List<LeakageFinding> leakage) {
        return leakage.stream()
            .collect(Collectors.groupingBy(
                finding -> finding.kind().name().toLowerCase(Locale.ROOT),
                LinkedHashMap::new,
                Collectors.counting()
            ));
    }

    private Map<String, Long> mergeMaps(
        List<ValidatedHypothesis> hypotheses,
        java.util.function.Function<GeneratorCoverage, Map<String, Long>> extractor
    ) {
        LinkedHashMap<String, Long> merged = new LinkedHashMap<>();
        for (ValidatedHypothesis hypothesis : hypotheses) {
            extractor.apply(hypothesis.generatorCoverage()).forEach((key, value) -> merged.merge(key, value, Long::sum));
        }
        return Map.copyOf(merged);
    }

    private HypothesisOperator operatorFor(String operatorId) {
        if (RepeatedSubexpressionFactorizationHypothesisOperator.RULE_ID.equals(operatorId)) {
            return new RepeatedSubexpressionFactorizationHypothesisOperator();
        }
        if (TelescopingFractionHypothesisOperator.RULE_ID.equals(operatorId)) {
            return new TelescopingFractionHypothesisOperator();
        }
        if (RationalNormalizationHypothesisOperator.RULE_ID.equals(operatorId)) {
            return new RationalNormalizationHypothesisOperator();
        }
        return null;
    }

    /**
     * Returns the best available {@link HypothesisOperator} for the hypothesis.
     *
     * <p>For well-known operators (with hardcoded Java classes) the existing hand-written
     * implementation is returned. For any other hypothesis, this method compiles a
     * {@link DynamicPatternOperator} from the hypothesis's left/right patterns, allowing
     * generalized hypotheses to become executable without a new Java class.</p>
     */
    private HypothesisOperator operatorFor(PatternHypothesisMiner.GeneralizedHypothesis hypothesis) {
        HypothesisOperator known = operatorFor(hypothesis.operatorId());
        if (known != null) {
            return known;
        }
        // Fall back to dynamic compilation from the mined patterns
        DynamicOperatorCompiler.CompilationResult result = dynamicCompiler.compile(
            hypothesis.hypothesisId(),
            "GENERALIZED_FROM_SUPPORT",
            hypothesis.leftPattern(),
            hypothesis.rightPattern()
        );
        return result.operator().orElse(null);
    }

    private static boolean fallbackUsed(List<String> rulePath) {
        return rulePath != null && rulePath.stream()
            .filter(ruleId -> ruleId != null)
            .map(ruleId -> ruleId.toLowerCase(Locale.ROOT))
            .anyMatch(ruleId -> ruleId.contains("fallback"));
    }

    private static boolean macroOpportunity(String family, List<String> rulePath) {
        return "substitution".equals(family) || (rulePath != null && rulePath.size() >= 2);
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

    private List<String> aggregateRulePath(DiscoveryBenchmarkEvidence evidence) {
        List<String> path = evidence.withoutMacroRun().appliedRuleIds();
        if (path == null || path.isEmpty()) {
            return List.of();
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
        out.append("> ").append(COVERAGE_NOTE).append("\n\n");
        out.append("| Hypothesis | Family | Operator | Support | Generated + | Generated - | Benchmarked + | Negative gate | Ablation | Public evidence |\n");
        out.append("| --- | --- | --- | ---: | ---: | ---: | ---: | --- | --- | --- |\n");
        for (ValidatedHypothesis hypothesis : report.validatedHypotheses()) {
            long blockedNegatives = hypothesis.negativeHoldoutResults().stream()
                .filter(NegativeHoldoutResult::blocked)
                .count();
            out.append("| ").append(escape(hypothesis.hypothesisId()))
                .append(" | ").append(escape(hypothesis.family()))
                .append(" | ").append(escape(hypothesis.operatorId()))
                .append(" | ").append(hypothesis.supportCount())
                .append(" | ").append(hypothesis.generatorCoverage().generatedPositiveCount())
                .append(" | ").append(hypothesis.generatorCoverage().generatedNegativeCount())
                .append(" | ").append(hypothesis.holdoutResults().size())
                .append(" | ").append(blockedNegatives).append("/").append(hypothesis.negativeHoldoutResults().size())
                .append(" | ").append(escape(hypothesis.ablationEvidence().ablationStatus()))
                .append(" | ").append(hypothesis.publicEvidenceAccepted() ? "accepted" : escape(String.join(", ", hypothesis.publicEvidenceRejectionReasons())))
                .append(" |\n");
        }
        out.append("\n## Generator coverage\n\n");
        out.append("- positive holdouts: ").append(report.generatorCoverage().generatedPositiveCount()).append('\n');
        out.append("- negative holdouts: ").append(report.generatorCoverage().generatedNegativeCount()).append('\n');
        out.append("- filtered leakage cases: ").append(report.generatorCoverage().filteredLeakageCount()).append('\n');
        out.append("- template coverage: ").append(escape(report.generatorCoverage().byTemplate().toString())).append('\n');
        out.append("- structure coverage: ").append(escape(report.generatorCoverage().byStructureClass().toString())).append('\n');
        out.append("- domain coverage: ").append(escape(report.generatorCoverage().byDomain().toString())).append('\n');
        out.append("- assumption coverage: ").append(escape(report.generatorCoverage().byAssumptionClass().toString())).append('\n');
        out.append("- leakage coverage: ").append(escape(report.generatorCoverage().filteredLeakageByKind().toString())).append('\n');
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

    private String normalize(String value) {
        return value == null ? "" : value.replaceAll("\\s+", "");
    }

    private String comparableExpressionKey(String expression) {
        try {
            return canonicalizer.canonicalize(expression);
        } catch (RuntimeException exception) {
            return normalize(expression);
        }
    }

    record ValidationReport(
        List<ValidatedHypothesis> validatedHypotheses,
        List<RejectedHypothesis> rejectedHypotheses,
        List<PromotionRecord> promotionRecords,
        long publicAcceptedCount,
        GeneratorCoverage generatorCoverage
    ) {
        ValidationReport {
            validatedHypotheses = validatedHypotheses == null ? List.of() : List.copyOf(validatedHypotheses);
            rejectedHypotheses = rejectedHypotheses == null ? List.of() : List.copyOf(rejectedHypotheses);
            promotionRecords = promotionRecords == null ? List.of() : List.copyOf(promotionRecords);
            generatorCoverage = generatorCoverage == null ? GeneratorCoverage.empty() : generatorCoverage;
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
        List<NegativeHoldoutResult> negativeHoldoutResults,
        AblationEvidence ablationEvidence,
        PromotionRecord promotionRecord,
        boolean publicEvidenceAccepted,
        List<String> publicEvidenceRejectionReasons,
        GeneratorCoverage generatorCoverage
    ) {
        ValidatedHypothesis {
            supportingExampleIds = supportingExampleIds == null ? List.of() : List.copyOf(supportingExampleIds);
            holdoutResults = holdoutResults == null ? List.of() : List.copyOf(holdoutResults);
            negativeHoldoutResults = negativeHoldoutResults == null ? List.of() : List.copyOf(negativeHoldoutResults);
            publicEvidenceRejectionReasons = publicEvidenceRejectionReasons == null ? List.of() : List.copyOf(publicEvidenceRejectionReasons);
            generatorCoverage = generatorCoverage == null ? GeneratorCoverage.empty() : generatorCoverage;
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
        String templateId,
        String structureClass,
        String domainClass,
        String assumptionClass,
        long seed,
        String generatorVersion,
        String provenance,
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
            templateId = templateId == null ? "" : templateId;
            structureClass = structureClass == null ? "" : structureClass;
            domainClass = domainClass == null ? "" : domainClass;
            assumptionClass = assumptionClass == null ? "" : assumptionClass;
            generatorVersion = generatorVersion == null ? "" : generatorVersion;
            provenance = provenance == null ? "" : provenance;
            oracleStatus = oracleStatus == null || oracleStatus.isBlank() ? "UNAVAILABLE" : oracleStatus;
            oracleEvidence = oracleEvidence == null ? "" : oracleEvidence;
            rulePath = rulePath == null ? List.of() : List.copyOf(rulePath);
        }
    }

    record NegativeHoldoutResult(
        String holdoutId,
        String inputExpression,
        String targetExpression,
        String templateId,
        String structureClass,
        String domainClass,
        String assumptionClass,
        long seed,
        String generatorVersion,
        String provenance,
        boolean blocked,
        boolean operatorFired,
        boolean rewroteToForbiddenTarget,
        List<String> generatedCandidates
    ) {
        NegativeHoldoutResult {
            templateId = templateId == null ? "" : templateId;
            structureClass = structureClass == null ? "" : structureClass;
            domainClass = domainClass == null ? "" : domainClass;
            assumptionClass = assumptionClass == null ? "" : assumptionClass;
            generatorVersion = generatorVersion == null ? "" : generatorVersion;
            provenance = provenance == null ? "" : provenance;
            generatedCandidates = generatedCandidates == null ? List.of() : List.copyOf(generatedCandidates);
        }
    }

    record GeneratorCoverage(
        long generatedPositiveCount,
        long generatedNegativeCount,
        Map<String, Long> byTemplate,
        Map<String, Long> byStructureClass,
        Map<String, Long> byDomain,
        Map<String, Long> byAssumptionClass,
        long filteredLeakageCount,
        Map<String, Long> filteredLeakageByKind,
        String note
    ) {
        GeneratorCoverage {
            byTemplate = byTemplate == null ? Map.of() : Map.copyOf(byTemplate);
            byStructureClass = byStructureClass == null ? Map.of() : Map.copyOf(byStructureClass);
            byDomain = byDomain == null ? Map.of() : Map.copyOf(byDomain);
            byAssumptionClass = byAssumptionClass == null ? Map.of() : Map.copyOf(byAssumptionClass);
            filteredLeakageByKind = filteredLeakageByKind == null ? Map.of() : Map.copyOf(filteredLeakageByKind);
            note = note == null ? "" : note;
        }

        private static GeneratorCoverage empty() {
            return new GeneratorCoverage(0, 0, Map.of(), Map.of(), Map.of(), Map.of(), 0, Map.of(), COVERAGE_NOTE);
        }
    }

    private record GeneratedHoldoutSuite(
        List<HoldoutCase> positiveHoldouts,
        List<HoldoutCase> negativeHoldouts,
        GeneratorCoverage generatorCoverage
    ) {
        private GeneratedHoldoutSuite {
            positiveHoldouts = positiveHoldouts == null ? List.of() : List.copyOf(positiveHoldouts);
            negativeHoldouts = negativeHoldouts == null ? List.of() : List.copyOf(negativeHoldouts);
            generatorCoverage = generatorCoverage == null ? GeneratorCoverage.empty() : generatorCoverage;
        }

        private List<HoldoutCase> allHoldouts() {
            ArrayList<HoldoutCase> all = new ArrayList<>(positiveHoldouts);
            all.addAll(negativeHoldouts);
            return List.copyOf(all);
        }

        private static GeneratedHoldoutSuite empty() {
            return new GeneratedHoldoutSuite(List.of(), List.of(), GeneratorCoverage.empty());
        }
    }

    private enum HoldoutExpectation {
        POSITIVE,
        NEGATIVE
    }

    private record HoldoutCase(
        String id,
        String inputExpression,
        String targetExpression,
        HoldoutExpectation expectation,
        String templateId,
        String structureClass,
        String domainClass,
        String assumptionClass,
        long seed,
        String generatorVersion,
        String provenance,
        List<String> enabledRulePacks
    ) {
        private HoldoutCase {
            id = id == null ? "" : id;
            inputExpression = inputExpression == null ? "" : inputExpression;
            targetExpression = targetExpression == null ? "" : targetExpression;
            expectation = expectation == null ? HoldoutExpectation.POSITIVE : expectation;
            templateId = templateId == null ? "" : templateId;
            structureClass = structureClass == null ? "" : structureClass;
            domainClass = domainClass == null ? "" : domainClass;
            assumptionClass = assumptionClass == null ? "" : assumptionClass;
            generatorVersion = generatorVersion == null ? "" : generatorVersion;
            provenance = provenance == null ? "" : provenance;
            enabledRulePacks = enabledRulePacks == null ? List.of() : List.copyOf(enabledRulePacks);
        }
    }

    private enum LeakageKind {
        EXACT,
        CANONICAL_FORM,
        ALPHA_EQUIVALENT,
        INVERSE
    }

    private record LeakageFinding(String holdoutId, String supportExampleId, LeakageKind kind) {
        private LeakageFinding {
            holdoutId = holdoutId == null ? "" : holdoutId;
            supportExampleId = supportExampleId == null ? "" : supportExampleId;
            kind = kind == null ? LeakageKind.CANONICAL_FORM : kind;
        }
    }

    private static final class HoldoutLeakageChecker {
        private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

        private final ExpressionParser parser = new ExpressionParser();
        private final ExpressionCanonicalizer canonicalizer = new ExpressionCanonicalizer();

        List<LeakageFinding> findLeakage(
            List<PatternHypothesisMiner.SupportExampleRef> supportExamples,
            List<HoldoutCase> holdouts
        ) {
            List<LeakageFinding> findings = new ArrayList<>();
            for (HoldoutCase holdout : holdouts == null ? List.<HoldoutCase>of() : holdouts) {
                for (PatternHypothesisMiner.SupportExampleRef support : supportExamples == null
                    ? List.<PatternHypothesisMiner.SupportExampleRef>of()
                    : supportExamples) {
                    LeakageKind kind = classify(support, holdout);
                    if (kind != null) {
                        findings.add(new LeakageFinding(holdout.id(), support.exampleId(), kind));
                        break;
                    }
                }
            }
            return List.copyOf(findings);
        }

        private LeakageKind classify(PatternHypothesisMiner.SupportExampleRef support, HoldoutCase holdout) {
            String supportRaw = rawPairKey(support.inputExpression(), support.targetExpression());
            String holdoutRaw = rawPairKey(holdout.inputExpression(), holdout.targetExpression());
            if (supportRaw.equals(holdoutRaw)) {
                return LeakageKind.EXACT;
            }

            String supportCanonical = canonicalPairKey(support.inputExpression(), support.targetExpression());
            String holdoutCanonical = canonicalPairKey(holdout.inputExpression(), holdout.targetExpression());
            if (supportCanonical.equals(holdoutCanonical)) {
                return LeakageKind.CANONICAL_FORM;
            }

            String supportAlpha = alphaPairKey(support.inputExpression(), support.targetExpression());
            String holdoutAlpha = alphaPairKey(holdout.inputExpression(), holdout.targetExpression());
            if (supportAlpha.equals(holdoutAlpha)) {
                return LeakageKind.ALPHA_EQUIVALENT;
            }

            if (holdoutRaw.equals(rawPairKey(support.targetExpression(), support.inputExpression()))
                || holdoutCanonical.equals(canonicalPairKey(support.targetExpression(), support.inputExpression()))
                || holdoutAlpha.equals(alphaPairKey(support.targetExpression(), support.inputExpression()))) {
                return LeakageKind.INVERSE;
            }
            return null;
        }

        private String rawPairKey(String input, String target) {
            return normalize(input) + "->" + normalize(target);
        }

        private String canonicalPairKey(String input, String target) {
            return canonicalExpressionKey(input) + "->" + canonicalExpressionKey(target);
        }

        private String alphaPairKey(String input, String target) {
            Map<String, String> variableMap = new LinkedHashMap<>();
            return alphaExpressionKey(input, variableMap) + "->" + alphaExpressionKey(target, variableMap);
        }

        private String canonicalExpressionKey(String expression) {
            try {
                return canonicalizer.canonicalize(expression);
            } catch (RuntimeException exception) {
                return normalize(expression);
            }
        }

        private String alphaExpressionKey(String expression, Map<String, String> variableMap) {
            Expr parsed = parseCanonical(expression);
            if (parsed == null) {
                return alphaNormalizeLexically(expression, variableMap);
            }
            return astKey(parsed, variableMap);
        }

        private Expr parseCanonical(String expression) {
            try {
                String canonical = canonicalizer.canonicalize(expression);
                return parser.parse(new InputRequest(InputType.TERM, canonical)).terms().getFirst();
            } catch (RuntimeException exception) {
                return null;
            }
        }

        private String astKey(Expr expression, Map<String, String> variableMap) {
            if (expression instanceof BinaryExpr binaryExpr) {
                return "bin(" + binaryExpr.operator().name()
                    + "," + astKey(binaryExpr.left(), variableMap)
                    + "," + astKey(binaryExpr.right(), variableMap)
                    + ")";
            }
            if (expression instanceof FunctionExpr functionExpr) {
                List<String> argumentKeys = functionExpr.arguments().stream()
                    .map(argument -> astKey(argument, variableMap))
                    .toList();
                return "fn(" + functionExpr.name().toLowerCase(Locale.ROOT)
                    + "," + String.join(",", argumentKeys)
                    + ")";
            }
            if (expression instanceof VariableExpr variableExpr) {
                String variable = variableExpr.name();
                if (variableMap != null) {
                    variable = variableMap.computeIfAbsent(variable, ignored -> "v" + variableMap.size());
                }
                return "var(" + variable + ")";
            }
            if (expression instanceof NumberExpr numberExpr) {
                return "num(" + formatNumber(numberExpr.value()) + ")";
            }
            return "unknown(" + expression + ")";
        }

        private String alphaNormalizeLexically(String expression, Map<String, String> variableMap) {
            String normalized = normalize(expression);
            Matcher matcher = IDENTIFIER.matcher(normalized);
            StringBuilder out = new StringBuilder();
            while (matcher.find()) {
                String identifier = matcher.group();
                String replacement = isFunctionCall(normalized, matcher.end())
                    ? identifier.toLowerCase(Locale.ROOT)
                    : variableMap.computeIfAbsent(identifier, ignored -> "v" + variableMap.size());
                matcher.appendReplacement(out, Matcher.quoteReplacement(replacement));
            }
            matcher.appendTail(out);
            return out.toString();
        }

        private static boolean isFunctionCall(String expression, int endIndex) {
            return endIndex < expression.length() && expression.charAt(endIndex) == '(';
        }

        private static String normalize(String expression) {
            return expression == null ? "" : expression.replaceAll("\\s+", "");
        }

        private static String formatNumber(double value) {
            if (Double.isFinite(value) && Math.rint(value) == value) {
                return Long.toString((long) value);
            }
            return Double.toString(value);
        }
    }
}
