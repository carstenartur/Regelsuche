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
 * Applies an explainable policy to candidate order and to ordinary BestFirst
 * frontier priority without introducing another candidate budget.
 */
public final class PolicyFrontierBestFirstSearchStrategy implements SearchStrategy {
    public static final int DEFAULT_MAX_ABSOLUTE_ADJUSTMENT = 1_000;
    private static final int PRIORITY_CEILING = Integer.MAX_VALUE / 2;
    private static final int PRIORITY_FLOOR = Integer.MIN_VALUE / 2;
    private static final long EVIDENCE_LIMIT = Integer.MAX_VALUE;

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
        SearchProblem ranked = new SearchProblem(
            problem.rootExpression(), problem.engine(), problem.scorer(),
            problem.canonicalizer(), problem.heuristic(), problem.memory(),
            problem.costModel(), CompositeSearchObserver.of(trace, problem.observer()),
            problem.target());
        try (TransformationDescriptor.Factory descriptors =
                new TransformationDescriptor.Factory(problem.target(), problem.canonicalizer())) {
            var result = new FrontierStrategy(
                policy, maxAbsoluteAdjustment, descriptors, trace)
                .searchWithDiagnostics(ranked);
            return new FrontierPolicySearchResult(result, trace.freeze());
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

    /** One candidate decision with admission and frontier-priority evidence. */
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
            contributions = contributions == null ? Map.of() : Map.copyOf(contributions);
            explanation = explanation == null ? "" : explanation;
            admissionOutcome = admissionOutcome == null ? "not-considered" : admissionOutcome;
        }
    }

    private static final class FrontierStrategy extends BestFirstSearchStrategy {
        private final SearchPolicy policy;
        private final int adjustmentLimit;
        private final TransformationDescriptor.Factory descriptors;
        private final FrontierTrace trace;

        private FrontierStrategy(
            SearchPolicy policy,
            int adjustmentLimit,
            TransformationDescriptor.Factory descriptors,
            FrontierTrace trace
        ) {
            this.policy = policy;
            this.adjustmentLimit = adjustmentLimit;
            this.descriptors = descriptors;
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
            Comparator<Candidate> deterministic = Comparator
                .comparing((Candidate item) -> item.transformation().rule())
                .thenComparing(item -> item.transformation().transformedExpression())
                .thenComparing(item -> item.transformation().applicationKey());
            List<Candidate> ranked = new ArrayList<>();
            for (Transformation transformation : transformations) {
                int distance = targetDistance.applyAsInt(transformation);
                PolicyDecision decision = policy.score(
                    new PolicyContext(
                        current.expression(), distance, targetEnabled,
                        problem.canonicalizer(), descriptor(current, transformation)),
                    transformation);
                ranked.add(new Candidate(transformation, decision, distance));
            }
            boolean fallback = !ranked.isEmpty()
                && ranked.stream().allMatch(item -> item.decision().fallback());
            if (fallback && targetEnabled) {
                ranked.sort(Comparator.comparingInt(Candidate::targetDistance)
                    .thenComparing(deterministic));
            } else if (fallback) {
                ranked.sort(deterministic);
            } else {
                ranked.sort(Comparator
                    .comparingInt((Candidate item) -> item.decision().priority())
                    .thenComparing(deterministic));
            }
            int targetWeight = targetEnabled ? problem.target().distanceWeight() : 0;
            trace.add(current, ranked, adjustmentLimit, targetWeight);
            return ranked.stream().map(Candidate::transformation).toList();
        }

        @Override
        protected int priority(SearchState state, SearchProblem problem) {
            int base = super.priority(state, problem);
            int adjusted = safeAdd(base, trace.adjustment(state));
            trace.priority(state, base, adjusted);
            return adjusted;
        }

        private TransformationDescriptor descriptor(
            SearchState current,
            Transformation transformation
        ) {
            return descriptors.from(new SearchEvent(
                -1, SearchEventType.TRANSFORMATION_GENERATED,
                transformation.transformedExpression(), "", current.depth() + 1, 0,
                current.canonicalHash(), current.expression(), transformation.rule(),
                transformation.kind(), transformation.mayIncreaseComplexity(),
                transformation.estimatedCostDelta(),
                transformation.equivalencePreservingByConstruction(),
                transformation.assumptions(), 0, 0, 0, ""));
        }
    }

    private static final class FrontierTrace implements SearchObserver {
        private final List<Entry> entries = new ArrayList<>();
        private final Map<Key, Entry> byTransition = new LinkedHashMap<>();
        private long nextGroup;
        private int nextDequeue;

        private void add(
            SearchState parent,
            List<Candidate> candidates,
            int adjustmentLimit,
            int targetWeight
        ) {
            long group = nextGroup++;
            for (int rank = 0; rank < candidates.size(); rank++) {
                Candidate candidate = candidates.get(rank);
                Entry entry = new Entry(
                    group, Key.of(parent, candidate.transformation()), candidate.decision(), rank,
                    adjustment(candidate.decision(), adjustmentLimit),
                    safeProduct(candidate.targetDistance(), targetWeight));
                entries.add(entry);
                byTransition.putIfAbsent(entry.key, entry);
            }
        }

        private int adjustment(SearchState state) {
            Entry entry = byTransition.get(Key.of(state));
            return entry == null ? 0 : entry.frontierAdjustment;
        }

        private void priority(SearchState state, int base, int adjusted) {
            Entry entry = byTransition.get(Key.of(state));
            if (entry != null) {
                entry.staticStatePriority = base;
                entry.composedFrontierPriority = safeAdd(
                    adjusted, entry.targetPriorityContribution);
            }
        }

        @Override
        public void onEvent(SearchEvent event) {
            Entry entry = byTransition.get(Key.of(event));
            switch (event.type()) {
                case TRANSFORMATION_GENERATED -> considered(entry, event.pruningReason());
                case STATE_ENQUEUED -> admitted(entry, true, "enqueued");
                case STATE_PRUNED_DUPLICATE -> admitted(entry, false, "duplicate-pruned");
                case STATE_PRUNED_BUDGET -> budget(event.expression());
                case STATE_DEQUEUED -> dequeued(entry);
                default -> {
                    // Other search events do not alter transition evidence.
                }
            }
        }

        private void considered(Entry entry, String pruningReason) {
            if (entry == null) {
                return;
            }
            entry.consideredBySearch = true;
            entry.admissionOutcome = pruningReason.isBlank()
                ? "generated"
                : "skipped:" + pruningReason;
        }

        private void admitted(Entry entry, boolean admitted, String outcome) {
            if (entry == null || !entry.consideredBySearch) {
                return;
            }
            entry.admittedToFrontier = admitted;
            entry.admissionOutcome = outcome;
        }

        private void budget(String parentExpression) {
            entries.stream()
                .filter(entry -> entry.key.parentExpression().equals(parentExpression))
                .filter(entry -> !entry.consideredBySearch)
                .forEach(entry -> entry.admissionOutcome = "candidate-budget-not-considered");
        }

        private void dequeued(Entry entry) {
            if (entry != null && entry.admittedToFrontier && entry.dequeueOrder < 0) {
                entry.dequeueOrder = nextDequeue++;
            }
        }

        private List<FrontierPriorityEvent> freeze() {
            return entries.stream().map(Entry::freeze).toList();
        }
    }

    private static final class Entry {
        private final long group;
        private final Key key;
        private final PolicyDecision decision;
        private final int rank;
        private final int frontierAdjustment;
        private final int targetPriorityContribution;
        private int staticStatePriority;
        private int composedFrontierPriority;
        private boolean consideredBySearch;
        private boolean admittedToFrontier;
        private String admissionOutcome = "not-considered";
        private int dequeueOrder = -1;

        private Entry(
            long group,
            Key key,
            PolicyDecision decision,
            int rank,
            int frontierAdjustment,
            int targetPriorityContribution
        ) {
            this.group = group;
            this.key = key;
            this.decision = decision;
            this.rank = rank;
            this.frontierAdjustment = frontierAdjustment;
            this.targetPriorityContribution = targetPriorityContribution;
        }

        private FrontierPriorityEvent freeze() {
            return new FrontierPriorityEvent(
                group, key.parentExpression(), key.ruleId(), key.childExpression(),
                decision.policyId(), decision.priority(), decision.confidencePermille(),
                decision.fallback(), decision.contributions(), decision.explanation(), rank,
                frontierAdjustment, staticStatePriority, targetPriorityContribution,
                composedFrontierPriority, consideredBySearch, admittedToFrontier,
                admissionOutcome, dequeueOrder);
        }
    }

    private record Candidate(
        Transformation transformation,
        PolicyDecision decision,
        int targetDistance
    ) {
    }

    private record Key(
        String parentExpression,
        String ruleId,
        String childExpression,
        int childDepth
    ) {
        private static Key of(SearchState parent, Transformation transformation) {
            return new Key(
                parent.expression(), transformation.rule(),
                transformation.transformedExpression(), parent.depth() + 1);
        }

        private static Key of(SearchState state) {
            return new Key(
                state.parentExpression(), state.appliedRuleId(),
                state.expression(), state.depth());
        }

        private static Key of(SearchEvent event) {
            return new Key(
                event.parentExpression(), event.ruleId(),
                event.expression(), event.depth());
        }
    }

    private static int adjustment(PolicyDecision decision, int limit) {
        if (decision.fallback() || decision.confidencePermille() == 0) {
            return 0;
        }
        long evidence = 0;
        for (Map.Entry<String, Integer> contribution : decision.contributions().entrySet()) {
            String name = contribution.getKey();
            if (!"targetDistance".equals(name) && !name.startsWith("unknown")) {
                evidence = clamp(evidence + contribution.getValue(),
                    -EVIDENCE_LIMIT, EVIDENCE_LIMIT);
            }
        }
        return (int) clamp(
            evidence * decision.confidencePermille() / 1_000L,
            -limit,
            limit);
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