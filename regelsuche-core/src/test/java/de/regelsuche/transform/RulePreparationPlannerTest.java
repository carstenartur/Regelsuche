package de.regelsuche.transform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.canonical.ExpressionCanonicalizer;
import de.regelsuche.parse.ExpressionFormatter;
import de.regelsuche.parse.ExpressionParser;
import java.util.List;
import org.junit.jupiter.api.Test;

class RulePreparationPlannerTest {
    private final ExpressionParser parser = new ExpressionParser();
    private final ExpressionCanonicalizer canonicalizer =
        new ExpressionCanonicalizer();

    @Test
    void preparesAnExactPolynomialFactorForCancellation() {
        RulePreparationPlanner planner = new RulePreparationPlanner();

        RulePreparationPlanner.PlanAttempt attempt = planner.plan(
            parser.parseTerm("(x^3 - 1) / (x - 1)"));

        assertEquals(
            RulePreparationPlanner.Status.PREPARED,
            attempt.status());
        RulePreparationPlanner.PreparedRuleApplication application =
            attempt.application().orElseThrow();
        assertEquals(
            canonicalizer.stableHash("x^2 + x + 1"),
            canonicalizer.stableHash(
                ExpressionFormatter.format(application.resultSubtree())));
        assertEquals(
            canonicalizer.stableHash(
                "((x - 1) * (x^2 + x + 1)) / (x - 1)"),
            canonicalizer.stableHash(
                ExpressionFormatter.format(application.preparedSubtree())));
        assertEquals(List.of("x - 1 != 0"), application.assumptions());
        assertEquals(
            "x ^ 3 - 1 = (x - 1) * B",
            application.residualObligation().equationTemplate());
        assertEquals(
            List.of(
                RulePreparationPlanner.PREPARATION_RULE_ID,
                RulePreparationPlanner.PRINCIPAL_RULE_ID),
            application.primitiveRuleIds());
        assertEquals("0", application.certificate().remainderExpression());
        assertEquals(1, attempt.work().configuredSolverAttempts());
        assertEquals(1, attempt.work().consumedSolverAttempts());
        assertEquals(0, attempt.work().remainingSolverAttempts());
        assertTrue(planner.verify(application));
    }

    @Test
    void nonDivisionIsNotApplicableWithoutSolverWork() {
        RulePreparationPlanner.PlanAttempt attempt =
            new RulePreparationPlanner().plan(parser.parseTerm("x^3 - 1"));

        assertEquals(
            RulePreparationPlanner.Status.NOT_APPLICABLE,
            attempt.status());
        assertTrue(attempt.residualObligation().isEmpty());
        assertEquals(0, attempt.work().consumedSolverAttempts());
    }

    @Test
    void explicitZeroDivisorIsUnsupportedWithoutSolverWork() {
        RulePreparationPlanner.PlanAttempt attempt =
            new RulePreparationPlanner().plan(parser.parseTerm("x / 0"));

        assertEquals(
            RulePreparationPlanner.Status.UNSUPPORTED,
            attempt.status());
        assertTrue(attempt.residualObligation().isEmpty());
        assertEquals(0, attempt.work().consumedSolverAttempts());
    }

    @Test
    void constantDivisorIsOutsideThePreparationFragment() {
        RulePreparationPlanner.PlanAttempt attempt =
            new RulePreparationPlanner().plan(parser.parseTerm("(x + 1) / 2"));

        assertEquals(
            RulePreparationPlanner.Status.UNSUPPORTED,
            attempt.status());
        assertTrue(attempt.residualObligation().isPresent());
        assertEquals(1, attempt.work().consumedSolverAttempts());
    }

    @Test
    void rejectsANonExactPolynomialQuotient() {
        RulePreparationPlanner.PlanAttempt attempt =
            new RulePreparationPlanner().plan(
                parser.parseTerm("(x^3 + 1) / (x - 1)"));

        assertEquals(
            RulePreparationPlanner.Status.NO_EXACT_QUOTIENT,
            attempt.status());
        assertTrue(attempt.application().isEmpty());
        assertTrue(attempt.residualObligation().isPresent());
        assertEquals(1, attempt.work().consumedSolverAttempts());
    }

    @Test
    void reportsUnsupportedMultivariateInputWithoutGuessing() {
        RulePreparationPlanner.PlanAttempt attempt =
            new RulePreparationPlanner().plan(
                parser.parseTerm("(x * y + 1) / (x - 1)"));

        assertEquals(
            RulePreparationPlanner.Status.UNSUPPORTED,
            attempt.status());
        assertTrue(attempt.application().isEmpty());
        assertTrue(attempt.residualObligation().isPresent());
    }

    @Test
    void leavesAnExistingDirectCancellationToThePrincipalRule() {
        RulePreparationPlanner.PlanAttempt attempt =
            new RulePreparationPlanner().plan(parser.parseTerm(
                "((x - 1) * (x^2 + x + 1)) / (x - 1)"));

        assertEquals(
            RulePreparationPlanner.Status.DIRECT_MATCH_AVAILABLE,
            attempt.status());
        assertTrue(attempt.application().isEmpty());
        assertEquals(0, attempt.work().consumedSolverAttempts());
    }

    @Test
    void reportsThePreparationBudgetAsInconclusive() {
        RulePreparationPlanner planner = new RulePreparationPlanner(
            new RulePreparationPlanner.Budget(0));

        RulePreparationPlanner.PlanAttempt attempt = planner.plan(
            parser.parseTerm("(x^3 - 1) / (x - 1)"));

        assertEquals(
            RulePreparationPlanner.Status.BUDGET_INCONCLUSIVE,
            attempt.status());
        assertTrue(attempt.application().isEmpty());
        assertTrue(attempt.residualObligation().isPresent());
        assertEquals(0, attempt.work().configuredSolverAttempts());
        assertEquals(0, attempt.work().consumedSolverAttempts());
    }

    @Test
    void corruptedCertificateIsRejected() {
        RulePreparationPlanner planner = new RulePreparationPlanner();
        assertFalse(planner.verify(null));
        RulePreparationPlanner.PreparedRuleApplication application =
            planner.plan(parser.parseTerm("(x^3 - 1) / (x - 1)"))
                .application()
                .orElseThrow();
        RulePreparationPlanner.Certificate certificate =
            application.certificate();
        RulePreparationPlanner.Certificate corrupted =
            new RulePreparationPlanner.Certificate(
                certificate.schema(),
                certificate.solverId(),
                certificate.dividendExpression(),
                certificate.divisorExpression(),
                certificate.quotientExpression(),
                "1",
                certificate.preparedExpression(),
                certificate.contentHash());
        RulePreparationPlanner.PreparedRuleApplication changed =
            new RulePreparationPlanner.PreparedRuleApplication(
                application.schema(),
                application.plannerId(),
                application.principalRuleId(),
                application.originalSubtree(),
                application.preparedSubtree(),
                application.resultSubtree(),
                application.bindings(),
                application.residualObligation(),
                application.assumptions(),
                application.primitiveRuleIds(),
                corrupted,
                application.work());

        assertFalse(planner.verify(changed));
    }
}
