package de.regelsuche.mining;

import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.FunctionExpr;
import de.regelsuche.ast.NumberExpr;
import de.regelsuche.ast.VariableExpr;
import de.regelsuche.parse.ExpressionFormatter;
import de.regelsuche.parse.ExpressionParser;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public class PatternGeneralizer {
    private final AstNormalizer normalizer;
    private final ParameterRelationMiner relationMiner;
    private final ExpressionParser expressionParser = new ExpressionParser();

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

    public Optional<GeneralizedPattern> generalizeSingleExampleSchema(SuccessfulTransformationPath path) {
        if (path == null) {
            return Optional.empty();
        }
        Optional<GeneralizedPattern> variableSchema = generalizeSingleVariableSchema(path);
        if (variableSchema.isPresent()) {
            return variableSchema;
        }
        NormalizedNode left = normalizer.normalize(path.originalExpression());
        NormalizedNode right = normalizer.normalize(path.targetExpression());
        Optional<NormalizedNode> placeholderSubtree = commonExpressionSubtrees(left, right).stream()
            .max(Comparator
                .comparingInt(this::nodeCount)
                .thenComparing(NormalizedNode::canonicalString));
        if (placeholderSubtree.isEmpty()) {
            return Optional.empty();
        }

        String placeholder = "A";
        NormalizedNode generalizedLeft = replaceSubtree(left, placeholderSubtree.orElseThrow(), placeholder);
        NormalizedNode generalizedRight = replaceSubtree(right, placeholderSubtree.orElseThrow(), placeholder);
        if (!containsPlaceholder(generalizedLeft, placeholder) || !containsPlaceholder(generalizedRight, placeholder)) {
            return Optional.empty();
        }
        String leftPattern = generalizedLeft.canonicalString();
        String rightPattern = generalizedRight.canonicalString();
        if (leftPattern.equals(left.canonicalString()) && rightPattern.equals(right.canonicalString())) {
            return Optional.empty();
        }
        Map<String, List<String>> expressionValues = Map.of(
            placeholder,
            List.of(placeholderSubtree.orElseThrow().canonicalString())
        );
        return Optional.of(new GeneralizedPattern(
            leftPattern,
            rightPattern,
            Map.of(),
            List.of(placeholder + " \u2208 {" + placeholderSubtree.orElseThrow().canonicalString() + "}"),
            expressionValues
        ));
    }

    private Optional<GeneralizedPattern> generalizeSingleVariableSchema(SuccessfulTransformationPath path) {
        Expr left;
        Expr right;
        try {
            left = expressionParser.parseTerm(path.originalExpression());
            right = expressionParser.parseTerm(path.targetExpression());
        } catch (IllegalArgumentException exception) {
            return Optional.empty();
        }
        Set<String> variables = new LinkedHashSet<>();
        collectVariables(left, variables);
        collectVariables(right, variables);
        if (variables.size() != 1) {
            return Optional.empty();
        }
        String variable = variables.iterator().next();
        Expr placeholder = new VariableExpr("A");
        Expr generalizedLeft = replaceVariable(left, variable, placeholder);
        Expr generalizedRight = replaceVariable(right, variable, placeholder);
        String leftPattern = ExpressionFormatter.format(generalizedLeft);
        String rightPattern = ExpressionFormatter.format(generalizedRight);
        if (leftPattern.equals(path.originalExpression()) && rightPattern.equals(path.targetExpression())) {
            return Optional.empty();
        }
        return Optional.of(new GeneralizedPattern(
            leftPattern,
            rightPattern,
            Map.of(),
            List.of("A \u2208 {" + variable + "}"),
            Map.of("A", List.of(variable))
        ));
    }

    private void collectVariables(Expr expression, Set<String> variables) {
        if (expression instanceof VariableExpr variable) {
            variables.add(variable.name());
            return;
        }
        if (expression instanceof FunctionExpr function) {
            for (Expr argument : function.arguments()) {
                collectVariables(argument, variables);
            }
            return;
        }
        if (expression instanceof BinaryExpr binary) {
            collectVariables(binary.left(), variables);
            collectVariables(binary.right(), variables);
        }
    }

    private Expr replaceVariable(Expr expression, String variable, Expr placeholder) {
        if (expression instanceof VariableExpr variableExpr) {
            return variableExpr.name().equals(variable) ? placeholder : variableExpr;
        }
        if (expression instanceof NumberExpr) {
            return expression;
        }
        if (expression instanceof FunctionExpr function) {
            return new FunctionExpr(function.name(), function.arguments().stream()
                .map(argument -> replaceVariable(argument, variable, placeholder))
                .toList());
        }
        BinaryExpr binary = (BinaryExpr) expression;
        return new BinaryExpr(
            replaceVariable(binary.left(), variable, placeholder),
            binary.operator(),
            replaceVariable(binary.right(), variable, placeholder)
        );
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

    private List<NormalizedNode> commonExpressionSubtrees(NormalizedNode left, NormalizedNode right) {
        Map<String, NormalizedNode> leftSubtrees = new LinkedHashMap<>();
        collectExpressionSubtrees(left, left, leftSubtrees);
        Map<String, NormalizedNode> rightSubtrees = new LinkedHashMap<>();
        collectExpressionSubtrees(right, right, rightSubtrees);
        List<NormalizedNode> common = new ArrayList<>();
        for (Map.Entry<String, NormalizedNode> entry : leftSubtrees.entrySet()) {
            if (rightSubtrees.containsKey(entry.getKey())) {
                common.add(entry.getValue());
            }
        }
        return common;
    }

    private void collectExpressionSubtrees(
        NormalizedNode node,
        NormalizedNode root,
        Map<String, NormalizedNode> subtrees
    ) {
        if (node.kind() != NormalizedNode.Kind.NUMBER && node != root) {
            subtrees.putIfAbsent(node.canonicalString(), node);
        }
        for (NormalizedNode child : node.children()) {
            collectExpressionSubtrees(child, root, subtrees);
        }
    }

    private NormalizedNode replaceSubtree(NormalizedNode node, NormalizedNode target, String placeholder) {
        if (node.equals(target)) {
            return NormalizedNode.placeholder(placeholder);
        }
        List<NormalizedNode> children = node.children().stream()
            .map(child -> replaceSubtree(child, target, placeholder))
            .toList();
        return switch (node.kind()) {
            case NUMBER -> NormalizedNode.number(node.number());
            case VARIABLE -> NormalizedNode.variable(node.name());
            case PLACEHOLDER -> NormalizedNode.placeholder(node.name());
            case ADD -> NormalizedNode.add(children);
            case MUL -> NormalizedNode.multiply(children);
            case POW -> NormalizedNode.pow(children.get(0), children.get(1));
            case FUNCTION -> NormalizedNode.function(node.name(), children);
        };
    }

    private boolean containsPlaceholder(NormalizedNode node, String placeholder) {
        if (node.kind() == NormalizedNode.Kind.PLACEHOLDER && placeholder.equals(node.name())) {
            return true;
        }
        return node.children().stream().anyMatch(child -> containsPlaceholder(child, placeholder));
    }

    private int nodeCount(NormalizedNode node) {
        int total = 1;
        for (NormalizedNode child : node.children()) {
            total += nodeCount(child);
        }
        return total;
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
