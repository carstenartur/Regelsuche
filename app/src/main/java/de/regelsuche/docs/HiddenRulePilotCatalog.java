package de.regelsuche.docs;

import de.regelsuche.docs.HiddenRulePilotCampaign.PilotCase;
import de.regelsuche.docs.HiddenRulePilotEvaluator.HiddenReference;
import de.regelsuche.docs.HiddenRulePilotRunner.NegativeHoldout;
import de.regelsuche.docs.HiddenRulePilotRunner.PositiveHoldout;
import de.regelsuche.docs.HiddenRulePilotRunner.RuntimeTask;
import de.regelsuche.search.SearchHeuristic;
import de.regelsuche.search.strategy.SearchProblem.SearchTarget;
import de.regelsuche.transform.AstRewriteTransformationEngine;
import de.regelsuche.transform.DifferenceOfSquaresPreparationOperator;
import de.regelsuche.transform.HypothesisTransformationEngine;
import de.regelsuche.transform.RewriteRule;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Fixed pilot corpus. Runtime tasks and hidden references remain separate record
 * fields; {@link HiddenRulePilotRunner} receives only {@link PilotCase#task()}.
 */
public final class HiddenRulePilotCatalog {
    private HiddenRulePilotCatalog() {
    }

    public static List<PilotCase> cases() {
        return List.of(
            neutralElementCase(),
            sophieGermainCase(),
            simpleCase(
                "case-003", "(x * 1) + 0", "x",
                List.of("ast_multiply_one_right", "ast_add_zero_right"),
                List.of(
                    new PositiveHoldout("p-005", "((y + z) * 1) + 0", "y + z"),
                    new PositiveHoldout("p-006", "(sin(t) * 1) + 0", "sin(t)")),
                List.of(
                    new NegativeHoldout("n-005", "(y * 2) + 0"),
                    new NegativeHoldout("n-006", "(y * 1) + 1")),
                reference(
                    "hidden_multiply_then_add_neutral_macro",
                    "neutral-element-simplification",
                    "(A * 1) + 0", "A")),
            simpleCase(
                "case-004", "(x - 0) / 1", "x",
                List.of("ast_subtract_zero", "ast_divide_one"),
                List.of(
                    new PositiveHoldout("p-007", "((y + z) - 0) / 1", "y + z"),
                    new PositiveHoldout("p-008", "(sin(t) - 0) / 1", "sin(t)")),
                List.of(
                    new NegativeHoldout("n-007", "(y - 1) / 1"),
                    new NegativeHoldout("n-008", "(y - 0) / 2")),
                reference(
                    "hidden_subtract_then_divide_neutral_macro",
                    "neutral-element-simplification",
                    "(A - 0) / 1", "A")),
            simpleCase(
                "case-005", "(x * x) * x", "x^3",
                List.of("ast_product_to_power_two", "ast_combine_powers"),
                List.of(
                    new PositiveHoldout(
                        "p-009", "((y + z) * (y + z)) * (y + z)", "(y + z)^3"),
                    new PositiveHoldout(
                        "p-010", "(sin(t) * sin(t)) * sin(t)", "sin(t)^3")),
                List.of(
                    new NegativeHoldout("n-009", "(y * y) * z"),
                    new NegativeHoldout("n-010", "(y * y) + y")),
                reference(
                    "hidden_cube_normalization_macro",
                    "power-normalization",
                    "(A * A) * A", "A^3")));
    }

    private static PilotCase neutralElementCase() {
        return simpleCase(
            "case-001", "(x + 0) * 1", "x",
            List.of("ast_add_zero_right", "ast_multiply_one_right"),
            List.of(
                new PositiveHoldout("p-001", "((y + z) + 0) * 1", "y + z"),
                new PositiveHoldout("p-002", "(sin(t) + 0) * 1", "sin(t)")),
            List.of(
                new NegativeHoldout("n-001", "(y + 1) * 1"),
                new NegativeHoldout("n-002", "(y + 0) * 2")),
            reference(
                "hidden_neutral_element_macro",
                "neutral-element-simplification",
                "(A + 0) * 1", "A"));
    }

    private static PilotCase sophieGermainCase() {
        RuntimeTask task = new RuntimeTask(
            "case-002",
            "x^4 + 4*y^4",
            SearchTarget.valueEquivalent(
                "(x^2 + 2*x*y + 2*y^2) * (x^2 - 2*x*y + 2*y^2)"),
            new HypothesisTransformationEngine(
                new AstRewriteTransformationEngine(),
                List.of(new DifferenceOfSquaresPreparationOperator()),
                8),
            new SearchHeuristic(4, 240, 1, 12, 240, 240),
            List.of(
                new PositiveHoldout(
                    "p-003",
                    "(m + 1)^4 + 4*n^4",
                    "((m + 1)^2 + 2*(m + 1)*n + 2*n^2)"
                        + " * ((m + 1)^2 - 2*(m + 1)*n + 2*n^2)"),
                new PositiveHoldout(
                    "p-004",
                    "sin(t)^4 + 4*z^4",
                    "(sin(t)^2 + 2*sin(t)*z + 2*z^2)"
                        + " * (sin(t)^2 - 2*sin(t)*z + 2*z^2)")),
            List.of(
                new NegativeHoldout("n-003", "x^4 + 3*y^4"),
                new NegativeHoldout("n-004", "x^4 + 4*y^3")));
        return new PilotCase(task, reference(
            "hidden_sophie_germain_macro",
            "quartic-factorization",
            "A^4 + 4*B^4",
            "(A^2 + 2*A*B + 2*B^2) * (A^2 - 2*A*B + 2*B^2)"));
    }

    private static PilotCase simpleCase(
        String id,
        String input,
        String target,
        List<String> primitiveRuleIds,
        List<PositiveHoldout> positives,
        List<NegativeHoldout> negatives,
        HiddenReference reference
    ) {
        Set<String> ids = Set.copyOf(primitiveRuleIds);
        List<RewriteRule> rules = AstRewriteTransformationEngine.defaultRules().stream()
            .filter(rule -> ids.contains(rule.id()))
            .toList();
        Set<String> actual = rules.stream().map(RewriteRule::id).collect(Collectors.toSet());
        if (!ids.equals(actual)) {
            throw new IllegalStateException("pilot primitive rule set is incomplete: " + ids + " != " + actual);
        }
        RuntimeTask task = new RuntimeTask(
            id,
            input,
            SearchTarget.valueEquivalent(target),
            new AstRewriteTransformationEngine(rules),
            new SearchHeuristic(4, 80, 1, 8, 40, 20),
            positives,
            negatives);
        return new PilotCase(task, reference);
    }

    private static HiddenReference reference(
        String id,
        String family,
        String left,
        String right
    ) {
        return new HiddenReference(id, family, left, right, List.of(), List.of(family));
    }
}
