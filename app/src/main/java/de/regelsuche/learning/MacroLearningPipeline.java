package de.regelsuche.learning;

import de.regelsuche.ast.Expr;
import de.regelsuche.equivalence.EquivalenceService;
import de.regelsuche.equivalence.SymPyEquivalenceService;
import de.regelsuche.inventory.ReusableRule;
import de.regelsuche.inventory.RuleInventoryRepository;
import de.regelsuche.mining.GeneralizedPattern;
import de.regelsuche.mining.KnownRuleRepository;
import de.regelsuche.mining.ParameterRelation;
import de.regelsuche.mining.PatternGeneralizer;
import de.regelsuche.mining.RulePatternCanonicalizer;
import de.regelsuche.mining.RulePatternInstantiator;
import de.regelsuche.mining.RulePatternNode;
import de.regelsuche.mining.RulePatternParser;
import de.regelsuche.mining.RuleStatus;
import de.regelsuche.mining.SuccessfulTransformationPath;
import de.regelsuche.parse.ExpressionFormatter;
import de.regelsuche.parse.ExpressionParser;
import de.regelsuche.validation.CandidateProofStatus;
import de.regelsuche.validation.CounterexampleSearchService;
import de.regelsuche.validation.DeterministicCounterexampleSearchService;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Evidence-first pipeline that promotes macros only after validation gates pass. */
public class MacroLearningPipeline {
    public static final double DEFAULT_CONFIDENCE_THRESHOLD = 0.8;
    private static final List<String> GENERATED_SUBSTITUTIONS =
        List.of("x", "y", "x + 1", "2*x", "x^2", "n + 2");
    private static final Pattern PLACEHOLDER = Pattern.compile("\\b[A-Z]\\b");

    private final RuleInventoryRepository inventory;
    private final PatternGeneralizer generalizer;
    private final EquivalenceService equivalenceService;
    private final CounterexampleSearchService counterexampleSearchService;
    private final KnownRuleRepository knownRules;
    private final double confidenceThreshold;
    private final RulePatternParser patternParser = new RulePatternParser();
    private final RulePatternInstantiator instantiator = new RulePatternInstantiator();
    private final ExpressionParser expressionParser = new ExpressionParser();

    public MacroLearningPipeline(RuleInventoryRepository inventory) {
        this(
            inventory,
            new PatternGeneralizer(),
            new SymPyEquivalenceService(),
            new DeterministicCounterexampleSearchService(),
            new KnownRuleRepository(),
            DEFAULT_CONFIDENCE_THRESHOLD
        );
    }

    public MacroLearningPipeline(
        RuleInventoryRepository inventory,
        PatternGeneralizer generalizer,
        EquivalenceService equivalenceService,
        CounterexampleSearchService counterexampleSearchService,
        KnownRuleRepository knownRules,
        double confidenceThreshold
    ) {
        if (inventory == null || generalizer == null || equivalenceService == null || counterexampleSearchService == null) {
            throw new IllegalArgumentException("pipeline dependencies must not be null");
        }
        this.inventory = inventory;
        this.generalizer = generalizer;
        this.equivalenceService = equivalenceService;
        this.counterexampleSearchService = counterexampleSearchService;
        this.knownRules = knownRules == null ? new KnownRuleRepository() : knownRules;
        this.confidenceThreshold = confidenceThreshold;
    }

    public MacroLearningResult learn(List<SuccessfulTransformationPath> replayPaths) {
        List<String> stages = new ArrayList<>();
        List<SuccessfulTransformationPath> successful = replayPaths == null
            ? List.of()
            : replayPaths.stream().filter(SuccessfulTransformationPath::equivalenceVerified).toList();
        stages.add("collect successful paths: " + successful.size());
        List<ReusableRule> touched = new ArrayList<>();
        List<ReusableRule> promoted = new ArrayList<>();
        List<MacroValidationExample> validationExamples = new ArrayList<>();
        List<CounterexampleSearchService.CounterexampleSearchResult> counterexampleResults = new ArrayList<>();
        for (SuccessfulTransformationPath path : successful) {
            generalizer.generalizeSingleExampleSchema(path)
                .ifPresent(pattern -> {
                    stages.add("extract source/target pairs: " + path.id());
                    stages.add("generalize schema: " + pattern.leftPattern() + " -> " + pattern.rightPattern());
                    stages.add("mine parameter relations: " + pattern.parameterRelations());
                    List<MacroValidationExample> examples = validateGeneratedSchema(pattern, stages);
                    validationExamples.addAll(examples);
                    boolean generatedValid = !examples.isEmpty() && examples.stream().allMatch(MacroValidationExample::equivalent);
                    boolean symbolic = equivalenceService.areEquivalent(pattern.leftPattern(), pattern.rightPattern());
                    CounterexampleSearchService.CounterexampleSearchResult counterexamples = counterexampleSearchService.search(
                        new CounterexampleSearchService.HypothesisInput(
                            path.id(),
                            pattern.leftPattern(),
                            pattern.rightPattern(),
                            generalizedAssumptions(path.assumptions())
                        ),
                        CounterexampleSearchService.CounterexampleBudget.defaultBudget()
                    );
                    counterexampleResults.add(counterexamples);
                    stages.add("search for counterexamples: " + counterexamples.status());
                    double confidence = confidence(symbolic, generatedValid, counterexamples);
                    stages.add("score confidence: " + confidence);
                    if (qualityGatesPass(pattern, examples, symbolic, generatedValid, counterexamples, confidence)) {
                        ReusableRule rule = promote(path, pattern, confidence, symbolic, generalizedAssumptions(path.assumptions()));
                        touched.add(rule);
                        promoted.add(rule);
                        stages.add("promote: " + rule.id());
                    } else {
                        stages.add("reject: quality gates failed for " + path.id());
                    }
                });
        }
        return new MacroLearningResult(touched, promoted, validationExamples, counterexampleResults, stages);
    }

    private List<MacroValidationExample> validateGeneratedSchema(GeneralizedPattern pattern, List<String> stages) {
        RulePatternNode leftPattern = patternParser.parse(pattern.leftPattern());
        RulePatternNode rightPattern = patternParser.parse(pattern.rightPattern());
        Set<String> detectedPlaceholders = new LinkedHashSet<>(placeholders(pattern.leftPattern()));
        detectedPlaceholders.addAll(placeholders(pattern.rightPattern()));
        if (detectedPlaceholders.size() > 1) {
            stages.add("multi-placeholder validation not supported yet: " + detectedPlaceholders);
            return List.of();
        }
        if (detectedPlaceholders.isEmpty()) {
            stages.add("reject: generated schema has no placeholders");
            return List.of();
        }
        String placeholder = detectedPlaceholders.iterator().next();
        List<MacroValidationExample> examples = new ArrayList<>();
        for (String sample : GENERATED_SUBSTITUTIONS) {
            Expr sampleExpression = expressionParser.parseTerm(sample);
            String left = ExpressionFormatter.format(instantiator.instantiate(leftPattern, Map.of(placeholder, sampleExpression)));
            String right = ExpressionFormatter.format(instantiator.instantiate(rightPattern, Map.of(placeholder, sampleExpression)));
            examples.add(new MacroValidationExample(placeholder + " = " + sample, left, right, equivalenceService.areEquivalent(left, right)));
        }
        return examples;
    }

    private boolean qualityGatesPass(
        GeneralizedPattern pattern,
        List<MacroValidationExample> examples,
        boolean symbolic,
        boolean generatedValid,
        CounterexampleSearchService.CounterexampleSearchResult counterexamples,
        double confidence
    ) {
        Set<String> sourcePlaceholders = placeholders(pattern.leftPattern());
        Set<String> targetPlaceholders = placeholders(pattern.rightPattern());
        boolean hasPlaceholder = !sourcePlaceholders.isEmpty();
        boolean targetBound = sourcePlaceholders.containsAll(targetPlaceholders);
        boolean structuredRelations = pattern.parameterRelations().stream()
            .filter(relation -> relation.contains("=") || relation.contains("!="))
            .allMatch(relation -> ParameterRelation.parse(relation).isPresent());
        boolean noCounterexample = counterexamples.status() != CounterexampleSearchService.Status.COUNTEREXAMPLE_FOUND;
        return hasPlaceholder
            && targetBound
            && structuredRelations
            && generatedValid
            && (symbolic || noCounterexample)
            && confidence >= confidenceThreshold
            && examples.stream().allMatch(MacroValidationExample::equivalent);
    }

    private Set<String> placeholders(String pattern) {
        Set<String> placeholders = new LinkedHashSet<>();
        Matcher matcher = PLACEHOLDER.matcher(pattern);
        while (matcher.find()) {
            placeholders.add(matcher.group());
        }
        return placeholders;
    }

    private double confidence(
        boolean symbolic,
        boolean generatedValid,
        CounterexampleSearchService.CounterexampleSearchResult counterexamples
    ) {
        if (!generatedValid || counterexamples.status() == CounterexampleSearchService.Status.COUNTEREXAMPLE_FOUND) {
            return 0.0;
        }
        if (symbolic) {
            return 0.95;
        }
        return counterexamples.status() == CounterexampleSearchService.Status.NO_COUNTEREXAMPLE_FOUND ? 0.85 : 0.75;
    }

    private ReusableRule promote(
        SuccessfulTransformationPath path,
        GeneralizedPattern pattern,
        double confidence,
        boolean symbolic,
        List<String> assumptions
    ) {
        String hash = RulePatternCanonicalizer.hash(pattern.leftPattern(), pattern.rightPattern());
        ReusableRule rule = new ReusableRule(
            "macro_" + Integer.toHexString(hash.hashCode()),
            pattern.leftPattern(),
            pattern.rightPattern(),
            pattern.parameterRelations(),
            symbolic ? CandidateProofStatus.SYMBOLICALLY_VERIFIED : CandidateProofStatus.VALIDATED_BY_EXAMPLES,
            knownRules.statusFor(pattern.leftPattern(), pattern.rightPattern()),
            1,
            Math.max(1.0, path.scoreImprovement()),
            Instant.now(),
            hash,
            null,
            0,
            1,
            List.of(path.id()),
            confidence,
            assumptions
        );
        inventory.save(rule);
        inventory.setEnabled(rule.id(), true);
        return rule;
    }

    private List<String> generalizedAssumptions(List<String> assumptions) {
        if (assumptions == null || assumptions.isEmpty()) {
            return List.of();
        }
        return assumptions.stream()
            .map(assumption -> assumption.replaceAll("\\b[a-z][a-zA-Z0-9_]*\\b", "A"))
            .distinct()
            .toList();
    }
}
