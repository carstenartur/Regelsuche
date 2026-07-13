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
 * Lets an explainable policy influence both candidate order and the ordinary
 * BestFirst frontier priority without introducing another candidate budget.
 *
 * <p>The underlying {@link BestFirstSearchStrategy} remains the sole owner of
 * guards, duplicate rejection and successful-enqueue accounting. A fallback
 * decision contributes exactly zero to frontier priority. Target distance is
 * still added independently by BestFirst after this strategy's bounded state
 * adjustment.</p>
 */
public final class PolicyFrontierBestFirstSearchStrategy implements SearchStrategy {
    public static final int DEFAULT_MAX_ABSOLUTE_ADJUSTMENT = 1_000;
    private static final int PRIORITY_CEILING = Integer.MAX_VALUE / 2;
    private static final int PRIORITY_FLOOR = Integer.MIN_VALUE / 2;
    private static final long MAX_SAFE_EVIDENCE_SUM = Long.MAX_VALUE / 1_000L;

    private final SearchPolicy policy;
    private final int maxAbsoluteAdjustment;

    public PolicyFrontierBestFirstSearchStrategy(SearchPolicy policy) {
        this(policy, DEFAULT_MAX_ABSOLUTE_ADJUSTMENT);
    }

    public PolicyFrontierBestFirstSearchStrategy(
        SearchPolicy policy,
        int maxAbsoluteAdjustment
    ) {
        this.policy = Objects.requireNonNull(policy, "policy");
        if (maxAbsoluteAdjustment < 1) {
            throw new IllegalArgumentException("maxAbsoluteAdjustment must be positive");
        }
        this.maxAbsoluteAdjustment = maxAbsoluteAdjustment;
    }

    @Override
    public List<SearchState> search(SearchProblem problem) {
        return execute(problem).search().states();
    }

    public FrontierPolicySearchResult searchWithDiagnostics(SearchProblem problem) {
        return execute(problem);
    }

    private FrontierPolicySearchResult execute(SearchProblem problem) {
        Objects.requireNonNull(problem, "problem");
        FrontierTrace trace = new FrontierTrace();
        SearchProblem rankedProblem = new SearchProblem(
            problem.rootExpression(),
            problem.engine(),
            problem.scorer(),
            problem.canonicalizer(),
            problem.heuristic(),
            problem.memory(),
            problem.costModel(),
            CompositeSearchObserver.of(trace, problem.observer()),
            problem.target());
        try (TransformationDescriptor.Factory descriptorFactory =
                new TransformationDescriptor.Factory(
                    problem.target(), problem.canonicalizer())) {
            BestFirstSearchStrategy.GoalSearchResult result =
                new FrontierBestFirstSearchStrategy(
                    policy,
                    maxAbsoluteAdjustment,
                    descriptorFactory,
                    trace)
                    .searchWithDiagnostics(rankedProblem);
            return new FrontierPolicySearchResult(result, trace.events());
        }
    }

    public record FrontierPolicySearchResult(
        BestFirstSearchStrategy.GoalSearchResult search,
        List<FrontierPriorityEvent> policyEvents
    ) {
        public FrontierPolicySearchResult {
            policyEvents = List.copyOf(policyEvents);
        }

        public boolean reached() {
            return search.reached();
        }
    }

    /** One deterministic policy decision and its composed frontier outcome. */
    public record FrontierPriorityEvent(
        long decisionGroup,
        String parentExpression,
        String ruleId,
        String transformedExpression,
        String policyId,
        int candidatePolicyPriority,
        int confidencePermille,
        boolean fallback,
        Map<String, Integer> contributions,
        String explanation,
        int candidateRank,
        int frontierAdjustment,
        int staticStatePriority,
        int targetPriorityContribution,
        int composedFrontierPriority,
        boolean consideredBySearch,
        boolean admittedToFrontier,
        String admissionOutcome,
        int dequeueOrder
    ) {
        public FrontierPriorityEvent {
            parentExpression = parentExpression == null ? "" : parentExpression;
            ruleId = ruleId == null ? "" : ruleId;
            transformedExpression = transformedExpression == null ? "" : transformedExpression;
            policyId = policyId == null ? "" : policyId;
            contributions = contributions == null ? Map.of() : Map.copyOf(contributions);
            explanation = explanation == null ? "" : explanation;
            admissionOutcome = admissionOutcome == null ? "not-considered" : admissionOutcome;
        }
    }

    private static final class FrontierBestFirstSearchStrategy extends BestFirstSearchStrategy {
        private final SearchPolicy policy;
        private final int maxAbsoluteAdjustment;
        private final TransformationDescriptor.Factory descriptorFactory;
        private final FrontierTrace trace;

        private FrontierBestFirstSearchStrategy(
            SearchPolicy policy,
            int maxAbsoluteAdjustment,
            TransformationDescriptor.Factory descriptorFactory,
            FrontierTrace trace
        ) {
            this.policy = policy;
            this.maxAbsoluteAdjustment = maxAbsoluteAdjustment;
            this.descriptorFactory = descriptorFactory;
            this.trace = trace;
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
            List<RankedTransformation> ranked = new ArrayList<>(transformations.stream()
                .map(transformation -> ranked(
                    problem,
                    current,
                    transformation,
                    targetEnabled,
                    targetDistance.applyAsInt(transformation)))
                .toList());

            boolean completeFallback = !ranked.isEmpty()
                && ranked.stream().allMatch(item -> item.decision().fallback());
            if (completeFallback) {
                if (targetEnabled) {
                    ranked.sort(Comparator
                        .comparingInt(RankedTransformation::targetDistance)
                        .thenComparing(deterministic));
                } else {
                    ranked.sort(deterministic);
                }
            } else {
                ranked.sort(Comparator
                    .comparingInt((RankedTransformation item) -> item.decision().priority())
                    .thenComparing(deterministic));
            }

            trace.startGroup(
                current,
                ranked,
                maxAbsoluteAdjustment,
                targetEnabled ? problem.target().distanceWeight() : 0);
            return ranked.stream().map(RankedTransformation::transformation).toList();
        }

        @Override
        protected int priority(SearchState state, SearchProblem problem) {
            int staticPriority = super.priority(state, problem);
            int adjustment = trace.adjustmentFor(state);
            int adjusted = saturatedPriority(staticPriority, adjustment);
            trace.recordPriority(state, staticPriority, adjusted);
            return adjusted;
        }

        private RankedTransformation ranked(
            SearchProblem problem,
            SearchState current,
            Transformation transformation,
            boolean targetEnabled,
            int targetDistance
        ) {
            TransformationDescriptor descriptor = descriptor(current, transformation);
            PolicyDecision decision = policy.score(
                new PolicyContext(
                    current.expression(),
                    targetDistance,
                    targetEnabled,
                    problem.canonicalizer(),
                    descriptor),
                transformation);
            return new RankedTransformation(
                transformation,
                decision,
                descriptor,
                targetDistance);
        }

        private TransformationDescriptor descriptor(
            SearchState current,
            Transformation transformation
        ) {
            return descriptorFactory.from(new SearchEvent(
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
                ""));
        }
    }

    private static final class FrontierTrace implements SearchObserver {
        private final List<MutableFrontierEvent> events = new ArrayList<>();
        private final Map<TransitionKey, Integer> adjustments = new LinkedHashMap<>();
        private long nextDecisionGroup;
        private int nextDequeueOrder;

        private void startGroup(
            SearchState parent,
            List<RankedTransformation> ranked,
            int maxAbsoluteAdjustment,
            int targetDistanceWeight
        ) {
            long group = nextDecisionGroup++;
            for (int rank = 0; rank < ranked.size(); rank++) {
                RankedTransformation item = ranked.get(rank);
                int adjustment = boundedAdjustment(
                    item.decision(), maxAbsoluteAdjustment);
                int targetContribution = saturatedProduct(
                    item.targetDistance(), targetDistanceWeight);
                MutableFrontierEvent event = new MutableFrontierEvent(
                    group,
                    parent,
                    item,
                    rank,
                    adjustment,
                    targetContribution);
                events.add(event);
                adjustments.putIfAbsent(event.key, adjustment);
            }
        }

        private int adjustmentFor(SearchState state) {
            if (state.parentExpression() == null || state.appliedRuleId() == null) {
                return 0;
            }
            return adjustments.getOrDefault(TransitionKey.from(state), 0);
        }

        private void recordPriority(
            SearchState state,
            int staticPriority,
            int adjustedPriority
        ) {
            if (state.parentExpression() == null || state.appliedRuleId() == null) {
                return;
            }
            TransitionKey key = TransitionKey.from(state);
            for (MutableFrontierEvent event : events) {
                if (event.key.equals(key)) {
                    event.staticStatePriority = staticPriority;
                    event.composedFrontierPriority = saturatedPriority(
                        adjustedPriority,
                        event.targetPriorityContribution);
                }
            }
        }

        @Override
        public void onEvent(SearchEvent event) {
            switch (event.type()) {
                case TRANSFORMATION_GENERATED -> transformationConsidered(event);
                case STATE_ENQUEUED -> admission(event, true, "enqueued");
                case STATE_PRUNED_DUPLICATE -> admission(event, false, "duplicate-pruned");
                case STATE_DEQUEUED -> dequeued(event);
                default -> {
                    // Other telemetry does not change a recorded transition outcome.
                }
            }
        }

        private void transformationConsidered(SearchEvent searchEvent) {
            MutableFrontierEvent event = firstMatching(
                TransitionKey.from(searchEvent), candidate -> !candidate.consideredBySearch);
            if (event == null) {
                return;
            }
            event.consideredBySearch = true;
            event.admissionOutcome = searchEvent.pruningReason().isBlank()
                ? "generated"
                : "skipped:" + searchEvent.pruningReason();
        }

        private void admission(
            SearchEvent searchEvent,
            boolean admitted,
            String outcome
        ) {
            MutableFrontierEvent event = firstMatching(
                TransitionKey.from(searchEvent),
                candidate -> candidate.consideredBySearch
                    && !candidate.admissionResolved
                    && candidate.admissionOutcome.equals("generated"));
            if (event == null) {
                return;
            }
            event.admissionResolved = true;
            event.admittedToFrontier = admitted;
            event.admissionOutcome = outcome;
        }

        private void dequeued(SearchEvent searchEvent) {
            MutableFrontierEvent event = firstMatching(
                TransitionKey.from(searchEvent),
                candidate -> candidate.admittedToFrontier
                    && candidate.dequeueOrder < 0);
            if (event != null) {
                event.dequeueOrder = nextDequeueOrder++;
            }
        }

        private MutableFrontierEvent firstMatching(
            TransitionKey key,
            java.util.function.Predicate<MutableFrontierEvent> predicate
        ) {
            for (MutableFrontierEvent event : events) {
                if (event.key.equals(key) && predicate.test(event)) {
                    return event;
                }
            }
            return null;
        }

        private List<FrontierPriorityEvent> events() {
            return events.stream().map(MutableFrontierEvent::freeze).toList();
        }
    }

    private static final class MutableFrontierEvent {
        private final long decisionGroup;
        private final TransitionKey key;
        private final PolicyDecision decision;
        private final int candidateRank;
        private final int frontierAdjustment;
        private final int targetPriorityContribution;
        private int staticStatePriority;
        private int composedFrontierPriority;
        private boolean consideredBySearch;
        private boolean admissionResolved;
        private boolean admittedToFrontier;
        private String admissionOutcome = "not-considered";
        private int dequeueOrder = -1;

        private MutableFrontierEvent(
            long decisionGroup,
            SearchState parent,
            RankedTransformation ranked,
            int candidateRank,
            int frontierAdjustment,
            int targetPriorityContribution
        ) {
            this.decisionGroup = decisionGroup;
            this.key = TransitionKey.from(parent, ranked.transformation());
            this.decision = ranked.decision();
            this.candidateRank = candidateRank;
            this.frontierAdjustment = frontierAdjustment;
            this.targetPriorityContribution = targetPriorityContribution;
        }

        private FrontierPriorityEvent freeze() {
            return new FrontierPriorityEvent(
                decisionGroup,
                key.parentExpression(),
                key.ruleId(),
                key.childExpression(),
                decision.policyId(),
                decision.priority(),
                decision.confidencePermille(),
                decision.fallback(),
                decision.contributions(),
                decision.explanation(),
                candidateRank,
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
        TransformationDescriptor descriptor,
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

        private static TransitionKey from(
            SearchState parent,
            Transformation transformation
        ) {
            return new TransitionKey(
                parent.expression(),
                transformation.rule(),
                transformation.transformedExpression(),
                parent.depth() + 1);
        }

        private static TransitionKey from(SearchState state) {
            return new TransitionKey(
                state.parentExpression(),
                state.appliedRuleId(),
                state.expression(),
                state.depth());
        }

        private static TransitionKey from(SearchEvent event) {
            return new TransitionKey(
                event.parentExpression(),
                event.ruleId(),
                event.expression(),
                event.depth());
        }
    }

    private static int boundedAdjustment(
        PolicyDecision decision,
        int maxAbsoluteAdjustment
    ) {
        if (decision.fallback() || decision.confidencePermille() == 0) {
            return 0;
        }
        long evidence = 0;
        for (Map.Entry<String, Integer> contribution : decision.contributions().entrySet()) {
            if ("targetDistance".equals(contribution.getKey())
                    || contribution.getKey().startsWith("unknown")) {
                continue;
            }
            evidence = saturatedEvidenceAdd(evidence, contribution.getValue());
        }
        long scaled = evidence * decision.confidencePermille() / 1_000L;
        return clamp(scaled, -maxAbsoluteAdjustment, maxAbsoluteAdjustment);
    }

    private static long saturatedEvidenceAdd(long left, int right) {
        if (right > 0 && left > MAX_SAFE_EVIDENCE_SUM - right) {
            return MAX_SAFE_EVIDENCE_SUM;
        }
        if (right < 0 && left < -MAX_SAFE_EVIDENCE_SUM - right) {
            return -MAX_SAFE_EVIDENCE_SUM;
        }
        return left + right;
    }

    private static int saturatedProduct(int left, int right) {
        return clamp((long) left * right, PRIORITY_FLOOR, PRIORITY_CEILING);
    }

    private static int saturatedPriority(int base, int adjustment) {
        return clamp((long) base + adjustment, PRIORITY_FLOOR, PRIORITY_CEILING);
    }

    private static int clamp(long value, int minimum, int maximum) {
        return (int) Math.max(minimum, Math.min(maximum, value));
    }
}