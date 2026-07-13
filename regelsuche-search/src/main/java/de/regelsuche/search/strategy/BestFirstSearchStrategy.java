package de.regelsuche.search.strategy;

import de.regelsuche.ast.Expr;
import de.regelsuche.canonical.ExpressionCanonicalizer;
import de.regelsuche.parse.ExpressionParser;
import de.regelsuche.scoring.ExpressionScore;
import de.regelsuche.search.strategy.SearchProblem.SearchTarget;
import de.regelsuche.search.strategy.SearchProblem.TargetRelation;
import de.regelsuche.transform.RewriteKind;
import de.regelsuche.transform.Transformation;
import de.regelsuche.value.ExprValueFactory;
import de.regelsuche.value.ExprValueFactory.AssociativeCommutativeValue;
import de.regelsuche.value.ExprValueFactory.ExprValue;
import de.regelsuche.value.ExprValueFactory.OrderedValue;
import de.regelsuche.value.ExprValueFactory.ValueKey;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.PriorityQueue;
import java.util.Set;

public class BestFirstSearchStrategy implements SearchStrategy {
    @Override
    public List<SearchState> search(SearchProblem problem) {
        return searchWithDiagnostics(problem).states();
    }

    /**
     * Executes the same search as {@link #search(SearchProblem)} and additionally
     * returns deterministic target/failure diagnostics when a target is attached.
     */
    public GoalSearchResult searchWithDiagnostics(SearchProblem problem) {
        Objects.requireNonNull(problem, "problem");
        try (ValueIdentitySession identity = new ValueIdentitySession(problem.canonicalizer())) {
            TargetSession target = new TargetSession(problem.target(), identity);
            SearchState rootState = createRootState(problem, identity);
            SearchFrame frame = createFrame(problem, identity, target);
            frame.frontier().add(rootState);
            frame.telemetry().searchStarted(rootState, frame.frontier().size(), frame.visited().size());

            while (shouldContinue(problem, frame)) {
                processNextState(problem, frame);
            }
            frame.telemetry().searchFinished(
                rootState,
                frame.frontier().size(),
                frame.visited().size(),
                frame.explored().size());
            return GoalSearchResult.from(problem, frame, rootState);
        }
    }

    private SearchFrame createFrame(
        SearchProblem problem,
        ValueIdentitySession identity,
        TargetSession target
    ) {
        return new SearchFrame(
            new PriorityQueue<>(priorityComparator(problem, target)),
            new ArrayList<>(),
            new HashSet<>(),
            SearchTelemetry.forProblem(problem),
            identity,
            target,
            new GoalProgress()
        );
    }

    private SearchState createRootState(SearchProblem problem, ValueIdentitySession identity) {
        String root = normalize(problem.rootExpression());
        ExpressionScore rootScore = problem.scorer().score(root);
        return new SearchState(
            root,
            0,
            rootScore,
            List.of(root),
            List.of(),
            Set.of(),
            0,
            identity.valueHash(root),
            null,
            null,
            RewriteKind.NORMALIZE,
            false,
            0,
            true,
            0
        );
    }

    private Comparator<SearchState> priorityComparator(SearchProblem problem, TargetSession target) {
        return Comparator
            .comparingInt((SearchState state) -> target.adjustedPriority(priority(state, problem), state.expression()))
            .thenComparingInt(SearchState::depth)
            .thenComparing(SearchState::canonicalHash)
            .thenComparing(SearchState::expression)
            .thenComparing(state -> String.join("->", state.appliedRuleIds()))
            .thenComparing(state -> String.join("->", state.path()))
            .thenComparing(state -> String.join("->", sortedValues(state.appliedRuleApplications())));
    }

    private boolean shouldContinue(SearchProblem problem, SearchFrame frame) {
        return !frame.frontier().isEmpty()
            && frame.explored().size() < problem.heuristic().maxVisitedExpressions()
            && !(frame.target().stopWhenReached() && frame.progress().reachedState != null);
    }

    private void processNextState(SearchProblem problem, SearchFrame frame) {
        SearchState current = frame.frontier().remove();
        frame.telemetry().stateDequeued(current, frame.frontier().size(), frame.visited().size());
        if (!markVisited(current, frame) || pruneByTransposition(problem, current, frame)) {
            return;
        }
        frame.explored().add(current);
        frame.progress().record(current, frame.target().distance(current.expression()));
        if (frame.target().reached(current.expression())) {
            frame.progress().reachedState = current;
            if (frame.target().stopWhenReached()) {
                return;
            }
        }
        if (!pruneByDepth(problem, current, frame)) {
            expandState(problem, current, frame);
        }
    }

    private boolean markVisited(SearchState current, SearchFrame frame) {
        if (frame.visited().add(stateKey(current))) {
            frame.telemetry().stateVisited(current, frame.frontier().size(), frame.visited().size());
            return true;
        }
        frame.progress().duplicatePrunes++;
        frame.telemetry().statePrunedDuplicate(current, frame.frontier().size(), frame.visited().size(), 0);
        return false;
    }

    private boolean pruneByTransposition(SearchProblem problem, SearchState current, SearchFrame frame) {
        if (current.depth() == 0 || problem.memory() == null) {
            return false;
        }
        if (TranspositionGate.evaluate(
                problem.memory(),
                current,
                current.canonicalHash() + "#" + current.depth(),
                List.of(frame.identity().legacyHash(current.expression())))
                != TranspositionGate.Verdict.PRUNE) {
            return false;
        }
        frame.progress().transpositionPrunes++;
        frame.telemetry().statePrunedTransposition(current, frame.frontier().size(), frame.visited().size());
        return true;
    }

    private boolean pruneByDepth(SearchProblem problem, SearchState current, SearchFrame frame) {
        if (current.depth() < problem.heuristic().maxDepth()) {
            return false;
        }
        frame.progress().depthPrunes++;
        frame.telemetry().statePrunedDepth(current, frame.frontier().size(), frame.visited().size());
        return true;
    }

    private void expandState(SearchProblem problem, SearchState current, SearchFrame frame) {
        List<Transformation> transformations = sortedTransformations(problem, current, frame.target());
        frame.progress().expandedStates++;
        frame.progress().generatedTransformations += transformations.size();
        if (transformations.isEmpty()) {
            frame.progress().statesWithoutTransformations++;
        }
        frame.telemetry().stateExpanded(
            current,
            frame.frontier().size(),
            frame.visited().size(),
            transformations.size());
        int generated = 0;
        for (Transformation transformation : transformations) {
            if (candidateBudgetReached(problem, current, frame, generated)) {
                break;
            }
            if (tryEnqueueTransformation(problem, current, transformation, frame, generated)) {
                generated++;
            }
        }
    }

    private List<Transformation> sortedTransformations(
        SearchProblem problem,
        SearchState current,
        TargetSession target
    ) {
        List<Transformation> transformations = new ArrayList<>(problem.engine().transform(current.expression()));
        Comparator<Transformation> deterministic = Comparator
            .comparing(Transformation::rule)
            .thenComparing(Transformation::transformedExpression)
            .thenComparing(Transformation::applicationKey);
        if (target.enabled()) {
            transformations.sort(Comparator
                .comparingInt((Transformation transformation) ->
                    target.distance(transformation.transformedExpression()))
                .thenComparing(deterministic));
        } else {
            transformations.sort(deterministic);
        }
        return transformations;
    }

    private boolean candidateBudgetReached(
        SearchProblem problem,
        SearchState current,
        SearchFrame frame,
        int generated
    ) {
        if (generated < problem.heuristic().maxCandidatesPerState()) {
            return false;
        }
        frame.progress().candidateBudgetPrunes++;
        frame.telemetry().statePrunedBudget(
            current,
            frame.frontier().size(),
            frame.visited().size(),
            generated);
        return true;
    }

    private boolean tryEnqueueTransformation(
        SearchProblem problem,
        SearchState current,
        Transformation transformation,
        SearchFrame frame,
        int generated
    ) {
        frame.telemetry().transformationGenerated(
            current,
            transformation,
            frame.frontier().size(),
            frame.visited().size(),
            generated);
        String skipReason = skipReason(problem, current, transformation);
        if (!skipReason.isBlank()) {
            frame.progress().skippedTransformations++;
            frame.telemetry().transformationSkipped(
                current,
                transformation,
                frame.frontier().size(),
                frame.visited().size(),
                generated,
                skipReason);
            return false;
        }
        SearchState nextState = createNextState(problem, current, transformation, frame.identity());
        if (frame.visited().contains(stateKey(nextState))) {
            frame.progress().duplicatePrunes++;
            frame.telemetry().statePrunedDuplicate(
                nextState,
                frame.frontier().size(),
                frame.visited().size(),
                generated);
            return false;
        }
        frame.frontier().add(nextState);
        frame.progress().enqueuedStates++;
        frame.telemetry().stateEnqueued(
            nextState,
            frame.frontier().size(),
            frame.visited().size(),
            generated + 1);
        return true;
    }

    private String skipReason(SearchProblem problem, SearchState current, Transformation transformation) {
        if (current.appliedRuleApplications().contains(transformation.applicationKey())) {
            return "repeated-rule-application";
        }
        int expandedSteps = expandedSteps(current, transformation);
        if (expandedSteps > problem.heuristic().maxExpandingSteps()) {
            return "max-expanding-steps";
        }
        if (transformation.transformedExpression().equals(current.expression())) {
            return "same-expression";
        }
        return "";
    }

    private SearchState createNextState(
        SearchProblem problem,
        SearchState current,
        Transformation transformation,
        ValueIdentitySession identity
    ) {
        String nextExpression = transformation.transformedExpression();
        ExpressionScore nextScore = problem.scorer().score(nextExpression);
        int improvement = current.score().weightedTotal() - nextScore.weightedTotal();
        return new SearchState(
            nextExpression,
            current.depth() + 1,
            nextScore,
            pathWith(current.path(), nextExpression),
            appliedRuleIdsWith(current.appliedRuleIds(), transformation.rule()),
            appliedApplicationsWith(current.appliedRuleApplications(), transformation.applicationKey()),
            expandedSteps(current, transformation),
            identity.valueHash(nextExpression),
            current.expression(),
            transformation.rule(),
            transformation.kind(),
            transformation.mayIncreaseComplexity(),
            transformation.estimatedCostDelta(),
            transformation.equivalencePreservingByConstruction(),
            improvement,
            rewriteKindsWith(current.appliedRuleKinds(), transformation.kind()),
            equivalenceFlagsWith(
                current.equivalencePreservingFlags(),
                transformation.equivalencePreservingByConstruction()),
            assumptionsWith(current.assumptions(), transformation.assumptions())
        );
    }

    private int expandedSteps(SearchState current, Transformation transformation) {
        return current.expandedStepCount() + (transformation.kind() == RewriteKind.EXPAND ? 1 : 0);
    }

    private List<String> pathWith(List<String> path, String nextExpression) {
        List<String> nextPath = new ArrayList<>(path);
        nextPath.add(nextExpression);
        return nextPath;
    }

    private List<String> appliedRuleIdsWith(List<String> appliedRuleIds, String ruleId) {
        List<String> nextRuleIds = new ArrayList<>(appliedRuleIds);
        nextRuleIds.add(ruleId);
        return nextRuleIds;
    }

    private Set<String> appliedApplicationsWith(Set<String> applications, String applicationKey) {
        Set<String> nextApplications = new HashSet<>(applications);
        nextApplications.add(applicationKey);
        return nextApplications;
    }

    private List<RewriteKind> rewriteKindsWith(List<RewriteKind> rewriteKinds, RewriteKind rewriteKind) {
        List<RewriteKind> nextKinds = new ArrayList<>(rewriteKinds);
        nextKinds.add(rewriteKind);
        return nextKinds;
    }

    private List<Boolean> equivalenceFlagsWith(List<Boolean> flags, boolean equivalencePreserving) {
        List<Boolean> nextFlags = new ArrayList<>(flags);
        nextFlags.add(equivalencePreserving);
        return nextFlags;
    }

    private List<String> assumptionsWith(List<String> assumptions, List<String> additionalAssumptions) {
        List<String> nextAssumptions = new ArrayList<>(assumptions);
        nextAssumptions.addAll(additionalAssumptions);
        return nextAssumptions;
    }

    protected int priority(SearchState state) {
        int depthPenalty = state.depth() * 2;
        int expansionPenalty = state.expandedStepCount() * 5;
        int noImprovementPenalty = state.improvement() <= 0 && state.depth() > 0 ? 4 : 0;
        return state.score().weightedTotal() + depthPenalty + expansionPenalty + noImprovementPenalty;
    }

    /**
     * Transformation-objective-aware priority. Target distance is applied by
     * the queue wrapper so subclasses such as A* inherit the same target signal.
     */
    protected int priority(SearchState state, SearchProblem problem) {
        if (problem.costModel() == null) {
            return priority(state);
        }
        int depthPenalty = state.depth() * 2;
        int expansionPenalty = state.expandedStepCount() * 5;
        int noImprovementPenalty = state.improvement() <= 0 && state.depth() > 0 ? 4 : 0;
        int modelCost = problem.costModel().cost(state.expression(), problem.canonicalizer(), state.score());
        if (modelCost == Integer.MAX_VALUE) {
            return Integer.MAX_VALUE / 2;
        }
        return modelCost + depthPenalty + expansionPenalty + noImprovementPenalty;
    }

    private String stateKey(SearchState state) {
        return state.canonicalHash() + ":" + String.join(",", sortedValues(state.appliedRuleApplications()));
    }

    private List<String> sortedValues(Set<String> values) {
        return values.stream().sorted().toList();
    }

    private static String normalize(String expression) {
        return Objects.requireNonNull(expression, "expression").trim().replaceAll("\\s+", " ");
    }

    private record SearchFrame(
        PriorityQueue<SearchState> frontier,
        List<SearchState> explored,
        Set<String> visited,
        SearchTelemetry telemetry,
        ValueIdentitySession identity,
        TargetSession target,
        GoalProgress progress
    ) {
    }

    private static final class GoalProgress {
        private SearchState reachedState;
        private SearchState bestState;
        private int bestDistance = Integer.MAX_VALUE;
        private int expandedStates;
        private int generatedTransformations;
        private int enqueuedStates;
        private int skippedTransformations;
        private int duplicatePrunes;
        private int transpositionPrunes;
        private int depthPrunes;
        private int candidateBudgetPrunes;
        private int statesWithoutTransformations;

        private void record(SearchState state, int distance) {
            if (distance < bestDistance
                    || distance == bestDistance && betterTieBreak(state, bestState)) {
                bestDistance = distance;
                bestState = state;
            }
        }

        private static boolean betterTieBreak(SearchState candidate, SearchState current) {
            return current == null
                || candidate.depth() < current.depth()
                || candidate.depth() == current.depth()
                    && candidate.expression().compareTo(current.expression()) < 0;
        }
    }

    /** Search terminal status; it is diagnostic telemetry, not proof evidence. */
    public enum GoalStatus {
        UNTARGETED,
        ROOT_ALREADY_TARGET,
        REACHED,
        STATE_BUDGET,
        DEPTH_BUDGET,
        CANDIDATE_BUDGET,
        NO_TRANSFORMATIONS,
        FRONTIER_EXHAUSTED,
        UNPARSEABLE_TARGET
    }

    public record GoalMetrics(
        int exploredStates,
        int expandedStates,
        int generatedTransformations,
        int enqueuedStates,
        int skippedTransformations,
        int duplicatePrunes,
        int transpositionPrunes,
        int depthPrunes,
        int candidateBudgetPrunes,
        int statesWithoutTransformations,
        int identityCacheHits,
        int identityCacheMisses,
        int cachedExpressions,
        int internedValues
    ) {
    }

    /** Deterministic target outcome alongside the ordinary explored-state list. */
    public record GoalSearchResult(
        List<SearchState> states,
        SearchState reachedState,
        SearchState bestState,
        int bestDistance,
        GoalStatus status,
        GoalMetrics metrics
    ) {
        public GoalSearchResult {
            states = List.copyOf(states);
        }

        public boolean reached() {
            return status == GoalStatus.REACHED || status == GoalStatus.ROOT_ALREADY_TARGET;
        }

        private static GoalSearchResult from(
            SearchProblem problem,
            SearchFrame frame,
            SearchState rootState
        ) {
            GoalProgress progress = frame.progress();
            GoalStatus status;
            if (!frame.target().enabled()) {
                status = GoalStatus.UNTARGETED;
            } else if (!frame.target().parseable()) {
                status = GoalStatus.UNPARSEABLE_TARGET;
            } else if (progress.reachedState != null) {
                status = progress.reachedState.depth() == 0
                    ? GoalStatus.ROOT_ALREADY_TARGET
                    : GoalStatus.REACHED;
            } else if (frame.explored().size() >= problem.heuristic().maxVisitedExpressions()) {
                status = GoalStatus.STATE_BUDGET;
            } else if (progress.candidateBudgetPrunes > 0) {
                status = GoalStatus.CANDIDATE_BUDGET;
            } else if (progress.depthPrunes > 0) {
                status = GoalStatus.DEPTH_BUDGET;
            } else if (progress.expandedStates > 0 && progress.generatedTransformations == 0) {
                status = GoalStatus.NO_TRANSFORMATIONS;
            } else {
                status = GoalStatus.FRONTIER_EXHAUSTED;
            }
            SearchState best = progress.bestState == null ? rootState : progress.bestState;
            int distance = frame.target().enabled() ? progress.bestDistance : -1;
            return new GoalSearchResult(
                frame.explored(),
                progress.reachedState,
                best,
                distance,
                status,
                new GoalMetrics(
                    frame.explored().size(),
                    progress.expandedStates,
                    progress.generatedTransformations,
                    progress.enqueuedStates,
                    progress.skippedTransformations,
                    progress.duplicatePrunes,
                    progress.transpositionPrunes,
                    progress.depthPrunes,
                    progress.candidateBudgetPrunes,
                    progress.statesWithoutTransformations,
                    frame.identity().cacheHits(),
                    frame.identity().cacheMisses(),
                    frame.identity().cachedExpressionCount(),
                    frame.identity().internedValueCount()
                )
            );
        }
    }

    /** Scoped target identity and structural multiset distance. */
    private static final class TargetSession {
        private static final int UNPARSEABLE_DISTANCE = 100_000;

        private final SearchTarget target;
        private final ValueIdentitySession identity;
        private final String normalizedTarget;
        private final ExprValue targetValue;
        private final Map<ValueKey, Integer> targetOccurrences;
        private final Map<String, Integer> distanceCache = new LinkedHashMap<>();

        private TargetSession(SearchTarget target, ValueIdentitySession identity) {
            this.target = target;
            this.identity = identity;
            normalizedTarget = target == null ? "" : normalize(target.targetExpression());
            targetValue = target == null
                ? null
                : identity.value(normalizedTarget).orElse(null);
            targetOccurrences = targetValue == null
                ? Map.of()
                : occurrenceMultiset(targetValue);
        }

        private boolean enabled() {
            return target != null;
        }

        private boolean parseable() {
            return !enabled()
                || target.relation() == TargetRelation.SYNTAX_EXACT
                || targetValue != null;
        }

        private boolean stopWhenReached() {
            return enabled() && target.stopWhenReached();
        }

        private boolean reached(String expression) {
            if (!enabled()) {
                return false;
            }
            if (target.relation() == TargetRelation.SYNTAX_EXACT) {
                return normalize(expression).equals(normalizedTarget);
            }
            return identity.value(expression)
                .map(value -> value.sameValue(targetValue))
                .orElse(false);
        }

        private int distance(String expression) {
            if (!enabled()) {
                return 0;
            }
            String normalized = normalize(expression);
            return distanceCache.computeIfAbsent(normalized, ignored -> computeDistance(normalized));
        }

        private int computeDistance(String expression) {
            if (target.relation() == TargetRelation.SYNTAX_EXACT
                    && expression.equals(normalizedTarget)) {
                return 0;
            }
            if (targetValue == null) {
                return UNPARSEABLE_DISTANCE;
            }
            return identity.value(expression)
                .map(value -> {
                    int semantic = semanticDistance(value, targetValue, targetOccurrences);
                    if (target.relation() != TargetRelation.SYNTAX_EXACT) {
                        return semantic;
                    }
                    return Math.min(UNPARSEABLE_DISTANCE - 1, semantic + 1);
                })
                .orElse(UNPARSEABLE_DISTANCE);
        }

        private int adjustedPriority(int basePriority, String expression) {
            if (!enabled() || target.distanceWeight() == 0) {
                return basePriority;
            }
            long adjusted = (long) basePriority
                + (long) target.distanceWeight() * distance(expression);
            return adjusted >= Integer.MAX_VALUE / 2
                ? Integer.MAX_VALUE / 2
                : (int) adjusted;
        }

        private static int semanticDistance(
            ExprValue candidate,
            ExprValue target,
            Map<ValueKey, Integer> targetOccurrences
        ) {
            if (candidate.sameValue(target)) {
                return 0;
            }
            Map<ValueKey, Integer> candidateOccurrences = occurrenceMultiset(candidate);
            Set<ValueKey> keys = new HashSet<>(candidateOccurrences.keySet());
            keys.addAll(targetOccurrences.keySet());
            long difference = 0;
            for (ValueKey key : keys) {
                difference += Math.abs(
                    candidateOccurrences.getOrDefault(key, 0)
                        - targetOccurrences.getOrDefault(key, 0));
            }
            if (!rootSignature(candidate).equals(rootSignature(target))) {
                difference += 2;
            }
            return difference >= UNPARSEABLE_DISTANCE
                ? UNPARSEABLE_DISTANCE - 1
                : (int) difference;
        }

        private static Map<ValueKey, Integer> occurrenceMultiset(ExprValue root) {
            Map<ValueKey, Integer> counts = new LinkedHashMap<>();
            collect(root, 1, counts);
            return Map.copyOf(counts);
        }

        private static void collect(ExprValue value, int multiplicity, Map<ValueKey, Integer> counts) {
            counts.merge(value.key(), multiplicity, Math::addExact);
            if (value instanceof OrderedValue ordered) {
                ordered.operands().forEach(operand -> collect(operand, multiplicity, counts));
            } else if (value instanceof AssociativeCommutativeValue ac) {
                ac.multiplicities().forEach((operand, count) ->
                    collect(operand, Math.multiplyExact(multiplicity, count), counts));
            }
        }

        private static String rootSignature(ExprValue value) {
            if (value instanceof OrderedValue ordered) {
                return "ordered:" + ordered.operator().id();
            }
            if (value instanceof AssociativeCommutativeValue ac) {
                return "ac:" + ac.operator().id();
            }
            return value.getClass().getSimpleName();
        }
    }

    /** One bounded owner for all mathematical values encountered by one search. */
    static final class ValueIdentitySession implements AutoCloseable {
        static final String HASH_PREFIX = "value-v1:";
        static final String LEGACY_FALLBACK_PREFIX = "legacy-v1:";

        private final ExpressionCanonicalizer canonicalizer;
        private final ExpressionParser parser = new ExpressionParser();
        private final ExprValueFactory factory = new ExprValueFactory();
        private final Map<String, String> valueHashesByExpression = new LinkedHashMap<>();
        private final Map<String, ExprValue> valuesByExpression = new LinkedHashMap<>();
        private final Set<String> unparseableExpressions = new HashSet<>();
        private final Map<String, String> legacyHashesByExpression = new LinkedHashMap<>();
        private int cacheHits;
        private int cacheMisses;

        ValueIdentitySession(ExpressionCanonicalizer canonicalizer) {
            this.canonicalizer = Objects.requireNonNull(canonicalizer, "canonicalizer");
        }

        String valueHash(String expression) {
            String normalized = normalize(expression);
            String existing = valueHashesByExpression.get(normalized);
            if (existing != null) {
                cacheHits++;
                return existing;
            }
            cacheMisses++;
            String hash = value(normalized)
                .map(value -> HASH_PREFIX + sha256(value.key().encoded()))
                .orElseGet(() -> LEGACY_FALLBACK_PREFIX + legacyHash(normalized));
            valueHashesByExpression.put(normalized, hash);
            return hash;
        }

        Optional<ExprValue> value(String expression) {
            String normalized = normalize(expression);
            ExprValue existing = valuesByExpression.get(normalized);
            if (existing != null) {
                return Optional.of(existing);
            }
            if (unparseableExpressions.contains(normalized)) {
                return Optional.empty();
            }
            try {
                Expr parsed = parser.parseTerm(normalized);
                Expr canonical = canonicalizer.canonicalize(parsed);
                ExprValue value = factory.fromExpr(canonical);
                valuesByExpression.put(normalized, value);
                return Optional.of(value);
            } catch (IllegalArgumentException exception) {
                unparseableExpressions.add(normalized);
                return Optional.empty();
            }
        }

        String legacyHash(String expression) {
            String normalized = normalize(expression);
            return legacyHashesByExpression.computeIfAbsent(normalized, canonicalizer::stableHash);
        }

        int cacheHits() {
            return cacheHits;
        }

        int cacheMisses() {
            return cacheMisses;
        }

        int cachedExpressionCount() {
            return valueHashesByExpression.size();
        }

        int internedValueCount() {
            return factory.size();
        }

        @Override
        public void close() {
            valueHashesByExpression.clear();
            valuesByExpression.clear();
            unparseableExpressions.clear();
            legacyHashesByExpression.clear();
            factory.close();
        }

        private static String sha256(String value) {
            try {
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
            } catch (NoSuchAlgorithmException exception) {
                throw new IllegalStateException("SHA-256 unavailable", exception);
            }
        }
    }
}
