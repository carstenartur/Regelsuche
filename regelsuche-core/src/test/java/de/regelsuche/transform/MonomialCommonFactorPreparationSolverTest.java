package de.regelsuche.transform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.ast.Expr;
import de.regelsuche.ast.NumberExpr;
import de.regelsuche.canonical.ExpressionCanonicalizer;
import de.regelsuche.parse.ExpressionFormatter;
import de.regelsuche.parse.ExpressionParser;
import java.util.List;
import org.junit.jupiter.api.Test;

class MonomialCommonFactorPreparationSolverTest {
    private static final String SOURCE = "x^2 * y + x * z";
    private final ExpressionParser parser = new ExpressionParser();
    private final ExpressionCanonicalizer canonicalizer =
        new ExpressionCanonicalizer();

    @Test
    void synthesizesAndVerifiesACommonVariablePowerFactor() {
        MonomialCommonFactorPreparationSolver solver = solver();
        var attempt = solver.plan(term(SOURCE));
        assertEquals(status("PREPARED"), attempt.status());
        var application = attempt.application().orElseThrow();
        assertTrue(solver.verify(application));
        assertExpression("x", application.commonFactor());
        assertExpression("x * y", application.leftRemainder());
        assertExpression("z", application.rightRemainder());
        assertExpression("x * (x * y) + x * z", application.preparedSubtree());
        assertExpression("x * (x * y + z)", application.resultSubtree());
        assertEquals(List.of(), application.assumptions());
        assertEquals(
            List.of(
                MonomialCommonFactorPreparationSolver.PREPARATION_RULE_ID,
                MonomialCommonFactorPreparationSolver.PRINCIPAL_RULE_ID),
            application.primitiveRuleIds());
    }

    @Test
    void extractsNumericVariableGcdAndCertifiedUnitQuotient() {
        var numeric = prepared("6 * x^2 * y + 9 * x * z");
        assertExpression("3 * x", numeric.commonFactor());
        assertExpression("2 * x * y", numeric.leftRemainder());
        assertExpression("3 * z", numeric.rightRemainder());
        assertExpression(
            "(3 * x) * (2 * x * y + 3 * z)",
            numeric.resultSubtree());

        var unit = prepared("x^2 + x");
        assertExpression("x", unit.commonFactor());
        assertExpression("x", unit.leftRemainder());
        assertExpression("1", unit.rightRemainder());
        assertExpression("x * (x + 1)", unit.resultSubtree());
    }

    @Test
    void classifiesNoFactorUnsupportedDirectAndLimitedCases() {
        assertAttempt("x^2 + y", "NOT_APPLICABLE", true);
        assertAttempt("sin(x) + x", "UNSUPPORTED", true);
        assertAttempt("x * y + x * z", "DIRECT_MATCH_AVAILABLE", false);

        var limited = new MonomialCommonFactorPreparationSolver(
            new MonomialCommonFactorPreparationSolver.Budget(1, 16, 1_000_000))
            .plan(term(SOURCE));
        assertEquals(status("BUDGET_INCONCLUSIVE"), limited.status());
        assertEquals(1, limited.work().inspectedFactors());
        assertEquals(0, limited.work().remainingFactorBudget());
    }

    @Test
    void rejectsAmbiguousAndOutOfRangeDoubleIntegerCoefficients() {
        var limits = new ExactPositiveMonomial.Limits(
            1,
            1,
            ExactPositiveMonomial.MAX_EXACT_DOUBLE_INTEGER);
        var unsafe = new ExactPositiveMonomial.Parser(limits).parse(
            new NumberExpr((double) (1L << 53)));
        assertEquals(
            ExactPositiveMonomial.ParseStatus.UNSUPPORTED,
            unsafe.status());
        assertThrows(
            IllegalArgumentException.class,
            () -> new ExactPositiveMonomial.Limits(
                1,
                1,
                ExactPositiveMonomial.MAX_EXACT_DOUBLE_INTEGER + 1));
    }

    @Test
    void corruptedCertificateIsRejected() {
        MonomialCommonFactorPreparationSolver solver = solver();
        var valid = solver.plan(term(SOURCE)).application().orElseThrow();
        var c = valid.certificate();
        var corrupted = new MonomialCommonFactorPreparationSolver.Certificate(
            c.schema(), c.solverId(), c.leftTermExpression(),
            c.rightTermExpression(), c.commonFactorExpression(),
            c.leftRemainderExpression(), c.rightRemainderExpression(),
            c.preparedExpression(), c.resultExpression(),
            c.leftMonomialDescriptor(), c.rightMonomialDescriptor(),
            c.commonMonomialDescriptor(), "corrupted");
        var invalid = new MonomialCommonFactorPreparationSolver.PreparedApplication(
            valid.schema(), valid.solverId(), valid.principalRuleId(),
            valid.originalSubtree(), valid.preparedSubtree(),
            valid.resultSubtree(), valid.commonFactor(), valid.leftRemainder(),
            valid.rightRemainder(), valid.bindings(), valid.residualObligation(),
            valid.assumptions(), valid.primitiveRuleIds(), valid.budget(),
            corrupted, valid.work());
        assertFalse(solver.verify(invalid));
    }

    @Test
    void invalidBudgetsAreRejected() {
        assertThrows(IllegalArgumentException.class,
            () -> new MonomialCommonFactorPreparationSolver.Budget(-1, 1, 1));
        assertThrows(IllegalArgumentException.class,
            () -> new MonomialCommonFactorPreparationSolver.Budget(1, 0, 1));
        assertThrows(IllegalArgumentException.class,
            () -> new MonomialCommonFactorPreparationSolver.Budget(1, 1, 0));
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

    private MonomialCommonFactorPreparationSolver.PreparedApplication prepared(
        String expression
    ) {
        return solver().plan(term(expression)).application().orElseThrow();
    }

    private MonomialCommonFactorPreparationSolver solver() {
        return new MonomialCommonFactorPreparationSolver();
    }

    private Expr term(String expression) {
        return parser.parseTerm(expression);
    }

    private MonomialCommonFactorPreparationSolver.Status status(String name) {
        return MonomialCommonFactorPreparationSolver.Status.valueOf(name);
    }

    private void assertExpression(String expected, Expr actual) {
        assertEquals(
            canonicalizer.stableHash(expected),
            canonicalizer.stableHash(ExpressionFormatter.format(actual)));
    }
}
