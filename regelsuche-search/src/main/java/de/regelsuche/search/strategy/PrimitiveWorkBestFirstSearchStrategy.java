package de.regelsuche.search.strategy;

import de.regelsuche.assumption.AssumptionSignature;
import de.regelsuche.canonical.ExpressionCanonicalizer;
import de.regelsuche.scoring.ExpressionScore;
import de.regelsuche.scoring.ExpressionScorer;
import de.regelsuche.transform.MeasuredTransformationEngine;
import de.regelsuche.transform.RewriteKind;
import de.regelsuche.transform.Transformation;
import de.regelsuche.transform.TransformationBatch;
import de.regelsuche.transform.TransformationWorkMetrics;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.PriorityQueue;
import java.util.Set;

/**
 * Deterministic syntax-targeted best-first search with explicit primitive-step
 * and total-work budgets.
 *
 * <p>Ordinary search-edge depth remains visible, but it never substitutes for
 * primitive depth. A composed program edge containing three primitive rewrites
 * consumes three primitive steps before it can enter the frontier. The total
 * work budget reserves one unit per possible primitive path edge for later
 * exact auditing; the remainder bounds transformation formation and outer
 * search administration.</p>
 */
public final class PrimitiveWorkBestFirstSearchStrategy {
    public Result search(Problem problem) {
        Objects.requireNonNull(problem, "problem");
        State root = State.root(problem);
        if (isTarget(root, problem)) {
            return rootResult(problem, root);
        }

        SearchContext context = SearchContext.start(problem, root);
        while (context.canContinue(problem)) {
            if (processNextState(problem, context) == LoopAction.STOP) {
                break;
            }
        }
        return finish(problem, context);
    }

    private static Result rootResult(Problem problem, State root) {
        return result(
            problem,
            List.of(root),
            root,
            root,
            Status.ROOT_ALREADY_TARGET,
            new MutableMetrics());
    }

    private static LoopAction processNextState(
        Problem problem,
        SearchContext context
    ) {
        State current = context.poll();
        if (!context.visit(current)) {
            return context.prune(problem, PruneKind.DUPLICATE);
        }
        if (context.exceedsWorkBudget(problem)) {
            return LoopAction.STOP;
        }

        context.considerBest(current, problem);
        if (isTarget(current, problem)) {
            context.reached = current;
            return LoopAction.STOP;
        }
        if (current.primitiveDepth()
                >= problem.budget().maxPrimitiveSteps()) {
            return context.prune(problem, PruneKind.PRIMITIVE_BUDGET);
        }
        return expand(problem, context, current);
    }

    private static LoopAction expand(
        Problem problem,
        SearchContext context,
        State current
    ) {
        TransformationBatch batch = problem.engine()
            .transformMeasured(current.expression());
        context.account(batch);
        if (context.exceedsWorkBudget(problem)) {
            return LoopAction.STOP;
        }

        List<Transformation> transformations = new ArrayList<>(
            batch.transformations());
        transformations.sort(transformationComparator(problem));
        return enqueueCandidates(problem, context, current, transformations);
    }

    private static LoopAction enqueueCandidates(
        Problem problem,
        SearchContext context,
        State current,
        List<Transformation> transformations
    ) {
        int accepted = 0;
        for (Transformation transformation : transformations) {
            if (context.metrics.workBudgetExceeded) {
                return LoopAction.STOP;
            }
            if (accepted >= problem.budget().maxCandidatesPerState()) {
                return context.prune(problem, PruneKind.CANDIDATE_BUDGET);
            }
            CandidateAction action = processCandidate(
                problem, context, current, transformation);
            if (action == CandidateAction.STOP) {
                return LoopAction.STOP;
            }
            if (action == CandidateAction.ENQUEUED) {
                accepted++;
            }
        }
        return LoopAction.CONTINUE;
    }

    private static CandidateAction processCandidate(
        Problem problem,
        SearchContext context,
        State current,
        Transformation transformation
    ) {
        if (current.appliedRuleApplications().contains(
                transformation.applicationKey())) {
            return context.reject(problem, PruneKind.REPEATED_APPLICATION);
        }

        int nextExpandingSteps = nextExpandingSteps(current, transformation);
        if (nextExpandingSteps > problem.budget().maxExpandingSteps()) {
            return context.reject(problem, PruneKind.EXPANSION_BUDGET);
        }

        int nextPrimitiveDepth = nextPrimitiveDepth(current, transformation);
        if (nextPrimitiveDepth < 0
                || nextPrimitiveDepth > problem.budget().maxPrimitiveSteps()) {
            return context.reject(problem, PruneKind.PRIMITIVE_BUDGET);
        }
        if (transformation.transformedExpression().equals(
                current.expression())) {
            return context.reject(problem, PruneKind.SAME_EXPRESSION);
        }

        State next = current.next(
            problem,
            transformation,
            nextPrimitiveDepth,
            nextExpandingSteps);
        if (!context.queue(next)) {
            return context.reject(problem, PruneKind.DUPLICATE);
        }
        context.metrics.enqueuedStates = add(
            context.metrics.enqueuedStates, 1);
        return context.exceedsWorkBudget(problem)
            ? CandidateAction.STOP
            : CandidateAction.ENQUEUED;
    }

    private static int nextExpandingSteps(
        State current,
        Transformation transformation
    ) {
        return current.expandingSteps()
            + (transformation.kind() == RewriteKind.EXPAND ? 1 : 0);
    }

    private static int nextPrimitiveDepth(
        State current,
        Transformation transformation
    ) {
        try {
            return Math.addExact(
                current.primitiveDepth(),
                transformation.primitiveStepCount());
        } catch (ArithmeticException exception) {
            return -1;
        }
    }

    private static boolean isTarget(State state, Problem problem) {
        return state.expression().equals(problem.targetExpression());
    }

    private static Result finish(Problem problem, SearchContext context) {
        return result(
            problem,
            context.explored,
            context.reached,
            context.best,
            status(problem, context),
            context.metrics);
    }

    private static Status status(Problem problem, SearchContext context) {
        if (context.reached != null) {
            return Status.REACHED;
        }
        if (context.metrics.workBudgetExceeded) {
            return Status.WORK_BUDGET;
        }
        if (!context.frontier.isEmpty()
                && context.explored.size()
                    >= problem.budget().maxExploredStates()) {
            return Status.OUTER_STATE_BUDGET;
        }
        if (context.metrics.candidateBudgetPrunes > 0) {
            return Status.CANDIDATE_BUDGET;
        }
        if (context.metrics.primitiveBudgetPrunes > 0) {
            return Status.PRIMITIVE_BUDGET;
        }
        if (context.metrics.expandedStates > 0
                && context.metrics.generatedTransformations == 0) {
            return Status.NO_TRANSFORMATIONS;
        }
        return Status.FRONTIER_EXHAUSTED;
    }

    private static Result result(
        Problem problem,
        List<State> explored,
        State reached,
        State best,
        Status status,
        MutableMetrics metrics
    ) {
        return new Result(
            explored,
            reached,
            best,
            status,
            new Metrics(
                explored.size(),
                metrics.expandedStates,
                metrics.generatedTransformations,
                metrics.enqueuedStates,
                metrics.duplicatePrunes,
                metrics.repeatedApplicationPrunes,
                metrics.sameExpressionPrunes,
                metrics.expansionBudgetPrunes,
                metrics.primitiveBudgetPrunes,
                metrics.candidateBudgetPrunes,
                metrics.statesWithoutTransformations,
                metrics.engineBatches,
                metrics.work,
                problem.budget().maxPrimitiveSteps(),
                problem.budget().maxWorkUnits()));
    }

    private static Comparator<State> comparator(Problem problem) {
        return Comparator
            .comparingInt((State state) -> priority(state, problem))
            .thenComparingInt(State::primitiveDepth)
            .thenComparingInt(State::edgeDepth)
            .thenComparing(State::expression)
            .thenComparing(state -> String.join("->", state.appliedRuleIds()))
            .thenComparing(state -> String.join("->", state.path()));
    }

    private static Comparator<Transformation> transformationComparator(
        Problem problem
    ) {
        return Comparator
            .comparingInt((Transformation transformation) ->
                syntaxDistance(
                    transformation.transformedExpression(),
                    problem.targetExpression(),
                    problem.canonicalizer()))
            .thenComparingInt(Transformation::primitiveStepCount)
            .thenComparing(Transformation::rule)
            .thenComparing(Transformation::transformedExpression)
            .thenComparing(Transformation::applicationKey);
    }

    private static int priority(State state, Problem problem) {
        long value = state.score().weightedTotal();
        value += (long) state.primitiveDepth() * 2L;
        value += (long) state.expandingSteps() * 5L;
        value += syntaxDistance(
            state.expression(),
            problem.targetExpression(),
            problem.canonicalizer());
        return value >= Integer.MAX_VALUE / 2
            ? Integer.MAX_VALUE / 2
            : value <= Integer.MIN_VALUE / 2
                ? Integer.MIN_VALUE / 2
                : (int) value;
    }

    private static int syntaxDistance(
        String expression,
        String target,
        ExpressionCanonicalizer canonicalizer
    ) {
        if (expression.equals(target)) {
            return 0;
        }
        int nodes;
        int targetNodes;
        try {
            nodes = canonicalizer.astNodeCount(expression);
            targetNodes = canonicalizer.astNodeCount(target);
        } catch (RuntimeException exception) {
            nodes = expression.length();
            targetNodes = target.length();
        }
        long distance = 1L + Math.abs((long) nodes - targetNodes)
            + Math.abs((long) expression.length() - target.length());
        return distance >= Integer.MAX_VALUE / 4
            ? Integer.MAX_VALUE / 4
            : (int) distance;
    }

    private static boolean better(
        State candidate,
        State current,
        Problem problem
    ) {
        int candidatePriority = priority(candidate, problem);
        int currentPriority = priority(current, problem);
        return candidatePriority < currentPriority
            || (candidatePriority == currentPriority
                && candidate.primitiveDepth() < current.primitiveDepth())
            || (candidatePriority == currentPriority
                && candidate.primitiveDepth() == current.primitiveDepth()
                && candidate.expression().compareTo(current.expression()) < 0);
    }

    private static String stateKey(State state) {
        return state.expression() + "\u0000"
            + state.primitiveDepth() + "\u0000"
            + AssumptionSignature.ofExpressions(state.assumptions()).fingerprint();
    }

    private static String normalize(String expression, String name) {
        if (expression == null || expression.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return expression.trim().replaceAll("\\s+", " ");
    }

    private static long add(long left, long right) {
        try {
            return Math.addExact(left, right);
        } catch (ArithmeticException exception) {
            return Long.MAX_VALUE;
        }
    }

    public record Problem(
        String inputExpression,
        String targetExpression,
        MeasuredTransformationEngine engine,
        ExpressionScorer scorer,
        ExpressionCanonicalizer canonicalizer,
        Budget budget
    ) {
        public Problem {
            inputExpression = normalize(inputExpression, "inputExpression");
            targetExpression = normalize(targetExpression, "targetExpression");
            Objects.requireNonNull(engine, "engine");
            Objects.requireNonNull(scorer, "scorer");
            Objects.requireNonNull(canonicalizer, "canonicalizer");
            Objects.requireNonNull(budget, "budget");
        }
    }

    /** Total work budget including a worst-case exact path-audit reserve. */
    public record Budget(
        int maxPrimitiveSteps,
        int maxExploredStates,
        int maxCandidatesPerState,
        int maxExpandingSteps,
        long maxWorkUnits
    ) {
        public Budget {
            if (maxPrimitiveSteps < 1
                    || maxExploredStates < 1
                    || maxCandidatesPerState < 1
                    || maxExpandingSteps < 0
                    || maxWorkUnits <= maxPrimitiveSteps) {
                throw new IllegalArgumentException(
                    "primitive-work search budgets are invalid or leave no "
                        + "mechanical work after exact path-audit reservation");
            }
        }

        public long exactPathAuditReserve() {
            return maxPrimitiveSteps;
        }

        public long mechanicalSearchWorkBudget() {
            return maxWorkUnits - exactPathAuditReserve();
        }
    }

    public record State(
        String expression,
        int edgeDepth,
        int primitiveDepth,
        ExpressionScore score,
        List<String> path,
        List<String> appliedRuleIds,
        List<String> primitiveRuleIds,
        Set<String> appliedRuleApplications,
        List<String> assumptions,
        int expandingSteps,
        String canonicalHash
    ) {
        public State {
            expression = normalize(expression, "expression");
            if (edgeDepth < 0 || primitiveDepth < 0 || expandingSteps < 0) {
                throw new IllegalArgumentException(
                    "state depths must not be negative");
            }
            Objects.requireNonNull(score, "score");
            path = List.copyOf(path);
            appliedRuleIds = List.copyOf(appliedRuleIds);
            primitiveRuleIds = List.copyOf(primitiveRuleIds);
            appliedRuleApplications = Set.copyOf(appliedRuleApplications);
            assumptions = AssumptionSignature.ofExpressions(assumptions)
                .normalizedAssumptions();
            canonicalHash = normalize(canonicalHash, "canonicalHash");
            if (primitiveRuleIds.size() != primitiveDepth) {
                throw new IllegalArgumentException(
                    "primitiveDepth must equal retained primitive lineage size");
            }
        }

        private static State root(Problem problem) {
            String input = problem.inputExpression();
            return new State(
                input,
                0,
                0,
                problem.scorer().score(input),
                List.of(input),
                List.of(),
                List.of(),
                Set.of(),
                List.of(),
                0,
                problem.canonicalizer().stableHash(input));
        }

        private State next(
            Problem problem,
            Transformation transformation,
            int nextPrimitiveDepth,
            int nextExpandingSteps
        ) {
            String output = transformation.transformedExpression();
            List<String> nextPath = append(path, output);
            List<String> nextRules = append(
                appliedRuleIds, transformation.rule());
            List<String> nextPrimitiveRules = new ArrayList<>(
                primitiveRuleIds);
            nextPrimitiveRules.addAll(transformation.primitiveRuleIds());
            Set<String> nextApplications = new LinkedHashSet<>(
                appliedRuleApplications);
            nextApplications.add(transformation.applicationKey());
            List<String> nextAssumptions = new ArrayList<>(assumptions);
            nextAssumptions.addAll(transformation.assumptions());
            return new State(
                output,
                edgeDepth + 1,
                nextPrimitiveDepth,
                problem.scorer().score(output),
                nextPath,
                nextRules,
                nextPrimitiveRules,
                nextApplications,
                nextAssumptions,
                nextExpandingSteps,
                problem.canonicalizer().stableHash(output));
        }

        public boolean programUsed() {
            return appliedRuleIds.stream()
                .anyMatch(rule -> rule.startsWith("program:"));
        }

        private static <T> List<T> append(List<T> values, T value) {
            List<T> result = new ArrayList<>(values);
            result.add(value);
            return List.copyOf(result);
        }
    }

    public enum Status {
        ROOT_ALREADY_TARGET,
        REACHED,
        WORK_BUDGET,
        OUTER_STATE_BUDGET,
        PRIMITIVE_BUDGET,
        CANDIDATE_BUDGET,
        NO_TRANSFORMATIONS,
        FRONTIER_EXHAUSTED
    }

    public record Metrics(
        int exploredStates,
        long expandedStates,
        long generatedTransformations,
        long enqueuedStates,
        long duplicatePrunes,
        long repeatedApplicationPrunes,
        long sameExpressionPrunes,
        long expansionBudgetPrunes,
        long primitiveBudgetPrunes,
        long candidateBudgetPrunes,
        long statesWithoutTransformations,
        long engineBatches,
        TransformationWorkMetrics transformationWork,
        int primitiveStepBudget,
        long workUnitBudget
    ) {
        public Metrics {
            Objects.requireNonNull(transformationWork, "transformationWork");
        }

        public long exactPathAuditReserve() {
            return primitiveStepBudget;
        }

        public long mechanicalSearchWorkBudget() {
            return workUnitBudget - exactPathAuditReserve();
        }

        public long outerSearchWorkUnits() {
            long total = exploredStates;
            total = add(total, expandedStates);
            total = add(total, generatedTransformations);
            total = add(total, enqueuedStates);
            total = add(total, duplicatePrunes);
            total = add(total, repeatedApplicationPrunes);
            total = add(total, sameExpressionPrunes);
            total = add(total, expansionBudgetPrunes);
            total = add(total, primitiveBudgetPrunes);
            total = add(total, candidateBudgetPrunes);
            total = add(total, statesWithoutTransformations);
            return add(total, engineBatches);
        }

        public long totalMechanicalWorkUnits() {
            return add(
                transformationWork.totalWorkUnits(),
                outerSearchWorkUnits());
        }
    }

    public record Result(
        List<State> exploredStates,
        State reachedState,
        State bestState,
        Status status,
        Metrics metrics
    ) {
        public Result {
            exploredStates = List.copyOf(exploredStates);
            Objects.requireNonNull(bestState, "bestState");
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(metrics, "metrics");
        }

        public boolean reached() {
            return status == Status.REACHED
                || status == Status.ROOT_ALREADY_TARGET;
        }
    }

    private enum LoopAction {
        CONTINUE,
        STOP
    }

    private enum CandidateAction {
        ENQUEUED,
        REJECTED,
        STOP
    }

    private enum PruneKind {
        DUPLICATE,
        REPEATED_APPLICATION,
        SAME_EXPRESSION,
        EXPANSION_BUDGET,
        PRIMITIVE_BUDGET,
        CANDIDATE_BUDGET
    }

    private static final class SearchContext {
        private final PriorityQueue<State> frontier;
        private final Set<String> queued = new HashSet<>();
        private final Set<String> visited = new HashSet<>();
        private final List<State> explored = new ArrayList<>();
        private final MutableMetrics metrics = new MutableMetrics();
        private State best;
        private State reached;

        private SearchContext(Problem problem, State root) {
            frontier = new PriorityQueue<>(comparator(problem));
            frontier.add(root);
            queued.add(stateKey(root));
            best = root;
        }

        private static SearchContext start(Problem problem, State root) {
            return new SearchContext(problem, root);
        }

        private boolean canContinue(Problem problem) {
            return !frontier.isEmpty()
                && explored.size() < problem.budget().maxExploredStates()
                && !metrics.workBudgetExceeded;
        }

        private State poll() {
            State current = frontier.remove();
            queued.remove(stateKey(current));
            return current;
        }

        private boolean visit(State current) {
            if (!visited.add(stateKey(current))) {
                return false;
            }
            explored.add(current);
            return true;
        }

        private void considerBest(State current, Problem problem) {
            if (better(current, best, problem)) {
                best = current;
            }
        }

        private void account(TransformationBatch batch) {
            metrics.engineBatches = add(metrics.engineBatches, 1);
            metrics.work = metrics.work.plus(batch.workMetrics());
            metrics.expandedStates = add(metrics.expandedStates, 1);
            metrics.generatedTransformations = add(
                metrics.generatedTransformations,
                batch.transformations().size());
            if (batch.transformations().isEmpty()) {
                metrics.statesWithoutTransformations = add(
                    metrics.statesWithoutTransformations, 1);
            }
        }

        private boolean queue(State state) {
            String key = stateKey(state);
            if (visited.contains(key) || !queued.add(key)) {
                return false;
            }
            frontier.add(state);
            return true;
        }

        private LoopAction prune(Problem problem, PruneKind kind) {
            increment(kind);
            return exceedsWorkBudget(problem)
                ? LoopAction.STOP
                : LoopAction.CONTINUE;
        }

        private CandidateAction reject(Problem problem, PruneKind kind) {
            increment(kind);
            return exceedsWorkBudget(problem)
                ? CandidateAction.STOP
                : CandidateAction.REJECTED;
        }

        private void increment(PruneKind kind) {
            switch (kind) {
                case DUPLICATE -> metrics.duplicatePrunes = add(
                    metrics.duplicatePrunes, 1);
                case REPEATED_APPLICATION ->
                    metrics.repeatedApplicationPrunes = add(
                        metrics.repeatedApplicationPrunes, 1);
                case SAME_EXPRESSION -> metrics.sameExpressionPrunes = add(
                    metrics.sameExpressionPrunes, 1);
                case EXPANSION_BUDGET -> metrics.expansionBudgetPrunes = add(
                    metrics.expansionBudgetPrunes, 1);
                case PRIMITIVE_BUDGET -> metrics.primitiveBudgetPrunes = add(
                    metrics.primitiveBudgetPrunes, 1);
                case CANDIDATE_BUDGET -> metrics.candidateBudgetPrunes = add(
                    metrics.candidateBudgetPrunes, 1);
            }
        }

        private boolean exceedsWorkBudget(Problem problem) {
            if (metrics.totalMechanicalWorkUnits(explored.size())
                    > problem.budget().mechanicalSearchWorkBudget()) {
                metrics.workBudgetExceeded = true;
            }
            return metrics.workBudgetExceeded;
        }
    }

    private static final class MutableMetrics {
        private long expandedStates;
        private long generatedTransformations;
        private long enqueuedStates;
        private long duplicatePrunes;
        private long repeatedApplicationPrunes;
        private long sameExpressionPrunes;
        private long expansionBudgetPrunes;
        private long primitiveBudgetPrunes;
        private long candidateBudgetPrunes;
        private long statesWithoutTransformations;
        private long engineBatches;
        private TransformationWorkMetrics work =
            TransformationWorkMetrics.ZERO;
        private boolean workBudgetExceeded;

        private long totalMechanicalWorkUnits(int exploredStates) {
            long total = exploredStates;
            total = add(total, expandedStates);
            total = add(total, generatedTransformations);
            total = add(total, enqueuedStates);
            total = add(total, duplicatePrunes);
            total = add(total, repeatedApplicationPrunes);
            total = add(total, sameExpressionPrunes);
            total = add(total, expansionBudgetPrunes);
            total = add(total, primitiveBudgetPrunes);
            total = add(total, candidateBudgetPrunes);
            total = add(total, statesWithoutTransformations);
            total = add(total, engineBatches);
            return add(total, work.totalWorkUnits());
        }
    }
}
