package de.regelsuche.math.algorithms.equivalence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.Expr;
import de.regelsuche.parse.ExpressionFormatter;
import de.regelsuche.parse.ExpressionParser;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class SparsePolynomialHornerTest {
    private final ExpressionParser parser = new ExpressionParser();

    @Test void groupsSignedNonadjacentTermsAndSearchesVariableOrders() {
        assertExactAndAtMost("2*a*b*c+3*c*c-b*a*b*c+3*c*c-b", 7);
        assertExactAndAtMost("2*p*q*r+5*r*p*r-q*p*q*r-2*r*p*r-q", 8);
    }

    @Test void considersOneCommonDenominatorAndKeepsLargeIntegersExact() {
        assertExactAndAtMost("1+x/2-x*x/8+x*x*x/16-5*x*x*x*x/128", 9);
        assertExactAndAtMost("238732414637843*x/250000000000000-6450306886639899*x*x*x/50000000000000000", 5);
    }

    @Test void handlesOneGlobalSignAndExactCancellation() {
        assertExactAndAtMost("-a*b-2*b*c-a-c", 6);
        assertExactAndAtMost("x*x-x*x", 0);
    }

    @Test void rejectsPartialRationalFunctionsAndExcessiveArity() {
        assertThrows(IllegalArgumentException.class, () -> SparsePolynomialHorner.propose(parser.parseTerm("x*x/x"), ignored -> { }));
        assertTrue(SparsePolynomialHorner.propose(parser.parseTerm("a+b+c+d+e"), ignored -> { }).isEmpty());
    }

    private void assertExactAndAtMost(String source, int maximumOperations) {
        var work = new AtomicLong();
        Expr parsed = parser.parseTerm(source);
        Expr candidate = SparsePolynomialHorner.propose(parsed, work::addAndGet).orElseThrow();
        String formatted = ExpressionFormatter.format(candidate);
        new ExactPolynomialAnalysis().requireEquivalent(source, formatted);
        assertTrue(operations(candidate) <= maximumOperations, formatted);
        assertTrue(work.get() > 0);
        assertEquals(candidate, SparsePolynomialHorner.propose(parsed, ignored -> { }).orElseThrow());
    }

    private static int operations(Expr expression) {
        return expression instanceof BinaryExpr binary ? 1 + operations(binary.left()) + operations(binary.right()) : 0;
    }
}
