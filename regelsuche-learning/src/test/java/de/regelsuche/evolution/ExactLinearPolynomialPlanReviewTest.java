package de.regelsuche.evolution;

import static org.junit.jupiter.api.Assertions.*;

import de.regelsuche.evolution.ExactFinitePolynomialPlanReplayArtifactVerifier.LoadedArtifact;
import de.regelsuche.evolution.ExactLinearPolynomialPlanResolver.Formation;
import de.regelsuche.math.algorithms.equivalence.ExactLinearPolynomialHoleSolver;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

class ExactLinearPolynomialPlanReviewTest {
    private static final ExactLinearPolynomialHoleSolver.Limits SOLVER_LIMITS =
        new ExactLinearPolynomialHoleSolver.Limits(4, 32, 128, 100_000);
    private static final SchematicProofPlan.Limits PLAN_LIMITS = new SchematicProofPlan.Limits(8, 8, 4, 200_000);

    @Test
    void malformedUtf16CannotSubstituteAnotherSourceInVerifiedNegativeReplay() {
        var resolver = new ExactLinearPolynomialPlanResolver();
        var verifier = new ExactLinearPolynomialPlanEvidenceVerifier();
        var expected = new Formation("x+?", "${alpha}*x", List.of("alpha"), List.of(), SOLVER_LIMITS);
        var plan = resolver.createPlan("unicode-source", expected, PLAN_LIMITS);
        var run = resolver.resolve(plan, expected);
        assertEquals(ExactLinearPolynomialHoleSolver.Status.UNSUPPORTED, run.solverResult().status());
        var reference = verifier.describeRun(run);
        byte[] retained = run.toCanonicalJson().getBytes(StandardCharsets.UTF_8);

        assertThrows(IllegalArgumentException.class, () -> {
            var foreign = new Formation("x+" + (char) 0xd800, "${alpha}*x", List.of("alpha"), List.of(), SOLVER_LIMITS);
            // Before the fix both formation hashes and complete run UTF-8 bytes alias x+?.
            var replay = verifier.verify(plan, foreign, reference, id -> new LoadedArtifact(id, retained));
            assertEquals(ExactLinearPolynomialHoleSolver.Status.UNSUPPORTED, replay.run().solverResult().status());
        });
    }

    @Test
    void everyFormationTextRejectsUnpairedSurrogatesBeforeIssuingAPlan() {
        for (String malformed : List.of("x" + (char) 0xd800, "x" + (char) 0xdc00,
                "x" + (char) 0xd800 + "y", "x" + (char) 0xdc00 + (char) 0xd800)) {
            assertAll(
                () -> assertThrows(IllegalArgumentException.class,
                    () -> new Formation(malformed, "${alpha}*x", List.of("alpha"), List.of(), SOLVER_LIMITS)),
                () -> assertThrows(IllegalArgumentException.class,
                    () -> new Formation("x", "${alpha}*" + malformed, List.of("alpha"), List.of(), SOLVER_LIMITS)),
                () -> assertThrows(IllegalArgumentException.class,
                    () -> new Formation("x", "${alpha}*x", List.of("alpha"), List.of(malformed), SOLVER_LIMITS)));
        }
    }

    @Test
    void validSupplementaryUnicodeRemainsByteExactInNegativeReplay() {
        String symbol = new String(Character.toChars(0x1d465));
        var formation = new Formation("x+" + symbol, "${alpha}*" + symbol, List.of("alpha"),
            List.of(symbol + " > 0"), SOLVER_LIMITS);
        var resolver = new ExactLinearPolynomialPlanResolver();
        var verifier = new ExactLinearPolynomialPlanEvidenceVerifier();
        var plan = resolver.createPlan("valid-unicode", formation, PLAN_LIMITS);
        var run = resolver.resolve(plan, formation);
        byte[] canonical = run.toCanonicalJson().getBytes(StandardCharsets.UTF_8);
        assertEquals(run.toCanonicalJson(), new String(canonical, StandardCharsets.UTF_8));
        var replay = verifier.verify(plan, formation, verifier.describeRun(run), id -> new LoadedArtifact(id, canonical));
        assertEquals(formation, replay.formation());
        assertEquals(List.of(symbol + " > 0"), replay.run().solverResult().assumptions());
        assertEquals(ExactLinearPolynomialHoleSolver.Status.UNSUPPORTED, replay.run().solverResult().status());
        assertThrows(IllegalArgumentException.class, () -> verifier.verifyCandidate(replay));
    }
}
