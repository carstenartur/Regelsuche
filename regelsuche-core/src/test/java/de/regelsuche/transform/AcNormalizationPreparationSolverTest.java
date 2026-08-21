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

class AcNormalizationPreparationSolverTest {
    private final ExpressionParser parser = new ExpressionParser();
    private final ExpressionCanonicalizer canonicalizer =
        new ExpressionCanonicalizer();

    @Test
    void exposesABuriedFactorModuloAssociativityAndCommutativity() {
        AcNormalizationPreparationSolver solver =
            new AcNormalizationPreparationSolver();

        AcNormalizationPreparationSolver.PlanAttempt attempt = solver.plan(
            parser.parseTerm("(b * (a * c)) / a"));

        assertEquals(
            AcNormalizationPreparationSolver.Status.PREPARED,
            attempt.status());
        AcNormalizationPreparationSolver.PreparedApplication application =
            attempt.application().orElseThrow();
        assertEquals(
            canonicalizer.stableHash("b * c"),
            canonicalizer.stableHash(ExpressionFormatter.format(
                application.resultSubtree())));
        assertEquals(
            canonicalizer.stableHash("(a * (b * c)) / a"),
            canonicalizer.stableHash(ExpressionFormatter.format(
                application.preparedSubtree())));
        assertEquals(List.of("a != 0"), application.assumptions());
        assertEquals(
            List.of(
                AcNormalizationPreparationSolver.PREPARATION_RULE_ID,
                AcNormalizationPreparationSolver.PRINCIPAL_RULE_ID),
            application.primitiveRuleIds());
        assertEquals(1, application.certificate().selectedFactorIndex());
        assertEquals(3, application.certificate().originalFactorHashes().size());
        assertEquals(3, application.certificate().preparedFactorHashes().size());
        assertEquals(3, attempt.work().inspectedFactors());
        assertTrue(solver.verify(application));
    }

    @Test
    void selectsTheFirstRepeatedFactorDeterministically() {
        AcNormalizationPreparationSolver.PreparedApplication application =
            new AcNormalizationPreparationSolver()
                .plan(parser.parseTerm("(b * (a * a)) / a"))
                .application()
                .orElseThrow();

        assertEquals(1, application.certificate().selectedFactorIndex());
        assertEquals(
            canonicalizer.stableHash("b * a"),
            canonicalizer.stableHash(ExpressionFormatter.format(
                application.resultSubtree())));
    }

    @Test
    void numericNonZeroFactorNeedsNoOpenAssumption() {
        AcNormalizationPreparationSolver.PreparedApplication application =
            new AcNormalizationPreparationSolver()
                .plan(parser.parseTerm("(x * (2 * y)) / 2"))
                .application()
                .orElseThrow();

        assertTrue(application.assumptions().isEmpty());
        assertEquals(
            canonicalizer.stableHash("x * y"),
            canonicalizer.stableHash(ExpressionFormatter.format(
                application.resultSubtree())));
    }

    @Test
    void leavesAnExistingDirectCancellationToThePrincipalRule() {
        AcNormalizationPreparationSolver.PlanAttempt attempt =
            new AcNormalizationPreparationSolver().plan(
                parser.parseTerm("(a * (b * c)) / a"));

        assertEquals(
            AcNormalizationPreparationSolver.Status.DIRECT_MATCH_AVAILABLE,
            attempt.status());
        assertTrue(attempt.application().isEmpty());
        assertEquals(0, attempt.work().inspectedFactors());
    }

    @Test
    void refusesToInventAMissingFactor() {
        AcNormalizationPreparationSolver.PlanAttempt attempt =
            new AcNormalizationPreparationSolver().plan(
                parser.parseTerm("(a * (b * c)) / d"));

        assertEquals(
            AcNormalizationPreparationSolver.Status.NOT_APPLICABLE,
            attempt.status());
        assertEquals(
            "divisor-is-not-an-existing-ac-factor",
            attempt.detail());
        assertTrue(attempt.application().isEmpty());
    }

    @Test
    void doesNotDistributeThroughAddition() {
        AcNormalizationPreparationSolver.PlanAttempt attempt =
            new AcNormalizationPreparationSolver().plan(
                parser.parseTerm("(a + b) / a"));

        assertEquals(
            AcNormalizationPreparationSolver.Status.NOT_APPLICABLE,
            attempt.status());
        assertTrue(attempt.application().isEmpty());
    }

    @Test
    void reportsFactorLimitAsBudgetInconclusive() {
        AcNormalizationPreparationSolver solver =
            new AcNormalizationPreparationSolver(
                new AcNormalizationPreparationSolver.Budget(3));

        AcNormalizationPreparationSolver.PlanAttempt attempt = solver.plan(
            parser.parseTerm("(a * (b * (c * d))) / c"));

        assertEquals(
            AcNormalizationPreparationSolver.Status.BUDGET_INCONCLUSIVE,
            attempt.status());
        assertEquals(3, attempt.work().configuredFactorLimit());
        assertEquals(3, attempt.work().inspectedFactors());
        assertEquals(0, attempt.work().remainingFactorCapacity());
        assertTrue(attempt.residualObligation().isPresent());
    }

    @Test
    void corruptedSelectedFactorWitnessIsRejected() {
        AcNormalizationPreparationSolver solver =
            new AcNormalizationPreparationSolver();
        AcNormalizationPreparationSolver.PreparedApplication application =
            solver.plan(parser.parseTerm("(b * (a * c)) / a"))
                .application()
                .orElseThrow();
        AcNormalizationPreparationSolver.Certificate certificate =
            application.certificate();
        AcNormalizationPreparationSolver.Certificate corrupted =
            new AcNormalizationPreparationSolver.Certificate(
                certificate.schema(),
                certificate.solverId(),
                certificate.operator(),
                certificate.originalNumeratorExpression(),
                certificate.preparedNumeratorExpression(),
                certificate.divisorExpression(),
                certificate.resultExpression(),
                0,
                certificate.originalFactorHashes(),
                certificate.preparedFactorHashes(),
                certificate.contentHash());
        AcNormalizationPreparationSolver.PreparedApplication changed =
            new AcNormalizationPreparationSolver.PreparedApplication(
                application.schema(),
                application.solverId(),
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

        assertFalse(solver.verify(changed));
    }

    @Test
    void invalidBudgetsAndWorkLedgersFailClosed() {
        assertThrows(
            IllegalArgumentException.class,
            () -> new AcNormalizationPreparationSolver.Budget(-1));
        assertThrows(
            IllegalArgumentException.class,
            () -> new AcNormalizationPreparationSolver.WorkLedger(
                3,
                2,
                2));
    }
}
