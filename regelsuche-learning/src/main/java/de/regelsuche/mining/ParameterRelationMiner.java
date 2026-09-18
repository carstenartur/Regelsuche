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
        return mineBivariateProductRelations(placeholderValues);
    }

    /**
     * Learn the smallest two-parameter relation currently needed by arithmetic
     * program generalisation: repeated placeholders may denote either of two
     * independently varying parameters, their product, or a constant.
     *
     * <p>This is deliberately domain-neutral. It does not know about modular
     * exponentiation or any consuming rewrite; it only recognizes an exact
     * element-wise integer relation present in every training observation.</p>
     */
    private RelationResult mineBivariateProductRelations(
        Map<String, List<Integer>> placeholderValues
    ) {
        List<Map.Entry<String, List<Integer>>> entries =
            new ArrayList<>(placeholderValues.entrySet());
        for (int firstIndex = 0; firstIndex < entries.size(); firstIndex++) {
            List<Integer> first = entries.get(firstIndex).getValue();
            if (!eligibleIndependentBase(first)) {
                continue;
            }
            for (int secondIndex = firstIndex + 1;
                    secondIndex < entries.size(); secondIndex++) {
                List<Integer> second = entries.get(secondIndex).getValue();
                if (!eligibleIndependentBase(second)
                        || first.size() != second.size()
                        || first.equals(second)) {
                    continue;
                }
                Map<String, NormalizedNode> replacements =
                    new LinkedHashMap<>();
                List<String> descriptions = new ArrayList<>();
                boolean complete = true;
                boolean usesFirst = false;
                boolean usesSecond = false;
                boolean usesProduct = false;
                for (Map.Entry<String, List<Integer>> entry :
                        placeholderValues.entrySet()) {
                    BivariateRelation relation = findBivariateRelation(
                        entry.getValue(), first, second);
                    if (relation == null) {
                        complete = false;
                        break;
                    }
                    replacements.put(entry.getKey(), relation.node());
                    descriptions.add(entry.getKey() + " = "
                        + relation.description());
                    usesFirst |= relation.kind()
                        == BivariateRelationKind.FIRST;
                    usesSecond |= relation.kind()
                        == BivariateRelationKind.SECOND;
                    usesProduct |= relation.kind()
                        == BivariateRelationKind.PRODUCT;
                }
                if (complete && usesFirst && usesSecond && usesProduct) {
                    return new RelationResult(replacements, descriptions);
                }
            }
        }
        return RelationResult.empty();
    }

    private boolean eligibleIndependentBase(List<Integer> values) {
        return values != null
            && values.size() >= 2
            && values.stream().distinct().count() >= 2;
    }

    private BivariateRelation findBivariateRelation(
        List<Integer> values,
        List<Integer> first,
        List<Integer> second
    ) {
        if (values.equals(first)) {
            return new BivariateRelation(
                NormalizedNode.variable("A"),
                "A",
                BivariateRelationKind.FIRST);
        }
        if (values.equals(second)) {
            return new BivariateRelation(
                NormalizedNode.variable("A2"),
                "A2",
                BivariateRelationKind.SECOND);
        }
        if (matchesProduct(values, first, second)) {
            return new BivariateRelation(
                NormalizedNode.multiply(List.of(
                    NormalizedNode.variable("A"),
                    NormalizedNode.variable("A2"))),
                "A*A2",
                BivariateRelationKind.PRODUCT);
        }
        if (values.stream().distinct().count() == 1) {
            int constant = values.getFirst();
            return new BivariateRelation(
                NormalizedNode.number(constant),
                Integer.toString(constant),
                BivariateRelationKind.CONSTANT);
        }
        return null;
    }

    private boolean matchesProduct(
        List<Integer> values,
        List<Integer> first,
        List<Integer> second
    ) {
        if (values.size() != first.size() || values.size() != second.size()) {
            return false;
        }
        for (int index = 0; index < values.size(); index++) {
            long product = (long) first.get(index) * second.get(index);
            if (product < Integer.MIN_VALUE || product > Integer.MAX_VALUE
                    || values.get(index) != (int) product) {
                return false;
            }
        }
        return true;
    }

    private enum BivariateRelationKind {
        FIRST,
        SECOND,
        PRODUCT,
        CONSTANT
    }

    private record BivariateRelation(
        NormalizedNode node,
        String description,
        BivariateRelationKind kind
    ) {
    }

    private Relation findRelation(List<Integer> values, List<Integer> baseValues) {
        if (matches(values, baseValues, a -> a)) {
            return new Relation(NormalizedNode.variable("A"), "A");
        }
        if (matches(values, baseValues, a -> -a)) {
            return new Relation(NormalizedNode.multiply(List.of(NormalizedNode.number(-1), NormalizedNode.variable("A"))), "-A");
        }
        for (int offset = -10; offset <= 10; offset++) {
            if (offset == 0) {
                continue;
            }
            int currentOffset = offset;
            if (matches(values, baseValues, a -> a + currentOffset)) {
                return new Relation(
                    NormalizedNode.add(List.of(NormalizedNode.variable("A"), NormalizedNode.number(currentOffset))),
                    currentOffset > 0 ? "A + " + currentOffset : "A - " + Math.abs(currentOffset)
                );
            }
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
            for (int offset = -10; offset <= 10; offset++) {
                if (offset == 0) {
                    continue;
                }
                int currentOffset = offset;
                if (matches(values, baseValues, a -> currentFactor * a + currentOffset)) {
                    return new Relation(
                        NormalizedNode.add(List.of(
                            NormalizedNode.multiply(List.of(NormalizedNode.number(currentFactor), NormalizedNode.variable("A"))),
                            NormalizedNode.number(currentOffset)
                        )),
                        currentOffset > 0
                            ? currentFactor + "*A + " + currentOffset
                            : currentFactor + "*A - " + Math.abs(currentOffset)
                    );
                }
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
                case FUNCTION -> NormalizedNode.function(node.name(), children);
            };
        }
    }
}
