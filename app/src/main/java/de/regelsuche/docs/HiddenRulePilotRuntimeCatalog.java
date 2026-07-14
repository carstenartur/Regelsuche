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
import de.regelsuche.transform.OccurrenceAwareAstRewriteTransformationEngine;
import de.regelsuche.transform.RewriteRule;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Runtime-visible hidden-rule corpora. They contain concrete tasks and primitive
 * inventories only: no hidden rule IDs, family labels, or generalized templates.
 */
public final class HiddenRulePilotRuntimeCatalog {
    private HiddenRulePilotRuntimeCatalog() {
    }

    /** Stable five-case corpus used by held-out search-policy experiments. */
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
                "case-005", "(x * x) * (x * x)", "x^4",
                List.of("ast_product_to_power_two", "ast_combine_powers"),
                List.of(
                    new PositiveHoldout(
                        "p-009",
                        "((y + z) * (y + z)) * ((y + z) * (y + z))",
                        "(y + z)^4"),
                    new PositiveHoldout(
                        "p-010",
                        "(sin(t) * sin(t)) * (sin(t) * sin(t))",
                        "sin(t)^4")),
                List.of(
                    new NegativeHoldout("n-009", "(y * y) * (z * z)"),
                    new NegativeHoldout("n-010", "(y * y) + (y * y)"))));
    }

    /** Twenty-case corpus used by the scaled hidden-rule rediscovery benchmark. */
    public static List<RuntimeTask> benchmarkTasks() {
        List<RuntimeTask> result = new ArrayList<>(tasks());
        result.addAll(List.of(
            simpleTask(
                "case-006", "(0 + x) * 1", "x",
                List.of("ast_add_zero_left", "ast_multiply_one_right"),
                positives("p-011", "(0 + (y + z)) * 1", "y + z",
                    "p-012", "(0 + sin(t)) * 1", "sin(t)"),
                negatives("n-011", "(1 + y) * 1", "n-012", "(0 + y) * 2")),
            simpleTask(
                "case-007", "1 * (x + 0)", "x",
                List.of("ast_multiply_one_left", "ast_add_zero_right"),
                positives("p-013", "1 * ((y + z) + 0)", "y + z",
                    "p-014", "1 * (sin(t) + 0)", "sin(t)"),
                negatives("n-013", "2 * (y + 0)", "n-014", "1 * (y + 1)")),
            simpleTask(
                "case-008", "(1 * x) + 0", "x",
                List.of("ast_multiply_one_left", "ast_add_zero_right"),
                positives("p-015", "(1 * (y + z)) + 0", "y + z",
                    "p-016", "(1 * sin(t)) + 0", "sin(t)"),
                negatives("n-015", "(2 * y) + 0", "n-016", "(1 * y) + 1")),
            simpleTask(
                "case-009", "(x - 0) + 0", "x",
                List.of("ast_subtract_zero", "ast_add_zero_right"),
                positives("p-017", "((y + z) - 0) + 0", "y + z",
                    "p-018", "(sin(t) - 0) + 0", "sin(t)"),
                negatives("n-017", "(y - 1) + 0", "n-018", "(y - 0) + 1")),
            simpleTask(
                "case-010", "(x / 1) - 0", "x",
                List.of("ast_divide_one", "ast_subtract_zero"),
                positives("p-019", "((y + z) / 1) - 0", "y + z",
                    "p-020", "(sin(t) / 1) - 0", "sin(t)"),
                negatives("n-019", "(y / 2) - 0", "n-020", "(y / 1) - 1")),
            simpleTask(
                "case-011", "(0 + x) / 1", "x",
                List.of("ast_add_zero_left", "ast_divide_one"),
                positives("p-021", "(0 + (y + z)) / 1", "y + z",
                    "p-022", "(0 + sin(t)) / 1", "sin(t)"),
                negatives("n-021", "(1 + y) / 1", "n-022", "(0 + y) / 2")),
            simpleTask(
                "case-012", "1 * (x - 0)", "x",
                List.of("ast_multiply_one_left", "ast_subtract_zero"),
                positives("p-023", "1 * ((y + z) - 0)", "y + z",
                    "p-024", "1 * (sin(t) - 0)", "sin(t)"),
                negatives("n-023", "2 * (y - 0)", "n-024", "1 * (y - 1)")),
            simpleTask(
                "case-013", "((x + 0) - 0) / 1", "x",
                List.of("ast_add_zero_right", "ast_subtract_zero", "ast_divide_one"),
                positives("p-025", "(((y + z) + 0) - 0) / 1", "y + z",
                    "p-026", "((sin(t) + 0) - 0) / 1", "sin(t)"),
                negatives("n-025", "((y + 1) - 0) / 1",
                    "n-026", "((y + 0) - 1) / 1")),
            simpleTask(
                "case-014", "((1 * x) / 1) + 0", "x",
                List.of("ast_multiply_one_left", "ast_divide_one", "ast_add_zero_right"),
                positives("p-027", "((1 * (y + z)) / 1) + 0", "y + z",
                    "p-028", "((1 * sin(t)) / 1) + 0", "sin(t)"),
                negatives("n-027", "((2 * y) / 1) + 0",
                    "n-028", "((1 * y) / 2) + 0")),
            simpleTask(
                "case-015", "(x * 0) + 0", "0",
                List.of("ast_multiply_zero_right", "ast_add_zero_right"),
                positives("p-029", "((y + z) * 0) + 0", "0",
                    "p-030", "(sin(t) * 0) + 0", "0"),
                negatives("n-029", "(y * 1) + 0", "n-030", "(y * 0) + 1")),
            simpleTask(
                "case-016", "(0 * x) - 0", "0",
                List.of("ast_multiply_zero_left", "ast_subtract_zero"),
                positives("p-031", "(0 * (y + z)) - 0", "0",
                    "p-032", "(0 * sin(t)) - 0", "0"),
                negatives("n-031", "(1 * y) - 0", "n-032", "(0 * y) - 1")),
            simpleTask(
                "case-017", "(x * 0) / 1", "0",
                List.of("ast_multiply_zero_right", "ast_divide_one"),
                positives("p-033", "((y + z) * 0) / 1", "0",
                    "p-034", "(sin(t) * 0) / 1", "0"),
                negatives("n-033", "(y * 1) / 1", "n-034", "(y * 0) / 2")),
            simpleTask(
                "case-018", "(1 * 0) * x", "0",
                List.of("ast_multiply_zero_right", "ast_multiply_zero_left"),
                positives("p-035", "(1 * 0) * (y + z)", "0",
                    "p-036", "(1 * 0) * sin(t)", "0"),
                negatives("n-035", "(2 * 0) * y", "n-036", "(1 * 1) * y")),
            simpleTask(
                "case-019", "(x * x)^2", "x^4",
                List.of("ast_product_to_power_two", "ast_power_of_power"),
                positives("p-037", "((y + z) * (y + z))^2", "(y + z)^4",
                    "p-038", "(sin(t) * sin(t))^2", "sin(t)^4"),
                negatives("n-037", "(y * z)^2", "n-038", "(y * y)^3")),
            simpleTask(
                "case-020", "x^2 * x * x", "x^4",
                List.of("ast_combine_powers"),
                positives("p-039", "(y + z)^2 * (y + z) * (y + z)", "(y + z)^4",
                    "p-040", "sin(t)^2 * sin(t) * sin(t)", "sin(t)^4"),
                negatives("n-039", "y^2 * z * z", "n-040", "y^3 * y * y"))));
        return List.copyOf(result);
    }

    private static RuntimeTask case002Task() {
        List<RewriteRule> factorRules = rulesById(Set.of(
            "ast_square_difference_factor",
            "ast_canonical_normalize"));
        return new RuntimeTask(
            "case-002",
            "x^4 + 4*y^4",
            syntaxTarget(
                "(x^2 + 2*y^2 - 2*x*y) * (x^2 + 2*y^2 + 2*x*y)"),
            new HypothesisTransformationEngine(
                new OccurrenceAwareAstRewriteTransformationEngine(factorRules),
                List.of(new DifferenceOfSquaresPreparationOperator()),
                8),
            new SearchHeuristic(5, 320, 1, 12, 320, 320),
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
    }

    private static List<PositiveHoldout> positives(
        String firstId,
        String firstInput,
        String firstTarget,
        String secondId,
        String secondInput,
        String secondTarget
    ) {
        return List.of(
            new PositiveHoldout(firstId, firstInput, firstTarget),
            new PositiveHoldout(secondId, secondInput, secondTarget));
    }

    private static List<NegativeHoldout> negatives(
        String firstId,
        String firstInput,
        String secondId,
        String secondInput
    ) {
        return List.of(
            new NegativeHoldout(firstId, firstInput),
            new NegativeHoldout(secondId, secondInput));
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
            new OccurrenceAwareAstRewriteTransformationEngine(
                rulesById(Set.copyOf(primitiveRuleIds))),
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
