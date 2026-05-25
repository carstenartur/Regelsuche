package de.regelsuche.mining;

import de.regelsuche.inventory.ReusableRule;
import de.regelsuche.inventory.RuleInventoryRepository;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Selects which macro moves from the inventory are appropriate for the
 * current search state, based on:
 *
 * <ol>
 *   <li>Cost reduction: only rules that produced positive average improvement.</li>
 *   <li>Goal alignment: only rules whose left pattern looks structurally
 *       similar to the current expression (simple prefix/substring match
 *       on the canonical pattern).</li>
 *   <li>Frequency threshold: only rules that have been applied at least
 *       {@code minOccurrences} times (already satisfied at inventory time, but
 *       the selector can tighten the filter).</li>
 *   <li>Confidence threshold: only rules above a configurable confidence score.</li>
 * </ol>
 *
 * <p>This class is intentionally stateless so it can be reused across
 * parallel search workers.</p>
 */
public class GoalAwareMacroMoveSelector {

    /** Minimum confidence score for a rule to be eligible. */
    public static final double DEFAULT_MIN_CONFIDENCE = 0.5;

    /** Minimum average improvement for a rule to be eligible. */
    public static final double DEFAULT_MIN_IMPROVEMENT = 0.0;

    /** Minimum occurrence count for a rule to be eligible. */
    public static final int DEFAULT_MIN_OCCURRENCES = 1;

    private final RuleInventoryRepository inventory;
    private final double minConfidence;
    private final double minImprovement;
    private final int minOccurrences;

    public GoalAwareMacroMoveSelector(RuleInventoryRepository inventory) {
        this(inventory, DEFAULT_MIN_CONFIDENCE, DEFAULT_MIN_IMPROVEMENT, DEFAULT_MIN_OCCURRENCES);
    }

    public GoalAwareMacroMoveSelector(
        RuleInventoryRepository inventory,
        double minConfidence,
        double minImprovement,
        int minOccurrences
    ) {
        if (inventory == null) {
            throw new IllegalArgumentException("inventory must not be null");
        }
        this.inventory = inventory;
        this.minConfidence = minConfidence;
        this.minImprovement = minImprovement;
        this.minOccurrences = minOccurrences;
    }

    /**
     * Returns the subset of enabled inventory rules that are worth trying
     * for the given current expression.
     *
     * @param currentExpression  the expression at the current search node
     * @return candidate macro rules that may reduce cost for this expression
     */
    public List<ReusableRule> selectFor(String currentExpression) {
        return selectFor(currentExpression, null);
    }

    /**
     * Returns eligible macro rules sorted by score. If {@code goalExpression}
     * is provided, candidates must also overlap structurally with that target
     * (or expose tokens that may enable additional rules).
     */
    public List<ReusableRule> selectFor(String currentExpression, String goalExpression) {
        if (currentExpression == null || currentExpression.isBlank()) {
            return List.of();
        }
        List<ScoredMacroMove> selected = new ArrayList<>();
        for (ReusableRule rule : inventory.findAll()) {
            if (!inventory.isEnabled(rule.id())) {
                continue;
            }
            if (rule.confidenceScore() < minConfidence) {
                continue;
            }
            if (rule.averageImprovement() <= minImprovement) {
                continue;
            }
            if (rule.occurrenceCount() < minOccurrences) {
                continue;
            }
            double score = score(rule, currentExpression, goalExpression);
            if (score > 0.0) {
                selected.add(new ScoredMacroMove(rule, score));
            }
        }
        selected.sort(Comparator.comparingDouble(ScoredMacroMove::score).reversed());
        return selected.stream().map(ScoredMacroMove::rule).toList();
    }

    /** Returns a numeric score for diagnostics and tests; 0 means rejected. */
    public double score(ReusableRule rule, String currentExpression, String goalExpression) {
        if (rule == null || currentExpression == null || currentExpression.isBlank()) {
            return 0.0;
        }
        int currentOverlap = structuralOverlap(rule.leftPattern(), currentExpression);
        if (currentOverlap <= 0) {
            return 0.0;
        }
        int currentNamedOverlap = namedStructuralOverlap(rule.leftPattern(), currentExpression);
        if (currentNamedOverlap <= 0 && hasNamedTokens(rule.leftPattern())) {
            return 0.0;
        }
        int goalOverlap = goalExpression == null || goalExpression.isBlank()
            ? 0
            : Math.max(
                structuralOverlap(rule.rightPattern(), goalExpression),
                structuralOverlap(rule.leftPattern(), goalExpression)
            );
        int namedGoalOverlap = goalExpression == null || goalExpression.isBlank()
            ? 0
            : Math.max(
                namedStructuralOverlap(rule.rightPattern(), goalExpression),
                namedStructuralOverlap(rule.leftPattern(), goalExpression)
            );
        boolean exposesNewApplicableRules = exposesNewApplicableRules(rule, currentExpression);
        if (goalExpression != null && !goalExpression.isBlank() && namedGoalOverlap <= 0 && !exposesNewApplicableRules) {
            return 0.0;
        }
        double exposureBonus = exposesNewApplicableRules ? 1.0 : 0.0;
        return (rule.confidenceScore() * 10.0)
            + Math.max(0.0, rule.averageImprovement())
            + Math.log1p(rule.occurrenceCount())
            + currentOverlap
            + (2.0 * goalOverlap)
            + exposureBonus;
    }

    /**
     * Heuristic goal-alignment check.
     *
     * <p>A rule is considered goal-aligned when the structural skeleton of
     * its left pattern shares operator tokens or named identifiers with the
     * current expression. Pure integer literals are excluded to avoid
     * spurious matches (every expression "contains" the number 1 or 2).</p>
     */
    private boolean isGoalAligned(ReusableRule rule, String currentExpression) {
        return structuralOverlap(rule.leftPattern(), currentExpression) > 0;
    }

    private int structuralOverlap(String pattern, String expression) {
        if (pattern == null || pattern.isBlank() || expression == null || expression.isBlank()) {
            return 0;
        }
        Set<String> expressionTokens = tokens(expression);
        int matches = 0;
        for (String token : tokens(pattern)) {
            if (expressionTokens.contains(token)) {
                matches++;
            }
        }
        return matches;
    }

    private int namedStructuralOverlap(String pattern, String expression) {
        Set<String> expressionTokens = tokens(expression);
        int matches = 0;
        for (String token : tokens(pattern)) {
            if (!token.startsWith("op:") && expressionTokens.contains(token)) {
                matches++;
            }
        }
        return matches;
    }

    private boolean exposesNewApplicableRules(ReusableRule rule, String currentExpression) {
        Set<String> current = tokens(currentExpression);
        Set<String> produced = tokens(rule.rightPattern());
        produced.removeAll(current);
        return !produced.isEmpty();
    }

    private boolean hasNamedTokens(String expression) {
        return tokens(expression).stream().anyMatch(token -> !token.startsWith("op:"));
    }

    private Set<String> tokens(String expression) {
        Set<String> result = new LinkedHashSet<>();
        for (String operator : List.of("+", "-", "*", "/", "^")) {
            if (expression.contains(operator)) {
                result.add("op:" + operator);
            }
        }
        // Extract significant tokens from pattern (operators and named identifiers only).
        String[] tokens = expression.split("[\\s()^+\\-*/,]+");
        for (String token : tokens) {
            if (token.isEmpty()) {
                continue;
            }
            // Skip single-uppercase-letter placeholders (A, B, C, ...) and N\d+ placeholders.
            if (token.matches("[A-Z]") || token.matches("N\\d+")) {
                continue;
            }
            // Skip pure integer literals — they appear in almost any expression and cause
            // false-positive goal-alignment matches.
            if (token.matches("-?\\d+")) {
                continue;
            }
            result.add(token);
        }
        return result;
    }

    public record ScoredMacroMove(ReusableRule rule, double score) {
    }
}
