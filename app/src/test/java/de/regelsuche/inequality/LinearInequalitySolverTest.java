package de.regelsuche.inequality;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.ast.Expr;
import de.regelsuche.ast.NumberExpr;
import de.regelsuche.input.InputRequest;
import de.regelsuche.input.InputType;
import de.regelsuche.parse.ExpressionParser;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class LinearInequalitySolverTest {

    private final ExpressionParser parser = new ExpressionParser();
    private final InequalityRewriteEngine engine = new InequalityRewriteEngine();
    private final LinearInequalitySolver solver = new LinearInequalitySolver();

    private Expr parse(String text) {
        return parser.parse(new InputRequest(InputType.TERM, text)).terms().get(0);
    }

    @Test
    void inequalityFlipsWhenMultiplyingNegative() {
        Inequality input = new Inequality(parse("x"), Comparator.LT, parse("3"));
        InequalityStep step = engine.multiplyBothSides(input, new NumberExpr(-2));
        // The comparator must flip from < to > when multiplying by a strictly negative literal.
        assertEquals(Comparator.GT, step.inequality().comparator());

        // End-to-end: -2*x < 4 -> x > -2.
        Inequality original = new Inequality(parse("-2*x"), Comparator.LT, parse("4"));
        Optional<LinearInequalitySolver.Solution> solution = solver.solve(original, "x");
        assertTrue(solution.isPresent());
        LinearInequalitySolver.Solution solved = solution.get();
        assertNotNull(solved.solved());
        assertEquals(Comparator.GT, solved.solved().comparator());
        assertEquals(-2.0, solved.value());
        assertEquals("x > -2", solved.solved().formatted());
    }

    @Test
    void addAndSubtractKeepComparator() {
        Inequality input = new Inequality(parse("x + 1"), Comparator.LE, parse("5"));
        InequalityStep added = engine.addBothSides(input, parse("2"));
        InequalityStep subtracted = engine.subtractBothSides(input, parse("1"));
        assertEquals(Comparator.LE, added.inequality().comparator());
        assertEquals(Comparator.LE, subtracted.inequality().comparator());
    }

    @Test
    void multiplyingByZeroIsRejected() {
        Inequality input = new Inequality(parse("x"), Comparator.LT, parse("3"));
        assertThrows(IllegalArgumentException.class,
            () -> engine.multiplyBothSides(input, new NumberExpr(0)));
    }

    @Test
    void divideBothSidesSurfacesNonZeroAssumption() {
        Inequality input = new Inequality(parse("2*x"), Comparator.GT, parse("4"));
        InequalityStep step = engine.divideBothSides(input, new NumberExpr(2));
        assertTrue(step.assumptions().stream()
            .anyMatch(a -> a.kind() == de.regelsuche.assumption.Assumption.Kind.NON_ZERO));
        assertEquals(Comparator.GT, step.inequality().comparator());
    }
}
