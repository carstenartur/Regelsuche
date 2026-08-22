package de.regelsuche.math.algorithms.linalg;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.ast.Equation;
import de.regelsuche.input.InputRequest;
import de.regelsuche.input.InputType;
import de.regelsuche.math.algorithms.equivalence.Rational;
import de.regelsuche.math.algorithms.linalg.ExactLinearSystem.ExactVector;
import de.regelsuche.math.algorithms.linalg.ExactLinearSystem.SolutionClassification;
import de.regelsuche.parse.ExpressionParser;
import de.regelsuche.representation.RepresentationBridge.Budget;
import de.regelsuche.representation.RepresentationBridge.Relation;
import de.regelsuche.representation.RepresentationBridge.Result;
import de.regelsuche.representation.RepresentationBridge.Status;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class LinearSystemRepresentationBridgeTest {
    private final ExpressionParser parser = new ExpressionParser();
    private final LinearSystemRepresentationBridge bridge =
        new LinearSystemRepresentationBridge();

    @Test
    void representsBasicSystemAsExactMatrixEquation() {
        List<Equation> source = system(
            "2*x + 3*y = 7; 4*x - y = 5");

        Result<ExactLinearSystem,
            LinearSystemRepresentationBridge.Certificate> result =
                bridge.analyze(source, new Budget(1_000));

        assertEquals(Status.REPRESENTED, result.status());
        assertEquals(Relation.SOLUTION_SET_EQUIVALENCE,
            result.relation().orElseThrow());
        ExactLinearSystem represented = result.representation().orElseThrow();
        assertEquals(List.of("x", "y"), represented.variables());
        assertEquals(Rational.of(2), represented.coefficients().get(0, 0));
        assertEquals(Rational.of(3), represented.coefficients().get(0, 1));
        assertEquals(Rational.of(4), represented.coefficients().get(1, 0));
        assertEquals(Rational.of(-1), represented.coefficients().get(1, 1));
        assertEquals(Rational.of(7), represented.rightHandSide().get(0));
        assertEquals(Rational.of(5), represented.rightHandSide().get(1));
        assertEquals(2, represented.coefficientRank());
        assertEquals(2, represented.augmentedRank());
        assertEquals(
            SolutionClassification.UNIQUE,
            represented.solutionClassification());
        assertTrue(bridge.verify(source, result));
    }

    @Test
    void choosesDeterministicVariableOrderAndSupportsExactRationals() {
        List<Equation> source = system(
            "y + x/2 = 3; x - y/3 = 1");

        Result<ExactLinearSystem,
            LinearSystemRepresentationBridge.Certificate> result =
                bridge.analyze(source, new Budget(1_000));

        ExactLinearSystem represented = result.representation().orElseThrow();
        assertEquals(List.of("x", "y"), represented.variables());
        assertEquals(
            new Rational(java.math.BigInteger.ONE,
                java.math.BigInteger.TWO),
            represented.coefficients().get(0, 0));
        assertEquals(Rational.ONE, represented.coefficients().get(0, 1));
        assertEquals(Rational.ONE, represented.coefficients().get(1, 0));
        assertEquals(
            new Rational(java.math.BigInteger.ONE.negate(),
                java.math.BigInteger.valueOf(3)),
            represented.coefficients().get(1, 1));
        assertTrue(bridge.verify(source, result));
    }

    @Test
    void distinguishesUnderdeterminedRedundantAndInconsistentSystems() {
        ExactLinearSystem underdetermined = bridge.analyze(
            system("x + y = 1"),
            new Budget(1_000)).representation().orElseThrow();
        assertEquals(
            SolutionClassification.UNDERDETERMINED,
            underdetermined.solutionClassification());
        assertEquals(
            ExactLinearSystem.DimensionShape.MORE_VARIABLES_THAN_EQUATIONS,
            underdetermined.dimensionShape());

        ExactLinearSystem redundant = bridge.analyze(
            system("x = 1; 2*x = 2"),
            new Budget(1_000)).representation().orElseThrow();
        assertEquals(
            SolutionClassification.UNIQUE,
            redundant.solutionClassification());
        assertEquals(1, redundant.redundantEquationCount());

        ExactLinearSystem inconsistent = bridge.analyze(
            system("x = 1; x = 2"),
            new Budget(1_000)).representation().orElseThrow();
        assertEquals(
            SolutionClassification.INCONSISTENT,
            inconsistent.solutionClassification());
        assertEquals(1, inconsistent.coefficientRank());
        assertEquals(2, inconsistent.augmentedRank());
    }

    @Test
    void preservesRectangularRowsAndSourceProvenance() {
        List<Equation> source = system(
            "y + 2*x = 3; x - y = 0; 3*x = 9");

        ExactLinearSystem represented = bridge.analyze(
            source,
            new Budget(2_000)).representation().orElseThrow();

        assertEquals(3, represented.equationCount());
        assertEquals(2, represented.variableCount());
        assertEquals(
            ExactLinearSystem.DimensionShape.MORE_EQUATIONS_THAN_VARIABLES,
            represented.dimensionShape());
        assertEquals(3, represented.rowOrigins().size());
        assertEquals(0, represented.rowOrigins().get(0).sourceIndex());
        assertEquals("y + 2 * x = 3", represented.rowOrigins()
            .get(0).sourceEquation());
    }

    @Test
    void rejectsNonlinearAndUnsupportedSystemsWithoutGuessing() {
        Result<ExactLinearSystem,
            LinearSystemRepresentationBridge.Certificate> product =
                bridge.analyze(
                    system("x*y = 1; x + y = 2"),
                    new Budget(1_000));
        assertEquals(Status.NONLINEAR, product.status());
        assertEquals(
            "PRODUCT_OF_NON_CONSTANT_FORMS",
            product.detailCode());

        Result<ExactLinearSystem,
            LinearSystemRepresentationBridge.Certificate> power =
                bridge.analyze(system("x^2 = 1"), new Budget(1_000));
        assertEquals(Status.NONLINEAR, power.status());

        Result<ExactLinearSystem,
            LinearSystemRepresentationBridge.Certificate> function =
                bridge.analyze(system("sin(x) = 0"), new Budget(1_000));
        assertEquals(Status.DOMAIN_UNSUPPORTED, function.status());
    }

    @Test
    void reportsBudgetExhaustionAsInconclusive() {
        Result<ExactLinearSystem,
            LinearSystemRepresentationBridge.Certificate> result =
                bridge.analyze(system("x + y = 1"), new Budget(1));

        assertEquals(Status.BUDGET_INCONCLUSIVE, result.status());
        assertEquals(1, result.work().consumedWorkUnits());
        assertEquals(0, result.work().remainingWorkUnits());
        assertFalse(result.represented());
    }

    @Test
    void independentVerificationRejectsTamperedMatrixData() {
        List<Equation> source = system("2*x + y = 3; x - y = 0");
        Result<ExactLinearSystem,
            LinearSystemRepresentationBridge.Certificate> original =
                bridge.analyze(source, new Budget(1_000));
        ExactLinearSystem represented = original.representation().orElseThrow();
        List<Rational> changedValues = new ArrayList<>(
            represented.rightHandSide().values());
        changedValues.set(0, Rational.of(4));
        ExactLinearSystem changed = new ExactLinearSystem(
            represented.coefficients(),
            represented.variables(),
            new ExactVector(changedValues),
            represented.rowOrigins(),
            represented.coefficientRank(),
            represented.augmentedRank(),
            represented.solutionClassification());
        Result<ExactLinearSystem,
            LinearSystemRepresentationBridge.Certificate> tampered =
                Result.represented(
                    changed,
                    original.certificate().orElseThrow(),
                    original.relation().orElseThrow(),
                    original.work(),
                    original.detailCode());

        assertFalse(bridge.verify(source, tampered));
        assertTrue(bridge.verify(source, original));
    }

    @Test
    void constantOnlySystemIsNotMisreportedAsLinearMap() {
        Result<ExactLinearSystem,
            LinearSystemRepresentationBridge.Certificate> result =
                bridge.analyze(system("1 = 1; 2 = 3"), new Budget(100));

        assertEquals(Status.NOT_APPLICABLE, result.status());
        assertEquals("SYSTEM_CONTAINS_NO_VARIABLES", result.detailCode());
    }

    private List<Equation> system(String input) {
        return parser.parse(new InputRequest(InputType.SYSTEM, input))
            .equations();
    }
}
