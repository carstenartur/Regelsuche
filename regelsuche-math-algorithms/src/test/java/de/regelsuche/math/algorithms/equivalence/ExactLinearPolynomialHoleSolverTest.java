package de.regelsuche.math.algorithms.equivalence;

import static org.junit.jupiter.api.Assertions.*;

import de.regelsuche.math.algorithms.equivalence.ExactLinearPolynomialHoleSolver.Limits;
import de.regelsuche.math.algorithms.equivalence.ExactLinearPolynomialHoleSolver.Status;
import de.regelsuche.scalar.ExactRational;
import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(10)
class ExactLinearPolynomialHoleSolverTest {
    private final ExactLinearPolynomialHoleSolver solver = new ExactLinearPolynomialHoleSolver();
    private final Limits limits = new Limits(8, 64, 512, 100_000);

    @Test
    void derivesRationalCoupledCoefficientsOutsideTheFrozenFiniteDomain() {
        String source = "17*x/7+3*y/11";
        String template = "${alpha}*(x+y)+${beta}*(x-y)";
        var finite = new ExactFinitePolynomialHoleSolver().solve(source, template,
            List.of(ExactFinitePolynomialHoleSolver.HoleDomain.integerRange("alpha", -3, 3),
                ExactFinitePolynomialHoleSolver.HoleDomain.integerRange("beta", -3, 3)), 4);
        assertEquals(ExactFinitePolynomialHoleSolver.SearchStatus.COMPLETE_WITHOUT_SOLUTION, finite.status());
        assertEquals(49, finite.evaluatedAssignments());

        var result = solver.solve(source, template, List.of("beta", "alpha"), List.of(), limits);
        assertEquals(Status.UNIQUE, result.status());
        var candidate = result.candidate().orElseThrow();
        assertEquals(Map.of("alpha", rational(104, 77), "beta", rational(83, 77)), candidate.bindings());
        new ExactPolynomialAnalysis().requireEquivalent(source, candidate.instantiatedExpression());
        assertFalse(result.reduction().orElseThrow().reduction().orElseThrow().rowOperations().isEmpty());
        assertTrue(result.work().elimination() > 0);
        assertTrue(result.work().verification() > 0);
        assertTrue(solver.replay(result));
        assertEquals(result.contentHash(), solver.solve(source, template, List.of("alpha", "beta"), List.of(), limits).contentHash());
    }

    @Test
    void distinguishesInconsistencyFromUnderdeterminationAndRetainsNoCandidate() {
        var inconsistent = solve("x+2*y", "${alpha}*(x+y)", "alpha");
        assertEquals(Status.INCONSISTENT, inconsistent.status());
        assertTrue(inconsistent.candidate().isEmpty());
        assertFalse(inconsistent.reduction().orElseThrow().reduction().orElseThrow().contradictionRows().isEmpty());
        var singular = solve("x", "${alpha}*x+${beta}*x", "alpha", "beta");
        assertEquals(Status.UNDERDETERMINED, singular.status());
        assertTrue(singular.candidate().isEmpty());
        assertEquals(1, singular.reduction().orElseThrow().reduction().orElseThrow().nullspaceBasis().size());
        assertEquals(Status.UNDERDETERMINED, solve("0", "${alpha}*x-${alpha}*x", "alpha").status());
    }

    @Test
    void rejectsNonlinearHolesAndDomainChangingDenominatorsWithoutSampling() {
        for (String template : List.of("${alpha}*${beta}*x", "${alpha}^2*x+${beta}",
                "(${alpha}*${beta}-${alpha}*${beta})*x", "${alpha}*x/${beta}")) {
            var result = solve("x", template, "alpha", "beta");
            assertEquals(Status.UNSUPPORTED, result.status(), template);
            assertTrue(result.candidate().isEmpty());
        }
        assertEquals(Status.UNSUPPORTED, solve("x/x", "${alpha}", "alpha").status());
        assertEquals(Status.UNSUPPORTED, solve("x", "${alpha}*x/x", "alpha").status());
        assertEquals(Status.UNSUPPORTED, solve("sin(x)", "${alpha}*x", "alpha").status());
        assertEquals(Status.UNSUPPORTED,
            solver.solve("x", "${alpha}*x", List.of("alpha"), List.of("x!=0"), limits).status());
    }

    @Test
    void preservesExactLiteralsAndAvoidsFormalHoleNameCapture() {
        var large = solve("9007199254740993*x", "${coefficient}*x", "coefficient");
        assertEquals(ExactRational.integer(9_007_199_254_740_993L), large.candidate().orElseThrow().bindings().get("coefficient"));
        var captured = solve("5*linearcoefficient0", "${coefficient}*linearcoefficient0", "coefficient");
        assertEquals(ExactRational.integer(5), captured.candidate().orElseThrow().bindings().get("coefficient"));
    }

    @Test
    void doesNotResetWorkBeforeEliminationOrIndependentChecking() {
        var complete = solve("17*x/7+3*y/11", "${alpha}*(x+y)+${beta}*(x-y)", "alpha", "beta");
        int consumed = complete.work().consumed();
        var exact = new Limits(8, 64, 512, consumed);
        assertEquals(Status.UNIQUE, solver.solve(complete.sourceExpression(), complete.ansatzTemplate(),
            complete.holeIds(), List.of(), exact).status());
        var shortBudget = solver.solve(complete.sourceExpression(), complete.ansatzTemplate(), complete.holeIds(),
            List.of(), new Limits(8, 64, 512, consumed - 1));
        assertEquals(Status.BUDGET_INCONCLUSIVE, shortBudget.status());
        assertTrue(shortBudget.candidate().isEmpty());
        assertEquals(consumed - 1, shortBudget.work().consumed());
        assertEquals(Status.BUDGET_INCONCLUSIVE,
            solver.solve("x", "${alpha}*x", List.of("alpha"), List.of(), new Limits(8, 64, 512, 0)).status());
    }

    @Test
    void rejectsDimensionsAndCoefficientGrowthUnderFrozenLimits() {
        assertEquals(Status.BUDGET_INCONCLUSIVE, solver.solve("x+y", "${alpha}*x+${beta}*y",
            List.of("alpha", "beta"), List.of(), new Limits(1, 64, 512, 100_000)).status());
        assertEquals(Status.BUDGET_INCONCLUSIVE, solver.solve("x+y", "${alpha}*x",
            List.of("alpha"), List.of(), new Limits(8, 1, 512, 100_000)).status());
        var bits = solver.solve("257*x", "${alpha}*x", List.of("alpha"), List.of(), new Limits(8, 64, 8, 100_000));
        assertEquals(Status.BUDGET_INCONCLUSIVE, bits.status());
        assertTrue(bits.candidate().isEmpty());
        assertEquals(Status.BUDGET_INCONCLUSIVE,
            solve("(x+y+z)^32", "${alpha}*x", "alpha").status(), "bounded projection exhaustion is not unsupported mathematics");
    }

    @Test
    void bindsActualNestedRowOperationsAndRejectsChangedCandidateReplay() {
        var original = solve("17*x/7+3*y/11", "${alpha}*(x+y)+${beta}*(x-y)", "alpha", "beta");
        var retained = original.reduction().orElseThrow();
        var reduced = retained.reduction().orElseThrow();
        var alteredOperations = new java.util.ArrayList<>(reduced.rowOperations());
        alteredOperations.add(de.regelsuche.math.algorithms.linalg.ExactRrefReduction.RowOperation.swap(0, 1));
        var altered = new de.regelsuche.math.algorithms.linalg.ExactRrefReduction(reduced.reducedCoefficients(),
            reduced.variables(), reduced.reducedRightHandSide(), reduced.coefficientPivots(), reduced.freeVariableColumns(),
            reduced.particularSolution(), reduced.nullspaceBasis(), reduced.contradictionRows(), alteredOperations,
            reduced.capabilityFrontier(), reduced.relation());
        var changedReduction = new de.regelsuche.math.algorithms.linalg.ExactRrefSolver.Result(retained.status(),
            java.util.Optional.of(altered), retained.certificate(), retained.work(), retained.detailCode());
        var forged = new ExactLinearPolynomialHoleSolver.Result(original.sourceExpression(), original.ansatzTemplate(),
            original.holeIds(), original.assumptions(), original.limits(), original.status(), original.constraints(),
            java.util.Optional.of(changedReduction), original.candidate(), original.work(), original.detailCode());
        assertFalse(solver.replay(forged));
        assertNotEquals(original.contentHash(), forged.contentHash(), "actual row lineage must affect the new wrapper identity");
        var certificate = retained.certificate().orElseThrow();
        var falseCertificate = new de.regelsuche.math.algorithms.linalg.ExactRrefSolver.Certificate(certificate.schema(),
            certificate.solverId(), certificate.relation(), "a".repeat(64), certificate.solutionClassification(),
            certificate.reducedAugmentedRows(), certificate.canonicalOperations(), certificate.coefficientPivots(),
            certificate.freeVariableColumns(), certificate.contradictionRows(), certificate.particularSolution(),
            certificate.nullspaceBasis(), certificate.capabilitiesBefore(), certificate.capabilitiesAfter(),
            certificate.newlyUnlockedCapabilities(), certificate.lostOrConditionalCapabilities(), certificate.contentHash());
        var forgedCertificateResult = new de.regelsuche.math.algorithms.linalg.ExactRrefSolver.Result(retained.status(),
            retained.reduction(), java.util.Optional.of(falseCertificate), retained.work(), retained.detailCode());
        var falseCertificateWrapper = new ExactLinearPolynomialHoleSolver.Result(original.sourceExpression(), original.ansatzTemplate(),
            original.holeIds(), original.assumptions(), original.limits(), original.status(), original.constraints(),
            java.util.Optional.of(forgedCertificateResult), original.candidate(), original.work(), original.detailCode());
        assertFalse(solver.replay(falseCertificateWrapper));
        assertNotEquals(original.contentHash(), falseCertificateWrapper.contentHash());
        var changedCandidate = new ExactLinearPolynomialHoleSolver.Candidate(original.candidate().orElseThrow().bindings(), "x+y");
        var falseCandidate = new ExactLinearPolynomialHoleSolver.Result(original.sourceExpression(), original.ansatzTemplate(),
            original.holeIds(), original.assumptions(), original.limits(), original.status(), original.constraints(),
            original.reduction(), java.util.Optional.of(changedCandidate), original.work(), original.detailCode());
        assertFalse(solver.replay(falseCandidate));
    }

    private ExactLinearPolynomialHoleSolver.Result solve(String source, String template, String... holes) {
        return solver.solve(source, template, List.of(holes), List.of(), limits);
    }

    private static ExactRational rational(long numerator, long denominator) {
        return new ExactRational(BigInteger.valueOf(numerator), BigInteger.valueOf(denominator));
    }
}
