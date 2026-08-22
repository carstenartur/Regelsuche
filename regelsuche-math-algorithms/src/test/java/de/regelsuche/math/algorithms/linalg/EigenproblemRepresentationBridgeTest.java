package de.regelsuche.math.algorithms.linalg;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.ast.Equation;
import de.regelsuche.input.InputRequest;
import de.regelsuche.input.InputType;
import de.regelsuche.math.algorithms.equivalence.Polynomial;
import de.regelsuche.math.algorithms.linalg.EigenproblemRepresentation.ModelDomain;
import de.regelsuche.math.algorithms.linalg.EigenproblemRepresentation.ModelInterpretation;
import de.regelsuche.math.algorithms.linalg.EigenproblemRepresentation.OperatorProperty;
import de.regelsuche.math.algorithms.linalg.EigenproblemRepresentationBridge.Certificate;
import de.regelsuche.math.algorithms.linalg.EigenproblemRepresentationBridge.Source;
import de.regelsuche.parse.ExpressionParser;
import de.regelsuche.representation.RepresentationBridge.Budget;
import de.regelsuche.representation.RepresentationBridge.Result;
import de.regelsuche.representation.RepresentationBridge.Status;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class EigenproblemRepresentationBridgeTest {
    private final ExpressionParser parser = new ExpressionParser();
    private final SymbolicLinearSystemRepresentationBridge systemBridge =
        new SymbolicLinearSystemRepresentationBridge();
    private final EigenproblemRepresentationBridge eigenBridge =
        new EigenproblemRepresentationBridge();

    @Test
    void recognizesExactFiniteDimensionalEigenproblem() {
        Source source = source(
            "a*x + b*y = lambda*x; c*x + d*y = lambda*y",
            List.of("x", "y"),
            true,
            ModelDomain.GENERIC_LINEAR_ALGEBRA,
            Set.of());

        Result<EigenproblemRepresentation, Certificate> result =
            eigenBridge.analyze(source, new Budget(2_000));

        assertEquals(Status.REPRESENTED, result.status());
        EigenproblemRepresentation eigen = result.representation()
            .orElseThrow();
        assertEquals(2, eigen.dimension());
        assertEquals(List.of("x", "y"), eigen.vectorCoordinates());
        assertEquals("lambda", eigen.eigenvalueParameter());
        assertEquals(
            Polynomial.variable("a"),
            eigen.operator().get(0, 0));
        assertEquals(
            Polynomial.variable("b"),
            eigen.operator().get(0, 1));
        assertEquals(
            Polynomial.variable("c"),
            eigen.operator().get(1, 0));
        assertEquals(
            Polynomial.variable("d"),
            eigen.operator().get(1, 1));
        assertEquals(ModelInterpretation.NONE, eigen.modelInterpretation());
        assertEquals(List.of("vector != 0"), eigen.requiredAssumptions());
        assertTrue(eigen.unlockedCapabilities().contains(
            EigenproblemRepresentation
                .CAPABILITY_EIGENPROBLEM_RECOGNIZED));
        assertTrue(eigen.unlockedCapabilities().contains(
            EigenproblemRepresentation
                .CAPABILITY_CHARACTERISTIC_POLYNOMIAL));
        assertFalse(eigen.unlockedCapabilities().contains(
            EigenproblemRepresentation
                .CAPABILITY_QUANTUM_OPERATOR_MODEL));
        assertTrue(eigenBridge.verify(source, result));
    }

    @Test
    void symbolNamesDoNotCreateAQuantumInterpretation() {
        Source source = source(
            "H*psi = E*psi",
            List.of("psi"),
            true,
            ModelDomain.GENERIC_LINEAR_ALGEBRA,
            Set.of(OperatorProperty.HERMITIAN));

        EigenproblemRepresentation eigen = eigenBridge.analyze(
            source,
            new Budget(1_000)).representation().orElseThrow();

        assertEquals(ModelInterpretation.NONE, eigen.modelInterpretation());
        assertFalse(eigen.unlockedCapabilities().contains(
            EigenproblemRepresentation
                .CAPABILITY_QUANTUM_OPERATOR_MODEL));
        assertFalse(eigen.unlockedCapabilities().contains(
            EigenproblemRepresentation
                .CAPABILITY_HERMITIAN_SPECTRAL_MODEL));
    }

    @Test
    void explicitQuantumDomainControlsPhysicalInterpretation() {
        Source quantum = source(
            "H*psi = E*psi",
            List.of("psi"),
            true,
            ModelDomain.FINITE_DIMENSIONAL_QUANTUM,
            Set.of());
        EigenproblemRepresentation genericOperator = eigenBridge.analyze(
            quantum,
            new Budget(1_000)).representation().orElseThrow();
        assertEquals(
            ModelInterpretation.QUANTUM_OPERATOR,
            genericOperator.modelInterpretation());
        assertTrue(genericOperator.unlockedCapabilities().contains(
            EigenproblemRepresentation
                .CAPABILITY_QUANTUM_OPERATOR_MODEL));
        assertFalse(genericOperator.unlockedCapabilities().contains(
            EigenproblemRepresentation
                .CAPABILITY_HERMITIAN_SPECTRAL_MODEL));

        Source hermitian = source(
            "H*psi = E*psi",
            List.of("psi"),
            true,
            ModelDomain.FINITE_DIMENSIONAL_QUANTUM,
            Set.of(OperatorProperty.HERMITIAN));
        EigenproblemRepresentation observable = eigenBridge.analyze(
            hermitian,
            new Budget(1_000)).representation().orElseThrow();
        assertEquals(
            ModelInterpretation.HERMITIAN_QUANTUM_OBSERVABLE,
            observable.modelInterpretation());
        assertTrue(observable.unlockedCapabilities().contains(
            EigenproblemRepresentation
                .CAPABILITY_HERMITIAN_SPECTRAL_MODEL));
    }

    @Test
    void nonZeroVectorAssumptionIsMandatory() {
        Source source = source(
            "a*x = lambda*x",
            List.of("x"),
            false,
            ModelDomain.GENERIC_LINEAR_ALGEBRA,
            Set.of());

        Result<EigenproblemRepresentation, Certificate> result =
            eigenBridge.analyze(source, new Budget(1_000));

        assertEquals(Status.ASSUMPTION_REQUIRED, result.status());
        assertEquals("NON_ZERO_EIGENVECTOR_REQUIRED", result.detailCode());
        assertFalse(result.represented());
    }

    @Test
    void rejectsNonSquareNonHomogeneousAndWrongEigenvalueShapes() {
        Result<EigenproblemRepresentation, Certificate> nonSquare =
            eigenBridge.analyze(source(
                "a*x + b*y = lambda*x",
                List.of("x", "y"),
                true,
                ModelDomain.GENERIC_LINEAR_ALGEBRA,
                Set.of()), new Budget(1_000));
        assertEquals(Status.NOT_APPLICABLE, nonSquare.status());
        assertEquals(
            "EIGENPROBLEM_REQUIRES_SQUARE_SYSTEM",
            nonSquare.detailCode());

        Result<EigenproblemRepresentation, Certificate> nonHomogeneous =
            eigenBridge.analyze(source(
                "a*x = lambda*x + 1",
                List.of("x"),
                true,
                ModelDomain.GENERIC_LINEAR_ALGEBRA,
                Set.of()), new Budget(1_000));
        assertEquals(Status.NOT_APPLICABLE, nonHomogeneous.status());
        assertEquals(
            "EIGENPROBLEM_REQUIRES_HOMOGENEOUS_SYSTEM",
            nonHomogeneous.detailCode());

        Result<EigenproblemRepresentation, Certificate> offDiagonal =
            eigenBridge.analyze(source(
                "a*x + lambda*y = lambda*x; c*x + d*y = lambda*y",
                List.of("x", "y"),
                true,
                ModelDomain.GENERIC_LINEAR_ALGEBRA,
                Set.of()), new Budget(2_000));
        assertEquals(Status.NOT_APPLICABLE, offDiagonal.status());
        assertEquals(
            "EIGENVALUE_PARAMETER_OCCURS_OFF_DIAGONAL",
            offDiagonal.detailCode());

        Result<EigenproblemRepresentation, Certificate> wrongDiagonal =
            eigenBridge.analyze(source(
                "a*x = 2*lambda*x",
                List.of("x"),
                true,
                ModelDomain.GENERIC_LINEAR_ALGEBRA,
                Set.of()), new Budget(1_000));
        assertEquals(Status.NOT_APPLICABLE, wrongDiagonal.status());
        assertEquals(
            "DIAGONAL_IS_NOT_OPERATOR_MINUS_EIGENVALUE",
            wrongDiagonal.detailCode());
    }

    @Test
    void reverseEquationOrientationIsNotSilentlyNormalizedAsSamePattern() {
        Result<EigenproblemRepresentation, Certificate> result =
            eigenBridge.analyze(source(
                "lambda*x = a*x",
                List.of("x"),
                true,
                ModelDomain.GENERIC_LINEAR_ALGEBRA,
                Set.of()), new Budget(1_000));

        assertEquals(Status.NOT_APPLICABLE, result.status());
        assertEquals(
            "DIAGONAL_IS_NOT_OPERATOR_MINUS_EIGENVALUE",
            result.detailCode());
    }

    @Test
    void budgetExhaustionIsInconclusive() {
        Source source = source(
            "a*x = lambda*x",
            List.of("x"),
            true,
            ModelDomain.GENERIC_LINEAR_ALGEBRA,
            Set.of());

        Result<EigenproblemRepresentation, Certificate> result =
            eigenBridge.analyze(source, new Budget(0));

        assertEquals(Status.BUDGET_INCONCLUSIVE, result.status());
        assertFalse(result.represented());
    }

    @Test
    void independentVerificationRejectsTamperedCertificate() {
        Source source = source(
            "a*x = lambda*x",
            List.of("x"),
            true,
            ModelDomain.GENERIC_LINEAR_ALGEBRA,
            Set.of());
        Result<EigenproblemRepresentation, Certificate> original =
            eigenBridge.analyze(source, new Budget(1_000));
        Certificate certificate = original.certificate().orElseThrow();
        Certificate changedCertificate = new Certificate(
            certificate.schema(),
            certificate.bridgeId(),
            certificate.relation(),
            certificate.sourceSystemHash(),
            certificate.eigenvalueParameter(),
            certificate.vectorCoordinates(),
            certificate.operatorRows(),
            certificate.shiftedOperatorRows(),
            certificate.requiredAssumptions(),
            certificate.modelInterpretation(),
            certificate.declaredModelDomain(),
            certificate.declaredOperatorProperties(),
            certificate.unlockedCapabilities(),
            "0".repeat(64));
        Result<EigenproblemRepresentation, Certificate> changed =
            Result.represented(
                original.representation().orElseThrow(),
                changedCertificate,
                original.relation().orElseThrow(),
                original.work(),
                original.detailCode());

        assertFalse(eigenBridge.verify(source, changed));
        assertTrue(eigenBridge.verify(source, original));
    }

    @Test
    void eigenvalueParameterCannotAlsoBeADeclaredCoordinate() {
        SymbolicLinearSystem system = symbolic(
            "a*x = lambda*x",
            List.of("x", "lambda"));

        assertThrows(IllegalArgumentException.class, () -> new Source(
            system,
            "lambda",
            true,
            ModelDomain.GENERIC_LINEAR_ALGEBRA,
            Set.of()));
    }

    private Source source(
        String expression,
        List<String> unknowns,
        boolean nonZero,
        ModelDomain domain,
        Set<OperatorProperty> properties
    ) {
        String eigenvalue = expression.contains("E")
            ? "E"
            : "lambda";
        return new Source(
            symbolic(expression, unknowns),
            eigenvalue,
            nonZero,
            domain,
            properties);
    }

    private SymbolicLinearSystem symbolic(
        String expression,
        List<String> unknowns
    ) {
        List<Equation> equations = parser.parse(
            new InputRequest(InputType.SYSTEM, expression)).equations();
        return systemBridge.analyze(
            new SymbolicLinearSystemRepresentationBridge.Source(
                equations,
                unknowns),
            new Budget(5_000)).representation().orElseThrow();
    }
}
