package de.regelsuche.parse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.FunctionExpr;
import de.regelsuche.polynomial.ExactParsedUnivariatePolynomialView;
import de.regelsuche.scalar.ExactRational;
import java.util.List;
import org.junit.jupiter.api.Test;

class ExactParsedSubtermProjectorTest {
    private final ExpressionParser parser = new ExpressionParser();
    private final ExactParsedSubtermProjector projector =
        new ExactParsedSubtermProjector();

    @Test
    void projectsANestedExactPolynomialWithoutReparseOrNewAstNodes() {
        ExactParsedTerm root = parser.parseExactTerm(
            "f(1/3*x^2 - 2/3*x + 1/3, y)");
        Expr selected = ((FunctionExpr) root.expression())
            .arguments().getFirst();
        String expected = ExpressionFormatter.format(selected);

        var first = projector.project(root, List.of(0), expected);
        var repeated = projector.project(root, List.of(0), expected);

        assertTrue(first.successful(), first.detailCode());
        ExactParsedTerm projected = first.projected().orElseThrow();
        assertSame(selected, projected.expression());
        assertEquals(
            "1/3*x^2 - 2/3*x + 1/3",
            projected.source());
        assertEquals(7, projected.literals().size());
        assertEquals(
            ExactRational.ONE,
            projected.literals().get(0).exactValue());
        assertEquals(
            ExactRational.integer(3),
            projected.literals().get(1).exactValue());
        assertEquals(
            ExactRational.integer(2),
            projected.literals().get(2).exactValue());
        assertSame(
            projected.literals().getFirst(),
            projected.literalFor(
                projected.literals().getFirst().node())
                .orElseThrow());
        assertEquals(
            new ExactParsedTerm.SourceRange(
                0,
                projected.source().length()),
            projected.rootSourceRange());
        assertTrue(
            first.work().units("projection.path-navigation-steps") > 0);
        assertTrue(
            first.work().units("projection.shifted-literal-bindings") > 0);
        assertTrue(first.work().within(projector.policy().maxWorkUnits()));
        assertEquals(first.certificateHash(), repeated.certificateHash());
        assertTrue(first.certificateHash().matches("sha256:[0-9a-f]{64}"));

        var polynomial = new ExactParsedUnivariatePolynomialView()
            .analyze(projected);
        assertTrue(polynomial.supported(), polynomial.detailCode());
    }

    @Test
    void retainsGroupingInTheProjectedSourceButNotInTheAstIdentity() {
        ExactParsedTerm root = parser.parseExactTerm(
            "a + ((x^2 - 1))");
        Expr selected = ((BinaryExpr) root.expression()).right();

        var result = projector.project(
            root,
            List.of(1),
            ExpressionFormatter.format(selected));

        assertTrue(result.successful(), result.detailCode());
        ExactParsedTerm projected = result.projected().orElseThrow();
        assertSame(selected, projected.expression());
        assertEquals("((x^2 - 1))", projected.source());
        assertEquals(
            new ExactParsedTerm.SourceRange(
                0,
                projected.source().length()),
            projected.rootSourceRange());
        assertEquals("2", projected.literals().get(0).sourceLexeme());
        assertEquals("1", projected.literals().get(1).sourceLexeme());
    }

    @Test
    void shiftsLiteralRangesIntoTheSelectedCoordinateSystem() {
        ExactParsedTerm root = parser.parseExactTerm(
            "q + ((01 / 004))");
        Expr selected = ((BinaryExpr) root.expression()).right();

        var result = projector.project(
            root,
            List.of(1),
            ExpressionFormatter.format(selected));

        ExactParsedTerm projected = result.projected().orElseThrow();
        assertEquals("((01 / 004))", projected.source());
        assertEquals(2, projected.literals().size());
        assertEquals(2, projected.literals().get(0).startInclusive());
        assertEquals(4, projected.literals().get(0).endExclusive());
        assertEquals(7, projected.literals().get(1).startInclusive());
        assertEquals(10, projected.literals().get(1).endExclusive());
        assertEquals(
            ExactRational.ONE,
            projected.literals().get(0).exactValue());
        assertEquals(
            ExactRational.integer(4),
            projected.literals().get(1).exactValue());
    }

    @Test
    void keepsEqualSubtreesAtDifferentPathsAsDifferentOccurrences() {
        ExactParsedTerm root = parser.parseExactTerm(
            "(x^2 - 1) + (x^2 - 1)");
        BinaryExpr sum = (BinaryExpr) root.expression();
        assertEquals(sum.left(), sum.right());
        assertNotSame(sum.left(), sum.right());

        var left = projector.project(
            root,
            List.of(0),
            ExpressionFormatter.format(sum.left()));
        var right = projector.project(
            root,
            List.of(1),
            ExpressionFormatter.format(sum.right()));

        assertTrue(left.successful(), left.detailCode());
        assertTrue(right.successful(), right.detailCode());
        assertEquals(
            left.projected().orElseThrow().source(),
            right.projected().orElseThrow().source());
        assertNotEquals(
            left.selectedRange().orElseThrow(),
            right.selectedRange().orElseThrow());
        assertNotEquals(left.certificateHash(), right.certificateHash());
    }

    @Test
    void rejectsAStaleFormatterSnapshotWithoutCreatingAProjection() {
        ExactParsedTerm root = parser.parseExactTerm("x^2 - 1");

        var result = projector.project(root, List.of(), "x ^ 2 + 1");

        assertFalse(result.successful());
        assertEquals(
            ExactParsedSubtermProjector.Status.POSITION_STALE,
            result.status());
        assertEquals(
            "SELECTED_POSITION_TEXT_IS_STALE",
            result.detailCode());
        assertEquals(
            "x ^ 2 - 1",
            result.actualFormattedText().orElseThrow());
        assertTrue(result.projected().isEmpty());
        assertTrue(result.selectedRange().isPresent());
        assertTrue(result.rangeCommitmentHash().isPresent());
    }

    @Test
    void distinguishesMissingPathsFromSourceFreeSyntheticNodes() {
        ExactParsedTerm ordinary = parser.parseExactTerm("x + 1");
        var missing = projector.project(ordinary, List.of(2), "missing");

        ExactParsedTerm unary = parser.parseExactTerm("-x");
        var syntheticZero = projector.project(unary, List.of(0), "0");

        assertEquals(
            ExactParsedSubtermProjector.Status.POSITION_NOT_PRESENT,
            missing.status());
        assertEquals(
            "SELECTED_PATH_IS_NOT_PRESENT",
            missing.detailCode());
        assertEquals(
            ExactParsedSubtermProjector.Status.UNSUPPORTED,
            syntheticZero.status());
        assertEquals(
            "SELECTED_OCCURRENCE_HAS_NO_SOURCE_RANGE",
            syntheticZero.detailCode());
        assertTrue(missing.projected().isEmpty());
        assertTrue(syntheticZero.projected().isEmpty());
    }

    @Test
    void projectsTheRootWithoutRetainingOuterWhitespace() {
        ExactParsedTerm root = parser.parseExactTerm("  x + 1  ");

        var result = projector.project(root, List.of(), "x + 1");

        assertTrue(result.successful(), result.detailCode());
        assertSame(root.expression(), result.projected().orElseThrow()
            .expression());
        assertEquals("x + 1", result.projected().orElseThrow().source());
        assertEquals(
            new ExactParsedTerm.SourceRange(2, 7),
            result.selectedRange().orElseThrow());
    }

    @Test
    void reportsStructuralAndWorkLimitsAsBudgetInconclusive() {
        ExactParsedTerm root = parser.parseExactTerm("x + 1");
        var noDepth = new ExactParsedSubtermProjector(
            new ExactParsedSubtermProjector.Policy(
                0,
                100,
                100,
                100,
                100,
                10_000));
        var noWork = new ExactParsedSubtermProjector(
            new ExactParsedSubtermProjector.Policy(
                10,
                100,
                100,
                100,
                100,
                1));

        var depthResult = noDepth.project(root, List.of(0), "x");
        var workResult = noWork.project(root, List.of(), "x + 1");

        assertEquals(
            ExactParsedSubtermProjector.Status.BUDGET_INCONCLUSIVE,
            depthResult.status());
        assertEquals("MAX_PATH_DEPTH_EXCEEDED", depthResult.detailCode());
        assertEquals(
            ExactParsedSubtermProjector.Status.BUDGET_INCONCLUSIVE,
            workResult.status());
        assertEquals(
            "SUBTERM_PROJECTION_WORK_BUDGET_EXCEEDED",
            workResult.detailCode());
        assertTrue(depthResult.projected().isEmpty());
        assertTrue(workResult.projected().isEmpty());
    }
}
