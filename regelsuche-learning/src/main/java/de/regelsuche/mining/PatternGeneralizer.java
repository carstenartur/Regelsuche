package de.regelsuche.mining;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class PatternGeneralizer {
    private final AstNormalizer normalizer;
    private final ParameterRelationMiner relationMiner;

    public PatternGeneralizer() {
        this(new AstNormalizer(), new ParameterRelationMiner());
    }

    PatternGeneralizer(AstNormalizer normalizer, ParameterRelationMiner relationMiner) {
        this.normalizer = normalizer;
        this.relationMiner = relationMiner;
    }

    public String skeleton(SuccessfulTransformationPath path) {
        NormalizedNode left = normalizer.normalize(path.originalExpression());
        NormalizedNode right = normalizer.normalize(path.targetExpression());
        return left.skeletonString() + "->" + right.skeletonString();
    }

    public Optional<GeneralizedPattern> generalize(List<SuccessfulTransformationPath> paths) {
        if (paths.isEmpty()) {
            return Optional.empty();
        }
        List<NormalizedNode> leftNodes = new ArrayList<>();
        List<NormalizedNode> rightNodes = new ArrayList<>();
        for (SuccessfulTransformationPath path : paths) {
            leftNodes.add(normalizer.normalize(path.originalExpression()));
            rightNodes.add(normalizer.normalize(path.targetExpression()));
        }
        PlaceholderState state = new PlaceholderState();
        Optional<NormalizedNode> leftPattern = generalizeNodes(leftNodes, state);
        Optional<NormalizedNode> rightPattern = generalizeNodes(rightNodes, state);
        if (leftPattern.isEmpty() || rightPattern.isEmpty()) {
            return Optional.empty();
        }
        // Mine integer relations if any integer placeholders exist.
        ParameterRelationMiner.RelationResult relations = state.values.isEmpty()
            ? ParameterRelationMiner.RelationResult.empty()
            : relationMiner.mine(state.values);
        // Require at least one kind of abstraction: integer relations or expression placeholders.
        boolean hasIntegerRelation = !relations.isEmpty();
        boolean hasExpressionPlaceholders = !state.expressionValues.isEmpty();
        if (!hasIntegerRelation && !hasExpressionPlaceholders) {
            return Optional.empty();
        }
        String left = relations.apply(leftPattern.orElseThrow()).canonicalString();
        String right = relations.apply(rightPattern.orElseThrow()).canonicalString();
        // Build combined descriptions: integer relations + expression placeholder notes.
        List<String> descriptions = new ArrayList<>(relations.descriptions());
        for (Map.Entry<String, List<String>> entry : state.expressionValues.entrySet()) {
            descriptions.add(entry.getKey() + " \u2208 {" + String.join(", ", entry.getValue()) + "}");
        }
        return Optional.of(new GeneralizedPattern(
            left, right, state.values, descriptions, state.expressionValues
        ));
    }

    private Optional<NormalizedNode> generalizeNodes(List<NormalizedNode> nodes, PlaceholderState state) {
        NormalizedNode first = nodes.getFirst();
        boolean allEqual = nodes.stream().allMatch(first::equals);
        if (allEqual) {
            return Optional.of(first);
        }
        boolean allNumbers = nodes.stream().allMatch(node -> node.kind() == NormalizedNode.Kind.NUMBER);
        if (allNumbers) {
            String placeholder = state.nextPlaceholder();
            state.values.put(placeholder, nodes.stream().map(NormalizedNode::number).toList());
            return Optional.of(NormalizedNode.placeholder(placeholder));
        }
        // Anti-unification over variables: different variable names become expression placeholders.
        boolean allVariables = nodes.stream().allMatch(node -> node.kind() == NormalizedNode.Kind.VARIABLE);
        if (allVariables) {
            boolean allSameName = nodes.stream().allMatch(n -> first.name().equals(n.name()));
            if (!allSameName) {
                String placeholder = state.nextExpressionPlaceholder();
                state.expressionValues.put(placeholder,
                    nodes.stream().map(NormalizedNode::canonicalString).distinct().toList());
                return Optional.of(NormalizedNode.placeholder(placeholder));
            }
        }
        boolean sameShape = nodes.stream().allMatch(first::sameShape);
        if (!sameShape) {
            // Anti-unification over subtrees: structurally different subtrees become expression placeholders.
            String placeholder = state.nextExpressionPlaceholder();
            state.expressionValues.put(placeholder,
                nodes.stream().map(NormalizedNode::canonicalString).distinct().toList());
            return Optional.of(NormalizedNode.placeholder(placeholder));
        }
        if (first.kind() == NormalizedNode.Kind.NUMBER || first.kind() == NormalizedNode.Kind.VARIABLE) {
            return Optional.empty();
        }
        List<NormalizedNode> generalizedChildren = new ArrayList<>();
        for (int childIndex = 0; childIndex < first.children().size(); childIndex++) {
            List<NormalizedNode> childNodes = new ArrayList<>();
            for (NormalizedNode node : nodes) {
                childNodes.add(node.children().get(childIndex));
            }
            Optional<NormalizedNode> child = generalizeNodes(childNodes, state);
            if (child.isEmpty()) {
                return Optional.empty();
            }
            generalizedChildren.add(child.orElseThrow());
        }
        return switch (first.kind()) {
            case ADD -> Optional.of(NormalizedNode.add(generalizedChildren));
            case MUL -> Optional.of(NormalizedNode.multiply(generalizedChildren));
            case POW -> Optional.of(NormalizedNode.pow(generalizedChildren.get(0), generalizedChildren.get(1)));
            case FUNCTION -> Optional.of(NormalizedNode.function(first.name(), generalizedChildren));
            case PLACEHOLDER -> Optional.of(first);
            case NUMBER, VARIABLE -> Optional.empty();
        };
    }

    private static final class PlaceholderState {
        private int integerIndex;
        private int expressionIndex;
        private final Map<String, List<Integer>> values = new LinkedHashMap<>();
        private final Map<String, List<String>> expressionValues = new LinkedHashMap<>();

        private String nextPlaceholder() {
            integerIndex++;
            return "N" + integerIndex;
        }

        private String nextExpressionPlaceholder() {
            // Use single uppercase letters (B, C, D, ...) for expression-level placeholders.
            // We skip 'A' since ParameterRelationMiner uses 'A' as its canonical parameter name.
            expressionIndex++;
            return String.valueOf((char) ('A' + expressionIndex)); // 1→B, 2→C, ...
        }
    }
}
