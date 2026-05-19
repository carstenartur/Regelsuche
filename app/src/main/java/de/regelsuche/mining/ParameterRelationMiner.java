package de.regelsuche.mining;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class ParameterRelationMiner {
    public RelationResult mine(Map<String, List<Integer>> placeholderValues) {
        if (placeholderValues.isEmpty()) {
            return RelationResult.empty();
        }
        for (Map.Entry<String, List<Integer>> candidateBase : placeholderValues.entrySet()) {
            int orientation = candidateBase.getValue().stream().allMatch(value -> value < 0) ? -1 : 1;
            List<Integer> baseValues = candidateBase.getValue().stream().map(value -> value * orientation).toList();
            if (baseValues.stream().distinct().count() < 2 || baseValues.stream().anyMatch(value -> value == 0)) {
                continue;
            }
            Map<String, NormalizedNode> replacements = new LinkedHashMap<>();
            List<String> descriptions = new ArrayList<>();
            boolean complete = true;
            for (Map.Entry<String, List<Integer>> entry : placeholderValues.entrySet()) {
                Relation relation = findRelation(entry.getValue(), baseValues);
                if (relation == null) {
                    complete = false;
                    break;
                }
                replacements.put(entry.getKey(), relation.node());
                descriptions.add(entry.getKey() + " = " + relation.description());
            }
            if (complete && replacements.values().stream().anyMatch(node -> node.canonicalString().contains("A"))) {
                return new RelationResult(replacements, descriptions);
            }
        }
        return RelationResult.empty();
    }

    private Relation findRelation(List<Integer> values, List<Integer> baseValues) {
        if (matches(values, baseValues, a -> a)) {
            return new Relation(NormalizedNode.variable("A"), "A");
        }
        if (matches(values, baseValues, a -> -a)) {
            return new Relation(NormalizedNode.multiply(List.of(NormalizedNode.number(-1), NormalizedNode.variable("A"))), "-A");
        }
        for (int factor = -10; factor <= 10; factor++) {
            if (factor == -1 || factor == 0 || factor == 1) {
                continue;
            }
            int currentFactor = factor;
            if (matches(values, baseValues, a -> currentFactor * a)) {
                return new Relation(
                    NormalizedNode.multiply(List.of(NormalizedNode.number(currentFactor), NormalizedNode.variable("A"))),
                    currentFactor + "*A"
                );
            }
        }
        if (matches(values, baseValues, a -> a * a)) {
            return new Relation(NormalizedNode.pow(NormalizedNode.variable("A"), NormalizedNode.number(2)), "A^2");
        }
        if (matches(values, baseValues, a -> -(a * a))) {
            return new Relation(
                NormalizedNode.multiply(List.of(
                    NormalizedNode.number(-1),
                    NormalizedNode.pow(NormalizedNode.variable("A"), NormalizedNode.number(2))
                )),
                "-A^2"
            );
        }
        if (values.stream().distinct().count() == 1) {
            int constant = values.getFirst();
            return new Relation(NormalizedNode.number(constant), Integer.toString(constant));
        }
        return null;
    }

    private boolean matches(List<Integer> values, List<Integer> baseValues, RelationFunction function) {
        for (int i = 0; i < values.size(); i++) {
            if (values.get(i) != function.apply(baseValues.get(i))) {
                return false;
            }
        }
        return true;
    }

    private interface RelationFunction {
        int apply(int value);
    }

    private record Relation(NormalizedNode node, String description) {
    }

    public static final class RelationResult {
        private final Map<String, NormalizedNode> replacements;
        private final List<String> descriptions;

        private RelationResult(Map<String, NormalizedNode> replacements, List<String> descriptions) {
            this.replacements = Map.copyOf(replacements);
            this.descriptions = List.copyOf(descriptions);
        }

        static RelationResult empty() {
            return new RelationResult(Map.of(), List.of());
        }

        boolean isEmpty() {
            return replacements.isEmpty();
        }

        List<String> descriptions() {
            return descriptions;
        }

        NormalizedNode apply(NormalizedNode node) {
            if (node.kind() == NormalizedNode.Kind.PLACEHOLDER) {
                return replacements.getOrDefault(node.name(), node);
            }
            List<NormalizedNode> children = node.children().stream().map(this::apply).toList();
            return switch (node.kind()) {
                case NUMBER -> NormalizedNode.number(node.number());
                case VARIABLE -> NormalizedNode.variable(node.name());
                case PLACEHOLDER -> replacements.getOrDefault(node.name(), node);
                case ADD -> NormalizedNode.add(children);
                case MUL -> NormalizedNode.multiply(children);
                case POW -> NormalizedNode.pow(children.get(0), children.get(1));
            };
        }
    }
}
