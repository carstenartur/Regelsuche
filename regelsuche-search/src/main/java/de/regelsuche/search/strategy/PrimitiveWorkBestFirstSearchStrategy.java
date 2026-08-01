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
import java.util.Optional;
import java.util.PriorityQueue;
import java.util.Set;

/**
 * Deterministic syntax-targeted best-first search with explicit primitive-step
 * and transformation-work budgets.
 *
 * <p>Ordinary search-edge depth remains visible, but it never substitutes for
 * primitive depth. A composed program edge containing three primitive rewrites
 * consumes three primitive steps before it can enter the frontier.</p>
 */
public final class PrimitiveWorkBestFirstSearchStrategy {
    public Result search(Problem problem) {
        Objects.requireNonNull(problem, "problem");
        State root = State.root(problem);
        if (root.expression().equals(problem.targetExpression())) {
            return result(
                problem,
                List.of(root),
                root,
                root,
                Status.ROOT_ALREADY_TARGET,
                new MutableMetrics());
        }

        PriorityQueue<State> frontier = new PriorityQueue<>(
            comparator(problem));
        Set<String> queued = new HashSet<>();
        Set<String> visited = new HashSet<>();
        List<State> explored = new ArrayList<>();
        MutableMetrics metrics = new MutableMetrics();
        frontier.add(root);
        queued.add(stateKey(root));
        State best = root;
        State reached = null;

        while (!frontier.isEmpty()
                && explored.size() < problem.budget().maxExploredStates()
                && !metrics.workBudgetExceeded) {
            State current = frontier.remove();
            queued.remove(stateKey(current));
            if (!visited.add(stateKey(current))) {
                metrics.duplicatePrunes++;
                continue;
            }
            explored.add(current);
            if (better(current, best, problem)) {
                best = current;
            }
            if (current.expression().equals(problem.targetExpression())) {
                reached = current;
                break;
            }
            if (current.primitiveDepth()
                    >= problem.budget().maxPrimitiveSteps()) {
                metrics.primitiveBudgetPrunes++;
                continue;
            }

            TransformationBatch batch = problem.engine()
                .transformMeasured(current.expression());
            metrics.engineBatches++;
            metrics.work = metrics.work.plus(batch.workMetrics());
            if (metrics.work.totalWorkUnits()
                    > problem.budget().maxWorkUnits()) {
                metrics.workBudgetExceeded = true;
                break;
            }
            metrics.expandedStates++;
            metrics.generatedTransformations += batch.transformations().size();
            if (batch.transformations().isEmpty()) {
                metrics.statesWithoutTransformations++;
            }

            List<Transformation> transformations = new ArrayList<>(
                batch.transformations());
            transformations.sort(transformationComparator(problem));
            int accepted = 0;
            for (Transformation transformation : transformations) {
                if (accepted >= problem.budget().maxCandidatesPerState()) {
                    metrics.candidateBudgetPrunes++;
                    break;
                }
                if (current.appliedRuleApplications().contains(
                        transformation.applicationKey())) {
                    metrics.repeatedApplicationPrunes++;
                    continue;
                }
                int nextExpandingSteps = current.expandingSteps()
                    + (transformation.kind() == RewriteKind.EXPAND ? 1 : 0);
                if (nextExpandingSteps
                        > problem.budget().maxExpandingSteps()) {
                    metrics.expansionBudgetPrunes++;
                    continue;
                }
                int nextPrimitiveDepth;
                try {
                    nextPrimitiveDepth = Math.addExact(
                        current.primitiveDepth(),
                        transformation.primitiveStepCount());
                } catch (ArithmeticException exception) {
                    metrics.primitiveBudgetPrunes++;
                    continue;
                }
                if (nextPrimitiveDepth
                        > problem.budget().maxPrimitiveSteps()) {
                    metrics.primitiveBudgetPrunes++;
                    continue;
                }
                if (transformation.transformedExpression().equals(
                        current.expression())) {
                    metrics.sameExpressionPrunes++;
                    continue;
                }

                State next = current.next(
                    problem,
                    transformation,
                    nextPrimitiveDepth,
                    nextExpandingSteps);
                String key = stateKey(next);
                if (visited.contains(key) || !queued.add(key)) {
                    metrics.duplicatePrunes++;
                    continue;
                }
                frontier.add(next);
                metrics.enqueuedStates++;
                accepted++;
            }
        }

        Status status;
        if (reached != null) {
            status = Status.REACHED;
        } else if (metrics.workBudgetExceeded) {
            status = Status.WORK_BUDGET;
        } else if (!frontier.isEmpty()
                && explored.size() >= problem.budget().maxExploredStates()) {
            status = Status.OUTER_STATE_BUDGET;
        } else if (metrics.candidateBudgetPrunes > 0) {
            status = Status.CANDIDATE_BUDGET;
        } else if (metrics.primitiveBudgetPrunes > 0) {
            status = Status.PRIMITIVE_BUDGET;
        } else if (metrics.expandedStates > 0
                && metrics.generatedTransformations == 0) {
            status = Status.NO_TRANSFORMATIONS;
        } else {
            status = Status.FRONTIER_EXHAUSTED;
        }
        return result(problem, explored, reached, best, status, metrics);
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
        return priority(candidate, problem) < priority(current, problem)
            || priority(candidate, problem) == priority(current, problem)
                && candidate.primitiveDepth() < current.primitiveDepth()
            || priority(candidate, problem) == priority(current, problem)
                && candidate.primitiveDepth() == current.primitiveDepth()
                && candidate.expression().compareTo(current.expression()) < 0;
    }

    private static String stateKey(State state) {
        return state.expression() + "\u0000"
            + AssumptionSignature.ofExpressions(state.assumptions()).fingerprint();
    }

    private static String normalize(String expression, String name) {
        if (expression == null || expression.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return expression.trim().replaceAll("\\s+", " ");
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
                    || maxWorkUnits < 1) {
                throw new IllegalArgumentException(
                    "primitive-work search budgets are invalid");
            }
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

        public Optional<State> reached() {
            return Optional.ofNullable(reachedState);
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
    }
}
