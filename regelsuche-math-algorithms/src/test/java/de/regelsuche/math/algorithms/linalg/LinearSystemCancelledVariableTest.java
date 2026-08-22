package de.regelsuche.math.algorithms.linalg;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.ast.Equation;
import de.regelsuche.input.InputRequest;
import de.regelsuche.input.InputType;
import de.regelsuche.math.algorithms.equivalence.Rational;
import de.regelsuche.math.algorithms.linalg.ExactLinearSystem.SolutionClassification;
import de.regelsuche.parse.ExpressionParser;
import de.regelsuche.representation.RepresentationBridge.Budget;
import java.util.List;
import org.junit.jupiter.api.Test;

class LinearSystemCancelledVariableTest {
    private final ExpressionParser parser = new ExpressionParser();
    private final LinearSystemRepresentationBridge bridge =
        new LinearSystemRepresentationBridge();

    @Test
    void cancelledVariableRemainsAFreeCoordinate() {
        List<Equation> source = system("x - x = 0");

        var result = bridge.analyze(source, new Budget(1_000));
        ExactLinearSystem represented = result.representation().orElseThrow();

        assertEquals(List.of("x"), represented.variables());
        assertEquals(Rational.ZERO, represented.coefficients().get(0, 0));
        assertEquals(Rational.ZERO, represented.rightHandSide().get(0));
        assertEquals(0, represented.coefficientRank());
        assertEquals(0, represented.augmentedRank());
        assertEquals(
            SolutionClassification.UNDERDETERMINED,
            represented.solutionClassification());
        assertTrue(bridge.verify(source, result));
    }

    @Test
    void cancelledVariableWithFalseConstantConstraintIsInconsistent() {
        List<Equation> source = system("x - x = 1");

        var result = bridge.analyze(source, new Budget(1_000));
        ExactLinearSystem represented = result.representation().orElseThrow();

        assertEquals(List.of("x"), represented.variables());
        assertEquals(0, represented.coefficientRank());
        assertEquals(1, represented.augmentedRank());
        assertEquals(
            SolutionClassification.INCONSISTENT,
            represented.solutionClassification());
        assertTrue(bridge.verify(source, result));
    }

    @Test
    void cancelledVariableIsNotDroppedBesideAnotherConstrainedVariable() {
        List<Equation> source = system("x - x = 0; y = 2");

        var result = bridge.analyze(source, new Budget(1_000));
        ExactLinearSystem represented = result.representation().orElseThrow();

        assertEquals(List.of("x", "y"), represented.variables());
        assertEquals(Rational.ZERO, represented.coefficients().get(0, 0));
        assertEquals(Rational.ZERO, represented.coefficients().get(0, 1));
        assertEquals(Rational.ZERO, represented.coefficients().get(1, 0));
        assertEquals(Rational.ONE, represented.coefficients().get(1, 1));
        assertEquals(
            SolutionClassification.UNDERDETERMINED,
            represented.solutionClassification());
        assertTrue(bridge.verify(source, result));
    }

    private List<Equation> system(String input) {
        return parser.parse(new InputRequest(InputType.SYSTEM, input))
            .equations();
    }
}
