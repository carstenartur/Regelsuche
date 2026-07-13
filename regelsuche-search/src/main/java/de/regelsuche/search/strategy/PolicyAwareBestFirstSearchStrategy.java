package de.regelsuche.search.strategy;

import de.regelsuche.search.policy.SearchPolicy;
import de.regelsuche.search.policy.SearchPolicy.PolicyContext;
import de.regelsuche.search.policy.SearchPolicy.PolicyDecision;
import de.regelsuche.search.telemetry.CompositeSearchObserver;
import de.regelsuche.search.telemetry.SearchEvent;
import de.regelsuche.search.telemetry.SearchObserver;
import de.regelsuche.transform.Transformation;
import java.util.ArrayList;
import java.util.Comparator;
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
    private final SearchPolicy policy;

    public PolicyAwareBestFirstSearchStrategy(SearchPolicy policy) {
        this.policy = Objects.requireNonNull(policy, "policy");
    }

    @Override
    public List<SearchState> search(SearchProblem problem) {
        return searchWithDiagnostics(problem).search().states();
    }

    public PolicySearchResult searchWithDiagnostics(SearchProblem problem) {
        Objects.requireNonNull(problem, "problem");
        PolicyTrace trace = new PolicyTrace();
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
        BestFirstSearchStrategy.GoalSearchResult result =
            new PolicyBestFirstSearchStrategy(policy, trace).searchWithDiagnostics(rankedProblem);
        return new PolicySearchResult(result, trace.events());
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

    /** One deterministic, explainable candidate-ranking decision and its real search outcome. */
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
        boolean consideredBySearch,
        boolean admittedToFrontier,
        String admissionOutcome
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

        /** Compatibility alias for the former trace field, now backed by real admission. */
        public boolean selectedByCandidateBudget() {
            return admittedToFrontier;
        }
    }

    private static final class PolicyBestFirstSearchStrategy extends BestFirstSearchStrategy {
        private final SearchPolicy policy;
        private final PolicyTrace trace;

        private PolicyBestFirstSearchStrategy(SearchPolicy policy, PolicyTrace trace) {
            this.policy = policy;
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
                .map(transformation -> new RankedTransformation(
                    transformation,
                    policy.score(
                        new PolicyContext(
                            current.expression(),
                            targetDistance.applyAsInt(transformation),
                            targetEnabled,
                            problem.canonicalizer()),
                        transformation)))
                .toList());

            boolean completeFallback = !ranked.isEmpty()
                && ranked.stream().allMatch(item -> item.decision().fallback());
            if (completeFallback) {
                if (targetEnabled) {
                    ranked.sort(Comparator
                        .comparingInt((RankedTransformation item) ->
                            targetDistance.applyAsInt(item.transformation()))
                        .thenComparing(deterministic));
                } else {
                    ranked.sort(deterministic);
                }
            } else {
                ranked.sort(Comparator
                    .comparingInt((RankedTransformation item) -> item.decision().priority())
                    .thenComparing(deterministic));
            }

            trace.startGroup(current.expression(), ranked);
            return ranked.stream().map(RankedTransformation::transformation).toList();
        }
    }

    private static final class PolicyTrace implements SearchObserver {
        private final List<MutableRankingEvent> events = new ArrayList<>();
        private long nextDecisionGroup;
        private RankingGroup activeGroup;

        private void startGroup(String parentExpression, List<RankedTransformation> ranked) {
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
                    parentExpression,
                    item.transformation(),
                    item.decision(),
                    rank);
                groupEvents.add(event);
                events.add(event);
            }
            activeGroup = new RankingGroup(groupEvents);
        }

        @Override
        public void onEvent(SearchEvent event) {
            switch (event.type()) {
                case TRANSFORMATION_GENERATED -> transformationConsidered(event);
                case STATE_ENQUEUED -> completePending(event, true, "enqueued");
                case STATE_PRUNED_DUPLICATE -> completePending(event, false, "duplicate-pruned");
                case STATE_PRUNED_BUDGET -> finishActive("candidate-budget-not-considered");
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
        private boolean consideredBySearch;
        private boolean admittedToFrontier;
        private String admissionOutcome = "not-considered";

        private MutableRankingEvent(
            long decisionGroup,
            String parentExpression,
            Transformation transformation,
            PolicyDecision decision,
            int deterministicRank
        ) {
            this.decisionGroup = decisionGroup;
            this.parentExpression = parentExpression;
            this.transformation = transformation;
            this.decision = decision;
            this.deterministicRank = deterministicRank;
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
                consideredBySearch,
                admittedToFrontier,
                admissionOutcome);
        }
    }

    private record RankedTransformation(
        Transformation transformation,
        PolicyDecision decision
    ) {
    }
}
