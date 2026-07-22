package de.regelsuche.math.algorithms.equivalence;

import de.regelsuche.canonical.ExpressionCanonicalizer;
import de.regelsuche.transform.AstRewriteTransformationEngine;
import de.regelsuche.transform.HypothesisTransformationEngine;
import de.regelsuche.transform.RationalNormalizationHypothesisOperator;
import de.regelsuche.transform.RewriteRule;
import de.regelsuche.transform.Transformation;
import de.regelsuche.transform.TransformationEngine;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Bounded candidate-formation and held-out search adapter for rational rewrites.
 *
 * <p>Formation receives expressions and assumptions only. It selects the
 * generic assumption-aware rational-normalization operator when every supplied
 * formation seed yields an independently confirmed simplification. No target
 * expression is represented by the formation API.</p>
 *
 * <p>Evaluation composes the selected operator with an explicit frozen
 * primitive inventory. The default inventory contains only difference-of-
 * squares factoring and division-by-one cleanup. Partial fractions, nested
 * division normalization and the wider default rule graph are intentionally
 * absent.</p>
 */
public final class RationalAssumptionRewriteAdapter {
    public static final String CANDIDATE_FORM_ID =
        "ASSUMPTION_SENSITIVE_FACTOR_CANCELLATION";
    public static final String OPERATOR_ID =
        RationalNormalizationHypothesisOperator.RULE_ID;
    public static final List<String> FROZEN_PRIMITIVE_RULE_IDS = List.of(
        "ast_divide_one",
        "ast_square_difference_factor");

    private final RationalNormalizationHypothesisOperator formationOperator;
    private final RationalFunctionNormalFormEquivalenceService equivalence;
    private final ExpressionCanonicalizer canonicalizer;
    private final TransformationEngine evaluationEngine;

    public RationalAssumptionRewriteAdapter() {
        this(
            new RationalNormalizationHypothesisOperator(),
            new RationalFunctionNormalFormEquivalenceService(),
            new ExpressionCanonicalizer());
    }

    RationalAssumptionRewriteAdapter(
        RationalNormalizationHypothesisOperator formationOperator,
        RationalFunctionNormalFormEquivalenceService equivalence,
        ExpressionCanonicalizer canonicalizer
    ) {
        this.formationOperator = Objects.requireNonNull(
            formationOperator, "formationOperator");
        this.equivalence = Objects.requireNonNull(equivalence, "equivalence");
        this.canonicalizer = Objects.requireNonNull(
            canonicalizer, "canonicalizer");
        List<RewriteRule> primitives = AstRewriteTransformationEngine
            .defaultRules().stream()
            .filter(rule -> FROZEN_PRIMITIVE_RULE_IDS.contains(rule.id()))
            .toList();
        if (primitives.size() != FROZEN_PRIMITIVE_RULE_IDS.size()) {
            throw new IllegalStateException(
                "frozen rational primitive inventory is incomplete: "
                    + primitives.stream().map(RewriteRule::id).toList());
        }
        this.evaluationEngine = new HypothesisTransformationEngine(
            new AstRewriteTransformationEngine(primitives, 12, 24),
            List.of(formationOperator),
            12);
    }

    public FormationResult formCandidate(
        List<FormationSeed> seeds,
        ResourceBudget budget
    ) {
        Objects.requireNonNull(seeds, "seeds");
        Objects.requireNonNull(budget, "budget");
        if (seeds.isEmpty()) {
            throw new IllegalArgumentException(
                "at least one formation seed is required");
        }
        BudgetLedger ledger = new BudgetLedger(budget);
        List<FormationEvidence> evidence = new ArrayList<>();
        for (FormationSeed seed : seeds) {
            Optional<FormationEvidence> selected;
            try {
                selected = selectFormationCandidate(seed, ledger);
            } catch (BudgetExceededException exception) {
                return new FormationResult(
                    FormationStatus.NO_CANDIDATE,
                    Optional.empty(),
                    List.copyOf(evidence),
                    ledger.snapshot(),
                    exception.getMessage());
            }
            if (selected.isEmpty()) {
                return new FormationResult(
                    FormationStatus.NO_CANDIDATE,
                    Optional.empty(),
                    List.copyOf(evidence),
                    ledger.snapshot(),
                    "formation seed produced no confirmed simplifying "
                        + "rational-normalization candidate: " + seed.seedId());
            }
            evidence.add(selected.orElseThrow());
        }
        CandidateForm candidate = new CandidateForm(
            CANDIDATE_FORM_ID,
            OPERATOR_ID,
            formationOperator.getClass().getName(),
            evidence.stream().map(FormationEvidence::seedId).toList(),
            FROZEN_PRIMITIVE_RULE_IDS);
        return new FormationResult(
            FormationStatus.SELECTED,
            Optional.of(candidate),
            List.copyOf(evidence),
            ledger.snapshot(),
            "every formation seed supports the same generic operator");
    }

    private Optional<FormationEvidence> selectFormationCandidate(
        FormationSeed seed,
        BudgetLedger ledger
    ) {
        ledger.exploreState();
        List<Transformation> candidates = formationOperator
            .generateCandidates(seed.expression()).stream()
            .sorted(Comparator
                .comparingInt((Transformation candidate) ->
                    canonicalizer.astNodeCount(
                        candidate.transformedExpression()))
                .thenComparing(Transformation::transformedExpression)
                .thenComparing(Transformation::applicationKey))
            .toList();
        for (Transformation candidate : candidates) {
            ledger.evaluateCandidate();
            var validation = equivalence.evaluate(
                seed.expression(),
                candidate.transformedExpression(),
                seed.assumptions());
            if (validation.status()
                    != RationalFunctionNormalFormEquivalenceService.Status.CONFIRMED) {
                continue;
            }
            int before = canonicalizer.astNodeCount(seed.expression());
            int after = canonicalizer.astNodeCount(
                candidate.transformedExpression());
            if (after >= before || candidate.assumptions().isEmpty()) {
                continue;
            }
            return Optional.of(new FormationEvidence(
                seed.seedId(),
                seed.sourceReference(),
                seed.expression(),
                seed.assumptions(),
                candidate.transformedExpression(),
                candidate.assumptions(),
                before,
                after,
                validation.requiredNonZeroFactors(),
                validation.providedNonZeroFactors(),
                validation.leftCrossNormalForm(),
                validation.rightCrossNormalForm()));
        }
        return Optional.empty();
    }

    public SearchResult evaluate(
        EvaluationTask task,
        FormationResult formation,
        ResourceBudget budget
    ) {
        Objects.requireNonNull(task, "task");
        Objects.requireNonNull(formation, "formation");
        Objects.requireNonNull(budget, "budget");
        if (formation.status() != FormationStatus.SELECTED
                || formation.candidate().isEmpty()) {
            return new SearchResult(
                SearchStatus.CANDIDATE_NOT_FORMED,
                task.taskId(),
                task.source(),
                task.target(),
                List.of(),
                new ResourceUse(
                    budget.maxExploredStates(),
                    0,
                    budget.maxExploredStates(),
                    budget.maxCandidateEvaluations(),
                    0,
                    budget.maxCandidateEvaluations()),
                "held-out evaluation cannot run without a formed candidate");
        }
        BudgetLedger ledger = new BudgetLedger(budget);
        ArrayDeque<SearchNode> queue = new ArrayDeque<>();
        Set<String> visited = new LinkedHashSet<>();
        queue.add(new SearchNode(task.source(), List.of(), 0));
        visited.add(canonicalizer.stableHash(task.source()));
        String target = canonicalizer.canonicalize(task.target());

        while (!queue.isEmpty() && ledger.canExplore()) {
            SearchNode node = queue.removeFirst();
            ledger.exploreState();
            if (canonicalizer.canonicalize(node.expression()).equals(target)) {
                return success(task, node, ledger);
            }
            if (node.depth() >= budget.maxDepth()) {
                continue;
            }
            List<Transformation> candidates = evaluationEngine
                .transform(node.expression()).stream()
                .sorted(Comparator
                    .comparing(Transformation::rule)
                    .thenComparing(Transformation::transformedExpression)
                    .thenComparing(Transformation::applicationKey))
                .toList();
            for (Transformation candidate : candidates) {
                if (!ledger.canEvaluateCandidate()) {
                    return exhausted(task, ledger,
                        "candidate-evaluation budget exhausted");
                }
                ledger.evaluateCandidate();
                var validation = equivalence.evaluate(
                    node.expression(),
                    candidate.transformedExpression(),
                    task.assumptions());
                if (validation.status()
                        != RationalFunctionNormalFormEquivalenceService.Status.CONFIRMED) {
                    continue;
                }
                String hash = canonicalizer.stableHash(
                    candidate.transformedExpression());
                if (!visited.add(hash)) {
                    continue;
                }
                List<SearchStep> path = new ArrayList<>(node.steps());
                path.add(new SearchStep(
                    path.size() + 1,
                    candidate.rule(),
                    node.expression(),
                    candidate.transformedExpression(),
                    candidate.assumptions(),
                    validation.requiredNonZeroFactors(),
                    validation.providedNonZeroFactors(),
                    validation.leftCrossNormalForm(),
                    validation.rightCrossNormalForm()));
                queue.addLast(new SearchNode(
                    candidate.transformedExpression(),
                    List.copyOf(path),
                    node.depth() + 1));
            }
        }
        if (!queue.isEmpty()) {
            return exhausted(task, ledger, "explored-state budget exhausted");
        }
        return new SearchResult(
            SearchStatus.NO_RESULT,
            task.taskId(),
            task.source(),
            task.target(),
            List.of(),
            ledger.snapshot(),
            "selected cancellation form and frozen primitives did not reach "
                + "the held-out target");
    }

    private SearchResult success(
        EvaluationTask task,
        SearchNode node,
        BudgetLedger ledger
    ) {
        var finalValidation = equivalence.evaluate(
            task.source(), task.target(), task.assumptions());
        if (finalValidation.status()
                != RationalFunctionNormalFormEquivalenceService.Status.CONFIRMED) {
            return new SearchResult(
                SearchStatus.TARGET_REFUTED,
                task.taskId(),
                task.source(),
                task.target(),
                node.steps(),
                ledger.snapshot(),
                "reached target failed independent rational-function validation");
        }
        return new SearchResult(
            SearchStatus.REACHED_AND_CONFIRMED,
            task.taskId(),
            task.source(),
            task.target(),
            node.steps(),
            ledger.snapshot(),
            "target reached through independently confirmed rewrite steps");
    }

    private SearchResult exhausted(
        EvaluationTask task,
        BudgetLedger ledger,
        String detail
    ) {
        return new SearchResult(
            SearchStatus.BUDGET_EXHAUSTED,
            task.taskId(),
            task.source(),
            task.target(),
            List.of(),
            ledger.snapshot(),
            detail);
    }

    public enum FormationStatus {
        SELECTED,
        NO_CANDIDATE
    }

    public enum SearchStatus {
        REACHED_AND_CONFIRMED,
        NO_RESULT,
        BUDGET_EXHAUSTED,
        CANDIDATE_NOT_FORMED,
        TARGET_REFUTED
    }

    public record FormationSeed(
        String seedId,
        String expression,
        List<String> assumptions,
        String sourceReference
    ) {
        public FormationSeed {
            seedId = requireText(seedId, "seedId");
            expression = requireText(expression, "expression");
            assumptions = immutableStrings(assumptions, "assumptions");
            sourceReference = requireText(
                sourceReference, "sourceReference");
        }
    }

    public record CandidateForm(
        String candidateFormId,
        String operatorId,
        String implementationClass,
        List<String> supportSeedIds,
        List<String> frozenPrimitiveRuleIds
    ) {
        public CandidateForm {
            candidateFormId = requireText(
                candidateFormId, "candidateFormId");
            operatorId = requireText(operatorId, "operatorId");
            implementationClass = requireText(
                implementationClass, "implementationClass");
            supportSeedIds = immutableStrings(
                supportSeedIds, "supportSeedIds");
            frozenPrimitiveRuleIds = immutableStrings(
                frozenPrimitiveRuleIds, "frozenPrimitiveRuleIds");
        }
    }

    public record FormationEvidence(
        String seedId,
        String sourceReference,
        String inputExpression,
        List<String> declaredAssumptions,
        String selectedExpression,
        List<String> candidateAssumptions,
        int inputAstNodes,
        int selectedAstNodes,
        List<String> requiredNonZeroFactors,
        List<String> providedNonZeroFactors,
        String leftCrossNormalForm,
        String rightCrossNormalForm
    ) {
        public FormationEvidence {
            seedId = requireText(seedId, "seedId");
            sourceReference = requireText(
                sourceReference, "sourceReference");
            inputExpression = requireText(
                inputExpression, "inputExpression");
            declaredAssumptions = immutableStrings(
                declaredAssumptions, "declaredAssumptions");
            selectedExpression = requireText(
                selectedExpression, "selectedExpression");
            candidateAssumptions = immutableStrings(
                candidateAssumptions, "candidateAssumptions");
            if (inputAstNodes < 1 || selectedAstNodes < 1
                    || selectedAstNodes >= inputAstNodes) {
                throw new IllegalArgumentException(
                    "formation candidate must strictly reduce AST size");
            }
            requiredNonZeroFactors = immutableStrings(
                requiredNonZeroFactors, "requiredNonZeroFactors");
            providedNonZeroFactors = immutableStrings(
                providedNonZeroFactors, "providedNonZeroFactors");
            leftCrossNormalForm = requireText(
                leftCrossNormalForm, "leftCrossNormalForm");
            rightCrossNormalForm = requireText(
                rightCrossNormalForm, "rightCrossNormalForm");
        }
    }

    public record FormationResult(
        FormationStatus status,
        Optional<CandidateForm> candidate,
        List<FormationEvidence> evidence,
        ResourceUse resourceUse,
        String detail
    ) {
        public FormationResult {
            Objects.requireNonNull(status, "status");
            candidate = candidate == null ? Optional.empty() : candidate;
            evidence = evidence == null ? List.of() : List.copyOf(evidence);
            Objects.requireNonNull(resourceUse, "resourceUse");
            detail = requireText(detail, "detail");
            if (status == FormationStatus.SELECTED && candidate.isEmpty()) {
                throw new IllegalArgumentException(
                    "selected formation requires a candidate");
            }
        }
    }

    public record EvaluationTask(
        String taskId,
        String source,
        String target,
        List<String> assumptions
    ) {
        public EvaluationTask {
            taskId = requireText(taskId, "taskId");
            source = requireText(source, "source");
            target = requireText(target, "target");
            assumptions = immutableStrings(assumptions, "assumptions");
        }
    }

    public record SearchStep(
        int sequence,
        String ruleId,
        String source,
        String target,
        List<String> generatedAssumptions,
        List<String> requiredNonZeroFactors,
        List<String> providedNonZeroFactors,
        String leftCrossNormalForm,
        String rightCrossNormalForm
    ) {
        public SearchStep {
            if (sequence < 1) {
                throw new IllegalArgumentException(
                    "search-step sequence must be positive");
            }
            ruleId = requireText(ruleId, "ruleId");
            source = requireText(source, "source");
            target = requireText(target, "target");
            generatedAssumptions = immutableStrings(
                generatedAssumptions, "generatedAssumptions");
            requiredNonZeroFactors = immutableStrings(
                requiredNonZeroFactors, "requiredNonZeroFactors");
            providedNonZeroFactors = immutableStrings(
                providedNonZeroFactors, "providedNonZeroFactors");
            leftCrossNormalForm = requireText(
                leftCrossNormalForm, "leftCrossNormalForm");
            rightCrossNormalForm = requireText(
                rightCrossNormalForm, "rightCrossNormalForm");
        }
    }

    public record SearchResult(
        SearchStatus status,
        String taskId,
        String source,
        String target,
        List<SearchStep> steps,
        ResourceUse resourceUse,
        String detail
    ) {
        public SearchResult {
            Objects.requireNonNull(status, "status");
            taskId = requireText(taskId, "taskId");
            source = requireText(source, "source");
            target = requireText(target, "target");
            steps = steps == null ? List.of() : List.copyOf(steps);
            Objects.requireNonNull(resourceUse, "resourceUse");
            detail = requireText(detail, "detail");
        }
    }

    public record ResourceBudget(
        int maxDepth,
        int maxExploredStates,
        int maxCandidateEvaluations
    ) {
        public ResourceBudget {
            if (maxDepth < 0 || maxExploredStates < 1
                    || maxCandidateEvaluations < 1) {
                throw new IllegalArgumentException(
                    "resource budgets must be positive except maxDepth");
            }
        }
    }

    public record ResourceUse(
        int configuredExploredStates,
        int executedExploredStates,
        int remainingExploredStates,
        int configuredCandidateEvaluations,
        int executedCandidateEvaluations,
        int remainingCandidateEvaluations
    ) {
        public ResourceUse {
            if (configuredExploredStates < 0 || executedExploredStates < 0
                    || remainingExploredStates < 0
                    || configuredCandidateEvaluations < 0
                    || executedCandidateEvaluations < 0
                    || remainingCandidateEvaluations < 0
                    || executedExploredStates + remainingExploredStates
                        != configuredExploredStates
                    || executedCandidateEvaluations
                        + remainingCandidateEvaluations
                        != configuredCandidateEvaluations) {
                throw new IllegalArgumentException(
                    "resource accounting is not balanced");
            }
        }
    }

    private record SearchNode(
        String expression,
        List<SearchStep> steps,
        int depth
    ) {
    }

    private static final class BudgetLedger {
        private final ResourceBudget budget;
        private int exploredStates;
        private int candidateEvaluations;

        private BudgetLedger(ResourceBudget budget) {
            this.budget = budget;
        }

        private boolean canExplore() {
            return exploredStates < budget.maxExploredStates();
        }

        private void exploreState() {
            if (!canExplore()) {
                throw new BudgetExceededException(
                    "formation explored-state budget exhausted");
            }
            exploredStates++;
        }

        private boolean canEvaluateCandidate() {
            return candidateEvaluations
                < budget.maxCandidateEvaluations();
        }

        private void evaluateCandidate() {
            if (!canEvaluateCandidate()) {
                throw new BudgetExceededException(
                    "formation candidate-evaluation budget exhausted");
            }
            candidateEvaluations++;
        }

        private ResourceUse snapshot() {
            return new ResourceUse(
                budget.maxExploredStates(),
                exploredStates,
                budget.maxExploredStates() - exploredStates,
                budget.maxCandidateEvaluations(),
                candidateEvaluations,
                budget.maxCandidateEvaluations() - candidateEvaluations);
        }
    }

    private static final class BudgetExceededException extends RuntimeException {
        private BudgetExceededException(String message) {
            super(message);
        }
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    private static List<String> immutableStrings(
        List<String> values,
        String name
    ) {
        Objects.requireNonNull(values, name);
        if (values.stream().anyMatch(value -> value == null || value.isBlank())) {
            throw new IllegalArgumentException(
                name + " must not contain blank values");
        }
        return List.copyOf(values);
    }
}
