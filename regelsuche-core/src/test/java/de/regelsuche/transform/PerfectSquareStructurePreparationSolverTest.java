package de.regelsuche.transform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.ast.Expr;
import de.regelsuche.canonical.ExpressionCanonicalizer;
import de.regelsuche.parse.ExpressionFormatter;
import de.regelsuche.parse.ExpressionParser;
import java.util.List;
import org.junit.jupiter.api.Test;

class PerfectSquareStructurePreparationSolverTest {
    private static final String SOURCE = "4 * x^4 * y^2 - 9 * z^2";
    private final ExpressionParser parser = new ExpressionParser();
    private final ExpressionCanonicalizer canonicalizer =
        new ExpressionCanonicalizer();

    @Test
    void exposesAndVerifiesExactMonomialSquareRoots() {
        PerfectSquareStructurePreparationSolver solver = solver();
        var attempt = solver.plan(term(SOURCE));
        assertEquals(status("PREPARED"), attempt.status());
        var application = attempt.application().orElseThrow();
        assertTrue(solver.verify(application));
        assertExpression("2 * x^2 * y", application.leftRoot());
        assertExpression("3 * z", application.rightRoot());
        assertExpression(
            "(2 * x^2 * y)^2 - (3 * z)^2",
            application.preparedSubtree());
        assertExpression(
            "(2 * x^2 * y - 3 * z) * (2 * x^2 * y + 3 * z)",
            application.resultSubtree());
        assertEquals(List.of(), application.assumptions());
        assertEquals(
            List.of(
                PerfectSquareStructurePreparationSolver.PREPARATION_RULE_ID,
                PerfectSquareStructurePreparationSolver.PRINCIPAL_RULE_ID),
            application.primitiveRuleIds());
    }

    @Test
    void handlesUnitCoefficientsAndClassifiesNonSquares() {
        var unit = solver().plan(term("x^4 - y^2"))
            .application().orElseThrow();
        assertExpression("x^2", unit.leftRoot());
        assertExpression("y", unit.rightRoot());

        assertAttempt("2 * x^2 - y^2", "NOT_APPLICABLE", true);
        assertAttempt("x^3 - y^2", "NOT_APPLICABLE", true);
        assertAttempt("sin(x) - y^2", "UNSUPPORTED", true);
        assertAttempt("x^2 - y^2", "DIRECT_MATCH_AVAILABLE", false);
    }

    @Test
    void exhaustedFactorWorkIsInconclusive() {
        var attempt = new PerfectSquareStructurePreparationSolver(
            new PerfectSquareStructurePreparationSolver.Budget(
                1,
                16,
                1_000_000))
            .plan(term(SOURCE));
        assertEquals(status("BUDGET_INCONCLUSIVE"), attempt.status());
        assertEquals(1, attempt.work().inspectedFactors());
        assertEquals(0, attempt.work().remainingFactorBudget());
    }

    @Test
    void corruptedCertificateIsRejected() {
        PerfectSquareStructurePreparationSolver solver = solver();
        var valid = solver.plan(term(SOURCE)).application().orElseThrow();
        var c = valid.certificate();
        var corrupted = new PerfectSquareStructurePreparationSolver.Certificate(
            c.schema(), c.solverId(), c.leftTermExpression(),
            c.rightTermExpression(), c.leftRootExpression(),
            c.rightRootExpression(), c.preparedExpression(),
            c.resultExpression(), c.leftMonomialDescriptor(),
            c.rightMonomialDescriptor(), c.leftRootMonomialDescriptor(),
            c.rightRootMonomialDescriptor(), "corrupted");
        var invalid = new PerfectSquareStructurePreparationSolver.PreparedApplication(
            valid.schema(), valid.solverId(), valid.principalRuleId(),
            valid.originalSubtree(), valid.preparedSubtree(),
            valid.resultSubtree(), valid.leftRoot(), valid.rightRoot(),
            valid.bindings(), valid.residualObligation(), valid.assumptions(),
            valid.primitiveRuleIds(), valid.budget(), corrupted, valid.work());
        assertFalse(solver.verify(invalid));
    }

    @Test
    void invalidAndUnsafeBudgetsAreRejected() {
        assertThrows(IllegalArgumentException.class,
            () -> new PerfectSquareStructurePreparationSolver.Budget(-1, 1, 1));
        assertThrows(IllegalArgumentException.class,
            () -> new PerfectSquareStructurePreparationSolver.Budget(1, 0, 1));
        assertThrows(IllegalArgumentException.class,
            () -> new PerfectSquareStructurePreparationSolver(
                new PerfectSquareStructurePreparationSolver.Budget(
                    1,
                    1,
                    ExactPositiveMonomial.MAX_EXACT_DOUBLE_INTEGER + 1)));
    }

    private void assertAttempt(
        String expression,
        String expectedStatus,
        boolean retainsObligation
    ) {
        var attempt = solver().plan(term(expression));
        assertEquals(status(expectedStatus), attempt.status());
        assertTrue(attempt.application().isEmpty());
        assertEquals(retainsObligation, attempt.residualObligation().isPresent());
    }

    private PerfectSquareStructurePreparationSolver solver() {
        return new PerfectSquareStructurePreparationSolver();
    }

    private Expr term(String expression) {
        return parser.parseTerm(expression);
    }

    private PerfectSquareStructurePreparationSolver.Status status(String name) {
        return PerfectSquareStructurePreparationSolver.Status.valueOf(name);
    }

    private void assertExpression(String expected, Expr actual) {
        assertEquals(
            canonicalizer.stableHash(expected),
            canonicalizer.stableHash(ExpressionFormatter.format(actual)));
    }
}
