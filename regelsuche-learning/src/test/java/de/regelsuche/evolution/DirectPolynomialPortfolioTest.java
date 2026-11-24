package de.regelsuche.evolution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.math.algorithms.equivalence.ExactPolynomialAnalysis;
import de.regelsuche.math.algorithms.equivalence.SparsePolynomialForms;
import de.regelsuche.parse.ExpressionFormatter;
import de.regelsuche.parse.ExpressionParser;
import de.regelsuche.search.moves.MoveSearch;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DirectPolynomialPortfolioTest {
    private static final String INPUT = "(2*u+11)*w+(3*v+7)*w";
    private static final MoveSearch.Budget FULL = new MoveSearch.Budget(9, 9, 16_384, 256, 16_384, 24);

    @Test
    void polynomialSupportLowerBoundFitsTheActualArithmeticTrees() {
        var leaves = List.of("0", "1", "3", "x", "y", "z");
        var parser = new ExpressionParser();
        for (String left : leaves) {
            for (String right : leaves) {
                for (String op : List.of("+", "-", "*")) {
                    String term = "(" + left + op + right + ")";
                    for (String source : List.of(term, term + "*w", term + "+z", term + "^3", term + "/3")) {
                        var expression = parser.parseTerm(source);
                        long bound = SparsePolynomialForms.proposeWithSurfaceBound(expression, false,
                            units -> { }, ignored -> { });
                        assertTrue(bound <= TypedPolynomialSurfaceCost.evaluate(expression).value(), source);
                    }
                }
            }
        }
        for (String source : List.of("x^2+x", "2*x^6", "x^3+1", "2*x+3", "u*u+v")) {
            var expression = parser.parseTerm(source);
            assertEquals(2, SparsePolynomialForms.proposeWithSurfaceBound(expression, false,
                units -> { }, ignored -> { }), source);
        }
    }

    @Test
    void ordinaryOptInUsesOnePaidAndIndependentlyReplayedConstruction() {
        var result = solver().simplify(new ExpressionParser().parseTerm(INPUT), List.of(), FULL);
        assertTrue(result.withinBudget());
        assertTrue(result.outputScore() < result.inputScore());
        assertEquals(1, result.witness().size());
        assertTrue(result.witness().getFirst().move().ruleId().endsWith(":POLYNOMIAL_DIRECT"));
        assertTrue(result.witness().getFirst().verification().receipts().contains("exact-polynomial-step/v1"));
        assertTrue(result.replayWork() > 0);
        assertTrue(result.search().encodedResult().events().stream()
            .allMatch(event -> event.move().ruleId().endsWith(":POLYNOMIAL_DIRECT")));
        new ExactPolynomialAnalysis().requireEquivalent(INPUT, ExpressionFormatter.format(result.incumbent().expression()));
    }

    @Test
    void zeroTheoryBudgetAndInvalidDomainsCannotIssueProofs() {
        var input = new ExpressionParser().parseTerm(INPUT);
        var zeroTheory = new MoveSearch.Budget(9, 9, 0, 256, 16_384, 24);
        var bounded = solver().simplify(input, List.of(), zeroTheory);
        assertEquals(input, bounded.incumbent().expression());
        assertTrue(bounded.witness().isEmpty());
        var rationalFunction = new ExpressionParser().parseTerm("(a*a-1)/(a-1)");
        assertThrows(IllegalArgumentException.class, () -> solver().simplify(rationalFunction, List.of(), FULL));
    }

    @Test
    void sendsAReplayReceiptAndStrictSourceOnlyRequestsThroughTheWorker() throws Exception {
        var worker = new TypedExternalPolynomialComparisonWorker();
        var setup = worker.handle("{\"op\":\"initialize\",\"profile\":\"POLYNOMIAL_DIRECT_SHARED_V1\"}");
        assertEquals("POLYNOMIAL_DIRECT_SHARED_V1", setup.get("profile"));
        assertEquals(16_384, ((Map<?, ?>) setup.get("settings")).get("workBudget"));
        var result = worker.run("POLYNOMIAL_DIRECT_SHARED_V1", INPUT);
        assertTrue((Boolean) result.get("internalWorkWithinBudget"));
        var witness = (List<?>) result.get("witness");
        assertFalse(witness.isEmpty());
        assertTrue((Long) result.get("selectedReplayWork") > 0);
        String output = (String) result.get("output");
        assertFalse(output.contains("^"));
        new ExactPolynomialAnalysis().requireEquivalent(INPUT, output);
        assertThrows(IllegalArgumentException.class, () -> worker.handle(
            "{\"op\":\"run\",\"profile\":\"POLYNOMIAL_DIRECT_SHARED_V1\",\"source\":\"u+1\",\"target\":\"u\"}"));
        assertThrows(IllegalArgumentException.class, () -> worker.run("POLYNOMIAL_DIRECT_SHARED_V1", "q^3+4"));
    }

    @Test
    void ordinaryWorkerRetainsThePolynomialPowerObjective() {
        var worker = new TypedExternalPolynomialComparisonWorker();
        worker.initialize("POLYNOMIAL_DIRECT_V1");
        String source = "r*r*r+9*r*r+27*r+27";
        var result = worker.run("POLYNOMIAL_DIRECT_V1", source);
        assertEquals(2L, result.get("outputCost"));
        assertTrue((Boolean) result.get("internalWorkWithinBudget"));
        assertEquals(1, ((List<?>) result.get("witness")).size());
        new ExactPolynomialAnalysis().requireEquivalent(source, (String) result.get("output"));
    }

    @Test
    void carefullyKeepsTerminatingFractionsOnTheIntegerRatioWire() {
        var worker = new TypedExternalPolynomialComparisonWorker();
        worker.initialize("POLYNOMIAL_DIRECT_V1");
        for (String scale : List.of("3/8", "-7/16")) {
            String source = "((m+n)*(m-n)+n*n)*(" + scale + ")";
            var result = worker.run("POLYNOMIAL_DIRECT_V1", source);
            var output = (String) result.get("output");
            assertTrue(output.contains("/"));
            assertFalse(output.contains("."));
            assertTrue((Boolean) result.get("internalWorkWithinBudget"));
            assertEquals(1, ((List<?>) result.get("witness")).size());
            new ExactPolynomialAnalysis().requireEquivalent(source, output);
        }
    }

    @Test
    void oversizedGeneratedLiteralDoesNotReplaceTheIncumbentOrBreakExport() {
        String source = "1" + "0".repeat(76) + "^16+x";
        var parsed = new ExpressionParser().parseTerm(source);
        var direct = solver().simplify(parsed, List.of(), FULL);
        assertEquals(parsed, direct.incumbent().expression());
        assertTrue(direct.witness().isEmpty());
        assertTrue(direct.withinBudget());
        var worker = new TypedExternalPolynomialComparisonWorker();
        worker.initialize("POLYNOMIAL_DIRECT_V1");
        var result = worker.run("POLYNOMIAL_DIRECT_V1", source);
        assertEquals(parsed, new ExpressionParser().parseTerm((String) result.get("output")));
        assertTrue(((List<?>) result.get("witness")).isEmpty());
        assertTrue((Boolean) result.get("internalWorkWithinBudget"));
    }

    private static CheckedSchemaTaskSolver solver() {
        return TypedLearnedMoveInventory.primitives(TraceStrategyTransferExample.inventory())
            .newTaskSolverWithProfile(TaskConstructionProfile.POLYNOMIAL_DIRECT_V1);
    }
}
