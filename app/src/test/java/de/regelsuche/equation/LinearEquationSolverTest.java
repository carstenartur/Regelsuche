package de.regelsuche.equation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.assumption.Assumption;
import de.regelsuche.ast.Equation;
import de.regelsuche.demo.MathDomainDemos;
import de.regelsuche.parse.ExpressionParser;
import de.regelsuche.scalar.ExactRational;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class LinearEquationSolverTest {

    private final ExpressionParser parser = new ExpressionParser();
    private final LinearEquationSolver solver = new LinearEquationSolver();

    @Test
    void equationDemoSolvesLinearEquation() {
        // x + 3 = 7 -> x = 4
        MathDomainDemos demos = new MathDomainDemos();
        MathDomainDemos.Result result = demos.linearEquation();
        assertEquals("x + 3 = 7", result.inputExpression());
        assertEquals("x = 4", result.resultExpression());
    }

    @Test
    void solverProducesExactValueAndTracksDivisionAssumption() {
        Equation equation = parser.parseEquation("2*x = 10");
        Optional<LinearEquationSolver.Solution> solution = solver.solve(equation, "x");
        assertTrue(solution.isPresent());
        LinearEquationSolver.Solution solved = solution.get();
        assertEquals(LinearEquationSolver.Status.UNIQUE, solved.status());
        assertEquals(ExactRational.integer(5), solved.value());
        assertTrue(solved.assumptions().stream()
            .anyMatch(a -> a.kind() == Assumption.Kind.NON_ZERO),
            "expected non-zero assumption for divisor");
    }

    @Test
    void rationalSolutionsAndAdjacentLargeCoefficientsRemainExact() {
        var third = solver.solve(parser.parseEquation("3*x = 1"), "x").orElseThrow();
        assertEquals(ExactRational.parse("1/3"), third.value());
        var adjacent = solver.solve(parser.parseEquation(
            "9007199254740993*x = 9007199254740992*x + 1"), "x").orElseThrow();
        assertEquals(LinearEquationSolver.Status.UNIQUE, adjacent.status());
        assertEquals(ExactRational.ONE, adjacent.value());
    }

    @Test
    void identityAndNoSolutionAreReported() {
        Equation identity = parser.parseEquation("x + 1 = x + 1");
        Optional<LinearEquationSolver.Solution> id = solver.solve(identity, "x");
        assertTrue(id.isPresent());
        assertEquals(LinearEquationSolver.Status.IDENTITY, id.get().status());

        Equation contradiction = parser.parseEquation("x + 1 = x + 2");
        Optional<LinearEquationSolver.Solution> contra = solver.solve(contradiction, "x");
        assertTrue(contra.isPresent());
        assertEquals(LinearEquationSolver.Status.NO_SOLUTION, contra.get().status());
    }

    @Test
    void nonLinearEquationsAreRejected() {
        Equation nonLinear = parser.parseEquation("x^2 = 4");
        Optional<LinearEquationSolver.Solution> result = solver.solve(nonLinear, "x");
        assertFalse(result.isPresent(),
            "solver must refuse non-linear input rather than guess");
    }
}
