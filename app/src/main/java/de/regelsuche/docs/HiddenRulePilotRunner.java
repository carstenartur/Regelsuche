package de.regelsuche.docs;

import de.regelsuche.canonical.ExpressionCanonicalizer;
import de.regelsuche.equivalence.SymPyEquivalenceService;
import de.regelsuche.inventory.InMemoryRuleInventoryRepository;
import de.regelsuche.inventory.ReusableRule;
import de.regelsuche.learning.MacroLearningPipeline;
import de.regelsuche.learning.MacroLearningResult;
import de.regelsuche.mining.DynamicOperatorCompiler;
import de.regelsuche.mining.DynamicPatternOperator;
import de.regelsuche.mining.KnownRule;
import de.regelsuche.mining.KnownRuleRepository;
import de.regelsuche.mining.PatternGeneralizer;
import de.regelsuche.mining.RuleStatus;
import de.regelsuche.mining.SuccessfulTransformationPath;
import de.regelsuche.parse.ExpressionFormatter;
import de.regelsuche.parse.ExpressionParser;
import de.regelsuche.scoring.ExpressionScorer;
import de.regelsuche.search.SearchHeuristic;
import de.regelsuche.search.strategy.BestFirstSearchStrategy;
import de.regelsuche.search.strategy.BestFirstSearchStrategy.GoalMetrics;
import de.regelsuche.search.strategy.BestFirstSearchStrategy.GoalSearchResult;
import de.regelsuche.search.strategy.BestFirstSearchStrategy.GoalStatus;
import de.regelsuche.search.strategy.SearchProblem;
import de.regelsuche.search.strategy.SearchProblem.SearchTarget;
import de.regelsuche.search.strategy.SearchProblem.TargetRelation;
import de.regelsuche.search.strategy.SearchState;
import de.regelsuche.transform.HypothesisTransformationEngine;
import de.regelsuche.transform.Transformation;
import de.regelsuche.transform.TransformationEngine;
import de.regelsuche.validation.CandidateProofStatus;
import de.regelsuche.validation.CounterexampleSearchService;
import de.regelsuche.validation.DeterministicCounterexampleSearchService;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Executes the runtime half of a hidden-rule pilot without access to the hidden
 * reference rule, family label, or generalized target template.
 *
 * <p>The runtime sees only an opaque case id, concrete search tasks, a primitive
 * engine, and holdouts. Post-hoc comparison belongs to {@link HiddenRulePilotEvaluator}.</p>
 */
public final class HiddenRulePilotRunner {
    private final ExpressionScorer scorer = new ExpressionScorer();
    private final ExpressionCanonicalizer canonicalizer = new ExpressionCanonicalizer();
    private final ExpressionParser parser = new ExpressionParser();
    private final SymPyEquivalenceService equivalence = new SymPyEquivalenceService();
    private final DynamicOperatorCompiler compiler = new DynamicOperatorCompiler();

    public RuntimeResult run(RuntimeTask task) {
        Objects.requireNonNull(task, "task");
        GoalSearchResult discovery = search(
            task.primitiveEngine(),
            task.inputExpression(),
            task.target(),
            task.heuristic());
        if (!discovery.reached() || discovery.reachedState() == null
                || discovery.reachedState().depth() == 0) {
            return RuntimeResult.searchFailure(task.opaqueCaseId(), discovery);
        }

        SearchState reached = discovery.reachedState();
        SuccessfulTransformationPath path = toLearningPath(task, reached);
        MacroLearningResult learning = learn(path);
        if (learning.newlyActivated().isEmpty()) {
            return RuntimeResult.learningFailure(
                task.opaqueCaseId(), discovery, reached,
                CandidateValidationEvidence.from(learning, null),
                learning.stageEvidence());
        }

        ReusableRule learned = learning.newlyActivated().getFirst();
        CandidateValidationEvidence validation =
            CandidateValidationEvidence.from(learning, learned);
        DynamicOperatorCompiler.CompilationResult compilation = compiler.compile(
            "pilot-" + task.opaqueCaseId(),
            "frozen-v1",
            learned.leftPattern(),
            learned.rightPattern());
        if (!compilation.isSuccess()) {
            return RuntimeResult.compilationFailure(
                task.opaqueCaseId(), discovery, reached, learned, validation,
                learning.stageEvidence(), compilation.rejectionReason());
        }

        DynamicPatternOperator operator = compilation.operator().orElseThrow();
        HoldoutSummary holdouts = validateHoldouts(task, operator);
        CandidateSnapshot candidate = new CandidateSnapshot(
            learned.leftPattern(),
            learned.rightPattern(),
            learned.assumptions(),
            learned.confidenceScore(),
            operator.ruleId(),
            operator.provenanceHash());
        RuntimeStatus status;
        String failure;
        if (!validation.passed()) {
            status = RuntimeStatus.VALIDATION_FAILED;
            failure = "candidate validation evidence failed";
        } else if (!holdouts.allPassed()) {
            status = RuntimeStatus.HOLDOUT_FAILED;
            failure = "one or more holdouts failed";
        } else {
            status = RuntimeStatus.CANDIDATE_FROZEN;
            failure = "";
        }
        return new RuntimeResult(
            task.opaqueCaseId(),
            status,
            discovery.status(),
            discovery.metrics(),
            reached.path(),
            reached.appliedRuleIds(),
            reached.assumptions(),
            candidate,
            validation,
            holdouts,
            learning.stageEvidence(),
            failure);
    }

    private MacroLearningResult learn(SuccessfulTransformationPath path) {
        InMemoryRuleInventoryRepository inventory = new InMemoryRuleInventoryRepository();
        MacroLearningPipeline pipeline = new MacroLearningPipeline(
            inventory,
            new PatternGeneralizer(),
            equivalence,
            new DeterministicCounterexampleSearchService(),
            new RuntimeBlindKnownRules(),
            MacroLearningPipeline.DEFAULT_CONFIDENCE_THRESHOLD);
        return pipeline.learn(List.of(path));
    }

    private SuccessfulTransformationPath toLearningPath(RuntimeTask task, SearchState reached) {
        String first = reached.path().getFirst();
        String last = reached.path().getLast();
        boolean verified = equivalence.areEquivalent(first, last);
        return new SuccessfulTransformationPath(
            task.opaqueCaseId() + "-path",
            first,
            last,
            reached.path(),
            reached.appliedRuleIds(),
            scorer.score(first),
            reached.score(),
            verified,
            verified ? "symbolic-equivalence" : "equivalence-unconfirmed",
            Map.of("source", "hidden-rule-pilot-runtime"),
            reached.assumptions());
    }

    private HoldoutSummary validateHoldouts(RuntimeTask task, DynamicPatternOperator operator) {
        List<PositiveHoldoutResult> positives = task.positiveHoldouts().stream()
            .map(holdout -> validatePositive(task, operator, holdout))
            .toList();
        List<NegativeHoldoutResult> negatives = task.negativeHoldouts().stream()
            .map(holdout -> validateNegative(operator, holdout))
            .toList();
        return new HoldoutSummary(positives, negatives);
    }

    private PositiveHoldoutResult validatePositive(
        RuntimeTask task,
        DynamicPatternOperator operator,
        PositiveHoldout holdout
    ) {
        List<Transformation> direct = operator.generateCandidates(holdout.inputExpression());
        boolean applies = direct.stream().anyMatch(candidate ->
            equivalence.areEquivalent(candidate.transformedExpression(), holdout.targetExpression()));
        SearchTarget target = syntaxTarget(holdout.targetExpression());

        GoalSearchResult baseline = search(
            task.primitiveEngine(),
            holdout.inputExpression(),
            target,
            holdout.heuristicOr(task.heuristic()));
        TransformationEngine augmented = new HypothesisTransformationEngine(
            task.primitiveEngine(), List.of(operator), 4);
        GoalSearchResult withCandidate = search(
            augmented,
            holdout.inputExpression(),
            target,
            holdout.heuristicOr(task.heuristic()));
        AblationEvidence ablation = AblationEvidence.from(baseline, withCandidate);
        return new PositiveHoldoutResult(
            holdout.id(), applies, baseline.reached(), withCandidate.reached(), ablation);
    }

    private NegativeHoldoutResult validateNegative(
        DynamicPatternOperator operator,
        NegativeHoldout holdout
    ) {
        List<Transformation> candidates = operator.generateCandidates(holdout.inputExpression());
        return new NegativeHoldoutResult(holdout.id(), candidates.isEmpty(), candidates.size());
    }

    private GoalSearchResult search(
        TransformationEngine engine,
        String input,
        SearchTarget target,
        SearchHeuristic heuristic
    ) {
        SearchProblem problem = new SearchProblem(
            input, engine, scorer, canonicalizer, heuristic).withTarget(target);
        return new BestFirstSearchStrategy().searchWithDiagnostics(problem);
    }

    private SearchTarget syntaxTarget(String expression) {
        return SearchTarget.syntaxExact(ExpressionFormatter.format(parser.parseTerm(expression)));
    }

    public enum RuntimeStatus {
        SEARCH_FAILED,
        LEARNING_REJECTED,
        COMPILATION_REJECTED,
        VALIDATION_FAILED,
        HOLDOUT_FAILED,
        CANDIDATE_FROZEN
    }

    public record RuntimeTask(
        String opaqueCaseId,
        String inputExpression,
        SearchTarget target,
        TransformationEngine primitiveEngine,
        SearchHeuristic heuristic,
        List<PositiveHoldout> positiveHoldouts,
        List<NegativeHoldout> negativeHoldouts
    ) {
        public RuntimeTask {
            requireText(opaqueCaseId, "opaqueCaseId");
            requireText(inputExpression, "inputExpression");
            Objects.requireNonNull(target, "target");
            if (target.relation() != TargetRelation.SYNTAX_EXACT) {
                throw new IllegalArgumentException(
                    "hidden-rule tasks require an observable SYNTAX_EXACT endpoint");
            }
            Objects.requireNonNull(primitiveEngine, "primitiveEngine");
            Objects.requireNonNull(heuristic, "heuristic");
            positiveHoldouts = positiveHoldouts == null ? List.of() : List.copyOf(positiveHoldouts);
            negativeHoldouts = negativeHoldouts == null ? List.of() : List.copyOf(negativeHoldouts);
        }

        public String observableInput() {
            List<String> values = new ArrayList<>();
            values.add(opaqueCaseId);
            values.add(inputExpression);
            values.add(target.targetExpression());
            positiveHoldouts.forEach(holdout -> {
                values.add(holdout.id());
                values.add(holdout.inputExpression());
                values.add(holdout.targetExpression());
            });
            negativeHoldouts.forEach(holdout -> {
                values.add(holdout.id());
                values.add(holdout.inputExpression());
            });
            return String.join("\n", values);
        }
    }

    public record PositiveHoldout(
        String id,
        String inputExpression,
        String targetExpression,
        SearchHeuristic heuristic
    ) {
        public PositiveHoldout {
            requireText(id, "positive holdout id");
            requireText(inputExpression, "positive holdout input");
            requireText(targetExpression, "positive holdout target");
        }

        public PositiveHoldout(String id, String inputExpression, String targetExpression) {
            this(id, inputExpression, targetExpression, null);
        }

        SearchHeuristic heuristicOr(SearchHeuristic fallback) {
            return heuristic == null ? fallback : heuristic;
        }
    }

    public record NegativeHoldout(String id, String inputExpression) {
        public NegativeHoldout {
            requireText(id, "negative holdout id");
            requireText(inputExpression, "negative holdout input");
        }
    }

    public record CandidateSnapshot(
        String leftPattern,
        String rightPattern,
        List<String> assumptions,
        double confidence,
        String dynamicRuleId,
        String provenanceHash
    ) {
        public CandidateSnapshot {
            assumptions = assumptions == null ? List.of() : List.copyOf(assumptions);
        }
    }

    /** Structured validation evidence, independent of post-hoc hidden-rule similarity. */
    public record CandidateValidationEvidence(
        String proofStatus,
        int generatedValidationExamples,
        int failedValidationExamples,
        List<CounterexampleEvidence> counterexampleSearches
    ) {
        public CandidateValidationEvidence {
            proofStatus = proofStatus == null || proofStatus.isBlank() ? "NONE" : proofStatus;
            if (generatedValidationExamples < 0 || failedValidationExamples < 0
                    || failedValidationExamples > generatedValidationExamples) {
                throw new IllegalArgumentException("invalid validation example counts");
            }
            counterexampleSearches = counterexampleSearches == null
                ? List.of()
                : List.copyOf(counterexampleSearches);
        }

        static CandidateValidationEvidence empty() {
            return new CandidateValidationEvidence("NONE", 0, 0, List.of());
        }

        static CandidateValidationEvidence from(
            MacroLearningResult learning,
            ReusableRule learned
        ) {
            int generated = learning.validationExamples().size();
            int failed = (int) learning.validationExamples().stream()
                .filter(example -> !example.equivalent())
                .count();
            List<CounterexampleEvidence> searches = learning.counterexampleSearches().stream()
                .map(CounterexampleEvidence::from)
                .toList();
            return new CandidateValidationEvidence(
                learned == null ? "NONE" : learned.proofStatus().name(),
                generated,
                failed,
                searches);
        }

        public boolean passed() {
            CandidateProofStatus status;
            try {
                status = CandidateProofStatus.valueOf(proofStatus);
            } catch (IllegalArgumentException exception) {
                return false;
            }
            boolean positiveProof = switch (status) {
                case VALIDATED_BY_EXAMPLES, SYMBOLICALLY_VERIFIED,
                    FORMALLY_PROVABLE, FORMALLY_PROVED -> true;
                default -> false;
            };
            boolean noCounterexample = counterexampleSearches.stream()
                .noneMatch(search -> search.counterexamplePresent()
                    || search.status().equals(
                        CounterexampleSearchService.Status.COUNTEREXAMPLE_FOUND.name()));
            return positiveProof
                && generatedValidationExamples > 0
                && failedValidationExamples == 0
                && noCounterexample;
        }
    }

    public record CounterexampleEvidence(
        String status,
        boolean counterexamplePresent,
        List<String> attemptedSources,
        String explanation
    ) {
        public CounterexampleEvidence {
            status = status == null || status.isBlank() ? "INCONCLUSIVE" : status;
            attemptedSources = attemptedSources == null
                ? List.of()
                : attemptedSources.stream().distinct().sorted().toList();
            explanation = explanation == null ? "" : explanation;
        }

        static CounterexampleEvidence from(
            CounterexampleSearchService.CounterexampleSearchResult result
        ) {
            return new CounterexampleEvidence(
                result.status().name(),
                result.counterexample().isPresent(),
                result.attemptedSources(),
                result.explanation());
        }
    }

    public record RuntimeResult(
        String opaqueCaseId,
        RuntimeStatus status,
        GoalStatus searchStatus,
        GoalMetrics searchMetrics,
        List<String> path,
        List<String> primitiveRuleIds,
        List<String> assumptions,
        CandidateSnapshot candidate,
        CandidateValidationEvidence validationEvidence,
        HoldoutSummary holdouts,
        List<String> stageEvidence,
        String failureReason
    ) {
        public RuntimeResult {
            path = path == null ? List.of() : List.copyOf(path);
            primitiveRuleIds = primitiveRuleIds == null ? List.of() : List.copyOf(primitiveRuleIds);
            assumptions = assumptions == null ? List.of() : List.copyOf(assumptions);
            validationEvidence = validationEvidence == null
                ? CandidateValidationEvidence.empty()
                : validationEvidence;
            holdouts = holdouts == null ? HoldoutSummary.empty() : holdouts;
            stageEvidence = stageEvidence == null ? List.of() : List.copyOf(stageEvidence);
            failureReason = failureReason == null ? "" : failureReason;
        }

        static RuntimeResult searchFailure(String id, GoalSearchResult search) {
            return new RuntimeResult(
                id, RuntimeStatus.SEARCH_FAILED, search.status(), search.metrics(),
                search.states().stream().map(SearchState::expression).toList(),
                List.of(), List.of(), null, CandidateValidationEvidence.empty(),
                HoldoutSummary.empty(), List.of(),
                "primitive search did not reach the concrete task target");
        }

        static RuntimeResult learningFailure(
            String id,
            GoalSearchResult search,
            SearchState reached,
            CandidateValidationEvidence validation,
            List<String> stages
        ) {
            return new RuntimeResult(
                id, RuntimeStatus.LEARNING_REJECTED, search.status(), search.metrics(),
                reached.path(), reached.appliedRuleIds(), reached.assumptions(), null,
                validation, HoldoutSummary.empty(), stages,
                "macro learning rejected the path");
        }

        static RuntimeResult compilationFailure(
            String id,
            GoalSearchResult search,
            SearchState reached,
            ReusableRule learned,
            CandidateValidationEvidence validation,
            List<String> stages,
            String reason
        ) {
            CandidateSnapshot candidate = new CandidateSnapshot(
                learned.leftPattern(), learned.rightPattern(), learned.assumptions(),
                learned.confidenceScore(), "", "");
            return new RuntimeResult(
                id, RuntimeStatus.COMPILATION_REJECTED, search.status(), search.metrics(),
                reached.path(), reached.appliedRuleIds(), reached.assumptions(), candidate,
                validation, HoldoutSummary.empty(), stages, reason);
        }

        public boolean frozen() {
            return status == RuntimeStatus.CANDIDATE_FROZEN;
        }
    }

    public record HoldoutSummary(
        List<PositiveHoldoutResult> positives,
        List<NegativeHoldoutResult> negatives
    ) {
        public HoldoutSummary {
            positives = positives == null ? List.of() : List.copyOf(positives);
            negatives = negatives == null ? List.of() : List.copyOf(negatives);
        }

        static HoldoutSummary empty() {
            return new HoldoutSummary(List.of(), List.of());
        }

        public boolean allPassed() {
            return positives.stream().allMatch(PositiveHoldoutResult::passed)
                && negatives.stream().allMatch(NegativeHoldoutResult::passed);
        }

        public long materialAblations() {
            return positives.stream().filter(result -> result.ablation().materialBenefit()).count();
        }
    }

    public record PositiveHoldoutResult(
        String id,
        boolean directApplicationEquivalent,
        boolean baselineReached,
        boolean candidateReached,
        AblationEvidence ablation
    ) {
        public boolean passed() {
            return directApplicationEquivalent && candidateReached;
        }
    }

    public record NegativeHoldoutResult(String id, boolean noApplication, int candidateCount) {
        public boolean passed() {
            return noApplication;
        }
    }

    public record AblationEvidence(
        boolean baselineReached,
        boolean candidateReached,
        int baselineExploredStates,
        int candidateExploredStates,
        int baselineDepth,
        int candidateDepth,
        double stateReduction,
        boolean materialBenefit
    ) {
        static AblationEvidence from(GoalSearchResult baseline, GoalSearchResult candidate) {
            int baselineDepth = depth(baseline);
            int candidateDepth = depth(candidate);
            int baselineStates = baseline.metrics().exploredStates();
            int candidateStates = candidate.metrics().exploredStates();
            double reduction = baselineStates == 0
                ? 0.0
                : (baselineStates - candidateStates) / (double) baselineStates;
            boolean material = candidate.reached()
                && (!baseline.reached()
                    || reduction >= 0.5
                    || baselineDepth >= 0 && candidateDepth >= 0 && candidateDepth < baselineDepth);
            return new AblationEvidence(
                baseline.reached(), candidate.reached(), baselineStates, candidateStates,
                baselineDepth, candidateDepth, reduction, material);
        }

        private static int depth(GoalSearchResult result) {
            return result.reachedState() == null ? -1 : result.reachedState().depth();
        }
    }

    private static final class RuntimeBlindKnownRules extends KnownRuleRepository {
        @Override
        public RuleStatus statusFor(String leftPattern, String rightPattern) {
            return RuleStatus.NEW;
        }

        @Override
        public double similarityToKnownRules(String leftPattern, String rightPattern) {
            return 0.0;
        }

        @Override
        public List<KnownRule> all() {
            return List.of();
        }
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
