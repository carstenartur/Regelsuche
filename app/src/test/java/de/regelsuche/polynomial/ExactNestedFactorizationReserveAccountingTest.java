package de.regelsuche.polynomial;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.math.algorithms.polynomial.NativeUnivariateFactorizationEngine;
import de.regelsuche.moves.enumerate.TreePosition;
import de.regelsuche.parse.ExactParsedSubtermProjector;
import de.regelsuche.parse.ExactParsedTerm;
import de.regelsuche.parse.ExpressionFormatter;
import de.regelsuche.parse.ExpressionParser;
import java.util.List;
import org.junit.jupiter.api.Test;

class ExactNestedFactorizationReserveAccountingTest {
    private final ExpressionParser parser = new ExpressionParser();

    @Test
    void accountsBothFormattedSnapshotsAndStructuralPayloadBytes() {
        String variable = "payloadVariable";
        ExactParsedTerm root = parser.parseExactTerm(variable + "^2 - 1");
        String formatted = ExpressionFormatter.format(root.expression());
        TreePosition position = new TreePosition(List.of(), formatted);
        var pipeline = new ExactNestedFactorizationTransformationPipeline();

        var result = pipeline.transform(
            root,
            position,
            NativeUnivariateFactorizationEngine.boundedRationals());

        assertTrue(result.transformed(), result.detailCode());
        assertEquals(
            2L * formatted.length(),
            result.totalWork().units(
                "nested.application-staleness-text-code-units"));
        long variableBytes = variable.getBytes(UTF_8).length;
        assertTrue(
            result.totalWork().units(
                "nested.rewritten-structural-hash-payload-utf8-bytes")
                >= variableBytes);
        assertTrue(
            result.totalWork().units(
                "nested.replay-structural-hash-payload-utf8-bytes")
                >= variableBytes);
        assertTrue(result.totalWork().within(
            pipeline.policy().maxTotalWorkUnits()));
    }

    @Test
    void refusesBeforeProjectionWhenReplayPayloadCeilingDoesNotFit() {
        var projector = new ExactParsedSubtermProjector(
            new ExactParsedSubtermProjector.Policy(
                0,
                100_000,
                16,
                100_000,
                100_000,
                1L));
        var rendererDefaults =
            ExactFactorizationExpressionRenderer.Policy.boundedDefaults();
        var renderer = new ExactFactorizationExpressionRenderer(
            new ExactFactorizationExpressionRenderer.Policy(
                rendererDefaults.maxFactors(),
                rendererDefaults.maxPolynomialTerms(),
                rendererDefaults.maxExponent(),
                rendererDefaults.maxCoefficientBits(),
                100_000,
                100_000L));
        var defaults =
            ExactNestedFactorizationTransformationPipeline.Policy
                .boundedDefaults();
        var policy =
            new ExactNestedFactorizationTransformationPipeline.Policy(
                0,
                16,
                16,
                500_000L,
                defaults.structuralLimits(),
                defaults.maxCandidates(),
                defaults.evidenceRequirement());
        var pipeline = new ExactNestedFactorizationTransformationPipeline(
            projector,
            new ExactParsedUnivariatePolynomialView(),
            renderer,
            parser,
            new ExactParsedUnivariatePolynomialView(),
            policy);
        ExactParsedTerm root = parser.parseExactTerm("x^2 - 1");
        TreePosition position = new TreePosition(
            List.of(),
            ExpressionFormatter.format(root.expression()));

        var result = pipeline.transform(
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
}
