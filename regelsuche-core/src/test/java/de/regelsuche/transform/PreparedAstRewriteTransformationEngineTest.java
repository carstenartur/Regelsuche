package de.regelsuche.transform;

import static org.junit.jupiter.api.Assertions.assertEquals;

import de.regelsuche.ast.Expr;
import de.regelsuche.ast.NumberExpr;
import de.regelsuche.ast.VariableExpr;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class PreparedAstRewriteTransformationEngineTest {
    private final AstRewriteTransformationEngine reference =
        new AstRewriteTransformationEngine();
    private final PreparedAstRewriteTransformationEngine prepared =
        new PreparedAstRewriteTransformationEngine();

    @ParameterizedTest(name = "prepared parity for {0}")
    @MethodSource("expressions")
    void producesExactlyTheReferenceTransformations(String expression) {
        assertParity(reference, prepared, expression);
    }

    @Test
    void deterministicGeneratedExpressionMatrixHasExactParity() {
        for (String expression : generatedExpressions()) {
            assertParity(reference, prepared, expression);
        }
    }

    @Test
    void customGrowthAndCandidateBoundsRemainExact() {
        List<RewriteRule> rules = AstRewriteTransformationEngine.defaultRules();
        AstRewriteTransformationEngine boundedReference =
            new AstRewriteTransformationEngine(rules, 2, 3);
        PreparedAstRewriteTransformationEngine boundedPrepared =
            new PreparedAstRewriteTransformationEngine(rules, 2, 3);

        for (String expression : generatedExpressions()) {
            assertParity(boundedReference, boundedPrepared, expression);
        }
    }

    @Test
    void patternRuleSubclassesRetainTheirOverriddenContract() {
        RewriteRule overridden = new PatternRewriteRule(
            "overridden_pattern",
            PatternExpr.var("A"),
            PatternExpr.var("A")
        ) {
            @Override
            public boolean matches(Expr subtree) {
                return subtree instanceof VariableExpr variable
                    && variable.name().equals("x");
            }

            @Override
            public Expr apply(Expr subtree) {
                return new NumberExpr(7);
            }
        };
        AstRewriteTransformationEngine subclassReference =
            new AstRewriteTransformationEngine(List.of(overridden));
        PreparedAstRewriteTransformationEngine subclassPrepared =
            new PreparedAstRewriteTransformationEngine(List.of(overridden));

        assertParity(subclassReference, subclassPrepared, "x");
    }

    private static void assertParity(
        TransformationEngine expectedEngine,
        TransformationEngine actualEngine,
        String expression
    ) {
        List<Transformation> expected = expectedEngine.transform(expression);
        List<Transformation> actual = actualEngine.transform(expression);
        assertEquals(expected, actual, expression);
    }

    private static List<String> generatedExpressions() {
        List<String> atoms = List.of("x", "y", "0", "1", "2");
        Set<String> expressions = new LinkedHashSet<>(atoms);
        expressions.addAll(List.of(
            "sin(x)",
            "cos(y)",
            "f(x,y)",
            "x^2",
            "y^3"
        ));
        List<String> operators = List.of("+", "-", "*", "/", "^");

        for (int round = 0; round < 2 && expressions.size() < 160; round++) {
            List<String> leftValues = new ArrayList<>(expressions);
            int leftLimit = Math.min(leftValues.size(), round == 0 ? 10 : 24);
            for (int leftIndex = 0; leftIndex < leftLimit; leftIndex++) {
                String left = leftValues.get(leftIndex);
                for (String right : atoms) {
                    for (String operator : operators) {
                        expressions.add("(" + left + ")" + operator
                            + "(" + right + ")");
                        if (expressions.size() >= 160) {
                            return List.copyOf(expressions);
                        }
                    }
                }
            }
        }
        return List.copyOf(expressions);
    }

    private static Stream<String> expressions() {
        return Stream.of(
            "x + 0",
            "0 + x",
            "x * 1",
            "x * x",
            "(x + 1) * (x + 2)",
            "a * b + a * c",
            "a * b + c * b",
            "x^2 - y^2",
            "(x^2)^3",
            "x^2 * x^3",
            "sin(x + 0)",
            "f(x * 1, y + 0)",
            "x + x + y",
            "(x * y) / (x * 1)",
            "not valid (("
        );
    }
}
