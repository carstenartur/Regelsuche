package de.regelsuche.polynomial;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.FunctionExpr;
import de.regelsuche.math.algorithms.polynomial.NativeUnivariateFactorizationEngine;
import de.regelsuche.moves.enumerate.TreePosition;
import de.regelsuche.parse.ExactParsedSubtermProjector;
import de.regelsuche.parse.ExactParsedTerm;
import de.regelsuche.parse.ExpressionFormatter;
import de.regelsuche.parse.ExpressionParser;
import java.util.List;
import org.junit.jupiter.api.Test;

class ExactNestedFactorizationTransformationPipelineTest {
    private final ExpressionParser parser = new ExpressionParser();
    private final ExactNestedFactorizationTransformationPipeline pipeline =
        new ExactNestedFactorizationTransformationPipeline();

    @Test
    void factorsOneNestedOccurrenceAndPreservesItsSurroundings() {
        String sourceText = "f(1/2*x^2 - 1/2, y)";
        ExactParsedTerm root = parser.parseExactTerm(sourceText);
        FunctionExpr function = (FunctionExpr) root.expression();
        Expr selected = function.arguments().getFirst();
        Expr untouchedArgument = function.arguments().get(1);
        TreePosition position = new TreePosition(
            List.of(0),
            ExpressionFormatter.format(selected));

        var result = pipeline.transform(
            root,
            position,
            NativeUnivariateFactorizationEngine.boundedRationals());
        var repeated = pipeline.transform(
            parser.parseExactTerm(sourceText),
            position,
            NativeUnivariateFactorizationEngine.boundedRationals());

        assertTrue(result.transformed(), result.detailCode());
        assertTrue(result.projection().orElseThrow().successful());
        assertTrue(result.factorization().orElseThrow().executed());
        assertTrue(result.transformation().orElseThrow().transformed());
        FunctionExpr rewritten = (FunctionExpr) result.rewrittenRoot()
            .orElseThrow();
        assertSame(
            result.transformation().orElseThrow().reparsed()
                .orElseThrow().expression(),
            rewritten.arguments().getFirst());
        assertSame(untouchedArgument, rewritten.arguments().get(1));
        assertTrue(
            result.totalWork().units(
                "projection.path-navigation-steps") > 0);
        assertTrue(
            result.totalWork().units(
                "nested.replacement-ancestor-copies") > 0);
        assertTrue(
            result.totalWork().units(
                "nested.replay-structural-hash") > 0);
        assertTrue(result.totalWork().within(
            pipeline.policy().maxTotalWorkUnits()));
        assertEquals(result.certificateHash(), repeated.certificateHash());
        assertEquals(
            result.rewrittenStructuralHash().orElseThrow(),
            repeated.rewrittenStructuralHash().orElseThrow());
    }

    @Test
    void equalSubtreesRemainDifferentSelectedOccurrences() {
        ExactParsedTerm root = parser.parseExactTerm(
            "(x^2 - 1) + (x^2 - 1)");
        BinaryExpr original = (BinaryExpr) root.expression();
        assertEquals(original.left(), original.right());
        TreePosition leftPosition = new TreePosition(
            List.of(0),
            ExpressionFormatter.format(original.left()));
        TreePosition rightPosition = new TreePosition(
            List.of(1),
            ExpressionFormatter.format(original.right()));

        var left = pipeline.transform(
            root,
            leftPosition,
            NativeUnivariateFactorizationEngine.boundedRationals());
        var right = pipeline.transform(
            root,
            rightPosition,
            NativeUnivariateFactorizationEngine.boundedRationals());

        assertTrue(left.transformed(), left.detailCode());
        assertTrue(right.transformed(), right.detailCode());
        BinaryExpr leftRewritten = (BinaryExpr) left.rewrittenRoot()
            .orElseThrow();
        BinaryExpr rightRewritten = (BinaryExpr) right.rewrittenRoot()
            .orElseThrow();
        assertSame(original.right(), leftRewritten.right());
        assertSame(original.left(), rightRewritten.left());
        assertNotEquals(left.certificateHash(), right.certificateHash());
        assertNotEquals(
            left.projection().orElseThrow().selectedRange().orElseThrow(),
            right.projection().orElseThrow().selectedRange().orElseThrow());
    }

    @Test
    void rejectsAStalePositionBeforeFactorization() {
        ExactParsedTerm root = parser.parseExactTerm(
            "f(x^2 - 1, y)");
        TreePosition stale = new TreePosition(
            List.of(0),
            "x ^ 2 + 1");

        var result = pipeline.transform(
            root,
            stale,
            NativeUnivariateFactorizationEngine.boundedRationals());

        assertFalse(result.transformed());
        assertEquals(
            ExactNestedFactorizationTransformationPipeline.Status
                .POSITION_STALE,
            result.status());
        assertTrue(result.projection().isPresent());
        assertTrue(result.factorization().isEmpty());
        assertTrue(result.transformation().isEmpty());
        assertTrue(result.rewrittenRoot().isEmpty());
    }

    @Test
    void rootPathUsesTheSameReplacementAndReplayContract() {
        ExactParsedTerm root = parser.parseExactTerm(
            "1/2*x^2 - 1/2");
        TreePosition position = new TreePosition(
            List.of(),
            ExpressionFormatter.format(root.expression()));

        var result = pipeline.transform(
            root,
            position,
            NativeUnivariateFactorizationEngine.boundedRationals());

        assertTrue(result.transformed(), result.detailCode());
        assertSame(
            result.transformation().orElseThrow().reparsed()
                .orElseThrow().expression(),
            result.rewrittenRoot().orElseThrow());
        assertEquals(
            0,
            result.totalWork().units(
                "nested.replacement-ancestor-copies"));
    }

    @Test
    void refusesToStartProjectionWhenGlobalAuthorityCannotCoverIt() {
        var defaults =
            ExactNestedFactorizationTransformationPipeline.Policy
                .boundedDefaults();
        var tinyPolicy =
            new ExactNestedFactorizationTransformationPipeline.Policy(
                defaults.maxPathDepth(),
                defaults.maxRootNodes(),
                defaults.maxReplacementNodes(),
                1_000_000L,
                defaults.structuralLimits(),
                defaults.maxCandidates(),
                defaults.evidenceRequirement());
        var bounded = new ExactNestedFactorizationTransformationPipeline(
            new ExactParsedSubtermProjector(),
            new ExactParsedUnivariatePolynomialView(),
            new ExactFactorizationExpressionRenderer(),
            new ExpressionParser(),
            new ExactParsedUnivariatePolynomialView(),
            tinyPolicy);
        ExactParsedTerm root = parser.parseExactTerm("x^2 - 1");
        TreePosition position = new TreePosition(
            List.of(),
            ExpressionFormatter.format(root.expression()));

        var result = bounded.transform(
            root,
            position,
            NativeUnivariateFactorizationEngine.boundedRationals());

        assertFalse(result.transformed());
        assertEquals(
            ExactNestedFactorizationTransformationPipeline.Status
                .BUDGET_INCONCLUSIVE,
            result.status());
        assertEquals(
            "INSUFFICIENT_AUTHORITY_FOR_PROJECTION_AND_REPLAY",
            result.detailCode());
        assertTrue(result.projection().isEmpty());
        assertTrue(
            result.totalWork().units(
                "nested.root-preflight-node-visits") > 0);
    }

    @Test
    void negativeChildIndexDoesNotReachTheProjector() {
        ExactParsedTerm root = parser.parseExactTerm("x^2 - 1");
        TreePosition invalid = new TreePosition(
            List.of(-1),
            "x");

        var result = pipeline.transform(
            root,
            invalid,
            NativeUnivariateFactorizationEngine.boundedRationals());

        assertInvalidBeforeProjection(result, 1);
    }

    @Test
    void outOfRangeBinaryChildIndexDoesNotReachTheProjector() {
        ExactParsedTerm root = parser.parseExactTerm("x + 1");
        TreePosition invalid = new TreePosition(
            List.of(2),
            "unused");

        var result = pipeline.transform(
            root,
            invalid,
            NativeUnivariateFactorizationEngine.boundedRationals());

        assertInvalidBeforeProjection(result, 1);
    }

    @Test
    void descentPastLeafRemainsDistinctFromAnInvalidPath() {
        ExactParsedTerm root = parser.parseExactTerm("x + 1");
        TreePosition missing = new TreePosition(
            List.of(0, 0),
            "x");

        var result = pipeline.transform(
            root,
            missing,
            NativeUnivariateFactorizationEngine.boundedRationals());

        assertEquals(
            ExactNestedFactorizationTransformationPipeline.Status
                .POSITION_NOT_PRESENT,
            result.status());
        assertEquals("SELECTED_PATH_IS_NOT_PRESENT", result.detailCode());
        assertTrue(result.projection().isEmpty());
        assertEquals(
            2,
            result.totalWork().units(
                "nested.position-preflight-path-navigation"));
        assertEquals(
            0,
            result.totalWork().units(
                "nested.root-preflight-node-visits"));
    }

    private static void assertInvalidBeforeProjection(
        ExactNestedFactorizationTransformationPipeline.Result result,
        long pathLength
    ) {
        assertEquals(
            ExactNestedFactorizationTransformationPipeline.Status.UNSUPPORTED,
            result.status());
        assertEquals("INVALID_TREE_POSITION_PATH", result.detailCode());
        assertTrue(result.projection().isEmpty());
        assertEquals(
            pathLength,
            result.totalWork().units(
                "nested.position-preflight-path-navigation"));
        assertEquals(
            0,
            result.totalWork().units(
                "nested.root-preflight-node-visits"));
    }
}
