package de.regelsuche.math.algorithms.linalg;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.ast.Equation;
import de.regelsuche.input.InputRequest;
import de.regelsuche.input.InputType;
import de.regelsuche.math.algorithms.equivalence.Rational;
import de.regelsuche.math.algorithms.linalg.DirectScalarEliminationSolver.Certificate;
import de.regelsuche.math.algorithms.linalg.DirectScalarEliminationSolver.Result;
import de.regelsuche.math.algorithms.linalg.DirectScalarEliminationSolver.Source;
import de.regelsuche.math.algorithms.linalg.DirectScalarEliminationSolver.Status;
import de.regelsuche.math.algorithms.linalg.ExactLinearSystem.SolutionClassification;
import de.regelsuche.parse.ExpressionParser;
import de.regelsuche.representation.RepresentationBridge.Budget;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class DirectScalarEliminationSolverTest {
    private final ExpressionParser parser = new ExpressionParser();
    private final DirectScalarEliminationSolver solver =
        new DirectScalarEliminationSolver();
    private final LinearSystemRepresentationBridge representationBridge =
        new LinearSystemRepresentationBridge();

    @Test
    void solvesUniqueSystemWithoutConstructingMatrixRepresentation() {
        Result result = solve(
            "2*x + y = 5; x - y = 1",
            List.of("x", "y"),
            20_000);

        assertEquals(Status.SOLVED, result.status());
        ExactLinearSolutionConsequence consequence = result.consequence()
            .orElseThrow();
        assertEquals(SolutionClassification.UNIQUE,
            consequence.classification());
        assertEquals(
            List.of(Rational.of(2), Rational.ONE),
            consequence.particularSolution().orElseThrow().values());
        assertTrue(consequence.nullspaceBasis().isEmpty());
        assertTrue(result.workProfile().sourceAnalysisWork() > 0);
        assertTrue(result.workProfile().eliminationWork() > 0);
        assertTrue(result.workProfile().evidenceWork() > 0);
        assertTrue(solver.verify(source(
            "2*x + y = 5; x - y = 1",
            List.of("x", "y")), result));
    }

    @Test
    void exposesCanonicalAffineSolutionSpace() {
        Result result = solve(
            "x + y + z = 2; 2*x + 2*y + 2*z = 4",
            List.of("x", "y", "z"),
            30_000);

        ExactLinearSolutionConsequence consequence = result.consequence()
            .orElseThrow();
        assertEquals(SolutionClassification.UNDERDETERMINED,
            consequence.classification());
        assertEquals(
            List.of(Rational.of(2), Rational.ZERO, Rational.ZERO),
            consequence.particularSolution().orElseThrow().values());
        assertEquals(
            List.of(
                List.of(Rational.NEGATIVE_ONE, Rational.ONE, Rational.ZERO),
                List.of(Rational.NEGATIVE_ONE, Rational.ZERO, Rational.ONE)),
            consequence.nullspaceBasis().stream()
                .map(vector -> vector.values())
                .toList());
    }

    @Test
    void emitsNormalizedContradiction() {
        Result result = solve(
            "x + y = 1; x + y = 2",
            List.of("x", "y"),
            20_000);

        ExactLinearSolutionConsequence consequence = result.consequence()
            .orElseThrow();
        assertEquals(SolutionClassification.INCONSISTENT,
            consequence.classification());
        assertEquals(Optional.of(Rational.ONE),
            consequence.normalizedContradiction());
        assertTrue(consequence.particularSolution().isEmpty());
        assertTrue(consequence.nullspaceBasis().isEmpty());
    }

    @Test
    void preservesCancelledCoordinateSemantics() {
        ExactLinearSolutionConsequence free = solve(
            "x - x = 0",
            List.of("x"),
            10_000).consequence().orElseThrow();
        assertEquals(SolutionClassification.UNDERDETERMINED,
            free.classification());
        assertEquals(List.of(Rational.ZERO),
            free.particularSolution().orElseThrow().values());
        assertEquals(List.of(List.of(Rational.ONE)),
            free.nullspaceBasis().stream().map(vector -> vector.values()).toList());

        ExactLinearSolutionConsequence contradiction = solve(
            "x - x = 1",
            List.of("x"),
            10_000).consequence().orElseThrow();
        assertEquals(SolutionClassification.INCONSISTENT,
            contradiction.classification());
        assertEquals(Optional.of(Rational.ONE),
            contradiction.normalizedContradiction());
    }

    @Test
    void keepsRationalArithmeticExact() {
        Result result = solve(
            "x / 2 = 1; y / 3 = 1",
            List.of("x", "y"),
            20_000);

        assertEquals(
            List.of(Rational.of(2), Rational.of(3)),
            result.consequence().orElseThrow()
                .particularSolution().orElseThrow().values());
    }

    @Test
    void powerSemanticsMatchExactRepresentationRoute() {
        Source variableZero = source("x ^ 0 = 1", List.of("x"));
        assertEquals(
            Status.NONLINEAR,
            solver.solve(variableZero, new Budget(20_000)).status());
        assertEquals(
            de.regelsuche.representation.RepresentationBridge.Status.NONLINEAR,
            representationBridge.analyze(
                variableZero.equations(),
                new Budget(20_000)).status());

        Source zeroZero = source("0 ^ 0 * x = 1", List.of("x"));
        assertEquals(
            Status.DOMAIN_UNSUPPORTED,
            solver.solve(zeroZero, new Budget(20_000)).status());
        assertEquals(
            de.regelsuche.representation.RepresentationBridge.Status
                .DOMAIN_UNSUPPORTED,
            representationBridge.analyze(
                zeroZero.equations(),
                new Budget(20_000)).status());

        Source zeroNegative = source(
            "0 ^ (0 - 1) * x = 1",
            List.of("x"));
        assertEquals(
            Status.DOMAIN_UNSUPPORTED,
            solver.solve(zeroNegative, new Budget(20_000)).status());
        assertEquals(
            de.regelsuche.representation.RepresentationBridge.Status
                .DOMAIN_UNSUPPORTED,
            representationBridge.analyze(
                zeroNegative.equations(),
                new Budget(20_000)).status());

        Source negativeConstant = source(
            "2 ^ (0 - 2) * x = 1",
            List.of("x"));
        Result direct = solver.solve(negativeConstant, new Budget(20_000));
        var represented = representationBridge.analyze(
            negativeConstant.equations(),
            new Budget(20_000));
        assertEquals(Status.SOLVED, direct.status());
        assertEquals(
            de.regelsuche.representation.RepresentationBridge.Status.REPRESENTED,
            represented.status());
        assertEquals(
            List.of(Rational.of(4)),
            direct.consequence().orElseThrow()
                .particularSolution().orElseThrow().values());
        assertEquals(
            Rational.ONE.divide(Rational.of(4)),
            represented.representation().orElseThrow()
                .coefficients().get(0, 0));
        assertTrue(solver.verify(negativeConstant, direct));
        assertTrue(representationBridge.verify(
            negativeConstant.equations(),
            represented));

        Source identityExponent = source(
            "x ^ (1 + 0) = 2",
            List.of("x"));
        Result identityDirect = solver.solve(
            identityExponent,
            new Budget(20_000));
        var identityRepresented = representationBridge.analyze(
            identityExponent.equations(),
            new Budget(20_000));
        assertEquals(Status.SOLVED, identityDirect.status());
        assertEquals(
            de.regelsuche.representation.RepresentationBridge.Status.REPRESENTED,
            identityRepresented.status());
        assertEquals(
            List.of(Rational.of(2)),
            identityDirect.consequence().orElseThrow()
                .particularSolution().orElseThrow().values());

        Source exponentAboveOldDirectLimit = source(
            "2 ^ 21 * x = 2097152",
            List.of("x"));
        Result boundDirect = solver.solve(
            exponentAboveOldDirectLimit,
            new Budget(20_000));
        var boundRepresented = representationBridge.analyze(
            exponentAboveOldDirectLimit.equations(),
            new Budget(20_000));
        assertEquals(Status.SOLVED, boundDirect.status());
        assertEquals(
            de.regelsuche.representation.RepresentationBridge.Status.REPRESENTED,
            boundRepresented.status());
        assertEquals(
            List.of(Rational.ONE),
            boundDirect.consequence().orElseThrow()
                .particularSolution().orElseThrow().values());
        assertEquals(
            Rational.of(2_097_152),
            boundRepresented.representation().orElseThrow()
                .coefficients().get(0, 0));

        Source exponentOutsideSharedLimit = source(
            "2 ^ 65 * x = 1",
            List.of("x"));
        assertEquals(
            Status.DOMAIN_UNSUPPORTED,
            solver.solve(
                exponentOutsideSharedLimit,
                new Budget(20_000)).status());
        assertEquals(
            de.regelsuche.representation.RepresentationBridge.Status
                .DOMAIN_UNSUPPORTED,
            representationBridge.analyze(
                exponentOutsideSharedLimit.equations(),
                new Budget(20_000)).status());
    }

    @Test
    void unsupportedAndNonlinearInputsFailClosed() {
        Result nonlinear = solve(
            "x*y = 1",
            List.of("x", "y"),
            10_000);
        assertEquals(Status.NONLINEAR, nonlinear.status());
        assertTrue(nonlinear.consequence().isEmpty());

        Result undeclared = solve(
            "a*x = 1",
            List.of("x"),
            10_000);
        assertEquals(Status.DOMAIN_UNSUPPORTED, undeclared.status());
        assertTrue(undeclared.consequence().isEmpty());
    }

    @Test
    void budgetExhaustionRetainsNoPartialClaim() {
        Result result = solve("x = 1", List.of("x"), 0);

        assertEquals(Status.BUDGET_INCONCLUSIVE, result.status());
        assertEquals(0, result.work().configuredWorkUnits());
        assertEquals(0, result.work().consumedWorkUnits());
        assertTrue(result.consequence().isEmpty());
        assertTrue(result.certificate().isEmpty());
    }

    @Test
    void verificationRejectsCertificateTampering() {
        Source source = source("x = 4", List.of("x"));
        Result original = solver.solve(source, new Budget(10_000));
        Certificate certificate = original.certificate().orElseThrow();
        Certificate changedCertificate = new Certificate(
            certificate.schema(),
            certificate.solverId(),
            certificate.sourceHash(),
            certificate.sourceEquations(),
            certificate.variables(),
            certificate.reducedEquations(),
            certificate.canonicalOperations(),
            certificate.consequenceLines(),
            certificate.workProfile(),
            "0".repeat(64));
        Result changed = new Result(
            Status.SOLVED,
            original.consequence(),
            Optional.of(changedCertificate),
            original.workProfile(),
            original.work(),
            original.detailCode());

        assertFalse(solver.verify(source, changed));
        assertTrue(solver.verify(source, original));
    }

    @Test
    void evidenceAndWorkAreDeterministic() {
        Source source = source(
            "0*x + y = 3; x + y = 5",
            List.of("x", "y"));

        assertEquals(
            solver.solve(source, new Budget(20_000)),
            solver.solve(source, new Budget(20_000)));
    }

    private Result solve(
        String expression,
        List<String> variables,
        int budget
    ) {
        return solver.solve(source(expression, variables), new Budget(budget));
    }

    private Source source(String expression, List<String> variables) {
        List<Equation> equations = parser.parse(
            new InputRequest(InputType.SYSTEM, expression)).equations();
        return new Source(equations, variables);
    }
}
