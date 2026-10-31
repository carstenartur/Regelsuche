package de.regelsuche.ast.iteration;

import de.regelsuche.ast.*;
import de.regelsuche.symbol.SymbolId;
import java.math.BigInteger;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FiniteIterationModelTest {
    private final SymbolId i = new SymbolId(new UUID(0, 1), 1);
    private final SymbolId s = new SymbolId(new UUID(0, 1), 2);
    private IndexRange range() { return new IndexRange(BigInteger.ONE, BigInteger.ONE, BigInteger.TEN); }
    @Test void computesExclusiveEndpointAndRejectsInvalidRanges() {
        assertEquals(BigInteger.valueOf(11), range().endExclusive());
        assertThrows(IllegalArgumentException.class, () -> new IndexRange(BigInteger.ZERO, BigInteger.ZERO, BigInteger.ONE));
        assertThrows(IllegalArgumentException.class, () -> new IndexRange(BigInteger.ZERO, BigInteger.ONE, BigInteger.ONE.negate()));
        assertThrows(IllegalArgumentException.class, () -> new IndexRange(BigInteger.ZERO, BigInteger.ONE, BigInteger.ONE.shiftLeft(1025)));
    }
    @Test void rejectsBinderCollisionsAndIllegalReferences() {
        assertThrows(IllegalArgumentException.class, () -> new FiniteIteration.Sum(range(), i, Set.of(i), new NumberExpr(1), IntegerArithmetic.EXACT));
        assertThrows(IllegalArgumentException.class, () -> new FiniteIteration.Sum(range(), i, Set.of(), VariableExpr.scoped(s), IntegerArithmetic.EXACT));
        assertThrows(IllegalArgumentException.class, () -> new FiniteIteration.Sum(range(), i, Set.of(), new VariableExpr("i"), IntegerArithmetic.EXACT));
        assertThrows(IllegalArgumentException.class, () -> new FiniteIteration.Recurrence(range(), i, i, Set.of(), new NumberExpr(0), VariableExpr.scoped(i), IntegerArithmetic.EXACT));
        assertThrows(IllegalArgumentException.class, () -> new FiniteIteration.Recurrence(range(), i, s, Set.of(), VariableExpr.scoped(i), VariableExpr.scoped(s), IntegerArithmetic.EXACT));
        assertThrows(IllegalArgumentException.class, () -> new FiniteIteration.Recurrence(range(), i, s, Set.of(), VariableExpr.scoped(s), VariableExpr.scoped(s), IntegerArithmetic.EXACT));
    }
    @Test void ownsFreeSymbolSetAndBoundsExpressionTraversal() {
        var symbols = new HashSet<SymbolId>(); symbols.add(s);
        var sum = new FiniteIteration.Sum(range(), i, symbols, VariableExpr.scoped(s), IntegerArithmetic.EXACT);
        symbols.clear(); assertEquals(Set.of(s), sum.parameters());
        assertThrows(UnsupportedOperationException.class, () -> sum.parameters().clear());
        Expr deep = new NumberExpr(1);
        for (int n = 0; n < 70; n++) deep = new BinaryExpr(deep, BinaryOperator.ADD, new NumberExpr(1));
        Expr tooDeep = deep;
        assertThrows(IllegalArgumentException.class, () -> new FiniteIteration.Sum(range(), i, Set.of(), tooDeep, IntegerArithmetic.EXACT));
    }
    @Test void rejectsOversizedFunctionMetadataBeforeSourceBinding() {
        assertThrows(IllegalArgumentException.class, () -> new FiniteIteration.Sum(range(), i, Set.of(),
            new FunctionExpr("x".repeat(10000), new NumberExpr(1)), IntegerArithmetic.EXACT));
    }
    @Test void wordNormalizationIsSigned() {
        assertEquals(BigInteger.valueOf(-1), IntegerArithmetic.JAVA_INT.normalize(BigInteger.ONE.shiftLeft(32).subtract(BigInteger.ONE)));
        assertEquals(BigInteger.valueOf(Long.MIN_VALUE), IntegerArithmetic.JAVA_LONG.normalize(BigInteger.ONE.shiftLeft(63)));
    }
}
