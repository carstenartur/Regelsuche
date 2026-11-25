package de.regelsuche.search.strategy;

import de.regelsuche.transform.Transformation;
import de.regelsuche.value.ExprValueArena.ValueId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Run-local continuation labels. Semantic nodes do not own histories or notation. */
final class SearchContinuationQuotient {
    private record NodeKey(ValueId value, String unparsedSyntax, List<String> assumptions) {}
    private record PreparedKey(String syntax, List<String> assumptions) { }
    private record Label(int depth, int expansions, boolean checked) {
        static Label of(SearchState state) {
            return new Label(state.depth(), state.expandedStepCount(), state.transformations() != null
                && state.equivalencePreservingFlags().stream().allMatch(Boolean::booleanValue));
        }
        boolean dominates(Label other) {
            return depth <= other.depth && expansions <= other.expansions && (checked || !other.checked);
        }
    }
    private final BestFirstSearchStrategy.ValueIdentitySession identity;
    private final Map<NodeKey, Map<String, List<Label>>> nodes = new LinkedHashMap<>();
    private final Map<Object, List<Transformation>> successors = new LinkedHashMap<>();
    private int retainedLabels;
    private int syntaxObservations;
    private long duplicateArrivals;
    private long successorCacheHits;

    SearchContinuationQuotient(BestFirstSearchStrategy.ValueIdentitySession identity) {
        this.identity = identity;
    }

    private NodeKey key(SearchState state) {
        var value = identity.arenaValue(state.expression());
        return new NodeKey(value.orElse(null), value.isPresent() ? "" : state.expression(), state.assumptions());
    }

    boolean dominated(SearchState state) {
        var representations = nodes.get(key(state));
        var labels = representations == null ? null : representations.get(state.expression());
        if (labels == null || labels.stream().noneMatch(label -> label.dominates(Label.of(state)))) return false;
        duplicateArrivals++;
        return true;
    }

    boolean admit(SearchState state) {
        if (dominated(state)) return false;
        var representations = nodes.computeIfAbsent(key(state), ignored -> new LinkedHashMap<>());
        var labels = representations.get(state.expression());
        if (labels == null) {
            labels = new ArrayList<>();
            representations.put(state.expression(), labels);
            syntaxObservations++;
        }
        Label incoming = Label.of(state);
        int before = labels.size();
        labels.removeIf(incoming::dominates);
        labels.add(incoming);
        retainedLabels += labels.size() - before;
        return true;
    }

    List<Transformation> transformations(SearchProblem problem, SearchState state, boolean qualified) {
        String expression = state.expression();
        Object key = qualified ? new PreparedKey(expression, state.assumptions()) : expression;
        var retained = successors.get(key);
        if (retained != null) {
            successorCacheHits++;
            return retained;
        }
        var frozen = List.copyOf(qualified
            ? Transformation.requirePrimitiveOnly(((StateLocalTransformationEngine) problem.engine())
                .transformPrepared(expression, identity.preparedExpression(expression), state.assumptions()))
            : problem.transformations(expression));
        successors.put(key, frozen);
        return frozen;
    }

    int size() { return nodes.size(); }

    BestFirstSearchStrategy.QuotientMetrics metrics() {
        return new BestFirstSearchStrategy.QuotientMetrics(nodes.size(), syntaxObservations,
            retainedLabels, duplicateArrivals, successorCacheHits, identity.internedValueCount(),
            identity.structuralBytes(), identity.digestComputations());
    }
}
