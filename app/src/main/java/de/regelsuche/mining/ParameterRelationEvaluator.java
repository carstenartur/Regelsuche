package de.regelsuche.mining;

import de.regelsuche.ast.BinaryOperator;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.NumberExpr;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;

public class ParameterRelationEvaluator {
    private final RulePatternParser parser = new RulePatternParser();

    public Set<String> relationTargets(List<String> parameterRelations) {
        Set<String> targets = new LinkedHashSet<>();
        for (String relation : parameterRelations) {
            parseRelation(relation).ifPresent(parsed -> targets.add(parsed.target()));
        }
        return targets;
    }

    public Map<String, Expr> completeBindings(
        Set<String> placeholders,
        Map<String, Integer> baseBindings,
        List<String> parameterRelations
    ) {
        Map<String, Integer> numericBindings = new LinkedHashMap<>(baseBindings);
        Map<String, RulePatternNode> relations = new LinkedHashMap<>();
        for (String relation : parameterRelations) {
            parseRelation(relation).ifPresent(parsed -> relations.put(parsed.target(), parsed.expression()));
        }

        boolean changed;
        do {
            changed = false;
            for (Map.Entry<String, RulePatternNode> relation : relations.entrySet()) {
                if (!numericBindings.containsKey(relation.getKey())) {
                    OptionalInt evaluated = evaluate(relation.getValue(), numericBindings);
                    if (evaluated.isPresent()) {
                        numericBindings.put(relation.getKey(), evaluated.getAsInt());
                        changed = true;
                    }
                }
            }
        } while (changed);

        Map<String, Expr> result = new HashMap<>();
        for (String placeholder : placeholders) {
            Integer value = numericBindings.get(placeholder);
            if (value == null) {
                throw new IllegalArgumentException("Missing binding for placeholder " + placeholder);
            }
            result.put(placeholder, new NumberExpr(value));
        }
        return result;
    }

    private OptionalInt evaluate(RulePatternNode node, Map<String, Integer> bindings) {
        if (node instanceof PatternNumber number) {
            return OptionalInt.of(number.value());
        }
        if (node instanceof PatternVariable variable) {
            Integer value = bindings.get(variable.name());
            return value == null ? OptionalInt.empty() : OptionalInt.of(value);
        }
        if (node instanceof PatternFunction) {
            // Function patterns cannot be reduced to integer parameter relations.
            return OptionalInt.empty();
        }
        PatternBinary binary = (PatternBinary) node;
        OptionalInt left = evaluate(binary.left(), bindings);
        OptionalInt right = evaluate(binary.right(), bindings);
        if (left.isEmpty() || right.isEmpty()) {
            return OptionalInt.empty();
        }
        int leftValue = left.getAsInt();
        int rightValue = right.getAsInt();
        return switch (binary.op()) {
            case ADD -> OptionalInt.of(leftValue + rightValue);
            case SUB -> OptionalInt.of(leftValue - rightValue);
            case MUL -> OptionalInt.of(leftValue * rightValue);
            case DIV -> rightValue != 0 && leftValue % rightValue == 0 ? OptionalInt.of(leftValue / rightValue) : OptionalInt.empty();
            case POW -> rightValue >= 0 ? OptionalInt.of((int) Math.pow(leftValue, rightValue)) : OptionalInt.empty();
        };
    }

    private java.util.Optional<ParsedRelation> parseRelation(String relation) {
        int separator = relation.indexOf('=');
        if (separator <= 0 || separator == relation.length() - 1) {
            return java.util.Optional.empty();
        }
        String target = relation.substring(0, separator).trim();
        String expression = relation.substring(separator + 1).trim();
        if (target.isBlank() || expression.isBlank()) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.of(new ParsedRelation(target, parser.parse(expression)));
    }

    private record ParsedRelation(String target, RulePatternNode expression) {
    }
}
