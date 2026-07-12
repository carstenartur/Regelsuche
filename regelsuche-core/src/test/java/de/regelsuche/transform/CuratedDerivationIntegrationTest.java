package de.regelsuche.transform;

import static de.regelsuche.ast.BinaryOperator.ADD;
import static de.regelsuche.ast.BinaryOperator.MUL;
import static de.regelsuche.ast.BinaryOperator.POW;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.parse.ExpressionFormatter;
import de.regelsuche.parse.ExpressionParser;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * End-to-end tests for known derivations using the real parser, AST rewrite
 * engine, subtree rewriting and a bounded breadth-first search.
 */
class CuratedDerivationIntegrationTest {
    private static final int MAX_VISITED_STATES = 500;
    private final ExpressionParser parser = new ExpressionParser();

    @Test
    void exactRulesFindKnownMultiStepDerivationWhenSyntaxAlreadyMatches() {
        Derivation result = derive(
            "x*x + 2*x*y + y*y",
            "(x+y)^2",
            RecognitionProfile.exact(),
            3
        );

        assertTrue(result.found());
        assertEquals("complete-square", lastRule(result));
        assertTrue(result.rules().stream().filter("product-to-square"::equals).count() >= 2);
    }

    @Test
    void acRecognitionMakesReorderedKnownDerivationReachable() {
        Derivation exact = derive(
            "x*x + y*y + 2*y*x",
            "(x+y)^2",
            RecognitionProfile.exact(),
            3
        );
        Derivation ac = derive(
            "x*x + y*y + 2*y*x",
            "(x+y)^2",
            RecognitionProfile.arithmeticAc(),
            3
        );

        assertFalse(exact.found());
        assertTrue(ac.found());
        assertEquals("complete-square", lastRule(ac));
    }

    @Test
    void algebraicRecognitionFindsScaledCompleteSquareThatAcCannotReach() {
        Derivation ac = derive(
            "x*x + 3*x*a + 2.25*a*a",
            "(x+1.5*a)^2",
            RecognitionProfile.arithmeticAc(),
            3
        );
        Derivation algebraic = derive(
            "x*x + 3*x*a + 2.25*a*a",
            "(x+1.5*a)^2",
            RecognitionProfile.algebraicAc(),
            3
        );

        assertFalse(ac.found());
        assertTrue(algebraic.found());
        assertEquals(List.of("complete-square"), algebraic.rules());
    }

    private Derivation derive(String source, String target, RecognitionProfile profile, int maxDepth) {
        AstRewriteTransformationEngine engine = new AstRewriteTransformationEngine(curatedRules(profile), 12, 80);
        String normalizedSource = format(source);
        String normalizedTarget = format(target);
        ArrayDeque<SearchNode> queue = new ArrayDeque<>();
        Set<String> visited = new HashSet<>();
        queue.add(new SearchNode(normalizedSource, List.of(), List.of()));
        visited.add(normalizedSource);

        while (!queue.isEmpty() && visited.size() <= MAX_VISITED_STATES) {
            SearchNode current = queue.removeFirst();
            if (current.expression().equals(normalizedTarget)) {
                return new Derivation(true, current.expressions(), current.rules(), visited.size());
            }
            if (current.rules().size() >= maxDepth) {
                continue;
            }
            for (Transformation transformation : engine.transform(current.expression())) {
                String next = transformation.transformedExpression();
                if (!visited.add(next)) {
                    continue;
                }
                List<String> expressions = new ArrayList<>(current.expressions());
                expressions.add(next);
                List<String> rules = new ArrayList<>(current.rules());
                rules.add(transformation.rule());
                queue.addLast(new SearchNode(next, List.copyOf(expressions), List.copyOf(rules)));
            }
        }
        return new Derivation(false, List.of(), List.of(), visited.size());
    }

    private List<RewriteRule> curatedRules(RecognitionProfile profile) {
        PatternExpr value = PatternExpr.var("V");
        return List.of(
            new PatternRewriteRule(
                "product-to-square",
                PatternExpr.op(MUL, value, value),
                PatternExpr.op(POW, value, PatternExpr.num(2))
            ),
            completeSquare(profile)
        );
    }

    private PatternRewriteRule completeSquare(RecognitionProfile profile) {
        PatternExpr x = PatternExpr.var("X");
        PatternExpr a = PatternExpr.var("A");
        PatternExpr source = PatternExpr.op(
            ADD,
            PatternExpr.op(
                ADD,
                PatternExpr.op(POW, x, PatternExpr.num(2)),
                PatternExpr.op(MUL, PatternExpr.op(MUL, PatternExpr.num(2), x), a)
            ),
            PatternExpr.op(POW, a, PatternExpr.num(2))
        );
        PatternExpr target = PatternExpr.op(
            POW,
            PatternExpr.op(ADD, x, a),
            PatternExpr.num(2)
        );
        return new PatternRewriteRule("complete-square", source, target, profile);
    }

    private String format(String expression) {
        return ExpressionFormatter.format(parser.parseTerm(expression));
    }

    private static String lastRule(Derivation derivation) {
        return derivation.rules().get(derivation.rules().size() - 1);
    }

    private record SearchNode(String expression, List<String> expressions, List<String> rules) {
    }

    private record Derivation(boolean found, List<String> expressions, List<String> rules, int visitedStates) {
    }
}
