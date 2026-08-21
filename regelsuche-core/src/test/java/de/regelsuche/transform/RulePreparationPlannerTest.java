package de.regelsuche.transform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.parse.ExpressionFormatter;
import de.regelsuche.parse.ExpressionParser;
import java.util.List;
import org.junit.jupiter.api.Test;

class RulePreparationPlannerTest {
    private final ExpressionParser parser = new ExpressionParser();
    private final RewriteRule cancellationRule =
        AstRewriteTransformationEngine.defaultRules().stream()
            .filter(rule -> RulePreparationPlanner.CANCELLATION_RULE_ID
                .equals(rule.id()))
            .findFirst()
            .orElseThrow();

    @Test
    void preparesExactPolynomialFactorForOrdinaryCancellation() {
        var source = parser.parseTerm("(x^3 - 1) / (x - 1)");
        assertFalse(cancellationRule.matches(source));

        try (RulePreparationPlanner.Session session =
                RulePreparationPlanner.standard().openSession(
                    new RulePreparationPlanner.Budget(2, 2))) {
            RulePreparationPlanner.PlanningResult result = session.plan(
                cancellationRule,
                source,
                RulePreparationPlanner.Context.unqualified("inventory-v1")
            );

            assertEquals(RulePreparationPlanner.Status.PREPARED, result.status());
            assertEquals(1, result.applications().size());
            RulePreparationPlanner.PreparedRuleApplication application =
                result.applications().getFirst();
            assertEquals(
                "(x - 1) * (x ^ 2 + x + 1) / (x - 1)",
                ExpressionFormatter.format(application.preparedSubtree())
            );
            assertEquals(
                "x ^ 2 + x + 1",
                ExpressionFormatter.format(application.resultSubtree())
            );
            assertEquals(
                "x - 1",
                ExpressionFormatter.format(application.bindings().get("A"))
            );
            assertEquals(
                "x ^ 2 + x + 1",
                ExpressionFormatter.format(application.bindings().get("B"))
            );
            assertEquals(
                List.of(
                    RulePreparationPlanner.EXACT_POLYNOMIAL_FACTOR_STEP,
                    RulePreparationPlanner.CANCELLATION_RULE_ID
                ),
                application.primitiveRuleIds()
            );
            assertEquals(
                List.of("x - 1 != 0"),
                application.assumptions().stream()
                    .map(assumption -> assumption.expression())
                    .toList()
            );
            assertEquals(
                List.of(
                    "x ^ 3 - 1 = (x - 1) * (x ^ 2 + x + 1)"
                ),
                application.residualObligations()
            );
            assertTrue(application.certificate().verify());
            assertEquals(
                "inventory-v1",
                application.context().ruleInventoryHash()
            );
            assertEquals(
                "NO_DECLARED_ASSUMPTIONS",
                application.context().assumptionSignature()
            );
            assertFalse(application.solutionHash().isBlank());
            assertFalse(application.evidenceHash().isBlank());
            assertEquals(1, result.work().consumedSolverAttempts());
            assertEquals(1, result.work().consumedPreparedApplications());
        }
    }

    @Test
    void reusesTheSessionCacheWithoutRepeatingPolynomialDivision() {
        var source = parser.parseTerm("(x^4 - 1) / (x - 1)");
        try (RulePreparationPlanner.Session session =
                RulePreparationPlanner.standard().openSession(
                    new RulePreparationPlanner.Budget(1, 2))) {
            RulePreparationPlanner.Context context =
                RulePreparationPlanner.Context.unqualified("inventory-v1");

            RulePreparationPlanner.PlanningResult first = session.plan(
                cancellationRule,
                source,
                context
            );
            RulePreparationPlanner.PlanningResult second = session.plan(
                cancellationRule,
                source,
                context
            );

            assertEquals(RulePreparationPlanner.Status.PREPARED, first.status());
            assertEquals(RulePreparationPlanner.Status.PREPARED, second.status());
            assertEquals(
                "x ^ 3 + x ^ 2 + x + 1",
                ExpressionFormatter.format(
                    second.applications().getFirst().resultSubtree()
                )
            );
            assertEquals(1, second.work().consumedSolverAttempts());
            assertEquals(2, second.work().consumedPreparedApplications());
            assertEquals(1, second.work().cacheHits());
        }
    }

    @Test
    void rejectsNonExactDivisionRatherThanGuessingAQuotient() {
        var source = parser.parseTerm("(x^3 + 1) / (x - 1)");
        try (RulePreparationPlanner.Session session =
                RulePreparationPlanner.standard().openSession(
                    new RulePreparationPlanner.Budget(1, 1))) {
            RulePreparationPlanner.PlanningResult result = session.plan(
                cancellationRule,
                source,
                RulePreparationPlanner.Context.unqualified("inventory-v1")
            );

            assertEquals(
                RulePreparationPlanner.Status.NOT_APPLICABLE,
                result.status()
            );
            assertEquals("DIVISION_NOT_EXACT", result.detail());
            assertTrue(result.applications().isEmpty());
        }
    }

    @Test
    void reportsUnsupportedMultivariateFragmentsExplicitly() {
        var source = parser.parseTerm("(x*y - 1) / (x - 1)");
        try (RulePreparationPlanner.Session session =
                RulePreparationPlanner.standard().openSession(
                    new RulePreparationPlanner.Budget(1, 1))) {
            RulePreparationPlanner.PlanningResult result = session.plan(
                cancellationRule,
                source,
                RulePreparationPlanner.Context.unqualified("inventory-v1")
            );

            assertEquals(
                RulePreparationPlanner.Status.UNSUPPORTED,
                result.status()
            );
            assertTrue(result.applications().isEmpty());
        }
    }

    @Test
    void preparedApplicationBudgetBlocksProposalBeforeSolverWork() {
        var source = parser.parseTerm("(x^3 - 1) / (x - 1)");
        try (RulePreparationPlanner.Session session =
                RulePreparationPlanner.standard().openSession(
                    new RulePreparationPlanner.Budget(1, 0))) {
            RulePreparationPlanner.PlanningResult result = session.plan(
                cancellationRule,
                source,
                RulePreparationPlanner.Context.unqualified("inventory-v1")
            );

            assertEquals(
                RulePreparationPlanner.Status.BUDGET_EXHAUSTED,
                result.status()
            );
            assertEquals(0, result.work().consumedSolverAttempts());
            assertEquals(0, result.work().consumedPreparedApplications());
        }
    }

    @Test
    void disabledPlannerDoesNotInspectTheExpression() {
        var source = parser.parseTerm("(x^3 - 1) / (x - 1)");
        try (RulePreparationPlanner.Session session =
                RulePreparationPlanner.disabled().openSession(
                    RulePreparationPlanner.Budget.disabled())) {
            RulePreparationPlanner.PlanningResult result = session.plan(
                cancellationRule,
                source,
                RulePreparationPlanner.Context.unqualified("inventory-v1")
            );

            assertEquals(RulePreparationPlanner.Status.DISABLED, result.status());
            assertEquals(0, result.work().consumedSolverAttempts());
            assertTrue(result.applications().isEmpty());
        }
    }

    @Test
    void rejectsACorruptedFactorizationWitness() {
        var certificate =
            RulePreparationPlanner.PolynomialFactorizationCertificate.exact(
                parser.parseTerm("x^3 - 1"),
                parser.parseTerm("x - 1"),
                parser.parseTerm("x + 1")
            );

        assertFalse(certificate.verify());
    }

    @Test
    void exposesBudgetExhaustionWithoutExecutingTheSolver() {
        var source = parser.parseTerm("(x^3 - 1) / (x - 1)");
        try (RulePreparationPlanner.Session session =
                RulePreparationPlanner.standard().openSession(
                    new RulePreparationPlanner.Budget(0, 1))) {
            RulePreparationPlanner.PlanningResult result = session.plan(
                cancellationRule,
                source,
                RulePreparationPlanner.Context.unqualified("inventory-v1")
            );

            assertEquals(
                RulePreparationPlanner.Status.BUDGET_EXHAUSTED,
                result.status()
            );
            assertEquals(0, result.work().consumedSolverAttempts());
            assertTrue(result.applications().isEmpty());
        }
    }
}
