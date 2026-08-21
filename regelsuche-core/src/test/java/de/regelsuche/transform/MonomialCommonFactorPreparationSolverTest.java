package de.regelsuche.transform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.canonical.ExpressionCanonicalizer;
import de.regelsuche.parse.ExpressionFormatter;
import de.regelsuche.parse.ExpressionParser;
import java.util.List;
import org.junit.jupiter.api.Test;

class MonomialCommonFactorPreparationSolverTest {
    private final ExpressionParser parser = new ExpressionParser();
    private final ExpressionCanonicalizer canonicalizer =
        new ExpressionCanonicalizer();

    @Test
    void synthesizesACommonVariablePowerFactor() {
        MonomialCommonFactorPreparationSolver solver =
            new MonomialCommonFactorPreparationSolver();

        MonomialCommonFactorPreparationSolver.PlanAttempt attempt =
            solver.plan(parser.parseTerm("x^2 * y + x * z"));

        assertEquals(
            MonomialCommonFactorPreparationSolver.Status.PREPARED,
            attempt.status());
        MonomialCommonFactorPreparationSolver.PreparedApplication application =
            attempt.application().orElseThrow();
        assertTrue(solver.verify(application));
        assertSameExpression("x", application.commonFactor());
        assertSameExpression("x * y", application.leftRemainder());
        assertSameExpression("z", application.rightRemainder());
        assertSameExpression(
            "x * (x * y) + x * z",
            application.preparedSubtree());
        assertSameExpression(
            "x * (x * y + z)",
            application.resultSubtree());
        assertEquals(List.of(), application.assumptions());
        assertEquals(
            List.of(
                MonomialCommonFactorPreparationSolver.PREPARATION_RULE_ID,
                MonomialCommonFactorPreparationSolver.PRINCIPAL_RULE_ID),
            application.primitiveRuleIds());
    }

    @Test
    void extractsTheNumericAndVariableGreatestCommonMonomial() {
        MonomialCommonFactorPreparationSolver.PreparedApplication application =
            new MonomialCommonFactorPreparationSolver()
                .plan(parser.parseTerm("6 * x^2 * y + 9 * x * z"))
                .application()
                .orElseThrow();

        assertSameExpression("3 * x", application.commonFactor());
        assertSameExpression("2 * x * y", application.leftRemainder());
        assertSameExpression("3 * z", application.rightRemainder());
        assertSameExpression(
            "(3 * x) * (2 * x * y + 3 * z)",
            application.resultSubtree());
    }

    @Test
    void introducesOnlyTheCertifiedUnitRemainder() {
        MonomialCommonFactorPreparationSolver.PreparedApplication application =
            new MonomialCommonFactorPreparationSolver()
                .plan(parser.parseTerm("x^2 + x"))
                .application()
                .orElseThrow();

        assertSameExpression("x", application.commonFactor());
        assertSameExpression("x", application.leftRemainder());
        assertSameExpression("1", application.rightRemainder());
        assertSameExpression("x * (x + 1)", application.resultSubtree());
    }

    @Test
    void reportsNoCommonMonomialWithoutGuessing() {
        MonomialCommonFactorPreparationSolver.PlanAttempt attempt =
            new MonomialCommonFactorPreparationSolver()
                .plan(parser.parseTerm("x^2 + y"));

        assertEquals(
            MonomialCommonFactorPreparationSolver.Status.NOT_APPLICABLE,
            attempt.status());
        assertTrue(attempt.application().isEmpty());
        assertTrue(attempt.residualObligation().isPresent());
    }

    @Test
    void unsupportedTermsFailClosed() {
        MonomialCommonFactorPreparationSolver.PlanAttempt attempt =
            new MonomialCommonFactorPreparationSolver()
                .plan(parser.parseTerm("sin(x) + x"));

        assertEquals(
            MonomialCommonFactorPreparationSolver.Status.UNSUPPORTED,
            attempt.status());
        assertTrue(attempt.application().isEmpty());
    }

    @Test
    void factorLimitProducesAnInconclusiveOutcome() {
        MonomialCommonFactorPreparationSolver solver =
            new MonomialCommonFactorPreparationSolver(
                new MonomialCommonFactorPreparationSolver.Budget(
                    1,
                    16,
                    1_000_000));

        MonomialCommonFactorPreparationSolver.PlanAttempt attempt =
            solver.plan(parser.parseTerm("x^2 * y + x * z"));

        assertEquals(
            MonomialCommonFactorPreparationSolver.Status.BUDGET_INCONCLUSIVE,
            attempt.status());
        assertEquals(1, attempt.work().inspectedFactors());
        assertEquals(0, attempt.work().remainingFactorBudget());
    }

    @Test
    void directCommonFactorRemainsTheCheapFirstPath() {
        MonomialCommonFactorPreparationSolver.PlanAttempt attempt =
            new MonomialCommonFactorPreparationSolver()
                .plan(parser.parseTerm("x * y + x * z"));

        assertEquals(
            MonomialCommonFactorPreparationSolver.Status.DIRECT_MATCH_AVAILABLE,
            attempt.status());
        assertTrue(attempt.application().isEmpty());
    }

    @Test
    void corruptedCertificateIsRejected() {
        MonomialCommonFactorPreparationSolver solver =
            new MonomialCommonFactorPreparationSolver();
        MonomialCommonFactorPreparationSolver.PreparedApplication valid =
            solver.plan(parser.parseTerm("x^2 * y + x * z"))
                .application()
                .orElseThrow();
        MonomialCommonFactorPreparationSolver.Certificate certificate =
            valid.certificate();
        MonomialCommonFactorPreparationSolver.Certificate corrupted =
            new MonomialCommonFactorPreparationSolver.Certificate(
                certificate.schema(),
                certificate.solverId(),
                certificate.leftTermExpression(),
                certificate.rightTermExpression(),
                certificate.commonFactorExpression(),
                certificate.leftRemainderExpression(),
                certificate.rightRemainderExpression(),
                certificate.preparedExpression(),
                certificate.resultExpression(),
                certificate.leftMonomialDescriptor(),
                certificate.rightMonomialDescriptor(),
                certificate.commonMonomialDescriptor(),
                "corrupted");
        MonomialCommonFactorPreparationSolver.PreparedApplication invalid =
            new MonomialCommonFactorPreparationSolver.PreparedApplication(
                valid.schema(),
                valid.solverId(),
                valid.principalRuleId(),
                valid.originalSubtree(),
                valid.preparedSubtree(),
                valid.resultSubtree(),
                valid.commonFactor(),
                valid.leftRemainder(),
                valid.rightRemainder(),
                valid.bindings(),
                valid.residualObligation(),
                valid.assumptions(),
                valid.primitiveRuleIds(),
                valid.budget(),
                corrupted,
                valid.work());

        assertFalse(solver.verify(invalid));
    }

    @Test
    void invalidBudgetsAreRejected() {
        assertThrows(
            IllegalArgumentException.class,
            () -> new MonomialCommonFactorPreparationSolver.Budget(-1, 1, 1));
        assertThrows(
            IllegalArgumentException.class,
            () -> new MonomialCommonFactorPreparationSolver.Budget(1, 0, 1));
        assertThrows(
            IllegalArgumentException.class,
            () -> new MonomialCommonFactorPreparationSolver.Budget(1, 1, 0));
    }

    private void assertSameExpression(
        String expected,
        de.regelsuche.ast.Expr actual
    ) {
        assertEquals(
            canonicalizer.stableHash(expected),
            canonicalizer.stableHash(ExpressionFormatter.format(actual)));
    }
}
