package de.regelsuche.search.strategy;

import de.regelsuche.ast.Expr;
import de.regelsuche.canonical.ExpressionCanonicalizer;
import de.regelsuche.parse.ExpressionParser;
import de.regelsuche.search.policy.SearchPolicy;
import de.regelsuche.search.policy.SearchPolicy.PolicyContext;
import de.regelsuche.search.policy.SearchPolicy.PolicyDecision;
import de.regelsuche.search.strategy.SearchProblem.SearchTarget;
import de.regelsuche.search.strategy.SearchProblem.TargetRelation;
import de.regelsuche.transform.Transformation;
import de.regelsuche.transform.TransformationEngine;
import de.regelsuche.value.ExprValueFactory;
import de.regelsuche.value.ExprValueFactory.AssociativeCommutativeValue;
import de.regelsuche.value.ExprValueFactory.ExprValue;
import de.regelsuche.value.ExprValueFactory.OrderedValue;
import de.regelsuche.value.ExprValueFactory.ValueKey;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Applies an explainable policy after rule applicability and before the ordinary
 * per-state candidate budget. The unconfigured default remains
 * {@link BestFirstSearchStrategy}.
 *
 * <p>A confident policy exposes only its ranked budget prefix to the underlying
 * search. If every applicable candidate falls back, the complete original
 * candidate list is delegated so the ordinary static sorting and skip handling are
 * preserved exactly.</p>
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
        try (TargetDistance distance = new TargetDistance(
                problem.target(), problem.canonicalizer())) {
            TransformationEngine rankedEngine = new RankingEngine(
                problem.engine(),
                policy,
                trace,
                distance,
                problem.canonicalizer(),
                problem.heuristic().maxCandidatesPerState());
            SearchProblem rankedProblem = new SearchProblem(
                problem.rootExpression(),
                rankedEngine,
                problem.scorer(),
                problem.canonicalizer(),
                problem.heuristic(),
                problem.memory(),
                problem.costModel(),
                problem.observer(),
                problem.target());
            BestFirstSearchStrategy.GoalSearchResult result =
                new BestFirstSearchStrategy().searchWithDiagnostics(rankedProblem);
            return new PolicySearchResult(result, trace.events());
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

    /** One deterministic, explainable candidate-ranking decision. */
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
        boolean selectedByCandidateBudget
    ) {
        public RankingEvent {
            parentExpression = parentExpression == null ? "" : parentExpression;
            ruleId = ruleId == null ? "" : ruleId;
            transformedExpression = transformedExpression == null ? "" : transformedExpression;
            policyId = policyId == null ? "" : policyId;
            contributions = contributions == null ? Map.of() : Map.copyOf(contributions);
            explanation = explanation == null ? "" : explanation;
        }
    }

    private static final class RankingEngine implements TransformationEngine {
        private final TransformationEngine delegate;
        private final SearchPolicy policy;
        private final PolicyTrace trace;
        private final TargetDistance distance;
        private final ExpressionCanonicalizer canonicalizer;
        private final int candidateBudget;
        private long decisionGroup;

        private RankingEngine(
            TransformationEngine delegate,
            SearchPolicy policy,
            PolicyTrace trace,
            TargetDistance distance,
            ExpressionCanonicalizer canonicalizer,
            int candidateBudget
        ) {
            this.delegate = delegate;
            this.policy = policy;
            this.trace = trace;
            this.distance = distance;
            this.canonicalizer = canonicalizer;
            this.candidateBudget = Math.max(1, candidateBudget);
        }

        @Override
        public List<Transformation> transform(String expression) {
            List<Transformation> applicable = new ArrayList<>(delegate.transform(expression));
            Comparator<RankedTransformation> deterministic = Comparator
                .comparing((RankedTransformation ranked) -> ranked.transformation().rule())
                .thenComparing(ranked -> ranked.transformation().transformedExpression())
                .thenComparing(ranked -> ranked.transformation().applicationKey());
            List<RankedTransformation> ranked = applicable.stream()
                .map(transformation -> new RankedTransformation(
                    transformation,
                    policy.score(
                        new PolicyContext(
                            expression,
                            distance.distance(transformation.transformedExpression()),
                            distance.enabled(),
                            canonicalizer),
                        transformation)))
                .sorted(Comparator
                    .comparingInt((RankedTransformation item) -> item.decision().priority())
                    .thenComparing(deterministic))
                .toList();

            boolean completeFallback = !ranked.isEmpty()
                && ranked.stream().allMatch(item -> item.decision().fallback());
            int selectedCount = completeFallback
                ? ranked.size()
                : Math.min(candidateBudget, ranked.size());
            long group = decisionGroup++;
            for (int index = 0; index < ranked.size(); index++) {
                RankedTransformation item = ranked.get(index);
                trace.record(
                    group,
                    expression,
                    item.transformation(),
                    item.decision(),
                    index,
                    index < selectedCount);
            }
            if (completeFallback) {
                return applicable;
            }
            return ranked.stream()
                .limit(selectedCount)
                .map(RankedTransformation::transformation)
                .toList();
        }
    }

    private static final class PolicyTrace {
        private final List<RankingEvent> events = new ArrayList<>();

        private void record(
            long decisionGroup,
            String parentExpression,
            Transformation transformation,
            PolicyDecision decision,
            int deterministicRank,
            boolean selectedByCandidateBudget
        ) {
            events.add(new RankingEvent(
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
                selectedByCandidateBudget));
        }

        private List<RankingEvent> events() {
            return List.copyOf(events);
        }
    }

    private static final class TargetDistance implements AutoCloseable {
        private static final int UNPARSEABLE_DISTANCE = 100_000;

        private final SearchTarget target;
        private final ExpressionCanonicalizer canonicalizer;
        private final ExpressionParser parser = new ExpressionParser();
        private final ExprValueFactory factory = new ExprValueFactory();
        private final String normalizedTarget;
        private final ExprValue targetValue;
        private final Map<ValueKey, Integer> targetOccurrences;
        private final Map<String, Integer> cache = new HashMap<>();

        private TargetDistance(SearchTarget target, ExpressionCanonicalizer canonicalizer) {
            this.target = target;
            this.canonicalizer = canonicalizer;
            normalizedTarget = target == null ? "" : normalize(target.targetExpression());
            targetValue = target == null ? null : value(normalizedTarget);
            targetOccurrences = targetValue == null ? Map.of() : occurrenceMultiset(targetValue);
        }

        private boolean enabled() {
            return target != null;
        }

        private int distance(String expression) {
            if (!enabled()) {
                return 0;
            }
            String normalized = normalize(expression);
            return cache.computeIfAbsent(normalized, this::computeDistance);
        }

        private int computeDistance(String expression) {
            if (target.relation() == TargetRelation.SYNTAX_EXACT
                    && expression.equals(normalizedTarget)) {
                return 0;
            }
            if (targetValue == null) {
                return UNPARSEABLE_DISTANCE;
            }
            ExprValue candidate = value(expression);
            if (candidate == null) {
                return UNPARSEABLE_DISTANCE;
            }
            int semantic = semanticDistance(candidate, targetValue, targetOccurrences);
            if (target.relation() != TargetRelation.SYNTAX_EXACT) {
                return semantic;
            }
            return Math.min(UNPARSEABLE_DISTANCE - 1, semantic + 1);
        }

        private ExprValue value(String expression) {
            try {
                Expr parsed = parser.parseTerm(expression);
                return factory.fromExpr(canonicalizer.canonicalize(parsed));
            } catch (IllegalArgumentException exception) {
                return null;
            }
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

        private static void collect(
            ExprValue value,
            int multiplicity,
            Map<ValueKey, Integer> counts
        ) {
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

        private static String normalize(String expression) {
            return expression == null ? "" : expression.trim().replaceAll("\\s+", " ");
        }

        @Override
        public void close() {
            cache.clear();
            factory.close();
        }
    }

    private record RankedTransformation(
        Transformation transformation,
        PolicyDecision decision
    ) {
    }
}
