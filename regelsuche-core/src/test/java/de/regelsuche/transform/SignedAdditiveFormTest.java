package de.regelsuche.transform;

import static org.junit.jupiter.api.Assertions.*;

import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.BinaryOperator;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.NumberExpr;
import de.regelsuche.ast.VariableExpr;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class SignedAdditiveFormTest {
    private static final Expr A = new VariableExpr("a");
    private static final Expr B = new VariableExpr("b");
    private static final Expr C = new VariableExpr("c");
    @Test void cancelsNestedPositiveAndNegativeTerms() {
        assertEquals(sub(B, C), normalize(sub(sub(add(A, B), C), A)));
        assertEquals(new NumberExpr(0), normalize(sub(add(A, B), add(B, A))));
    }
    @Test void exposesRepeatedSignedDifferencesWithoutAnApplicationPattern() {
        assertEquals(add(sub(A, C), sub(B, C)), normalize(sub(sub(add(A, B), C), C)));
        assertEquals(sub(sub(new NumberExpr(0), A), B), normalize(sub(new NumberExpr(0), add(A, B))));
    }
    @Test void leavesNonAdditiveOperationsOpaque() {
        Expr product = new BinaryExpr(A, BinaryOperator.MUL, B);
        assertSame(product, normalize(product));
        assertEquals(product, normalize(sub(add(product, C), C)));
    }
    @Test void oversizedInputIsNotClaimedAsCompletelyProcessed() {
        Expr expression = A;
        for (int i = 0; i < SignedAdditiveForm.MAX_DEPTH + 1; i++) expression = add(expression, B);
        var result = SignedAdditiveForm.normalize(expression, work -> { });
        assertFalse(result.complete());
        assertSame(expression, result.expression());
    }
    @Test void chargesWorkAndPropagatesCancellation() {
        var charged = new AtomicLong();
        var cancellation = new IllegalStateException("cancelled by caller");
        assertSame(cancellation, assertThrows(IllegalStateException.class, () ->
                SignedAdditiveForm.normalize(sub(add(A, B), A), work -> {
                    if (charged.addAndGet(work) >= 3) throw cancellation;
                })));
        assertEquals(3, charged.get());
    }
    private static Expr normalize(Expr expression) {
        var result = SignedAdditiveForm.normalize(expression, work -> { });
        assertTrue(result.complete());
        return result.expression();
    }
    private static Expr add(Expr left, Expr right) { return new BinaryExpr(left, BinaryOperator.ADD, right); }
    private static Expr sub(Expr left, Expr right) { return new BinaryExpr(left, BinaryOperator.SUB, right); }
}
