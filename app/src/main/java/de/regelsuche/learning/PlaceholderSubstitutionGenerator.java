package de.regelsuche.learning;

import de.regelsuche.ast.Expr;
import de.regelsuche.mining.ParameterRelation;
import de.regelsuche.mining.PatternBinary;
import de.regelsuche.mining.PatternFunction;
import de.regelsuche.mining.PatternVariable;
import de.regelsuche.mining.RulePatternInstantiator;
import de.regelsuche.mining.RulePatternNode;
import de.regelsuche.mining.RulePatternParser;
import de.regelsuche.parse.ExpressionFormatter;
import de.regelsuche.parse.ExpressionParser;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Generates deterministic placeholder substitution grids while preserving supported parameter relations. */
public class PlaceholderSubstitutionGenerator {
    static final List<String> GENERATED_SUBSTITUTIONS =
        List.of("x", "y", "x + 1", "2*x", "x^2", "n + 2");
    private static final int MAX_GENERATED_INSTANCES = 256;

    private final ExpressionParser expressionParser = new ExpressionParser();
    private final RulePatternParser patternParser = new RulePatternParser();
    private final RulePatternInstantiator instantiator = new RulePatternInstantiator();

    public List<Map<String, Expr>> generate(Set<String> placeholders, List<String> assumptions) {
        if (placeholders == null || placeholders.isEmpty()) {
            return List.of();
        }
        Set<String> orderedPlaceholders = new LinkedHashSet<>(placeholders);
        RelationPlan relationPlan = relationPlan(orderedPlaceholders, assumptions == null ? List.of() : assumptions);
        List<String> independentPlaceholders = orderedPlaceholders.stream()
            .filter(placeholder -> !relationPlan.equalsRelations().containsKey(placeholder))
            .toList();
        if (wouldExceedInstanceBudget(independentPlaceholders.size())) {
            throw new UnsupportedRelationException("substitution grid exceeds safe budget for placeholders " + orderedPlaceholders);
        }

        List<Map<String, Expr>> substitutions = new ArrayList<>();
        populateGrid(independentPlaceholders, 0, new LinkedHashMap<>(), relationPlan, orderedPlaceholders, substitutions);
        return substitutions;
    }

    private RelationPlan relationPlan(Set<String> placeholders, List<String> assumptions) {
        Map<String, RulePatternNode> equalsRelations = new LinkedHashMap<>();
        List<NotEqualsRelation> notEqualsRelations = new ArrayList<>();
        for (String assumption : assumptions) {
            if (assumption == null || (!assumption.contains("=") && !assumption.contains("!="))) {
                continue;
            }
            ParameterRelation relation = ParameterRelation.parse(assumption)
                .orElseThrow(() -> new UnsupportedRelationException("unsupported relation syntax: " + assumption));
            Set<String> relationPlaceholders = placeholdersIn(relation.left(), relation.right());
            if (relationPlaceholders.isEmpty()) {
                continue;
            }
            if (!placeholders.containsAll(relationPlaceholders)) {
                throw new UnsupportedRelationException("relation uses unknown placeholders: " + assumption);
            }
            if (relation.operator() == ParameterRelation.Operator.NOT_EQUALS) {
                notEqualsRelations.add(new NotEqualsRelation(patternParser.parse(relation.left()), patternParser.parse(relation.right())));
                continue;
            }
            if (!placeholders.contains(relation.left()) || !relation.left().matches("[A-Z]")) {
                throw new UnsupportedRelationException("only placeholder-target equality relations are supported: " + assumption);
            }
            if (equalsRelations.put(relation.left(), patternParser.parse(relation.right())) != null) {
                throw new UnsupportedRelationException("multiple equality relations target placeholder " + relation.left());
            }
        }
        return new RelationPlan(equalsRelations, notEqualsRelations);
    }

    private Set<String> placeholdersIn(String left, String right) {
        Set<String> placeholders = new LinkedHashSet<>();
        collectPlaceholders(patternParser.parse(left), placeholders);
        collectPlaceholders(patternParser.parse(right), placeholders);
        return placeholders;
    }

    private void populateGrid(
        List<String> independentPlaceholders,
        int index,
        Map<String, Expr> current,
        RelationPlan relationPlan,
        Set<String> allPlaceholders,
        List<Map<String, Expr>> substitutions
    ) {
        if (index < independentPlaceholders.size()) {
            String placeholder = independentPlaceholders.get(index);
            for (String sample : GENERATED_SUBSTITUTIONS) {
                current.put(placeholder, expressionParser.parseTerm(sample));
                populateGrid(independentPlaceholders, index + 1, current, relationPlan, allPlaceholders, substitutions);
                current.remove(placeholder);
            }
            return;
        }

        Map<String, Expr> completed = completeRelations(current, relationPlan, allPlaceholders);
        if (satisfiesNotEquals(completed, relationPlan.notEqualsRelations())) {
            substitutions.add(completed);
        }
    }

    private Map<String, Expr> completeRelations(Map<String, Expr> base, RelationPlan relationPlan, Set<String> allPlaceholders) {
        Map<String, Expr> completed = new LinkedHashMap<>(base);
        boolean changed;
        do {
            changed = false;
            for (Map.Entry<String, RulePatternNode> relation : relationPlan.equalsRelations().entrySet()) {
                if (!completed.containsKey(relation.getKey()) && placeholdersResolvable(relation.getValue(), completed.keySet())) {
                    completed.put(relation.getKey(), instantiator.instantiate(relation.getValue(), completed));
                    changed = true;
                }
            }
        } while (changed);

        if (!completed.keySet().containsAll(allPlaceholders)) {
            Set<String> unresolved = new LinkedHashSet<>(allPlaceholders);
            unresolved.removeAll(completed.keySet());
            throw new UnsupportedRelationException("cyclic or unresolved placeholder relations: " + unresolved);
        }
        return completed;
    }

    private boolean satisfiesNotEquals(Map<String, Expr> bindings, List<NotEqualsRelation> notEqualsRelations) {
        for (NotEqualsRelation relation : notEqualsRelations) {
            String left = ExpressionFormatter.format(instantiator.instantiate(relation.left(), bindings));
            String right = ExpressionFormatter.format(instantiator.instantiate(relation.right(), bindings));
            if (left.equals(right)) {
                return false;
            }
        }
        return true;
    }

    private boolean wouldExceedInstanceBudget(int independentPlaceholderCount) {
        int generated = 1;
        for (int index = 0; index < independentPlaceholderCount; index++) {
            generated *= GENERATED_SUBSTITUTIONS.size();
            if (generated > MAX_GENERATED_INSTANCES) {
                return true;
            }
        }
        return false;
    }

    private boolean placeholdersResolvable(RulePatternNode node, Set<String> boundPlaceholders) {
        Set<String> placeholders = new LinkedHashSet<>();
        collectPlaceholders(node, placeholders);
        return boundPlaceholders.containsAll(placeholders);
    }

    private void collectPlaceholders(RulePatternNode node, Set<String> placeholders) {
        if (node instanceof PatternVariable variable && variable.name().matches("[A-Z]")) {
            placeholders.add(variable.name());
        } else if (node instanceof PatternBinary binary) {
            collectPlaceholders(binary.left(), placeholders);
            collectPlaceholders(binary.right(), placeholders);
        } else if (node instanceof PatternFunction function) {
            for (RulePatternNode argument : function.arguments()) {
                collectPlaceholders(argument, placeholders);
            }
        }
    }

    public static class UnsupportedRelationException extends RuntimeException {
        public UnsupportedRelationException(String message) {
            super(message);
        }
    }

    private record RelationPlan(
        Map<String, RulePatternNode> equalsRelations,
        List<NotEqualsRelation> notEqualsRelations
    ) {
    }

    private record NotEqualsRelation(RulePatternNode left, RulePatternNode right) {
    }
}
