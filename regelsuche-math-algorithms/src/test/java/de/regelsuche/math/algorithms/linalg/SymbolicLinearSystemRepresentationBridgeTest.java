package de.regelsuche.math.algorithms.linalg;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.ast.Equation;
import de.regelsuche.input.InputRequest;
import de.regelsuche.input.InputType;
import de.regelsuche.math.algorithms.equivalence.Polynomial;
import de.regelsuche.math.algorithms.linalg.SymbolicLinearSystemRepresentationBridge.Certificate;
import de.regelsuche.math.algorithms.linalg.SymbolicLinearSystemRepresentationBridge.Source;
import de.regelsuche.parse.ExpressionParser;
import de.regelsuche.representation.RepresentationBridge.Budget;
import de.regelsuche.representation.RepresentationBridge.Result;
import de.regelsuche.representation.RepresentationBridge.Status;
import java.util.List;
import org.junit.jupiter.api.Test;

class SymbolicLinearSystemRepresentationBridgeTest {
    private final ExpressionParser parser = new ExpressionParser();
    private final SymbolicLinearSystemRepresentationBridge bridge =
        new SymbolicLinearSystemRepresentationBridge();

    @Test
    void declaredUnknownsSeparateVectorCoordinatesFromScalarParameters() {
        Source source = source(
            "a*x + b*y = lambda*x; c*x + d*y = lambda*y",
            List.of("x", "y"));

        Result<SymbolicLinearSystem, Certificate> result = bridge.analyze(
            source,
            new Budget(5_000));

        assertEquals(Status.REPRESENTED, result.status());
        SymbolicLinearSystem system = result.representation().orElseThrow();
        assertEquals(List.of("x", "y"), system.unknowns());
        assertEquals(
            List.of("a", "b", "c", "d", "lambda"),
            system.scalarParameters());
        assertEquals(
            Polynomial.variable("a").subtract(
                Polynomial.variable("lambda")),
            system.coefficients().get(0, 0));
        assertEquals(
            Polynomial.variable("b"),
            system.coefficients().get(0, 1));
        assertEquals(
            Polynomial.variable("c"),
            system.coefficients().get(1, 0));
        assertEquals(
            Polynomial.variable("d").subtract(
                Polynomial.variable("lambda")),
            system.coefficients().get(1, 1));
        assertTrue(system.homogeneous());
        assertTrue(bridge.verify(source, result));
    }

    @Test
    void explicitUnknownOrderIsPreservedRatherThanInferredOrSorted() {
        Source source = source(
            "a*x + b*y = 1; c*x + d*y = 2",
            List.of("y", "x"));

        SymbolicLinearSystem system = bridge.analyze(
            source,
            new Budget(5_000)).representation().orElseThrow();

        assertEquals(List.of("y", "x"), system.unknowns());
        assertEquals(
            Polynomial.variable("b"),
            system.coefficients().get(0, 0));
        assertEquals(
            Polynomial.variable("a"),
            system.coefficients().get(0, 1));
    }

    @Test
    void productsOfParametersRemainValidScalarCoefficients() {
        Source source = source(
            "a*b*x + c*y = q; y = r",
            List.of("x", "y"));

        SymbolicLinearSystem system = bridge.analyze(
            source,
            new Budget(5_000)).representation().orElseThrow();

        assertEquals(
            Polynomial.variable("a").multiply(Polynomial.variable("b")),
            system.coefficients().get(0, 0));
        assertEquals(
            List.of("a", "b", "c", "q", "r"),
            system.scalarParameters());
        assertFalse(system.homogeneous());
    }

    @Test
    void undeclaredSymbolsAreParametersNotAdditionalUnknownCoordinates() {
        Source source = source("y*x = z", List.of("x"));

        SymbolicLinearSystem system = bridge.analyze(
            source,
            new Budget(2_000)).representation().orElseThrow();

        assertEquals(List.of("x"), system.unknowns());
        assertEquals(List.of("y", "z"), system.scalarParameters());
        assertEquals(
            Polynomial.variable("y"),
            system.coefficients().get(0, 0));
        assertEquals(
            Polynomial.variable("z"),
            system.rightHandSide().get(0));
    }

    @Test
    void nonlinearDependenceOnDeclaredUnknownsFailsClosed() {
        Result<SymbolicLinearSystem, Certificate> product = bridge.analyze(
            source("x*y = 1", List.of("x", "y")),
            new Budget(2_000));
        assertEquals(Status.NONLINEAR, product.status());
        assertEquals(
            "NONLINEAR_IN_DECLARED_UNKNOWNS",
            product.detailCode());

        Result<SymbolicLinearSystem, Certificate> square = bridge.analyze(
            source("a*x^2 = 1", List.of("x")),
            new Budget(2_000));
        assertEquals(Status.NONLINEAR, square.status());
    }

    @Test
    void unsupportedFunctionsAndSymbolicDenominatorsAreVisible() {
        Result<SymbolicLinearSystem, Certificate> function = bridge.analyze(
            source("sin(a)*x = 1", List.of("x")),
            new Budget(2_000));
        assertEquals(Status.DOMAIN_UNSUPPORTED, function.status());
        assertEquals(
            "FUNCTION_OUTSIDE_SYMBOLIC_POLYNOMIAL_FRAGMENT",
            function.detailCode());

        Result<SymbolicLinearSystem, Certificate> denominator = bridge.analyze(
            source("x/a = 1", List.of("x")),
            new Budget(2_000));
        assertEquals(Status.DOMAIN_UNSUPPORTED, denominator.status());
        assertEquals(
            "SYMBOLIC_OR_NON_CONSTANT_DENOMINATOR",
            denominator.detailCode());
    }

    @Test
    void budgetExhaustionIsInconclusiveAndProducesNoRepresentation() {
        Result<SymbolicLinearSystem, Certificate> result = bridge.analyze(
            source("a*x = 1", List.of("x")),
            new Budget(0));

        assertEquals(Status.BUDGET_INCONCLUSIVE, result.status());
        assertFalse(result.represented());
        assertEquals(0, result.work().consumedWorkUnits());
    }

    @Test
    void independentVerificationRejectsTamperedCertificate() {
        Source source = source("a*x = 1", List.of("x"));
        Result<SymbolicLinearSystem, Certificate> original = bridge.analyze(
            source,
            new Budget(2_000));
        Certificate certificate = original.certificate().orElseThrow();
        Certificate tamperedCertificate = new Certificate(
            certificate.schema(),
            certificate.bridgeId(),
            certificate.relation(),
            certificate.sourceEquations(),
            certificate.unknownOrder(),
            certificate.scalarParameters(),
            certificate.coefficientRows(),
            certificate.rightHandSide(),
            "0".repeat(64));
        Result<SymbolicLinearSystem, Certificate> tampered =
            Result.represented(
                original.representation().orElseThrow(),
                tamperedCertificate,
                original.relation().orElseThrow(),
                original.work(),
                original.detailCode());

        assertFalse(bridge.verify(source, tampered));
        assertTrue(bridge.verify(source, original));
    }

    @Test
    void sourceRequiresExplicitDistinctUnknownRoles() {
        List<Equation> equations = equations("a*x = 1");

        assertThrows(IllegalArgumentException.class,
            () -> new Source(equations, List.of()));
        assertThrows(IllegalArgumentException.class,
            () -> new Source(equations, List.of("x", "x")));
        assertThrows(IllegalArgumentException.class,
            () -> new Source(List.of(), List.of("x")));
    }

    private Source source(String expression, List<String> unknowns) {
        return new Source(equations(expression), unknowns);
    }

    private List<Equation> equations(String expression) {
        return parser.parse(new InputRequest(InputType.SYSTEM, expression))
            .equations();
    }
}
