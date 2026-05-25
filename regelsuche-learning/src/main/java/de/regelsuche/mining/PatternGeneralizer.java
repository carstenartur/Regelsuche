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
        ParameterRelationMiner.RelationResult relations = relationMiner.mine(state.values);
        if (relations.isEmpty()) {
            return Optional.empty();
        }
        String left = relations.apply(leftPattern.orElseThrow()).canonicalString();
        String right = relations.apply(rightPattern.orElseThrow()).canonicalString();
        return Optional.of(new GeneralizedPattern(left, right, state.values, relations.descriptions()));
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
        boolean sameShape = nodes.stream().allMatch(first::sameShape);
        if (!sameShape || first.kind() == NormalizedNode.Kind.NUMBER || first.kind() == NormalizedNode.Kind.VARIABLE) {
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
        private int index;
        private final Map<String, List<Integer>> values = new LinkedHashMap<>();

        private String nextPlaceholder() {
            index++;
            return "N" + index;
        }
    }
}
