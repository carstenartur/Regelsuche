package de.regelsuche.search.strategy;

import de.regelsuche.assumption.AssumptionSignature;
import de.regelsuche.canonical.ExpressionCanonicalizer;
import de.regelsuche.scoring.ExpressionScore;
import de.regelsuche.scoring.ExpressionScorer;
import de.regelsuche.search.program.BudgetedRewriteProgramExecution.PathBudget;
import de.regelsuche.search.program.RewriteExecution;
import de.regelsuche.search.strategy.SearchExpansionSource.WorkRevision;
import de.regelsuche.transform.ExecutionWork;
import de.regelsuche.transform.RewriteKind;
import de.regelsuche.transform.Transformation;
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
 * Deterministic syntax-targeted best-first search with explicit primitive,
 * exact-theory and total-work budgets.
 *
 * <p>Ordinary search-edge depth remains visible, but it never substitutes for
 * primitive depth. A composed program edge containing three primitive rewrites
 * consumes three primitive steps before it can enter the frontier. The total
 * work budget reserves one unit per possible primitive path edge for later
 * exact auditing; the remainder bounds transformation formation and outer
 * search administration. Mixed v2 execution additionally charges every observed
 * source candidate's mathematical work before frontier admission. The v1
 * mechanical formula remains available for frozen historical evaluations.</p>
 */
public final class WorkBudgetBestFirstSearchStrategy {
    /** Experimental evidence-bearing scheduler; legacy v1/v2 callers retain their frozen protocol. */
    public de.regelsuche.search.moves.MoveSearch.Result search(de.regelsuche.search.moves.MoveSearch.Problem problem) {
        return new de.regelsuche.search.moves.MoveSearch().search(problem);
    }
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
        State current = context.poll(problem);
        if (!context.visit(current, problem)) {
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
        if (!problem.mixedWork() && current.primitiveDepth()
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
        RewriteExecution execution = problem.source().expand(current.expression(),
            problem.budget().pathBudget().after(current.executionWork()));
        context.account(current, execution);
        if (context.exceedsWorkBudget(problem)) {
            return LoopAction.STOP;
        }

        List<Transformation> transformations = new ArrayList<>(
            execution.transformations());
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
        transformation.provenance().requireSource(current.expression());
        if (alreadyApplied(problem, current, transformation)) {
            return context.reject(problem, current, transformation, PruneKind.REPEATED_APPLICATION);
        }

        int nextExpandingSteps = nextExpandingSteps(current, transformation);
        if (nextExpandingSteps > problem.budget().maxExpandingSteps()) {
            return context.reject(problem, current, transformation, PruneKind.EXPANSION_BUDGET);
        }

        int nextPrimitiveDepth = nextPrimitiveDepth(current, transformation);
        if (nextPrimitiveDepth < 0
                || nextPrimitiveDepth > problem.budget().maxPrimitiveSteps()) {
            return context.reject(problem, current, transformation, PruneKind.PRIMITIVE_BUDGET);
        }
        if (!problem.budget().pathBudget().after(current.executionWork())
                .admits(transformation.executionWork())) {
            return context.reject(problem, current, transformation, PruneKind.PATH_WORK_BUDGET);
        }
        if (transformation.transformedExpression().equals(
                current.expression())) {
            return context.reject(problem, current, transformation, PruneKind.SAME_EXPRESSION);
        }

        State next = current.next(
            problem,
            transformation,
            nextPrimitiveDepth,
            nextExpandingSteps);
        if (context.contains(next, problem)) {
            return context.reject(problem, current, transformation, PruneKind.DUPLICATE);
        }
        if (problem.mixedWork() && !context.reserveEnqueue(problem)) {
            context.decisions.add(new CandidateDecision(current, transformation, Decision.WORK_BUDGET));
            return CandidateAction.STOP;
        }
        context.queue(next, problem);
        context.metrics.enqueuedStates = add(
            context.metrics.enqueuedStates, 1);
        context.decisions.add(new CandidateDecision(current, transformation, Decision.ENQUEUED));
        return context.exceedsWorkBudget(problem)
            ? CandidateAction.STOP
            : CandidateAction.ENQUEUED;
    }

    private static boolean alreadyApplied(Problem problem, State current, Transformation transformation) {
        return problem.mixedWork()
            ? current.transformations().stream().anyMatch(step -> step.provenance().equals(transformation.provenance()))
            : current.appliedRuleApplications().contains(transformation.applicationKey());
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
        return !problem.targetFree() && state.expression().equals(problem.targetExpression());
    }

    private static Result finish(Problem problem, SearchContext context) {
        return new Result(context.explored, context.reached, context.best, status(problem, context),
            context.metrics.snapshot(context.explored.size(), problem), context.expansions, context.decisions,
            RunConfiguration.of(problem));
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
        if (context.metrics.pathWorkBudgetPrunes > 0) {
            return Status.PATH_WORK_BUDGET;
        }
        if (context.expansions.stream().anyMatch(call -> !call.execution().complete())) {
            return Status.INCOMPLETE_EXPANSION;
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
            metrics.snapshot(
                explored.size(),
                problem), List.of(), List.of(), RunConfiguration.of(problem));
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
        if (target.isEmpty()) {
            return 0;
        }
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

    private static StateKey stateKey(State state, Problem problem) {
        return new StateKey(state.expression(), state.primitiveDepth(),
            AssumptionSignature.ofExpressions(state.assumptions()).fingerprint(),
            problem.mixedWork() ? state.transformations() : List.of());
    }

    private record StateKey(String expression, int primitiveDepth, String assumptions,
                            List<Transformation> transformations) {}

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
        SearchExpansionSource source,
        ExpressionScorer scorer,
        ExpressionCanonicalizer canonicalizer,
        Budget budget
    ) {
        public Problem {
            inputExpression = normalize(inputExpression, "inputExpression");
            // The empty string is reserved for the named target-free factory.
            // Whitespace and null remain invalid accidental targets.
            targetExpression = "".equals(targetExpression) ? "" : normalize(targetExpression, "targetExpression");
            Objects.requireNonNull(source, "source");
            Objects.requireNonNull(scorer, "scorer");
            Objects.requireNonNull(canonicalizer, "canonicalizer");
            Objects.requireNonNull(budget, "budget");
            if (source.workRevision() == WorkRevision.MECHANICAL_V1 && budget.maxExactTheoryWorkUnits() != 0) {
                throw new IllegalArgumentException("v1 mechanical evaluation cannot grant theory authority");
            }
        }

        boolean mixedWork() { return source.workRevision() == WorkRevision.MIXED_V2; }

        /** Explore under the ordinary budgets and retain the scorer-selected best state. */
        public static Problem withoutTarget(String inputExpression, SearchExpansionSource source,
                ExpressionScorer scorer, ExpressionCanonicalizer canonicalizer, Budget budget) {
            return new Problem(inputExpression, "", source, scorer, canonicalizer, budget);
        }

        public boolean targetFree() { return targetExpression.isEmpty(); }
    }

    /** Total work budget including a worst-case exact path-audit reserve. */
    public record Budget(
        int maxPrimitiveSteps,
        long maxExactTheoryWorkUnits,
        int maxExploredStates,
        int maxCandidatesPerState,
        int maxExpandingSteps,
        long maxWorkUnits
    ) {
        public Budget {
            if (maxPrimitiveSteps < 0
                    || maxExactTheoryWorkUnits < 0
                    || maxExploredStates < 1
                    || maxCandidatesPerState < 1
                    || maxExpandingSteps < 0
                    || maxWorkUnits <= maxPrimitiveSteps) {
                throw new IllegalArgumentException(
                    "primitive-work search budgets are invalid or leave no "
                        + "mechanical work after exact path-audit reservation");
            }
        }

        public static Budget primitive(int primitiveSteps, int states, int candidates, int expandingSteps, long work) {
            if (primitiveSteps < 1) throw new IllegalArgumentException("v1 requires a positive primitive allowance");
            return new Budget(primitiveSteps, 0, states, candidates, expandingSteps, work);
        }

        public PathBudget pathBudget() {
            return new PathBudget(maxPrimitiveSteps, maxExactTheoryWorkUnits);
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
        String canonicalHash,
        List<Transformation> transformations,
        ExecutionWork executionWork
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
            transformations = List.copyOf(transformations);
            requirePath(expression, edgeDepth, path, appliedRuleIds, primitiveRuleIds,
                appliedRuleApplications, assumptions, expandingSteps, transformations, executionWork);
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
                problem.canonicalizer().stableHash(input), List.of(), ExecutionWork.ZERO);
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
                problem.canonicalizer().stableHash(output), append(transformations, transformation),
                executionWork.plus(transformation.executionWork()));
        }

        public boolean programUsed() {
            return appliedRuleIds.stream()
                .anyMatch(rule -> rule.startsWith("program:"));
        }

        /** Common application/export state without flattening its verified typed path. */
        public SearchState toSearchState(de.regelsuche.scoring.ExpressionScorer scorer) {
            Transformation incoming = transformations.isEmpty() ? null : transformations.getLast();
            String parent = edgeDepth == 0 ? null : path.get(path.size() - 2);
            return new SearchState(expression, edgeDepth, score, path, appliedRuleIds, appliedRuleApplications,
                expandingSteps, canonicalHash, parent, incoming == null ? null : incoming.rule(),
                incoming == null ? RewriteKind.NORMALIZE : incoming.kind(),
                incoming != null && incoming.mayIncreaseComplexity(), incoming == null ? 0 : incoming.estimatedCostDelta(),
                incoming == null || incoming.equivalencePreservingByConstruction(),
                parent == null ? 0 : scorer.score(parent).weightedTotal() - score.weightedTotal(),
                transformations.stream().map(Transformation::kind).toList(),
                transformations.stream().map(Transformation::equivalencePreservingByConstruction).toList(),
                assumptions, transformations);
        }

        private static void requirePath(String expression, int depth, List<String> path,
                List<String> rules, List<String> primitives, Set<String> applications, List<String> assumptions,
                int expandingSteps, List<Transformation> steps, ExecutionWork work) {
            if (steps.size() != depth || path.size() != depth + 1 || !path.getLast().equals(expression)) {
                throw new IllegalArgumentException("state path length or endpoint differs from retained steps");
            }
            for (int i = 0; i < steps.size(); i++) {
                steps.get(i).provenance().requireSource(path.get(i));
                if (!steps.get(i).transformedExpression().equals(path.get(i + 1))) {
                    throw new IllegalArgumentException("state path is discontinuous");
                }
            }
            requireLineage(rules, primitives, applications, assumptions, expandingSteps, steps, work);
        }

        private static void requireLineage(List<String> rules, List<String> primitives, Set<String> applications,
                List<String> assumptions, int expandingSteps, List<Transformation> steps, ExecutionWork work) {
            if (!steps.stream().map(Transformation::rule).toList().equals(rules)
                    || !steps.stream().flatMap(step -> step.primitiveRuleIds().stream()).toList().equals(primitives)
                    || !Set.copyOf(steps.stream().map(Transformation::applicationKey).toList()).equals(applications)
                    || !AssumptionSignature.ofExpressions(steps.stream()
                        .flatMap(step -> step.assumptions().stream()).toList()).normalizedAssumptions().equals(assumptions)
                    || steps.stream().filter(step -> step.kind() == RewriteKind.EXPAND).count() != expandingSteps
                    || !steps.stream().map(Transformation::executionWork).reduce(ExecutionWork.ZERO, ExecutionWork::plus)
                        .equals(work)) {
                throw new IllegalArgumentException("state lineage or work differs from retained transformations");
            }
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
        PATH_WORK_BUDGET,
        INCOMPLETE_EXPANSION,
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
        long workUnitBudget,
        long pathWorkBudgetPrunes,
        long frontierAdmissionChecks,
        long exactTheoryWorkBudget,
        WorkRevision workRevision
    ) {
        public Metrics {
            Objects.requireNonNull(transformationWork, "transformationWork");
            Objects.requireNonNull(workRevision, "workRevision");
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
            total = add(total, pathWorkBudgetPrunes);
            total = add(total, frontierAdmissionChecks);
            return add(total, engineBatches);
        }

        public long totalMechanicalWorkUnits() {
            return add(
                transformationWork.totalWorkUnits(),
                outerSearchWorkUnits());
        }

        public long chargedSearchWorkUnits() {
            return workRevision == WorkRevision.MECHANICAL_V1 ? totalMechanicalWorkUnits()
                : Math.addExact(totalMechanicalWorkUnits(), transformationWork.candidateWork().canonicalWorkUnits());
        }
    }

    public record Result(
        List<State> exploredStates,
        State reachedState,
        State bestState,
        Status status,
        Metrics metrics,
        List<ExpansionObservation> expansions,
        List<CandidateDecision> candidateDecisions,
        RunConfiguration configuration
    ) {
        public Result {
            exploredStates = List.copyOf(exploredStates);
            Objects.requireNonNull(bestState, "bestState");
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(metrics, "metrics");
            expansions = List.copyOf(expansions);
            candidateDecisions = List.copyOf(candidateDecisions);
            Objects.requireNonNull(configuration, "configuration");
        }

        public boolean reached() {
            return status == Status.REACHED
                || status == Status.ROOT_ALREADY_TARGET;
        }

        public boolean expansionsComplete() {
            return expansions.stream().allMatch(call -> call.execution().complete());
        }

        public String toCanonicalJson() { return WorkSearchReplay.toCanonicalJson(this); }
    }

    public record ExpansionObservation(State source, RewriteExecution execution) {
        public ExpansionObservation {
            Objects.requireNonNull(source, "source");
            Objects.requireNonNull(execution, "execution");
        }
    }

    public enum Decision {
        ENQUEUED, WORK_BUDGET, DUPLICATE, REPEATED_APPLICATION, SAME_EXPRESSION,
        EXPANSION_BUDGET, PRIMITIVE_BUDGET, PATH_WORK_BUDGET, CANDIDATE_BUDGET
    }

    public record CandidateDecision(State source, Transformation transformation, Decision outcome) {
        public CandidateDecision {
            Objects.requireNonNull(source, "source");
            Objects.requireNonNull(transformation, "transformation").provenance().requireSource(source.expression());
            Objects.requireNonNull(outcome, "outcome");
        }
    }

    public record RunConfiguration(String inputExpression, String targetExpression, Budget budget, WorkRevision workRevision) {
        public RunConfiguration {
            Objects.requireNonNull(inputExpression, "inputExpression");
            Objects.requireNonNull(targetExpression, "targetExpression");
            Objects.requireNonNull(budget, "budget");
            Objects.requireNonNull(workRevision, "workRevision");
        }

        static RunConfiguration of(Problem problem) {
            return new RunConfiguration(problem.inputExpression(), problem.targetExpression(), problem.budget(),
                problem.source().workRevision());
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
        PATH_WORK_BUDGET,
        CANDIDATE_BUDGET
    }

    private static final class SearchContext {
        private final PriorityQueue<State> frontier;
        private final Set<StateKey> queued = new HashSet<>();
        private final Set<StateKey> visited = new HashSet<>();
        private final List<State> explored = new ArrayList<>();
        private final List<ExpansionObservation> expansions = new ArrayList<>();
        private final List<CandidateDecision> decisions = new ArrayList<>();
        private final MutableMetrics metrics = new MutableMetrics();
        private State best;
        private State reached;

        private SearchContext(Problem problem, State root) {
            frontier = new PriorityQueue<>(comparator(problem));
            frontier.add(root);
            queued.add(stateKey(root, problem));
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

        private State poll(Problem problem) {
            State current = frontier.remove();
            queued.remove(stateKey(current, problem));
            return current;
        }

        private boolean visit(State current, Problem problem) {
            if (!visited.add(stateKey(current, problem))) {
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

        private void account(State current, RewriteExecution batch) {
            expansions.add(new ExpansionObservation(current, batch));
            metrics.engineBatches = add(metrics.engineBatches, 1);
            metrics.work = metrics.work.plus(batch.workMetrics());
            metrics.expandedStates = add(metrics.expandedStates, 1);
            metrics.generatedTransformations = add(
                metrics.generatedTransformations,
                batch.transformations().size());
            metrics.pathWorkBudgetPrunes = add(metrics.pathWorkBudgetPrunes,
                batch.sourceObservations().stream().filter(observation -> !observation.admitted()).count());
            if (batch.transformations().isEmpty()) {
                metrics.statesWithoutTransformations = add(
                    metrics.statesWithoutTransformations, 1);
            }
        }

        private boolean contains(State state, Problem problem) {
            StateKey key = stateKey(state, problem);
            return visited.contains(key) || queued.contains(key);
        }

        private void queue(State state, Problem problem) {
            queued.add(stateKey(state, problem));
            frontier.add(state);
        }

        private boolean reserveEnqueue(Problem problem) {
            metrics.frontierAdmissionChecks = Math.addExact(metrics.frontierAdmissionChecks, 1);
            long charged = metrics.snapshot(explored.size(), problem).chargedSearchWorkUnits();
            if (charged >= problem.budget().mechanicalSearchWorkBudget()) {
                metrics.workBudgetExceeded = true;
            }
            return !metrics.workBudgetExceeded;
        }

        private LoopAction prune(Problem problem, PruneKind kind) {
            increment(kind);
            return exceedsWorkBudget(problem)
                ? LoopAction.STOP
                : LoopAction.CONTINUE;
        }

        private CandidateAction reject(Problem problem, State current, Transformation transformation, PruneKind kind) {
            decisions.add(new CandidateDecision(current, transformation, Decision.valueOf(kind.name())));
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
                case PATH_WORK_BUDGET -> metrics.pathWorkBudgetPrunes = add(metrics.pathWorkBudgetPrunes, 1);
                case CANDIDATE_BUDGET -> metrics.candidateBudgetPrunes = add(
                    metrics.candidateBudgetPrunes, 1);
            }
        }

        private boolean exceedsWorkBudget(Problem problem) {
            if (metrics.snapshot(explored.size(), problem).chargedSearchWorkUnits()
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
        private long pathWorkBudgetPrunes;
        private long frontierAdmissionChecks;
        private long candidateBudgetPrunes;
        private long statesWithoutTransformations;
        private long engineBatches;
        private TransformationWorkMetrics work =
            TransformationWorkMetrics.ZERO;
        private boolean workBudgetExceeded;

        private Metrics snapshot(int exploredStates, Problem problem) {
            return new Metrics(exploredStates, expandedStates, generatedTransformations, enqueuedStates,
                duplicatePrunes, repeatedApplicationPrunes, sameExpressionPrunes, expansionBudgetPrunes,
                primitiveBudgetPrunes, candidateBudgetPrunes, statesWithoutTransformations, engineBatches,
                work, problem.budget().maxPrimitiveSteps(), problem.budget().maxWorkUnits(),
                pathWorkBudgetPrunes, frontierAdmissionChecks, problem.budget().maxExactTheoryWorkUnits(),
                problem.source().workRevision());
        }
    }
}
