package de.regelsuche.transform;

import static de.regelsuche.ast.BinaryOperator.ADD;
import static de.regelsuche.ast.BinaryOperator.DIV;
import static de.regelsuche.ast.BinaryOperator.MUL;
import static de.regelsuche.ast.BinaryOperator.POW;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.ast.Expr;
import de.regelsuche.parse.ExpressionFormatter;
import de.regelsuche.parse.ExpressionParser;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ExprMatcherTest {
    private final ExpressionParser parser = new ExpressionParser();

    @Test
    void constrainedBindingCoversSympyWildExcludeSemantics() {
        ExprMatcher matcher = ExprMatcher.bind(
            "a",
            ExprMatcher.allOf(
                ExprMatcher.any(),
                ExprMatcher.not(ExprMatcher.contains(
                    ExprMatcher.anyOf(
                        ExprMatcher.literalVariable("x"),
                        ExprMatcher.literalVariable("y")
                    )
                ))
            )
        );

        ExprMatcher.MatchOutcome accepted = matcher.match(
            parser.parseTerm("3 * z"));
        ExprMatcher.MatchOutcome rejected = matcher.match(
            parser.parseTerm("3 * x"));

        assertTrue(accepted.matched());
        assertEquals(
            "3 * z",
            ExpressionFormatter.format(
                accepted.matches().getFirst().bindings().get("a"))
        );
        assertEquals(ExprMatcher.MatchStatus.NOT_MATCHED, rejected.status());
    }

    @Test
    void nestedMatchersBindWholeNodeAndInnerComponents() {
        ExprMatcher matcher = ExprMatcher.bind(
            "fraction",
            ExprMatcher.op(
                DIV,
                ExprMatcher.bind("numerator", ExprMatcher.any()),
                ExprMatcher.bind(
                    "denominator",
                    ExprMatcher.allOf(
                        ExprMatcher.numberLiteral(),
                        ExprMatcher.nonZeroNumberLiteral()
                    )
                )
            )
        );

        ExprMatcher.MatchOutcome result = matcher.match(
            parser.parseTerm("(x + 1) / 2"));

        assertTrue(result.matched());
        Map<String, Expr> bindings = result.matches().getFirst().bindings();
        assertEquals(
            "(x + 1) / 2",
            ExpressionFormatter.format(bindings.get("fraction"))
        );
        assertEquals(
            "x + 1",
            ExpressionFormatter.format(bindings.get("numerator"))
        );
        assertEquals(
            "2",
            ExpressionFormatter.format(bindings.get("denominator"))
        );
        assertFalse(matcher.match(parser.parseTerm("x / 0")).matched());
    }

    @Test
    void constraintsCanRelateIndependentBindingsModuloAc() {
        ExprMatcher matcher = ExprMatcher.where(
            ExprMatcher.op(
                ADD,
                ExprMatcher.bind("left", ExprMatcher.any()),
                ExprMatcher.bind("right", ExprMatcher.any())
            ),
            ExprMatcher.sameAs(
                "left",
                "right",
                RecognitionProfile.arithmeticAc()
            )
        );

        ExprMatcher.MatchOutcome result = matcher.match(
            parser.parseTerm("(a + b) + (b + a)"));

        assertTrue(result.matched());
        assertEquals(
            ExprMatcher.RecognitionStrength.EQUIVALENCE_AWARE,
            result.matches().getFirst().recognitionStrength()
        );
        assertFalse(matcher.match(
            parser.parseTerm("(a + b) + (b + c)"))
            .matched());
    }

    @Test
    void localPatternProfileCanBeNestedInsideExactOuterStructure() {
        PatternExpr x = PatternExpr.var("X");
        PatternExpr a = PatternExpr.var("A");
        PatternExpr square = PatternExpr.op(
            ADD,
            PatternExpr.op(
                ADD,
                PatternExpr.op(POW, x, PatternExpr.num(2)),
                PatternExpr.op(
                    MUL,
                    PatternExpr.op(MUL, PatternExpr.num(2), x),
                    a
                )
            ),
            PatternExpr.op(POW, a, PatternExpr.num(2))
        );
        ExprMatcher matcher = ExprMatcher.op(
            DIV,
            ExprMatcher.pattern(square, RecognitionProfile.arithmeticAc()),
            ExprMatcher.literalVariable("d")
        );

        ExprMatcher.MatchOutcome result = matcher.match(
            parser.parseTerm("(x^2 + a^2 + 2*a*x) / d"));

        assertTrue(result.matched());
        assertEquals(
            ExprMatcher.RecognitionStrength.EQUIVALENCE_AWARE,
            result.matches().getFirst().recognitionStrength()
        );
    }

    @Test
    void containsCanReturnBindingsFromAnExactDescendant() {
        ExprMatcher matcher = ExprMatcher.contains(ExprMatcher.bind(
            "square",
            ExprMatcher.pattern(PatternExpr.op(
                POW,
                PatternExpr.var("base"),
                PatternExpr.num(2)
            ))
        ));

        ExprMatcher.MatchOutcome result = matcher.match(
            parser.parseTerm("z + (a + b)^2"));

        assertTrue(result.matched());
        ExprMatcher.MatchResult match = result.matches().stream()
            .filter(candidate -> candidate.bindings().containsKey("square"))
            .findFirst()
            .orElseThrow();
        assertEquals(
            "(a + b)^2",
            ExpressionFormatter.format(match.bindings().get("square"))
        );
        assertEquals(
            "a + b",
            ExpressionFormatter.format(match.bindings().get("base"))
        );
    }

    @Test
    void negationCannotTurnAMatcherLimitIntoAPositiveMatch() {
        PatternExpr pattern = PatternExpr.variable("a0");
        StringBuilder expression = new StringBuilder("a8");
        for (int index = 1; index < 9; index++) {
            pattern = PatternExpr.op(
                ADD,
                pattern,
                PatternExpr.variable("a" + index)
            );
            expression.append(" + a").append(8 - index);
        }
        ExprMatcher matcher = ExprMatcher.not(ExprMatcher.pattern(
            pattern,
            RecognitionProfile.arithmeticAc()
        ));

        ExprMatcher.MatchOutcome result = matcher.match(
            parser.parseTerm(expression.toString()));

        assertEquals(ExprMatcher.MatchStatus.INCONCLUSIVE, result.status());
        assertFalse(result.matched());
        assertTrue(result.diagnostics().stream().anyMatch(diagnostic ->
            diagnostic.code().equals("COMMUTATIVE_OPERAND_LIMIT")));
    }

    @Test
    void patternExpressionsRemainUsableAsTemplatesOnly() {
        ExprTemplate template = PatternExpr.op(
            ADD,
            PatternExpr.var("left"),
            PatternExpr.var("right")
        );

        Expr expression = template.instantiate(Map.of(
            "left", parser.parseTerm("x"),
            "right", parser.parseTerm("1")
        ));

        assertEquals("x + 1", ExpressionFormatter.format(expression));
    }

    @Test
    void canonicalIdentityIncludesNestedConstraintSemantics() {
        ExprMatcher exact = ExprMatcher.bind(
            "x",
            ExprMatcher.pattern(PatternExpr.var("value"))
        );
        ExprMatcher ac = ExprMatcher.bindEquivalent(
            "x",
            ExprMatcher.pattern(PatternExpr.var("value")),
            RecognitionProfile.arithmeticAc()
        );

        assertFalse(exact.canonicalDescriptor().equals(
            ac.canonicalDescriptor()));
    }
}
