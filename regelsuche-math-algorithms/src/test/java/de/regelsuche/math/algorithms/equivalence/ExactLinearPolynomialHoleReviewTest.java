package de.regelsuche.math.algorithms.equivalence;

import static org.junit.jupiter.api.Assertions.*;

import de.regelsuche.math.algorithms.equivalence.ExactLinearPolynomialHoleSolver.Limits;
import de.regelsuche.math.algorithms.equivalence.ExactLinearPolynomialHoleSolver.Status;
import de.regelsuche.math.algorithms.linalg.ExactRrefSolver;
import java.util.List;
import org.junit.jupiter.api.Test;

class ExactLinearPolynomialHoleReviewTest {
    private final ExactLinearPolynomialHoleSolver solver = new ExactLinearPolynomialHoleSolver();
    private final Limits limits = new Limits(8, 64, 512, 100_000);

    @Test void completedRrefCannotStandInForTheUnfundedIndependentTemplateCheck() {
        String source = "17*x/7+3*y/11";
        String template = "${alpha}*(x+y)+${beta}*(x-y)";
        var complete = solver.solve(source, template, List.of("alpha", "beta"), List.of(), limits);
        assertEquals(Status.UNIQUE, complete.status());
        int beforeVerification = complete.work().consumed() - complete.work().verification();
        var stopped = solver.solve(source, template, complete.holeIds(), List.of(),
            new Limits(8, 64, 512, beforeVerification));
        assertEquals(Status.BUDGET_INCONCLUSIVE, stopped.status());
        assertEquals("CUMULATIVE_WORK_BUDGET_EXHAUSTED", stopped.detailCode());
        assertEquals(beforeVerification, stopped.work().consumed());
        assertEquals(0, stopped.work().verification());
        assertEquals(complete.constraints(), stopped.constraints());
        var retainedRref = stopped.reduction().orElseThrow();
        assertEquals(ExactRrefSolver.Status.SOLVED, retainedRref.status());
        assertEquals(complete.reduction().orElseThrow().reduction(), retainedRref.reduction());
        assertEquals(complete.reduction().orElseThrow().certificate(), retainedRref.certificate());
        assertTrue(stopped.candidate().isEmpty(), "a solved matrix alone does not authorize a template candidate");
        assertTrue(solver.replay(stopped));
        assertNotEquals(complete.contentHash(), stopped.contentHash());
    }

    @Test void cancelledDeclaredHoleStaysFreeAndCannotHideAnIndependentConstantContradiction() {
        String template = "${alpha}*x+(${beta}-${beta})*y+1";
        var singular = solver.solve("x+1", template, List.of("alpha", "beta"), List.of(), limits);
        assertEquals(Status.UNDERDETERMINED, singular.status());
        assertTrue(singular.candidate().isEmpty());
        var rref = singular.reduction().orElseThrow().reduction().orElseThrow();
        assertEquals(List.of("alpha", "beta"), rref.variables());
        assertEquals(List.of(1), rref.freeVariableColumns());
        assertEquals(List.of(Rational.ONE, Rational.ZERO), rref.particularSolution().orElseThrow().values());
        assertEquals(List.of(Rational.ZERO, Rational.ONE), rref.nullspaceBasis().getFirst().values());
        assertTrue(solver.replay(singular));

        var inconsistent = solver.solve("x+2", template, List.of("alpha", "beta"), List.of(), limits);
        assertEquals(Status.INCONSISTENT, inconsistent.status());
        assertTrue(inconsistent.candidate().isEmpty());
        assertFalse(inconsistent.reduction().orElseThrow().reduction().orElseThrow().contradictionRows().isEmpty());
        assertTrue(solver.replay(inconsistent));
        assertNotEquals(singular.contentHash(), inconsistent.contentHash());
    }
}
