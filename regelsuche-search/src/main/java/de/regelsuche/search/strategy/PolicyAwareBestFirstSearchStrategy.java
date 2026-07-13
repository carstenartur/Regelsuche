package de.regelsuche.search.strategy;

import de.regelsuche.search.learning.TransformationDescriptor;
import de.regelsuche.search.policy.SearchPolicy;
import de.regelsuche.search.policy.SearchPolicy.PolicyContext;
import de.regelsuche.search.policy.SearchPolicy.PolicyDecision;
import de.regelsuche.search.telemetry.CompositeSearchObserver;
import de.regelsuche.search.telemetry.SearchEvent;
import de.regelsuche.search.telemetry.SearchEventType;
import de.regelsuche.search.telemetry.SearchObserver;
import de.regelsuche.transform.Transformation;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.ToIntFunction;

/**
 * Applies an explainable policy after rule applicability and before the ordinary
 * per-state candidate budget. The policy may reorder candidates, while
 * {@link BestFirstSearchStrategy} remains the sole owner of guards, duplicate
 * rejection and successful-enqueue budget accounting.
 */
public final class PolicyAwareBestFirstSearchStrategy implements SearchStrategy {
    public static final int DEFAULT_MAX_FRONTIER_ADJUSTMENT = 1_000;
    private static final int PRIORITY_CEILING = Integer.MAX_VALUE / 2;
    private static final int PRIORITY_FLOOR = Integer.MIN_VALUE / 2;
    private static final long EVIDENCE_LIMIT = Integer.MAX_VALUE;

    private final SearchPolicy policy;
    private final int maxFrontierAdjustment;

    public PolicyAwareBestFirstSearchStrategy(SearchPolicy policy) {
        this(policy, 0);
    }

    /**
     * Enables a bounded policy contribution to successor frontier priority.
     * A value of zero preserves the candidate-order-only behavior.
     */
    public PolicyAwareBestFirstSearchStrategy(
        SearchPolicy policy,
        int maxFrontierAdjustment
    ) {
        this.policy = Objects.requireNonNull(policy, "policy");
        if (maxFrontierAdjustment < 0) {
            throw new IllegalArgumentException("maxFrontierAdjustment must not be negative");
        }
        this.maxFrontierAdjustment = maxFrontierAdjustment;
    }

    @Override
    public List<SearchState> search(SearchProblem problem) {
        PolicyTrace trace = maxFrontierAdjustment == 0 ? null : new PolicyTrace();
        return execute(problem, trace).states();
    }

    public PolicySearchResult searchWithDiagnostics(SearchProblem problem) {
        PolicyTrace trace = new PolicyTrace();
        BestFirstSearchStrategy.GoalSearchResult result = execute(problem, trace);
        return new PolicySearchResult(result, trace.events());
    }

    private BestFirstSearchStrategy.GoalSearchResult execute(
        SearchProblem problem,
        PolicyTrace trace
    ) {
        Objects.requireNonNull(problem, "problem");
        SearchObserver observer = trace == null
            ? problem.observer()
            : CompositeSearchObserver.of(trace, problem.observer());
        SearchProblem rankedProblem = new SearchProblem(
            problem.rootExpression(),
            problem.engine(),
            problem.scorer(),
            problem.canonicalizer(),
            problem.heuristic(),
            problem.memory(),
            problem.costModel(),
            observer,
            problem.target());
        try (TransformationDescriptor.Factory descriptorFactory =
                new TransformationDescriptor.Factory(problem.target(), problem.canonicalizer())) {
            return new PolicyBestFirstSearchStrategy(
                policy, trace, descriptorFactory, maxFrontierAdjustment)
                .searchWithDiagnostics(rankedProblem);
        }
    }

    public record PolicySearchResult(
        BestFirstSearchStrategy.GoalSearchResult search,
        List<RankingEvent> policyEvents
    ) {
        public PolicySearchResult {
            policyEvents = List.copyOf(policyEvents);
        }

        public boolean reached() {
            return search.reached();
        }
    }

    /** One deterministic, explainable candidate decision and its real search outcome. */
    public record RankingEvent(
        long decisionGroup,
        String parentExpression,
        String ruleId,
        String transformedExpression,
        String policyId,
        int priority,
        int confidencePermille,
        boolean fallback,
        Map<String, Integer> contributions,
        String explanation,
        int deterministicRank,
        int frontierAdjustment,
        int staticStatePriority,
        int targetPriorityContribution,
        int composedFrontierPriority,
        boolean consideredBySearch,
        boolean admittedToFrontier,
        String admissionOutcome,
        int dequeueOrder
    ) {
        public RankingEvent {
            parentExpression = parentExpression == null ? "" : parentExpression;
            ruleId = ruleId == null ? "" : ruleId;
            transformedExpression = transformedExpression == null ? "" : transformedExpression;
            policyId = policyId == null ? "" : policyId;
            contributions = contributions == null ? Map.of() : Map.copyOf(contributions);
            explanation = explanation == null ? "" : explanation;
            admissionOutcome = admissionOutcome == null ? "not-considered" : admissionOutcome;
        }
    }

    private static final class PolicyBestFirstSearchStrategy extends BestFirstSearchStrategy {
        private final SearchPolicy policy;
        private final PolicyTrace trace;
        private final TransformationDescriptor.Factory descriptorFactory;
        private final int maxFrontierAdjustment;

        private PolicyBestFirstSearchStrategy(
            SearchPolicy policy,
            PolicyTrace trace,
            TransformationDescriptor.Factory descriptorFactory,
            int maxFrontierAdjustment
        ) {
            this.policy = policy;
            this.trace = trace;
            this.descriptorFactory = descriptorFactory;
            this.maxFrontierAdjustment = maxFrontierAdjustment;
        }

        @Override
        protected List<Transformation> orderTransformations(
            SearchProblem problem,
            SearchState current,
            List<Transformation> transformations,
            boolean targetEnabled,
            ToIntFunction<Transformation> targetDistance
        ) {
            Comparator<RankedTransformation> deterministic = Comparator
                .comparing((RankedTransformation ranked) -> ranked.transformation().rule())
                .thenComparing(ranked -> ranked.transformation().transformedExpression())
                .thenComparing(ranked -> ranked.transformation().applicationKey());
            List<RankedTransformation> ranked = new ArrayList<>();
            for (Transformation transformation : transformations) {
                int distance = targetDistance.applyAsInt(transformation);
                PolicyDecision decision = policy.score(
                    new PolicyContext(
                        current.expression(), distance, targetEnabled,
                        problem.canonicalizer(), descriptor(current, transformation)),
                    transformation);
                ranked.add(new RankedTransformation(transformation, decision, distance));
            }

            boolean completeFallback = !ranked.isEmpty()
                && ranked.stream().allMatch(item -> item.decision().fallback());
            if (completeFallback && targetEnabled) {
                ranked.sort(Comparator
                    .comparingInt(RankedTransformation::targetDistance)
                    .thenComparing(deterministic));
            } else if (completeFallback) {
                ranked.sort(deterministic);
            } else {
                ranked.sort(Comparator
                    .comparingInt((RankedTransformation item) -> item.decision().priority())
                    .thenComparing(deterministic));
            }

            if (trace != null) {
                int targetWeight = targetEnabled ? problem.target().distanceWeight() : 0;
                trace.startGroup(
                    current, ranked, maxFrontierAdjustment, targetWeight);
            }
            return ranked.stream().map(RankedTransformation::transformation).toList();
        }

        @Override
        protected int priority(SearchState state, SearchProblem problem) {
            int base = super.priority(state, problem);
            if (trace == null || maxFrontierAdjustment == 0) {
                return base;
            }
            int adjusted = safeAdd(base, trace.frontierAdjustment(state));
            trace.recordPriority(state, base, adjusted);
            return adjusted;
        }

        private TransformationDescriptor descriptor(
            SearchState current,
            Transformation transformation
        ) {
            SearchEvent descriptorEvent = new SearchEvent(
                -1,
                SearchEventType.TRANSFORMATION_GENERATED,
                transformation.transformedExpression(),
                "",
                current.depth() + 1,
                0,
                current.canonicalHash(),
                current.expression(),
                transformation.rule(),
                transformation.kind(),
                transformation.mayIncreaseComplexity(),
                transformation.estimatedCostDelta(),
                transformation.equivalencePreservingByConstruction(),
                transformation.assumptions(),
                0,
                0,
                0,
                "");
            return descriptorFactory.from(descriptorEvent);
        }
    }

    private static final class PolicyTrace implements SearchObserver {
        private final List<MutableRankingEvent> events = new ArrayList<>();
        private final Map<TransitionKey, MutableRankingEvent> byTransition =
            new LinkedHashMap<>();
        private long nextDecisionGroup;
        private int nextDequeueOrder;
        private RankingGroup activeGroup;

        private void startGroup(
            SearchState parent,
            List<RankedTransformation> ranked,
            int adjustmentLimit,
            int targetWeight
        ) {
            finishActive("not-considered-before-next-expansion");
            long group = nextDecisionGroup++;
            if (ranked.isEmpty()) {
                return;
            }
            List<MutableRankingEvent> groupEvents = new ArrayList<>();
            for (int rank = 0; rank < ranked.size(); rank++) {
                RankedTransformation item = ranked.get(rank);
                MutableRankingEvent event = new MutableRankingEvent(
                    group,
                    parent,
                    item,
                    rank,
                    frontierAdjustment(item.decision(), adjustmentLimit),
                    safeProduct(item.targetDistance(), targetWeight));
                groupEvents.add(event);
                events.add(event);
                byTransition.putIfAbsent(event.key, event);
            }
            activeGroup = new RankingGroup(groupEvents);
        }

        private int frontierAdjustment(SearchState state) {
            MutableRankingEvent event = byTransition.get(TransitionKey.from(state));
            return event == null ? 0 : event.frontierAdjustment;
        }

        private void recordPriority(SearchState state, int base, int adjusted) {
            MutableRankingEvent event = byTransition.get(TransitionKey.from(state));
            if (event != null) {
                event.staticStatePriority = base;
                event.composedFrontierPriority = safeAdd(
                    adjusted, event.targetPriorityContribution);
            }
        }

        @Override
        public void onEvent(SearchEvent event) {
            switch (event.type()) {
                case TRANSFORMATION_GENERATED -> transformationConsidered(event);
                case STATE_ENQUEUED -> completePending(event, true, "enqueued");
                case STATE_PRUNED_DUPLICATE -> completePending(event, false, "duplicate-pruned");
                case STATE_PRUNED_BUDGET -> finishActive("candidate-budget-not-considered");
                case STATE_DEQUEUED -> recordDequeue(event);
                case SEARCH_FINISHED -> finishActive("search-finished-not-considered");
                default -> {
                    // Ranking admission is decided only by the events above.
                }
            }
        }

        private void transformationConsidered(SearchEvent searchEvent) {
            if (activeGroup == null) {
                return;
            }
            MutableRankingEvent ranking = activeGroup.next();
            if (ranking == null) {
                return;
            }
            ranking.consideredBySearch = true;
            if (!ranking.matches(searchEvent)) {
                ranking.admissionOutcome = "telemetry-mismatch";
                closeIfComplete();
                return;
            }
            if (!searchEvent.pruningReason().isBlank()) {
                ranking.admissionOutcome = "skipped:" + searchEvent.pruningReason();
                closeIfComplete();
                return;
            }
            ranking.admissionOutcome = "generated";
            activeGroup.pending = ranking;
        }

        private void completePending(SearchEvent searchEvent, boolean admitted, String outcome) {
            if (activeGroup == null || activeGroup.pending == null) {
                return;
            }
            MutableRankingEvent pending = activeGroup.pending;
            if (!pending.matches(searchEvent)) {
                pending.admissionOutcome = "telemetry-mismatch";
                activeGroup.pending = null;
                closeIfComplete();
                return;
            }
            pending.admittedToFrontier = admitted;
            pending.admissionOutcome = outcome;
            activeGroup.pending = null;
            closeIfComplete();
        }

        private void recordDequeue(SearchEvent searchEvent) {
            MutableRankingEvent event = byTransition.get(TransitionKey.from(searchEvent));
            if (event != null && event.admittedToFrontier && event.dequeueOrder < 0) {
                event.dequeueOrder = nextDequeueOrder++;
            }
        }

        private void closeIfComplete() {
            if (activeGroup != null && activeGroup.complete()) {
                activeGroup = null;
            }
        }

        private void finishActive(String outcome) {
            if (activeGroup == null) {
                return;
            }
            if (activeGroup.pending != null) {
                activeGroup.pending.admissionOutcome = "generated-without-admission";
                activeGroup.pending = null;
            }
            activeGroup.finishRemaining(outcome);
            activeGroup = null;
        }

        private List<RankingEvent> events() {
            finishActive("search-finished-not-considered");
            return events.stream().map(MutableRankingEvent::freeze).toList();
        }
    }

    private static final class RankingGroup {
        private final List<MutableRankingEvent> events;
        private int cursor;
        private MutableRankingEvent pending;

        private RankingGroup(List<MutableRankingEvent> events) {
            this.events = events;
        }

        private MutableRankingEvent next() {
            if (cursor >= events.size() || pending != null) {
                return null;
            }
            return events.get(cursor++);
        }

        private boolean complete() {
            return cursor >= events.size() && pending == null;
        }

        private void finishRemaining(String outcome) {
            while (cursor < events.size()) {
                events.get(cursor++).admissionOutcome = outcome;
            }
        }
    }

    private static final class MutableRankingEvent {
        private final long decisionGroup;
        private final String parentExpression;
        private final Transformation transformation;
        private final PolicyDecision decision;
        private final int deterministicRank;
        private final TransitionKey key;
        private final int frontierAdjustment;
        private final int targetPriorityContribution;
        private int staticStatePriority;
        private int composedFrontierPriority;
        private boolean consideredBySearch;
        private boolean admittedToFrontier;
        private String admissionOutcome = "not-considered";
        private int dequeueOrder = -1;

        private MutableRankingEvent(
            long decisionGroup,
            SearchState parent,
            RankedTransformation ranked,
            int deterministicRank,
            int frontierAdjustment,
            int targetPriorityContribution
        ) {
            this.decisionGroup = decisionGroup;
            this.parentExpression = parent.expression();
            this.transformation = ranked.transformation();
            this.decision = ranked.decision();
            this.deterministicRank = deterministicRank;
            this.key = TransitionKey.from(parent, transformation);
            this.frontierAdjustment = frontierAdjustment;
            this.targetPriorityContribution = targetPriorityContribution;
        }

        private boolean matches(SearchEvent event) {
            return parentExpression.equals(event.parentExpression())
                && transformation.rule().equals(event.ruleId())
                && transformation.transformedExpression().equals(event.expression());
        }

        private RankingEvent freeze() {
            return new RankingEvent(
                decisionGroup,
                parentExpression,
                transformation.rule(),
                transformation.transformedExpression(),
                decision.policyId(),
                decision.priority(),
                decision.confidencePermille(),
                decision.fallback(),
                decision.contributions(),
                decision.explanation(),
                deterministicRank,
                frontierAdjustment,
                staticStatePriority,
                targetPriorityContribution,
                composedFrontierPriority,
                consideredBySearch,
                admittedToFrontier,
                admissionOutcome,
                dequeueOrder);
        }
    }

    private record RankedTransformation(
        Transformation transformation,
        PolicyDecision decision,
        int targetDistance
    ) {
    }

    private record TransitionKey(
        String parentExpression,
        String ruleId,
        String childExpression,
        int childDepth
    ) {
        private TransitionKey {
            parentExpression = parentExpression == null ? "" : parentExpression;
            ruleId = ruleId == null ? "" : ruleId;
            childExpression = childExpression == null ? "" : childExpression;
        }

        private static TransitionKey from(SearchState parent, Transformation transformation) {
            return new TransitionKey(
                parent.expression(), transformation.rule(),
                transformation.transformedExpression(), parent.depth() + 1);
        }

        private static TransitionKey from(SearchState state) {
            return new TransitionKey(
                state.parentExpression(), state.appliedRuleId(),
                state.expression(), state.depth());
        }

        private static TransitionKey from(SearchEvent event) {
            return new TransitionKey(
                event.parentExpression(), event.ruleId(),
                event.expression(), event.depth());
        }
    }

    private static int frontierAdjustment(PolicyDecision decision, int limit) {
        if (limit == 0 || decision.fallback() || decision.confidencePermille() == 0) {
            return 0;
        }
        long evidence = 0;
        for (Map.Entry<String, Integer> contribution : decision.contributions().entrySet()) {
            String name = contribution.getKey();
            if (!"targetDistance".equals(name) && !name.startsWith("unknown")) {
                evidence = clamp(
                    evidence + contribution.getValue(), -EVIDENCE_LIMIT, EVIDENCE_LIMIT);
            }
        }
        return (int) clamp(
            evidence * decision.confidencePermille() / 1_000L, -limit, limit);
    }

    private static int safeProduct(int left, int right) {
        return (int) clamp((long) left * right, PRIORITY_FLOOR, PRIORITY_CEILING);
    }

    private static int safeAdd(int left, int right) {
        return (int) clamp((long) left + right, PRIORITY_FLOOR, PRIORITY_CEILING);
    }

    private static long clamp(long value, long minimum, long maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }
}