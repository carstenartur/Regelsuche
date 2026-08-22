package de.regelsuche.math.algorithms.linalg;

import static org.junit.jupiter.api.Assertions.assertThrows;

import de.regelsuche.math.algorithms.equivalence.Rational;
import de.regelsuche.math.algorithms.linalg.ExactLinearSystem.ExactMatrix;
import de.regelsuche.math.algorithms.linalg.ExactLinearSystem.ExactVector;
import de.regelsuche.math.algorithms.linalg.ExactRrefReduction.CapabilityFrontier;
import de.regelsuche.math.algorithms.linalg.ExactRrefReduction.Pivot;
import de.regelsuche.representation.RepresentationBridge.Relation;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ExactRrefReductionSolutionEvidenceTest {

    @Test
    void rejectsParticularSolutionThatDoesNotSatisfyReducedSystem() {
        assertThrows(IllegalArgumentException.class, () ->
            new ExactRrefReduction(
                new ExactMatrix(List.of(List.of(Rational.ONE))),
                List.of("x"),
                new ExactVector(List.of(Rational.ONE)),
                List.of(new Pivot(0, 0)),
                List.of(),
                Optional.of(new ExactVector(List.of(Rational.of(2)))),
                List.of(),
                List.of(),
                List.of(),
                uniqueFrontier(),
                Relation.SOLUTION_SET_EQUIVALENCE));
    }

    @Test
    void rejectsNullspaceBasisWithoutCanonicalFreeCoordinateIdentity() {
        assertThrows(IllegalArgumentException.class, () ->
            new ExactRrefReduction(
                new ExactMatrix(List.of(
                    List.of(Rational.ONE, Rational.ONE))),
                List.of("x", "y"),
                new ExactVector(List.of(Rational.ZERO)),
                List.of(new Pivot(0, 0)),
                List.of(1),
                Optional.of(new ExactVector(List.of(
                    Rational.ZERO,
                    Rational.ZERO))),
                List.of(new ExactVector(List.of(
                    Rational.of(-2),
                    Rational.of(2)))),
                List.of(),
                List.of(),
                parametricFrontier(),
                Relation.SOLUTION_SET_EQUIVALENCE));
    }

    private static CapabilityFrontier uniqueFrontier() {
        List<String> before = before();
        List<String> newlyUnlocked = List.of(
            ExactRrefReduction.CAPABILITY_EXACT_RREF,
            ExactRrefReduction.CAPABILITY_ROW_OPERATION_REPLAY,
            ExactRrefReduction.CAPABILITY_AFFINE_SOLUTION_SPACE,
            ExactRrefReduction.CAPABILITY_UNIQUE_SOLUTION);
        return new CapabilityFrontier(
            before,
            concat(before, newlyUnlocked),
            newlyUnlocked,
            List.of());
    }

    private static CapabilityFrontier parametricFrontier() {
        List<String> before = before();
        List<String> newlyUnlocked = List.of(
            ExactRrefReduction.CAPABILITY_EXACT_RREF,
            ExactRrefReduction.CAPABILITY_ROW_OPERATION_REPLAY,
            ExactRrefReduction.CAPABILITY_AFFINE_SOLUTION_SPACE,
            ExactRrefReduction.CAPABILITY_PARAMETRIC_SOLUTION);
        return new CapabilityFrontier(
            before,
            concat(before, newlyUnlocked),
            newlyUnlocked,
            List.of());
    }

    private static List<String> before() {
        return List.of(
            ExactRrefReduction.CAPABILITY_EXACT_LINEAR_SYSTEM,
            ExactRrefReduction.CAPABILITY_RANK_CLASSIFICATION);
    }

    private static List<String> concat(
        List<String> first,
        List<String> second
    ) {
        java.util.ArrayList<String> result = new java.util.ArrayList<>(first);
        result.addAll(second);
        return List.copyOf(result);
    }
}
