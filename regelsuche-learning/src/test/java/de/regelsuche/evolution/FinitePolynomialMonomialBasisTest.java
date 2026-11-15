package de.regelsuche.evolution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.evolution.ExactFinitePolynomialPlanRun.Status;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Public development controls for target-free finite monomial basis formation. */
@Timeout(10)
class FinitePolynomialMonomialBasisTest {
    private static final SchematicProofPlan.Limits LIMITS =
        new SchematicProofPlan.Limits(8, 8, 4, 200_000);
    private final ExactFinitePolynomialPlanResolver resolver =
        new ExactFinitePolynomialPlanResolver();

    @Test
    void enumeratesAnExactBoundedBasisAndChecksEveryAssignment() {
        FinitePolynomialTemplate template =
            FinitePolynomialTemplate.declaredUnivariateMonomialBasis(
                "bounded-quadratic", 2, -2, 2);
        assertEquals(FinitePolynomialTemplate.Origin.DECLARED_GRAMMAR,
            template.origin());
        assertEquals("${coef0} + ${coef1}*@v + ${coef2}*@v^2",
            template.expression());
        assertEquals(List.of("coef0", "coef1", "coef2"),
            template.domains().stream().map(domain -> domain.holeId()).toList());
        assertEquals(125L, template.assignmentCount());

        String source = "x^2-2*x+1";
        String ansatz = template.instantiateVariable("x");
        var plan = resolver.createPlan("bounded-quadratic-plan",
            source, ansatz, template.domains(), 4, LIMITS);
        var run = resolver.resolve(plan, source, ansatz, template.domains(), 4);

        assertEquals(Status.COMPLETE_WITH_RESOLUTIONS, run.status());
        assertEquals(125, run.solverResult().evaluatedAssignments());
        assertEquals(1, run.solverResult().matchingAssignments());
        assertEquals("coef0=1|coef1=-2|coef2=1",
            run.candidates().getFirst().solution().bindingKey());
        assertTrue(resolver.replay(plan, source, ansatz,
            template.domains(), 4, run));
        assertEquals(template.contentHash(),
            FinitePolynomialTemplate.declaredUnivariateMonomialBasis(
                "bounded-quadratic", 2, -2, 2).contentHash());
    }

    @Test
    void keepsACompleteNegativeResultDistinctFromAProofOfAbsenceInOtherDomains() {
        FinitePolynomialTemplate template =
            FinitePolynomialTemplate.declaredUnivariateMonomialBasis(
                "bounded-linear", 1, -1, 1);
        String source = "x^2+2*x";
        String ansatz = template.instantiateVariable("x");
        var plan = resolver.createPlan("bounded-linear-plan",
            source, ansatz, template.domains(), 4, LIMITS);
        var run = resolver.resolve(plan, source, ansatz, template.domains(), 4);
        assertEquals(Status.COMPLETE_WITHOUT_SOLUTION, run.status());
        assertEquals(9, run.solverResult().evaluatedAssignments());
        assertEquals(0, run.solverResult().matchingAssignments());
        assertTrue(resolver.replay(plan, source, ansatz,
            template.domains(), 4, run));
    }

    @Test
    void rejectsUnboundedDegreesRangesAndCartesianProducts() {
        assertThrows(IllegalArgumentException.class,
            () -> FinitePolynomialTemplate.declaredUnivariateMonomialBasis(
                "zero-degree", 0, -1, 1));
        assertThrows(IllegalArgumentException.class,
            () -> FinitePolynomialTemplate.declaredUnivariateMonomialBasis(
                "too-many-holes", 8, -1, 1));
        assertThrows(IllegalArgumentException.class,
            () -> FinitePolynomialTemplate.declaredUnivariateMonomialBasis(
                "empty-range", 2, 2, 1));
        assertThrows(IllegalArgumentException.class,
            () -> FinitePolynomialTemplate.declaredUnivariateMonomialBasis(
                "too-many-values", 2, -100, 100));
        assertThrows(IllegalArgumentException.class,
            () -> FinitePolynomialTemplate.declaredUnivariateMonomialBasis(
                "too-many-assignments", 7, 0, 4));
    }
}
