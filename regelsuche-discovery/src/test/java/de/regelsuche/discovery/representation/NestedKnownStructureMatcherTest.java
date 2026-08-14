package de.regelsuche.discovery.representation;

import static de.regelsuche.ast.BinaryOperator.ADD;
import static de.regelsuche.ast.BinaryOperator.DIV;
import static de.regelsuche.ast.BinaryOperator.MUL;
import static de.regelsuche.ast.BinaryOperator.POW;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.transform.ExprMatcher;
import de.regelsuche.transform.PatternExpr;
import de.regelsuche.transform.RecognitionProfile;
import java.util.List;
import org.junit.jupiter.api.Test;

class NestedKnownStructureMatcherTest {
    @Test
    void catalogEntryCanBindWholeAndNestedComponents() {
        KnownStructure rationalWithNumericDenominator = new KnownStructure(
            "numeric-denominator",
            "rational",
            ExprMatcher.bind(
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
            ),
            List.of(),
            List.of("backend:rational-normalizer"),
            "first-party"
        );
        KnownStructureMatcher matcher = new KnownStructureMatcher(
            new KnownStructureCatalog(
                "nested-v1",
                List.of(rationalWithNumericDenominator)
            )
        );

        KnownStructureMatch match = matcher.match("z + (x + 1) / 2").stream()
            .filter(candidate -> candidate.structureId().equals(
                "numeric-denominator"))
            .findFirst()
            .orElseThrow();

        assertEquals("(x + 1) / 2", match.bindings().get("fraction"));
        assertEquals("x + 1", match.bindings().get("numerator"));
        assertEquals("2", match.bindings().get("denominator"));
        assertEquals(new ExpressionOccurrencePath(List.of(1)),
            match.occurrencePath());
    }

    @Test
    void nestedAcMatcherRecognizesKnownFormInsideExactOuterShape() {
        PatternExpr x = PatternExpr.var("x");
        PatternExpr a = PatternExpr.var("a");
        PatternExpr completeSquare = PatternExpr.op(
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
        KnownStructure structure = new KnownStructure(
            "square-over-d",
            "algebra",
            ExprMatcher.op(
                DIV,
                ExprMatcher.pattern(
                    completeSquare,
                    RecognitionProfile.arithmeticAc()
                ),
                ExprMatcher.literalVariable("d")
            ),
            List.of(),
            List.of("rule:complete-square"),
            "first-party"
        );
        KnownStructureMatcher matcher = new KnownStructureMatcher(
            new KnownStructureCatalog("nested-v1", List.of(structure))
        );

        KnownStructureMatch match = matcher.match(
            "(x^2 + a^2 + 2*a*x) / d").getFirst();

        assertEquals(
            KnownStructureMatch.RECOGNITION_EQUIVALENCE_AWARE,
            match.recognitionMode()
        );
        assertTrue(match.wholeExpression());
    }

    @Test
    void relationalConstraintCanCompareSeparatelyBoundSubtrees() {
        KnownStructure repeatedModuloAc = new KnownStructure(
            "repeated-sum",
            "algebra",
            ExprMatcher.where(
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
            ),
            List.of(),
            List.of("rule:double-term"),
            "first-party"
        );
        KnownStructureMatcher matcher = new KnownStructureMatcher(
            new KnownStructureCatalog(
                "nested-v1",
                List.of(repeatedModuloAc)
            )
        );

        assertFalse(matcher.match("(a + b) + (b + a)").isEmpty());
        assertTrue(matcher.match("(a + b) + (b + c)").isEmpty());
    }

    @Test
    void recognitionLimitsRemainVisibleAndStrictApiFailsClosed() {
        PatternExpr pattern = PatternExpr.var("A0");
        StringBuilder expression = new StringBuilder("a8");
        for (int index = 1; index < 9; index++) {
            pattern = PatternExpr.op(
                ADD,
                pattern,
                PatternExpr.var("A" + index)
            );
            expression.append(" + a").append(8 - index);
        }
        KnownStructure wide = new KnownStructure(
            "wide-ac",
            "algebra",
            pattern,
            RecognitionProfile.arithmeticAc(),
            List.of(),
            List.of(),
            "first-party"
        );
        KnownStructureMatcher matcher = new KnownStructureMatcher(
            new KnownStructureCatalog("nested-v1", List.of(wide))
        );

        KnownStructureMatcher.ScanResult scan = matcher.scan(
            expression.toString());

        assertFalse(scan.complete());
        assertTrue(scan.diagnostics().stream().anyMatch(diagnostic ->
            diagnostic.code().equals("COMMUTATIVE_OPERAND_LIMIT")));
        assertThrows(
            KnownStructureMatcher.IncompleteRecognitionException.class,
            () -> matcher.match(expression.toString())
        );
    }
}
