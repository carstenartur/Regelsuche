package de.regelsuche.docs;

import de.regelsuche.docs.HiddenRulePilotRunner.NegativeHoldout;
import de.regelsuche.docs.HiddenRulePilotRunner.PositiveHoldout;
import de.regelsuche.docs.HiddenRulePilotRunner.RuntimeTask;
import de.regelsuche.parse.ExpressionFormatter;
import de.regelsuche.parse.ExpressionParser;
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
 * Runtime-visible five-case corpus. It contains concrete tasks and primitive
 * inventories only: no hidden rule IDs, family labels, or generalized templates.
 */
public final class HiddenRulePilotRuntimeCatalog {
    private HiddenRulePilotRuntimeCatalog() {
    }

    public static List<RuntimeTask> tasks() {
        return List.of(
            simpleTask(
                "case-001", "(x + 0) * 1", "x",
                List.of("ast_add_zero_right", "ast_multiply_one_right"),
                List.of(
                    new PositiveHoldout("p-001", "((y + z) + 0) * 1", "y + z"),
                    new PositiveHoldout("p-002", "(sin(t) + 0) * 1", "sin(t)")),
                List.of(
                    new NegativeHoldout("n-001", "(y + 1) * 1"),
                    new NegativeHoldout("n-002", "(y + 0) * 2"))),
            case002Task(),
            simpleTask(
                "case-003", "(x * 1) + 0", "x",
                List.of("ast_multiply_one_right", "ast_add_zero_right"),
                List.of(
                    new PositiveHoldout("p-005", "((y + z) * 1) + 0", "y + z"),
                    new PositiveHoldout("p-006", "(sin(t) * 1) + 0", "sin(t)")),
                List.of(
                    new NegativeHoldout("n-005", "(y * 2) + 0"),
                    new NegativeHoldout("n-006", "(y * 1) + 1"))),
            simpleTask(
                "case-004", "(x - 0) / 1", "x",
                List.of("ast_subtract_zero", "ast_divide_one"),
                List.of(
                    new PositiveHoldout("p-007", "((y + z) - 0) / 1", "y + z"),
                    new PositiveHoldout("p-008", "(sin(t) - 0) / 1", "sin(t)")),
                List.of(
                    new NegativeHoldout("n-007", "(y - 1) / 1"),
                    new NegativeHoldout("n-008", "(y - 0) / 2"))),
            simpleTask(
                "case-005", "(x * x) * x", "x^3",
                List.of("ast_product_to_power_two", "ast_combine_powers"),
                List.of(
                    new PositiveHoldout(
                        "p-009", "((y + z) * (y + z)) * (y + z)", "(y + z)^3"),
                    new PositiveHoldout(
                        "p-010", "(sin(t) * sin(t)) * sin(t)", "sin(t)^3")),
                List.of(
                    new NegativeHoldout("n-009", "(y * y) * z"),
                    new NegativeHoldout("n-010", "(y * y) + y"))));
    }

    private static RuntimeTask case002Task() {
        List<RewriteRule> factorRules = rulesById(Set.of(
            "ast_square_difference_factor",
            "ast_canonical_normalize"));
        return new RuntimeTask(
            "case-002",
            "x^4 + 4*y^4",
            syntaxTarget(
                "(x^2 - 2*x*y + 2*y^2) * (x^2 + 2*x*y + 2*y^2)"),
            new HypothesisTransformationEngine(
                new AstRewriteTransformationEngine(factorRules),
                List.of(new DifferenceOfSquaresPreparationOperator()),
                8),
            new SearchHeuristic(5, 320, 1, 12, 320, 320),
            List.of(
                new PositiveHoldout(
                    "p-003",
                    "(m + 1)^4 + 4*n^4",
                    "((m + 1)^2 - 2*(m + 1)*n + 2*n^2)"
                        + " * ((m + 1)^2 + 2*(m + 1)*n + 2*n^2)"),
                new PositiveHoldout(
                    "p-004",
                    "sin(t)^4 + 4*z^4",
                    "(sin(t)^2 - 2*sin(t)*z + 2*z^2)"
                        + " * (sin(t)^2 + 2*sin(t)*z + 2*z^2)")),
            List.of(
                new NegativeHoldout("n-003", "x^4 + 3*y^4"),
                new NegativeHoldout("n-004", "x^4 + 4*y^3")));
    }

    private static RuntimeTask simpleTask(
        String id,
        String input,
        String target,
        List<String> primitiveRuleIds,
        List<PositiveHoldout> positives,
        List<NegativeHoldout> negatives
    ) {
        return new RuntimeTask(
            id,
            input,
            syntaxTarget(target),
            new AstRewriteTransformationEngine(rulesById(Set.copyOf(primitiveRuleIds))),
            new SearchHeuristic(4, 80, 1, 8, 40, 20),
            positives,
            negatives);
    }

    private static List<RewriteRule> rulesById(Set<String> ids) {
        List<RewriteRule> rules = AstRewriteTransformationEngine.defaultRules().stream()
            .filter(rule -> ids.contains(rule.id()))
            .toList();
        Set<String> actual = rules.stream().map(RewriteRule::id).collect(Collectors.toSet());
        if (!ids.equals(actual)) {
            throw new IllegalStateException(
                "pilot primitive rule set is incomplete: " + ids + " != " + actual);
        }
        return rules;
    }

    private static SearchTarget syntaxTarget(String expression) {
        String formatted = ExpressionFormatter.format(new ExpressionParser().parseTerm(expression));
        return SearchTarget.syntaxExact(formatted);
    }
}
