package de.regelsuche.math.algorithms.linalg;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.math.algorithms.equivalence.Rational;
import de.regelsuche.math.algorithms.linalg.ExactLinearSystem.ExactMatrix;
import de.regelsuche.math.algorithms.linalg.ExactLinearSystem.ExactVector;
import de.regelsuche.math.algorithms.linalg.ExactLinearSystem.RowOrigin;
import de.regelsuche.math.algorithms.linalg.ExactLinearSystem.SolutionClassification;
import de.regelsuche.math.algorithms.linalg.ExactRrefSolver.Certificate;
import de.regelsuche.math.algorithms.linalg.ExactRrefSolver.Result;
import de.regelsuche.math.algorithms.linalg.ExactRrefSolver.Status;
import de.regelsuche.representation.RepresentationBridge.Budget;
import java.math.BigInteger;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ExactRrefSolverTest {
    private final ExactRrefSolver solver = new ExactRrefSolver();

    @Test
    void computesUniqueSolutionWithReplayableExactRowOperations() {
        ExactLinearSystem source = system(
            List.of(
                List.of(r(2), r(1)),
                List.of(r(1), r(-1))),
            List.of(r(5), r(1)),
            List.of("x", "y"),
            2,
            2,
            SolutionClassification.UNIQUE);

        Result result = solver.solve(source, new Budget(20_000));

        assertEquals(Status.SOLVED, result.status());
        ExactRrefReduction reduction = result.reduction().orElseThrow();
        assertEquals(
            List.of(
                List.of(r(1), r(0)),
                List.of(r(0), r(1))),
            reduction.reducedCoefficients().rows());
        assertEquals(
            List.of(r(2), r(1)),
            reduction.reducedRightHandSide().values());
        assertEquals(
            List.of(r(2), r(1)),
            reduction.particularSolution().orElseThrow().values());
        assertTrue(reduction.nullspaceBasis().isEmpty());
        assertTrue(reduction.freeVariableColumns().isEmpty());
        assertFalse(reduction.rowOperations().isEmpty());
        assertTrue(reduction.capabilityFrontier().newlyUnlocked().contains(
            ExactRrefReduction.CAPABILITY_UNIQUE_SOLUTION));
        assertTrue(solver.verify(source, result));
    }

    @Test
    void exposesExactParametricSolutionForUnderdeterminedSystem() {
        ExactLinearSystem source = system(
            List.of(
                List.of(r(1), r(1), r(1)),
                List.of(r(2), r(2), r(2))),
            List.of(r(2), r(4)),
            List.of("x", "y", "z"),
            1,
            1,
            SolutionClassification.UNDERDETERMINED);

        ExactRrefReduction reduction = solver.solve(
            source,
            new Budget(30_000)).reduction().orElseThrow();

        assertEquals(List.of(1, 2), reduction.freeVariableColumns());
        assertEquals(
            List.of(r(2), r(0), r(0)),
            reduction.particularSolution().orElseThrow().values());
        assertEquals(
            List.of(
                List.of(r(-1), r(1), r(0)),
                List.of(r(-1), r(0), r(1))),
            reduction.nullspaceBasis().stream()
                .map(ExactVector::values)
                .toList());
        assertEquals(
            SolutionClassification.UNDERDETERMINED,
            reduction.solutionClassification());
        assertTrue(reduction.capabilityFrontier().newlyUnlocked().contains(
            ExactRrefReduction.CAPABILITY_PARAMETRIC_SOLUTION));
        assertTrue(reduction.capabilityFrontier().lostOrConditional().isEmpty());
    }

    @Test
    void emitsExactContradictionWitnessForInconsistentSystem() {
        ExactLinearSystem source = system(
            List.of(
                List.of(r(1), r(1)),
                List.of(r(1), r(1))),
            List.of(r(1), r(2)),
            List.of("x", "y"),
            1,
            2,
            SolutionClassification.INCONSISTENT);

        ExactRrefReduction reduction = solver.solve(
            source,
            new Budget(20_000)).reduction().orElseThrow();

        assertEquals(List.of(1), reduction.contradictionRows());
        assertEquals(
            List.of(r(0), r(0), r(1)),
            reduction.reducedAugmentedRows().get(1));
        assertTrue(reduction.particularSolution().isEmpty());
        assertTrue(reduction.nullspaceBasis().isEmpty());
        assertEquals(
            SolutionClassification.INCONSISTENT,
            reduction.solutionClassification());
        assertTrue(reduction.capabilityFrontier().newlyUnlocked().contains(
            ExactRrefReduction.CAPABILITY_INCONSISTENCY_WITNESS));
    }

    @Test
    void keepsRationalArithmeticExact() {
        ExactLinearSystem source = system(
            List.of(
                List.of(fraction(1, 2), r(0)),
                List.of(r(0), fraction(1, 3))),
            List.of(r(1), r(1)),
            List.of("x", "y"),
            2,
            2,
            SolutionClassification.UNIQUE);

        ExactRrefReduction reduction = solver.solve(
            source,
            new Budget(20_000)).reduction().orElseThrow();

        assertEquals(
            List.of(r(2), r(3)),
            reduction.particularSolution().orElseThrow().values());
        assertEquals(
            List.of(
                List.of(r(1), r(0)),
                List.of(r(0), r(1))),
            reduction.reducedCoefficients().rows());
    }

    @Test
    void sourceRankMetadataIsCheckedAgainstIndependentReduction() {
        ExactLinearSystem falseMetadata = system(
            List.of(
                List.of(r(1), r(0)),
                List.of(r(0), r(1))),
            List.of(r(1), r(2)),
            List.of("x", "y"),
            1,
            1,
            SolutionClassification.UNDERDETERMINED);

        Result result = solver.solve(falseMetadata, new Budget(20_000));

        assertEquals(Status.INVALID_SOURCE, result.status());
        assertEquals("SOURCE_RANK_METADATA_MISMATCH", result.detailCode());
        assertTrue(result.reduction().isEmpty());
        assertFalse(solver.verify(falseMetadata, result));
    }

    @Test
    void exhaustedBudgetIsVisibleAndNeverReturnsPartialEvidence() {
        ExactLinearSystem source = system(
            List.of(List.of(r(1))),
            List.of(r(1)),
            List.of("x"),
            1,
            1,
            SolutionClassification.UNIQUE);

        Result result = solver.solve(source, new Budget(0));

        assertEquals(Status.BUDGET_INCONCLUSIVE, result.status());
        assertEquals(0, result.work().configuredWorkUnits());
        assertEquals(0, result.work().consumedWorkUnits());
        assertTrue(result.reduction().isEmpty());
        assertTrue(result.certificate().isEmpty());
    }

    @Test
    void independentVerificationRejectsTamperedCertificate() {
        ExactLinearSystem source = system(
            List.of(List.of(r(1))),
            List.of(r(4)),
            List.of("x"),
            1,
            1,
            SolutionClassification.UNIQUE);
        Result original = solver.solve(source, new Budget(10_000));
        Certificate certificate = original.certificate().orElseThrow();
        Certificate changedCertificate = new Certificate(
            certificate.schema(),
            certificate.solverId(),
            certificate.relation(),
            certificate.sourceSystemHash(),
            certificate.solutionClassification(),
            certificate.reducedAugmentedRows(),
            certificate.canonicalOperations(),
            certificate.coefficientPivots(),
            certificate.freeVariableColumns(),
            certificate.contradictionRows(),
            certificate.particularSolution(),
            certificate.nullspaceBasis(),
            certificate.capabilitiesBefore(),
            certificate.capabilitiesAfter(),
            certificate.newlyUnlockedCapabilities(),
            certificate.lostOrConditionalCapabilities(),
            "0".repeat(64));
        Result changed = new Result(
            Status.SOLVED,
            original.reduction(),
            Optional.of(changedCertificate),
            original.work(),
            original.detailCode());

        assertFalse(solver.verify(source, changed));
        assertTrue(solver.verify(source, original));
    }

    @Test
    void deterministicReductionProducesIdenticalEvidence() {
        ExactLinearSystem source = system(
            List.of(
                List.of(r(0), r(1)),
                List.of(r(1), r(1))),
            List.of(r(3), r(5)),
            List.of("x", "y"),
            2,
            2,
            SolutionClassification.UNIQUE);

        Result first = solver.solve(source, new Budget(20_000));
        Result second = solver.solve(source, new Budget(20_000));

        assertEquals(first, second);
    }

    private static ExactLinearSystem system(
        List<List<Rational>> coefficients,
        List<Rational> rightHandSide,
        List<String> variables,
        int coefficientRank,
        int augmentedRank,
        SolutionClassification classification
    ) {
        List<RowOrigin> origins = new java.util.ArrayList<>();
        for (int row = 0; row < coefficients.size(); row++) {
            origins.add(new RowOrigin(row, "source equation " + row));
        }
        return new ExactLinearSystem(
            new ExactMatrix(coefficients),
            variables,
            new ExactVector(rightHandSide),
            origins,
            coefficientRank,
            augmentedRank,
            classification);
    }

    private static Rational r(long value) {
        return Rational.of(value);
    }

    private static Rational fraction(long numerator, long denominator) {
        return new Rational(
            BigInteger.valueOf(numerator),
            BigInteger.valueOf(denominator));
    }
}
