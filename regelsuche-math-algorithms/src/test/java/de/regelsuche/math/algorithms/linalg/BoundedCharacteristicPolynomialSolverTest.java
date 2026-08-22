package de.regelsuche.math.algorithms.linalg;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.ast.Equation;
import de.regelsuche.input.InputRequest;
import de.regelsuche.input.InputType;
import de.regelsuche.math.algorithms.equivalence.Polynomial;
import de.regelsuche.math.algorithms.linalg.BoundedCharacteristicPolynomialSolver.Certificate;
import de.regelsuche.math.algorithms.linalg.BoundedCharacteristicPolynomialSolver.CharacteristicPolynomial;
import de.regelsuche.math.algorithms.linalg.BoundedCharacteristicPolynomialSolver.Result;
import de.regelsuche.math.algorithms.linalg.BoundedCharacteristicPolynomialSolver.Status;
import de.regelsuche.math.algorithms.linalg.EigenproblemRepresentation.ModelDomain;
import de.regelsuche.parse.ExpressionParser;
import de.regelsuche.representation.RepresentationBridge.Budget;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class BoundedCharacteristicPolynomialSolverTest {
    private final ExpressionParser parser = new ExpressionParser();
    private final SymbolicLinearSystemRepresentationBridge systemBridge =
        new SymbolicLinearSystemRepresentationBridge();
    private final EigenproblemRepresentationBridge eigenBridge =
        new EigenproblemRepresentationBridge();
    private final BoundedCharacteristicPolynomialSolver solver =
        new BoundedCharacteristicPolynomialSolver();

    @Test
    void computesExactTwoByTwoCharacteristicPolynomial() {
        EigenproblemRepresentation eigen = eigen(
            "a*x + b*y = lambda*x; c*x + d*y = lambda*y",
            List.of("x", "y"));

        Result result = solver.solve(
            eigen,
            new BoundedCharacteristicPolynomialSolver.Budget(10_000));

        assertEquals(Status.SOLVED, result.status());
        CharacteristicPolynomial characteristic = result
            .characteristicPolynomial().orElseThrow();
        Polynomial lambda = Polynomial.variable("lambda");
        Polynomial expected = Polynomial.variable("a")
            .subtract(lambda)
            .multiply(Polynomial.variable("d").subtract(lambda))
            .subtract(Polynomial.variable("b")
                .multiply(Polynomial.variable("c")));
        assertEquals(expected, characteristic.polynomial());
        assertEquals(
            expected.toCanonicalString() + " = 0",
            characteristic.singularityEquation());
        assertTrue(solver.verify(eigen, result));
    }

    @Test
    void computesOneDimensionalConsequence() {
        EigenproblemRepresentation eigen = eigen(
            "a*x = lambda*x",
            List.of("x"));

        CharacteristicPolynomial characteristic = solver.solve(
            eigen,
            new BoundedCharacteristicPolynomialSolver.Budget(100))
            .characteristicPolynomial()
            .orElseThrow();

        assertEquals(
            Polynomial.variable("a").subtract(
                Polynomial.variable("lambda")),
            characteristic.polynomial());
    }

    @Test
    void dimensionAndWorkLimitsRemainExplicit() {
        EigenproblemRepresentation twoByTwo = eigen(
            "a*x + b*y = lambda*x; c*x + d*y = lambda*y",
            List.of("x", "y"));
        BoundedCharacteristicPolynomialSolver oneDimensionalOnly =
            new BoundedCharacteristicPolynomialSolver(1);
        Result unsupported = oneDimensionalOnly.solve(
            twoByTwo,
            new BoundedCharacteristicPolynomialSolver.Budget(100));
        assertEquals(Status.DIMENSION_UNSUPPORTED, unsupported.status());
        assertFalse(unsupported.characteristicPolynomial().isPresent());

        Result exhausted = solver.solve(
            twoByTwo,
            new BoundedCharacteristicPolynomialSolver.Budget(0));
        assertEquals(Status.BUDGET_INCONCLUSIVE, exhausted.status());
        assertEquals(0, exhausted.work().consumedWorkUnits());
    }

    @Test
    void independentVerificationRejectsTamperedCertificate() {
        EigenproblemRepresentation eigen = eigen(
            "a*x = lambda*x",
            List.of("x"));
        Result original = solver.solve(
            eigen,
            new BoundedCharacteristicPolynomialSolver.Budget(100));
        Certificate certificate = original.certificate().orElseThrow();
        Certificate changedCertificate = new Certificate(
            certificate.schema(),
            certificate.solverId(),
            certificate.sourceHash(),
            certificate.dimension(),
            certificate.eigenvalueParameter(),
            certificate.canonicalPolynomial(),
            certificate.singularityEquation(),
            "0".repeat(64));
        Result changed = new Result(
            Status.SOLVED,
            original.characteristicPolynomial(),
            java.util.Optional.of(changedCertificate),
            original.work(),
            original.detailCode());

        assertFalse(solver.verify(eigen, changed));
        assertTrue(solver.verify(eigen, original));
    }

    @Test
    void exactZeroEntriesDoNotCreateSpuriousTerms() {
        EigenproblemRepresentation eigen = diagonal(
            List.of("a", "b", "c"),
            List.of("x", "y", "z"));

        Polynomial result = solver.solve(
            eigen,
            new BoundedCharacteristicPolynomialSolver.Budget(50_000))
            .characteristicPolynomial()
            .orElseThrow()
            .polynomial();
        Polynomial lambda = Polynomial.variable("lambda");
        Polynomial expected = Polynomial.variable("a").subtract(lambda)
            .multiply(Polynomial.variable("b").subtract(lambda))
            .multiply(Polynomial.variable("c").subtract(lambda));
        assertEquals(expected, result);
    }

    @Test
    void exactZeroCofactorsAvoidDenseFactorialWork() {
        EigenproblemRepresentation eigen = diagonal(
            List.of("a", "b", "c", "d", "e"),
            List.of("u", "v", "w", "x", "y"));

        Result result = solver.solve(
            eigen,
            new BoundedCharacteristicPolynomialSolver.Budget(100));

        assertEquals(Status.SOLVED, result.status());
        assertTrue(result.work().consumedWorkUnits() < 100);
        Polynomial lambda = Polynomial.variable("lambda");
        Polynomial expected = Polynomial.variable("a").subtract(lambda)
            .multiply(Polynomial.variable("b").subtract(lambda))
            .multiply(Polynomial.variable("c").subtract(lambda))
            .multiply(Polynomial.variable("d").subtract(lambda))
            .multiply(Polynomial.variable("e").subtract(lambda));
        assertEquals(
            expected,
            result.characteristicPolynomial().orElseThrow().polynomial());
    }

    private EigenproblemRepresentation diagonal(
        List<String> diagonalEntries,
        List<String> unknowns
    ) {
        if (diagonalEntries.size() != unknowns.size()) {
            throw new IllegalArgumentException(
                "diagonal and unknown dimensions must agree");
        }
        List<String> equations = new java.util.ArrayList<>();
        for (int index = 0; index < diagonalEntries.size(); index++) {
            equations.add(diagonalEntries.get(index)
                + "*"
                + unknowns.get(index)
                + " = lambda*"
                + unknowns.get(index));
        }
        return eigen(String.join("; ", equations), unknowns);
    }

    private EigenproblemRepresentation eigen(
        String expression,
        List<String> unknowns
    ) {
        List<Equation> equations = parser.parse(
            new InputRequest(InputType.SYSTEM, expression)).equations();
        SymbolicLinearSystem system = systemBridge.analyze(
            new SymbolicLinearSystemRepresentationBridge.Source(
                equations,
                unknowns),
            new Budget(10_000)).representation().orElseThrow();
        return eigenBridge.analyze(
            new EigenproblemRepresentationBridge.Source(
                system,
                "lambda",
                true,
                ModelDomain.GENERIC_LINEAR_ALGEBRA,
                Set.of()),
            new Budget(10_000)).representation().orElseThrow();
    }
}
