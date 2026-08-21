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

class RationalCommonDenominatorPreparationSolverTest {
    private static final String SOURCE = "a / b + c / d";
    private final ExpressionParser parser = new ExpressionParser();
    private final ExpressionCanonicalizer canonicalizer =
        new ExpressionCanonicalizer();

    @Test
    void preparesAndVerifiesACommonDenominator() {
        RationalCommonDenominatorPreparationSolver solver = solver();
        var attempt = solver.plan(term(SOURCE));
        assertEquals(status("PREPARED"), attempt.status());
        var application = attempt.application().orElseThrow();
        assertTrue(solver.verify(application));
        assertExpression(
            "(a * d) / (b * d) + (c * b) / (b * d)",
            application.preparedSubtree());
        assertExpression(
            "(a * d + c * b) / (b * d)",
            application.resultSubtree());
        assertExpression("a * d", application.leftScaledNumerator());
        assertExpression("c * b", application.rightScaledNumerator());
        assertExpression("b * d", application.commonDenominator());
        assertEquals(List.of("b * d != 0"), application.assumptions());
        assertEquals(
            List.of(
                RationalCommonDenominatorPreparationSolver.PREPARATION_RULE_ID,
                RationalCommonDenominatorPreparationSolver.PRINCIPAL_RULE_ID),
            application.primitiveRuleIds());
        assertEquals(5, application.bindings().size());
    }

    @Test
    void preservesSubtractionOrderAndSupportsStructuredDenominators() {
        var subtraction = prepared("a / b - c / d");
        assertExpression(
            "(a * d - c * b) / (b * d)",
            subtraction.resultSubtree());

        var structured = prepared("a / (b + 1) + c / d");
        assertExpression(
            "(a * d + c * (b + 1)) / ((b + 1) * d)",
            structured.resultSubtree());
        assertEquals(
            List.of("(b + 1) * d != 0"),
            structured.assumptions());
    }

    @Test
    void classifiesDirectUnsupportedAndNonMatchingCases() {
        assertAttempt(
            "a / b + c / b",
            "DIRECT_MATCH_AVAILABLE",
            false);
        assertAttempt(
            "a / 0 + c / d",
            "UNSUPPORTED",
            true);
        assertAttempt(
            "a + c / d",
            "NOT_APPLICABLE",
            false);
        assertAttempt(
            "a / b * c / d",
            "NOT_APPLICABLE",
            false);
    }

    @Test
    void reportsInputAndConstructionBudgetExhaustion() {
        var inputLimited =
            new RationalCommonDenominatorPreparationSolver(
                new RationalCommonDenominatorPreparationSolver.Budget(
                    3,
                    384))
                .plan(term(SOURCE));
        assertEquals(
            status("BUDGET_INCONCLUSIVE"),
            inputLimited.status());
        assertEquals(
            0,
            inputLimited.work().remainingInputNodeBudget());

        var outputLimited =
            new RationalCommonDenominatorPreparationSolver(
                new RationalCommonDenominatorPreparationSolver.Budget(
                    96,
                    4))
                .plan(term(SOURCE));
        assertEquals(
            status("BUDGET_INCONCLUSIVE"),
            outputLimited.status());
        assertEquals(
            0,
            outputLimited.work().remainingConstructedNodeBudget());
    }

    @Test
    void corruptedCertificateAndBindingsAreRejected() {
        RationalCommonDenominatorPreparationSolver solver = solver();
        var valid = solver.plan(term(SOURCE))
            .application()
            .orElseThrow();
        var c = valid.certificate();
        var corrupted = new RationalCommonDenominatorPreparationSolver.Certificate(
            c.schema(),
            c.solverId(),
            c.operator(),
            c.originalExpression(),
            c.preparedExpression(),
            c.resultExpression(),
            c.commonDenominatorExpression(),
            c.originalStructureHash(),
            c.preparedStructureHash(),
            c.resultStructureHash(),
            c.commonDenominatorStructureHash(),
            "corrupted");
        var invalid =
            new RationalCommonDenominatorPreparationSolver.PreparedApplication(
                valid.schema(),
                valid.solverId(),
                valid.principalRuleId(),
                valid.originalSubtree(),
                valid.preparedSubtree(),
                valid.resultSubtree(),
                valid.leftScaledNumerator(),
                valid.rightScaledNumerator(),
                valid.commonDenominator(),
                valid.bindings(),
                valid.residualObligation(),
                valid.assumptions(),
                valid.primitiveRuleIds(),
                valid.budget(),
                corrupted,
                valid.work());
        assertFalse(solver.verify(invalid));

        var wrongBindings =
            new RationalCommonDenominatorPreparationSolver.PreparedApplication(
                valid.schema(),
                valid.solverId(),
                valid.principalRuleId(),
                valid.originalSubtree(),
                valid.preparedSubtree(),
                valid.resultSubtree(),
                valid.leftScaledNumerator(),
                valid.rightScaledNumerator(),
                valid.commonDenominator(),
                java.util.Map.of(
                    "A", term("c"),
                    "B", term("b"),
                    "C", term("a"),
                    "D", term("d"),
                    "Q", valid.commonDenominator()),
                valid.residualObligation(),
                valid.assumptions(),
                valid.primitiveRuleIds(),
                valid.budget(),
                valid.certificate(),
                valid.work());
        assertFalse(solver.verify(wrongBindings));
    }

    @Test
    void invalidBudgetsAreRejected() {
        assertThrows(
            IllegalArgumentException.class,
            () -> new RationalCommonDenominatorPreparationSolver.Budget(
                0,
                1));
        assertThrows(
            IllegalArgumentException.class,
            () -> new RationalCommonDenominatorPreparationSolver.Budget(
                1,
                0));
    }

    private void assertAttempt(
        String expression,
        String expectedStatus,
        boolean retainsObligation
    ) {
        var attempt = solver().plan(term(expression));
        assertEquals(status(expectedStatus), attempt.status());
        assertTrue(attempt.application().isEmpty());
        assertEquals(
            retainsObligation,
            attempt.residualObligation().isPresent());
    }

    private RationalCommonDenominatorPreparationSolver.PreparedApplication
            prepared(String expression) {
        return solver().plan(term(expression))
            .application()
            .orElseThrow();
    }

    private RationalCommonDenominatorPreparationSolver solver() {
        return new RationalCommonDenominatorPreparationSolver();
    }

    private Expr term(String expression) {
        return parser.parseTerm(expression);
    }

    private RationalCommonDenominatorPreparationSolver.Status status(
        String name
    ) {
        return RationalCommonDenominatorPreparationSolver.Status.valueOf(name);
    }

    private void assertExpression(String expected, Expr actual) {
        assertEquals(
            canonicalizer.stableHash(expected),
            canonicalizer.stableHash(
                ExpressionFormatter.format(actual)));
    }
}
