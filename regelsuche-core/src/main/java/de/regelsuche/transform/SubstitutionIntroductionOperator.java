package de.regelsuche.transform;

import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.BinaryOperator;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.FunctionExpr;
import de.regelsuche.ast.NumberExpr;
import de.regelsuche.ast.VariableExpr;
import de.regelsuche.input.InputRequest;
import de.regelsuche.input.InputType;
import de.regelsuche.parse.ExpressionFormatter;
import de.regelsuche.parse.ExpressionParser;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Introduces structural placeholders for repeated non-trivial subexpressions. */
public final class SubstitutionIntroductionOperator implements HypothesisOperator {
    public static final String RULE_ID = "sympy.substitution.basic.introduction";
    private static final String PACK_ID = "sympy-polynomial-basic";
    private static final String LICENSE = "BSD-3-Clause";

    private final ExpressionParser parser = new ExpressionParser();

    @Override
    public List<Transformation> generateCandidates(String expression) {
        Expr root;
        try {
            root = parser.parse(new InputRequest(InputType.TERM, expression)).terms().getFirst();
        } catch (IllegalArgumentException exception) {
            return List.of();
        }
        String original = ExpressionFormatter.format(root);
        Map<Expr, CandidateStats> stats = new LinkedHashMap<>();
        collectStats(root, 0, stats);
        List<CandidateStats> sortedCandidates = stats.values().stream()
            .filter(candidate -> candidate.occurrences() >= 2)
            .filter(candidate -> isNonTrivial(candidate.expression()))
            .sorted(Comparator.comparingInt(CandidateStats::occurrences).reversed()
                .thenComparingInt(CandidateStats::nodeCount).reversed()
                .thenComparingInt(CandidateStats::depth)
                .thenComparing(CandidateStats::formatted))
            .toList();
        List<CandidateStats> selected = selectNonOverlapping(sortedCandidates);
        if (selected.isEmpty()) {
            selected = selectDenominatorFactors(root);
        }
        if (selected.isEmpty()) {
            selected = selectPowerBases(root);
        }
        if (selected.isEmpty()) {
            return List.of();
        }

        Set<String> blockedNames = new LinkedHashSet<>(collectVariableNames(root));
        LinkedHashMap<String, String> placeholderToReplacement = new LinkedHashMap<>();
        LinkedHashMap<String, Integer> placeholderOccurrences = new LinkedHashMap<>();
        Expr substituted = root;
        for (CandidateStats candidate : selected) {
            String placeholder = SubstitutionRewriteState.nextPlaceholder(blockedNames);
            Expr replaced = replaceExact(substituted, candidate.expression(), new VariableExpr(placeholder));
            if (replaced.equals(substituted)) {
                continue;
            }
            substituted = replaced;
            placeholderToReplacement.put(placeholder, candidate.formatted());
            placeholderOccurrences.put(placeholder, candidate.occurrences());
            blockedNames.add(placeholder);
        }
        String transformed = ExpressionFormatter.format(substituted);
        if (placeholderToReplacement.isEmpty() || transformed.equals(original)) {
            return List.of();
        }
        SubstitutionRewriteState.rememberAll(placeholderToReplacement);
        List<String> assumptions = new ArrayList<>();
        assumptions.add("substitution.operator=" + RULE_ID);
        assumptions.add("substitution.substituted=" + transformed);
        for (Map.Entry<String, String> entry : placeholderToReplacement.entrySet()) {
            String placeholder = entry.getKey();
            assumptions.add("substitution.placeholder." + placeholder + "=" + entry.getValue());
            assumptions.add("substitution.occurrences." + placeholder + "=" + placeholderOccurrences.get(placeholder));
        }
        String mappingKey = placeholderToReplacement.entrySet().stream()
            .map(entry -> entry.getKey() + "=" + compact(entry.getValue()))
            .reduce((left, right) -> left + ";" + right)
            .orElse("");
        return List.of(new Transformation(
            RULE_ID,
            transformed,
            RewriteKind.NORMALIZE,
            true,
            1,
            true,
            RULE_ID + "|source=sympy-derived|pack=" + PACK_ID
                + "|mappings=" + mappingKey
                + "|substituted=" + compact(transformed),
            assumptions,
            PACK_ID,
            LICENSE
        ));
    }

    private List<CandidateStats> selectNonOverlapping(List<CandidateStats> candidates) {
        List<CandidateStats> selected = new ArrayList<>();
        for (CandidateStats candidate : candidates) {
            boolean overlaps = false;
            for (CandidateStats existing : selected) {
                if (containsSubexpression(candidate.expression(), existing.expression())
                    || containsSubexpression(existing.expression(), candidate.expression())) {
                    overlaps = true;
                    break;
                }
            }
            if (!overlaps) {
                selected.add(candidate);
            }
        }
        return selected;
    }

    private List<CandidateStats> selectDenominatorFactors(Expr expression) {
        if (!(expression instanceof BinaryExpr div)
            || div.operator() != BinaryOperator.DIV
            || !(div.left() instanceof NumberExpr numerator)
            || Double.compare(numerator.value(), 1.0) != 0) {
            return List.of();
        }
        List<Expr> factors = flattenMultiplication(div.right());
        if (factors.size() < 2) {
            return List.of();
        }
        List<CandidateStats> selected = factors.stream()
            .filter(this::isNonTrivial)
            .map(factor -> new CandidateStats(
                factor,
                ExpressionFormatter.format(factor),
                1,
                1,
                nodeCount(factor)
            ))
            .sorted(Comparator.comparingInt(CandidateStats::nodeCount).reversed()
                .thenComparing(CandidateStats::formatted))
            .limit(3)
            .toList();
        return selected.size() >= 2 ? selected : List.of();
    }

    private List<Expr> flattenMultiplication(Expr expression) {
        if (expression instanceof BinaryExpr binary && binary.operator() == BinaryOperator.MUL) {
            List<Expr> factors = new ArrayList<>();
            factors.addAll(flattenMultiplication(binary.left()));
            factors.addAll(flattenMultiplication(binary.right()));
            return factors;
        }
        return List.of(expression);
    }

    private List<CandidateStats> selectPowerBases(Expr expression) {
        Map<Expr, Integer> counts = new LinkedHashMap<>();
        collectPowerBases(expression, counts);
        return counts.entrySet().stream()
            .map(entry -> new CandidateStats(
                entry.getKey(),
                ExpressionFormatter.format(entry.getKey()),
                entry.getValue(),
                1,
                nodeCount(entry.getKey())
            ))
            .filter(candidate -> isNonTrivial(candidate.expression()))
            .sorted(Comparator.comparingInt(CandidateStats::occurrences).reversed()
                .thenComparingInt(CandidateStats::nodeCount).reversed()
                .thenComparing(CandidateStats::formatted))
            .limit(2)
            .toList();
    }

    private void collectPowerBases(Expr expression, Map<Expr, Integer> counts) {
        if (expression instanceof BinaryExpr binary) {
            if (binary.operator() == BinaryOperator.POW
                && binary.right() instanceof NumberExpr exponent
                && exponent.value() >= 2.0
                && Math.rint(exponent.value()) == exponent.value()) {
                counts.merge(binary.left(), 1, Integer::sum);
            }
            collectPowerBases(binary.left(), counts);
            collectPowerBases(binary.right(), counts);
            return;
        }
        if (expression instanceof FunctionExpr functionExpr) {
            for (Expr argument : functionExpr.arguments()) {
                collectPowerBases(argument, counts);
            }
        }
    }

    private void collectStats(Expr expression, int depth, Map<Expr, CandidateStats> stats) {
        String formatted = ExpressionFormatter.format(expression);
        int nodeCount = nodeCount(expression);
        CandidateStats existing = stats.get(expression);
        if (existing == null) {
            stats.put(expression, new CandidateStats(expression, formatted, 1, depth, nodeCount));
        } else {
            stats.put(expression, new CandidateStats(
                expression,
                existing.formatted(),
                existing.occurrences() + 1,
                Math.min(existing.depth(), depth),
                existing.nodeCount()
            ));
        }
        if (expression instanceof BinaryExpr binary) {
            collectStats(binary.left(), depth + 1, stats);
            collectStats(binary.right(), depth + 1, stats);
        } else if (expression instanceof FunctionExpr functionExpr) {
            for (Expr argument : functionExpr.arguments()) {
                collectStats(argument, depth + 1, stats);
            }
        }
    }

    private int nodeCount(Expr expression) {
        if (expression instanceof BinaryExpr binary) {
            return 1 + nodeCount(binary.left()) + nodeCount(binary.right());
        }
        if (expression instanceof FunctionExpr functionExpr) {
            int count = 1;
            for (Expr argument : functionExpr.arguments()) {
                count += nodeCount(argument);
            }
            return count;
        }
        return 1;
    }

    private boolean isNonTrivial(Expr expression) {
        return !(expression instanceof NumberExpr) && !(expression instanceof VariableExpr);
    }

    private Set<String> collectVariableNames(Expr expression) {
        LinkedHashSet<String> names = new LinkedHashSet<>();
        collectVariableNames(expression, names);
        return names;
    }

    private void collectVariableNames(Expr expression, Set<String> names) {
        if (expression instanceof VariableExpr variableExpr) {
            names.add(variableExpr.name());
            return;
        }
        if (expression instanceof BinaryExpr binary) {
            collectVariableNames(binary.left(), names);
            collectVariableNames(binary.right(), names);
            return;
        }
        if (expression instanceof FunctionExpr functionExpr) {
            for (Expr argument : functionExpr.arguments()) {
                collectVariableNames(argument, names);
            }
        }
    }

    private Expr replaceExact(Expr expression, Expr target, Expr replacement) {
        if (expression.equals(target)) {
            return replacement;
        }
        if (expression instanceof BinaryExpr binary) {
            Expr left = replaceExact(binary.left(), target, replacement);
            Expr right = replaceExact(binary.right(), target, replacement);
            if (left == binary.left() && right == binary.right()) {
                return expression;
            }
            return new BinaryExpr(left, binary.operator(), right);
        }
        if (expression instanceof FunctionExpr functionExpr) {
            List<Expr> rewritten = new ArrayList<>(functionExpr.arguments().size());
            boolean changed = false;
            for (Expr argument : functionExpr.arguments()) {
                Expr replaced = replaceExact(argument, target, replacement);
                rewritten.add(replaced);
                if (replaced != argument) {
                    changed = true;
                }
            }
            return changed ? new FunctionExpr(functionExpr.name(), rewritten) : expression;
        }
        return expression;
    }

    private boolean containsSubexpression(Expr expression, Expr target) {
        if (expression.equals(target)) {
            return true;
        }
        if (expression instanceof BinaryExpr binary) {
            return containsSubexpression(binary.left(), target) || containsSubexpression(binary.right(), target);
        }
        if (expression instanceof FunctionExpr functionExpr) {
            for (Expr argument : functionExpr.arguments()) {
                if (containsSubexpression(argument, target)) {
                    return true;
                }
            }
        }
        return false;
    }

    private String compact(String expression) {
        return expression.replace(" ", "");
    }

    private record CandidateStats(
        Expr expression,
        String formatted,
        int occurrences,
        int depth,
        int nodeCount
    ) {
    }
}
