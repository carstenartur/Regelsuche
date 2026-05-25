package de.regelsuche.mining;

import de.regelsuche.inventory.ReusableRule;
import de.regelsuche.inventory.RuleInventoryRepository;

import java.util.ArrayList;
import java.util.List;

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
        if (currentExpression == null || currentExpression.isBlank()) {
            return List.of();
        }
        List<ReusableRule> selected = new ArrayList<>();
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
            if (isGoalAligned(rule, currentExpression)) {
                selected.add(rule);
            }
        }
        return List.copyOf(selected);
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
        String pattern = rule.leftPattern();
        if (pattern == null || pattern.isBlank()) {
            return false;
        }
        // Extract significant tokens from pattern (operators and named identifiers only).
        String[] tokens = pattern.split("[\\s()^+\\-*/,]+");
        int matches = 0;
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
            if (currentExpression.contains(token)) {
                matches++;
            }
        }
        return matches > 0;
    }
}
