package de.regelsuche.mining;

import de.regelsuche.equivalence.SymPyEquivalenceService;
import de.regelsuche.mining.DynamicOperatorCompiler.CompilationResult;
import de.regelsuche.mining.OpenTargetConjectureMiner.OpenTargetConjecture;
import de.regelsuche.validation.CounterexampleSearchService;
import de.regelsuche.validation.CounterexampleSearchService.CounterexampleBudget;
import de.regelsuche.validation.CounterexampleSearchService.CounterexampleSearchResult;
import de.regelsuche.validation.CounterexampleSearchService.HypothesisInput;
import de.regelsuche.validation.DeterministicCounterexampleSearchService;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Compiles and challenges an already-formed open-target conjecture.
 *
 * <p>Validation is deliberately downstream of candidate formation. Holdout targets,
 * counterexamples and post-hoc outcomes never flow back into the miner.</p>
 */
public final class OpenTargetConjectureEvaluator {
    public static final String SCHEMA = "regelsuche.open-target-conjecture-evaluation/v1";

    private final DynamicOperatorCompiler compiler;
    private final SymPyEquivalenceService equivalence;
    private final CounterexampleSearchService counterexampleSearch;

    public OpenTargetConjectureEvaluator() {
        this(
            new DynamicOperatorCompiler(),
            new SymPyEquivalenceService(),
            new DeterministicCounterexampleSearchService());
    }

    OpenTargetConjectureEvaluator(
        DynamicOperatorCompiler compiler,
        SymPyEquivalenceService equivalence,
        CounterexampleSearchService counterexampleSearch
    ) {
        this.compiler = Objects.requireNonNull(compiler, "compiler");
        this.equivalence = Objects.requireNonNull(equivalence, "equivalence");
        this.counterexampleSearch = Objects.requireNonNull(
            counterexampleSearch, "counterexampleSearch");
    }

    public EvaluationReport evaluate(OpenTargetConjecture conjecture, EvaluationPlan plan) {
        Objects.requireNonNull(conjecture, "conjecture");
        Objects.requireNonNull(plan, "plan");
        validateCandidate(conjecture);

        CompilationResult compilation = compiler.compile(
            conjecture.conjectureId(),
            plan.revision(),
            conjecture.leftPattern(),
            conjecture.rightPattern());
        if (!compilation.isSuccess()) {
            return compilationRejected(conjecture, plan, compilation.rejectionReason());
        }

        DynamicPatternOperator operator = compilation.operator().orElseThrow();
        List<PositiveHoldoutResult> positives = plan.positiveHoldouts().stream()
            .map(holdout -> evaluatePositive(operator, holdout))
            .toList();
        List<NegativeHoldoutResult> negatives = plan.negativeHoldouts().stream()
            .map(holdout -> evaluateNegative(operator, holdout))
            .toList();
        CounterexampleSearchResult counterexample = counterexampleSearch.search(
            new HypothesisInput(
                conjecture.conjectureId(),
                conjecture.leftPattern(),
                conjecture.rightPattern(),
                pathAssumptions(conjecture)),
            plan.counterexampleBudget());

        LinkedHashSet<String> blockers = new LinkedHashSet<>();
        List<String> failedPositiveIds = positives.stream()
            .filter(result -> !result.passed())
            .map(PositiveHoldoutResult::id)
            .toList();
        if (!failedPositiveIds.isEmpty()) {
            blockers.add("positive holdouts failed: " + String.join(",", failedPositiveIds));
        }
        List<String> failedNegativeIds = negatives.stream()
            .filter(result -> !result.passed())
            .map(NegativeHoldoutResult::id)
            .toList();
        if (!failedNegativeIds.isEmpty()) {
            blockers.add("negative holdouts failed: " + String.join(",", failedNegativeIds));
        }
        if (counterexample.status() == CounterexampleSearchService.Status.COUNTEREXAMPLE_FOUND) {
            blockers.add("counterexample found");
        }
        if (!counterexample.inferredAssumptions().isEmpty()) {
            blockers.add("counterexample search inferred assumptions");
        }
        if (counterexample.status() == CounterexampleSearchService.Status.INCONCLUSIVE) {
            blockers.add("counterexample search inconclusive");
        }

        boolean holdoutFailure = !failedPositiveIds.isEmpty() || !failedNegativeIds.isEmpty();
        EvaluationStatus status;
        if (holdoutFailure
                || counterexample.status() == CounterexampleSearchService.Status.COUNTEREXAMPLE_FOUND) {
            status = EvaluationStatus.REJECTED;
        } else if (counterexample.status() == CounterexampleSearchService.Status.INCONCLUSIVE
                || !counterexample.inferredAssumptions().isEmpty()) {
            status = EvaluationStatus.INCONCLUSIVE;
        } else {
            status = EvaluationStatus.ACCEPTED_FOR_PROOF;
        }
        return new EvaluationReport(
            SCHEMA,
            conjecture.conjectureId(),
            status,
            "COMPILED",
            operator.ruleId(),
            operator.provenanceHash(),
            plan.positiveHoldouts().size(),
            positives.size(),
            0,
            plan.negativeHoldouts().size(),
            negatives.size(),
            0,
            positives,
            negatives,
            CounterexampleEvidence.from(counterexample),
            List.copyOf(blockers),
            "NOT_EVALUATED",
            "NOT_EVALUATED");
    }

    private PositiveHoldoutResult evaluatePositive(
        DynamicPatternOperator operator,
        PositiveHoldout holdout
    ) {
        List<String> candidates = operator.generateCandidates(holdout.inputExpression()).stream()
            .map(transformation -> transformation.transformedExpression())
            .sorted()
            .toList();
        boolean equivalent = candidates.stream().anyMatch(candidate ->
            equivalence.areEquivalent(candidate, holdout.targetExpression()));
        return new PositiveHoldoutResult(
            holdout.id(), candidates.size(), equivalent, candidates);
    }

    private NegativeHoldoutResult evaluateNegative(
        DynamicPatternOperator operator,
        NegativeHoldout holdout
    ) {
        List<String> candidates = operator.generateCandidates(holdout.inputExpression()).stream()
            .map(transformation -> transformation.transformedExpression())
            .sorted()
            .toList();
        return new NegativeHoldoutResult(
            holdout.id(), candidates.size(), candidates.isEmpty(), candidates);
    }

    private EvaluationReport compilationRejected(
        OpenTargetConjecture conjecture,
        EvaluationPlan plan,
        String reason
    ) {
        return new EvaluationReport(
            SCHEMA,
            conjecture.conjectureId(),
            EvaluationStatus.COMPILATION_REJECTED,
            "REJECTED",
            "",
            "",
            plan.positiveHoldouts().size(),
            0,
            plan.positiveHoldouts().size(),
            plan.negativeHoldouts().size(),
            0,
            plan.negativeHoldouts().size(),
            List.of(),
            List.of(),
            CounterexampleEvidence.notRun(),
            List.of("compilation rejected: " + reason),
            "NOT_EVALUATED",
            "NOT_EVALUATED");
    }

    private static List<String> pathAssumptions(OpenTargetConjecture conjecture) {
        return conjecture.evidence().stream()
            .flatMap(evidence -> evidence.paths().stream())
            .flatMap(path -> path.assumptions().stream())
            .filter(value -> value != null && !value.isBlank())
            .distinct()
            .sorted()
            .toList();
    }

    private static void validateCandidate(OpenTargetConjecture conjecture) {
        requireText(conjecture.conjectureId(), "conjectureId");
        requireText(conjecture.leftPattern(), "leftPattern");
        requireText(conjecture.rightPattern(), "rightPattern");
        if (conjecture.leftPattern().equals(conjecture.rightPattern())) {
            throw new IllegalArgumentException("candidate patterns must differ");
        }
        if (conjecture.supportCount() < 2 || conjecture.distinctAlphaSupport() < 2) {
            throw new IllegalArgumentException(
                "candidate requires two independently supported observations");
        }
        if (!"OBSERVED_CONJECTURE".equals(conjecture.candidateStatus())
                || !"EQUIVALENCE_PRESERVING_CONVERGENT_PATHS".equals(
                    conjecture.evidenceStatus())) {
            throw new IllegalArgumentException("candidate lacks open-target convergence evidence");
        }
    }

    public enum EvaluationStatus {
        ACCEPTED_FOR_PROOF,
        INCONCLUSIVE,
        REJECTED,
        COMPILATION_REJECTED
    }

    public record EvaluationPlan(
        String revision,
        List<PositiveHoldout> positiveHoldouts,
        List<NegativeHoldout> negativeHoldouts,
        CounterexampleBudget counterexampleBudget
    ) {
        public EvaluationPlan {
            requireText(revision, "revision");
            positiveHoldouts = positiveHoldouts == null ? List.of() : positiveHoldouts.stream()
                .sorted(java.util.Comparator.comparing(PositiveHoldout::id))
                .toList();
            negativeHoldouts = negativeHoldouts == null ? List.of() : negativeHoldouts.stream()
                .sorted(java.util.Comparator.comparing(NegativeHoldout::id))
                .toList();
            if (positiveHoldouts.isEmpty() || negativeHoldouts.isEmpty()) {
                throw new IllegalArgumentException(
                    "fresh positive and negative holdouts are both required");
            }
            Set<String> ids = new LinkedHashSet<>();
            positiveHoldouts.forEach(holdout -> {
                if (!ids.add(holdout.id())) {
                    throw new IllegalArgumentException("duplicate holdout ID: " + holdout.id());
                }
            });
            negativeHoldouts.forEach(holdout -> {
                if (!ids.add(holdout.id())) {
                    throw new IllegalArgumentException("duplicate holdout ID: " + holdout.id());
                }
            });
            Objects.requireNonNull(counterexampleBudget, "counterexampleBudget");
        }
    }

    public record PositiveHoldout(String id, String inputExpression, String targetExpression) {
        public PositiveHoldout {
            requireText(id, "positive holdout id");
            requireText(inputExpression, "positive holdout input");
            requireText(targetExpression, "positive holdout target");
        }
    }

    public record NegativeHoldout(String id, String inputExpression) {
        public NegativeHoldout {
            requireText(id, "negative holdout id");
            requireText(inputExpression, "negative holdout input");
        }
    }

    public record PositiveHoldoutResult(
        String id,
        int candidateCount,
        boolean equivalentCandidate,
        List<String> candidateExpressions
    ) {
        public PositiveHoldoutResult {
            candidateExpressions = candidateExpressions == null
                ? List.of()
                : List.copyOf(candidateExpressions);
        }

        public boolean passed() {
            return equivalentCandidate;
        }
    }

    public record NegativeHoldoutResult(
        String id,
        int candidateCount,
        boolean noApplication,
        List<String> candidateExpressions
    ) {
        public NegativeHoldoutResult {
            candidateExpressions = candidateExpressions == null
                ? List.of()
                : List.copyOf(candidateExpressions);
        }

        public boolean passed() {
            return noApplication;
        }
    }

    public record CounterexampleEvidence(
        String status,
        List<String> attemptedSources,
        List<String> inferredAssumptions,
        List<String> assignments,
        String leftValue,
        String rightValue,
        String explanation
    ) {
        public CounterexampleEvidence {
            attemptedSources = attemptedSources == null ? List.of() : List.copyOf(attemptedSources);
            inferredAssumptions = inferredAssumptions == null
                ? List.of()
                : List.copyOf(inferredAssumptions);
            assignments = assignments == null ? List.of() : List.copyOf(assignments);
            leftValue = leftValue == null ? "" : leftValue;
            rightValue = rightValue == null ? "" : rightValue;
            explanation = explanation == null ? "" : explanation;
        }

        static CounterexampleEvidence from(CounterexampleSearchResult result) {
            List<String> assignments = result.counterexample()
                .map(CounterexampleSearchService.Counterexample::assignments)
                .orElse(List.of());
            String left = result.counterexample()
                .map(CounterexampleSearchService.Counterexample::leftValue)
                .orElse("");
            String right = result.counterexample()
                .map(CounterexampleSearchService.Counterexample::rightValue)
                .orElse("");
            return new CounterexampleEvidence(
                result.status().name(),
                result.attemptedSources(),
                result.inferredAssumptions(),
                assignments,
                left,
                right,
                result.explanation());
        }

        static CounterexampleEvidence notRun() {
            return new CounterexampleEvidence(
                "NOT_RUN", List.of(), List.of(), List.of(), "", "", "compilation failed");
        }
    }

    public record EvaluationReport(
        String schema,
        String conjectureId,
        EvaluationStatus status,
        String compilationStatus,
        String dynamicRuleId,
        String provenanceHash,
        int configuredPositiveHoldouts,
        int executedPositiveHoldouts,
        int skippedPositiveHoldouts,
        int configuredNegativeHoldouts,
        int executedNegativeHoldouts,
        int skippedNegativeHoldouts,
        List<PositiveHoldoutResult> positiveResults,
        List<NegativeHoldoutResult> negativeResults,
        CounterexampleEvidence counterexample,
        List<String> blockers,
        String proofStatus,
        String noveltyStatus
    ) {
        public EvaluationReport {
            positiveResults = positiveResults == null ? List.of() : List.copyOf(positiveResults);
            negativeResults = negativeResults == null ? List.of() : List.copyOf(negativeResults);
            counterexample = Objects.requireNonNull(counterexample, "counterexample");
            blockers = blockers == null ? List.of() : List.copyOf(blockers);
        }

        public boolean holdoutsComplete() {
            return configuredPositiveHoldouts == executedPositiveHoldouts
                && configuredNegativeHoldouts == executedNegativeHoldouts
                && skippedPositiveHoldouts == 0
                && skippedNegativeHoldouts == 0;
        }

        public boolean allHoldoutsPassed() {
            return holdoutsComplete()
                && positiveResults.stream().allMatch(PositiveHoldoutResult::passed)
                && negativeResults.stream().allMatch(NegativeHoldoutResult::passed);
        }

        public boolean acceptedForProof() {
            return status == EvaluationStatus.ACCEPTED_FOR_PROOF;
        }
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
