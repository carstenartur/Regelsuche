package de.regelsuche.polynomial;

import static org.junit.jupiter.api.Assertions.*;

import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.math.algorithms.polynomial.NativeUnivariateFactorizationEngine;
import de.regelsuche.moves.enumerate.TreePosition;
import de.regelsuche.parse.ExactParsedTerm;
import de.regelsuche.parse.ExactParsedSubtermProjector;
import de.regelsuche.parse.ExpressionFormatter;
import de.regelsuche.parse.ExpressionParser;
import de.regelsuche.transform.PolynomialTheorySubsumptionClassifier;
import java.util.List;
import org.junit.jupiter.api.Test;

class ExactPolynomialReplayIntegrationTest {
    private final ExpressionParser parser = new ExpressionParser();
    private final ExactNestedFactorizationTransformationPipeline pipeline = new ExactNestedFactorizationTransformationPipeline();

    @Test
    void rebindsTheSamePrimitiveToAnotherOccurrenceWithoutChangingItsSurroundings() {
        ExactParsedTerm root = parser.parseExactTerm("(x^2-1)+(x^2-1)");
        var direct = pipeline.transform(root, position(root, 0), NativeUnivariateFactorizationEngine.boundedRationals(), 0);
        assertTrue(direct.transformed(), direct.detailCode());
        var original = direct.transformation().orElseThrow();
        var store = new VerifiedPolynomialTransitionCacheStore(2);
        var retained = store.retain(original, "test", "v1",
            new VerifiedPolynomialTransitionCacheStore.Observation("first", List.of("root.left"), List.of()));
        var release = store.replay(store.lookup(retained.lookupRequest()));
        assertSame(original, release.authorization().orElseThrow());
        var replay = pipeline.replay(root, position(root, 1), release.authorization().orElseThrow());
        assertTrue(replay.transformed(), replay.detailCode());
        assertEquals(direct.primitiveTransformationId(), replay.primitiveTransformationId());
        assertNotEquals(direct.certificateHash(), replay.certificateHash());
        assertSame(((BinaryExpr) root.expression()).left(), ((BinaryExpr) replay.rewrittenRoot().orElseThrow()).left());
        assertSame(original, replay.transformation().orElseThrow());
        assertEquals("VERIFIED_PRIMITIVE_REBOUND_AND_REPLAYED", replay.detailCode());
        assertTrue(replay.totalWork().stages().keySet().stream().noneMatch(s -> s.startsWith("native.")));
        assertTrue(replay.totalWork().units("transform.source-evidence-literal-validation") > 0);
    }

    @Test
    void classifierRetainsAuthorityAndDoesNotReconstructItFromAPatternPair() {
        var classification = new PolynomialTheorySubsumptionClassifier(NativeUnivariateFactorizationEngine.boundedRationals())
            .classify("x^2-1", "(x-1)*(x+1)");
        assertTrue(classification.subsumed());
        var original = classification.transformation().orElseThrow();
        assertEquals(classification.applicationKey(), original.certificateHash());
        var root = parser.parseExactTerm("f(x^2-1,7)");
        var replay = pipeline.replay(root, position(root, 0), original);
        assertTrue(replay.transformed(), replay.detailCode());
        assertEquals(classification.applicationKey(), replay.primitiveTransformationId().orElseThrow());
        assertEquals(0, PolynomialTheorySubsumptionClassifier.Classification.class.getConstructors().length);
    }

    @Test
    void rejectsDifferentExactSourceAndStalePosition() {
        var source = parser.parseExactTerm("x^2-1");
        var direct = pipeline.transform(source, position(source), NativeUnivariateFactorizationEngine.boundedRationals(), 0);
        var changed = parser.parseExactTerm("7+(x^2-2)");
        var mismatch = pipeline.replay(changed, position(changed, 1), direct.transformation().orElseThrow());
        assertEquals(ExactNestedFactorizationTransformationPipeline.Status.SOURCE_EVIDENCE_MISMATCH, mismatch.status());
        assertTrue(mismatch.rewrittenRoot().isEmpty());
        var stale = pipeline.replay(changed, new TreePosition(List.of(1), "x ^ 2 - 1"), direct.transformation().orElseThrow());
        assertEquals(ExactNestedFactorizationTransformationPipeline.Status.POSITION_STALE, stale.status());
    }

    @Test
    void evictionStillPreventsNewPrimitiveRelease() {
        var classification = new PolynomialTheorySubsumptionClassifier(NativeUnivariateFactorizationEngine.boundedRationals());
        var store = new VerifiedPolynomialTransitionCacheStore(1);
        var observation = new VerifiedPolynomialTransitionCacheStore.Observation("seed", List.of("learning"), List.of());
        var first = store.retain(classification.classify("x^2-1", "(x-1)*(x+1)").transformation().orElseThrow(), "cache", "v1", observation);
        var lookup = store.lookup(first.lookupRequest());
        store.retain(classification.classify("x^2-4", "(x-2)*(x+2)").transformation().orElseThrow(), "cache", "v1", observation);
        assertTrue(store.replay(lookup).authorization().isEmpty());
    }

    @Test
    void replayCannotWidenCurrentStructuralCandidateOrEvidencePolicy() {
        var root = parser.parseExactTerm("x^2-1");
        var original = pipeline.transform(root, position(root),
            NativeUnivariateFactorizationEngine.boundedRationals(), 0).transformation().orElseThrow();
        for (var stricter : List.of(
                restricted(1, 1, FactorizationRequest.EvidenceRequirement.VERIFIED_DECOMPOSITION),
                restricted(64, 0, FactorizationRequest.EvidenceRequirement.VERIFIED_DECOMPOSITION),
                restricted(64, 1, FactorizationRequest.EvidenceRequirement.INDEPENDENT_COMPLETE))) {
            var result = stricter.replay(root, position(root), original);
            assertEquals(ExactNestedFactorizationTransformationPipeline.Status.BUDGET_INCONCLUSIVE,
                result.status(), result.detailCode());
            assertTrue(result.rewrittenRoot().isEmpty());
        }
    }

    @Test
    void independentlyCompleteAuthorityCanReplayUnderTheSameEvidenceRequirement() {
        var strict = restricted(64, 250_000, FactorizationRequest.EvidenceRequirement.INDEPENDENT_COMPLETE);
        var root = parser.parseExactTerm("x^2-1");
        var direct = strict.transform(root, position(root), NativeUnivariateFactorizationEngine.boundedRationals(), 0);
        assertTrue(direct.transformed(), direct.detailCode());
        var original = direct.transformation().orElseThrow();
        assertEquals(FactorizationVerifier.ClaimStrength.INDEPENDENTLY_CERTIFIED_COMPLETE,
            original.factorization().report().orElseThrow().claimStrength());
        var replay = strict.replay(root, position(root), original);
        assertTrue(replay.transformed(), replay.detailCode());
        assertEquals(original.certificateHash(), replay.primitiveTransformationId().orElseThrow());
    }

    private ExactNestedFactorizationTransformationPipeline restricted(int degree, int candidates,
            FactorizationRequest.EvidenceRequirement evidence) {
        var defaults = ExactNestedFactorizationTransformationPipeline.Policy.boundedDefaults();
        var limits = defaults.structuralLimits();
        return new ExactNestedFactorizationTransformationPipeline(new ExactParsedSubtermProjector(),
            new ExactParsedUnivariatePolynomialView(), new ExactFactorizationExpressionRenderer(),
            new ExpressionParser(), new ExactParsedUnivariatePolynomialView(),
            new ExactNestedFactorizationTransformationPipeline.Policy(defaults.maxPathDepth(),
                defaults.maxRootNodes(), defaults.maxReplacementNodes(), defaults.maxTotalWorkUnits(),
                new FactorizationRequest.StructuralLimits(limits.maxVariables(), degree,
                    limits.maxTerms(), limits.maxCoefficientBitLength()), candidates, evidence));
    }

    private TreePosition position(ExactParsedTerm root, Integer... path) {
        var selected = new TreePosition(List.of(path), "pending").subtreeAt(root.expression()).orElseThrow();
        return new TreePosition(List.of(path), ExpressionFormatter.format(selected));
    }
}
