package de.regelsuche.mining;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Bounded hypothesis grammar: two observed varying parameters, their product,
 * repeated bindings and constants. Agreement on TRAIN is not a proof of a
 * generalized mathematical identity; the ordinary validation gates still apply.
 */
final class BivariateProductRelations {
    private BivariateProductRelations() {}

    static ParameterRelationMiner.RelationResult mine(Map<String, List<Integer>> observations) {
        // Canonical parameter names must not depend on Map.of iteration order.
        var ordered = new TreeMap<>(observations);
        var entries = new ArrayList<>(ordered.entrySet());
        for (int i = 0; i < entries.size(); i++) {
            var first = entries.get(i).getValue();
            if (!varying(first)) continue;
            for (int j = i + 1; j < entries.size(); j++) {
                var second = entries.get(j).getValue();
                if (!varying(second) || first.size() != second.size() || first.equals(second)) continue;
                var result = explain(ordered, first, second);
                if (!result.isEmpty()) return result;
            }
        }
        return ParameterRelationMiner.RelationResult.empty();
    }

    private static boolean varying(List<Integer> values) {
        return values != null && values.size() >= 2 && values.stream().noneMatch(Objects::isNull)
            && values.stream().distinct().count() >= 2;
    }

    private static ParameterRelationMiner.RelationResult explain(Map<String, List<Integer>> observations,
            List<Integer> first, List<Integer> second) {
        var replacements = new LinkedHashMap<String, NormalizedNode>();
        var descriptions = new ArrayList<String>();
        boolean productObserved = false;
        for (var entry : observations.entrySet()) {
            var values = entry.getValue();
            if (values == null || values.size() != first.size() || values.stream().anyMatch(Objects::isNull)) {
                return ParameterRelationMiner.RelationResult.empty();
            }
            NormalizedNode node;
            if (values.equals(first)) node = NormalizedNode.variable("A");
            else if (values.equals(second)) node = NormalizedNode.variable("A2");
            else if (values.stream().distinct().count() == 1) node = NormalizedNode.number(values.getFirst());
            else if (productMatches(values, first, second)) {
                node = NormalizedNode.multiply(List.of(NormalizedNode.variable("A"), NormalizedNode.variable("A2")));
                productObserved = true;
            } else return ParameterRelationMiner.RelationResult.empty();
            replacements.put(entry.getKey(), node);
            descriptions.add(entry.getKey() + " = " + node.canonicalString());
        }
        return productObserved ? new ParameterRelationMiner.RelationResult(replacements, descriptions)
            : ParameterRelationMiner.RelationResult.empty();
    }

    private static boolean productMatches(List<Integer> values, List<Integer> first, List<Integer> second) {
        for (int i = 0; i < values.size(); i++) {
            // An int-by-int product fits in long; compare before any narrowing.
            if (values.get(i).longValue() != (long) first.get(i) * second.get(i)) return false;
        }
        return true;
    }
}
