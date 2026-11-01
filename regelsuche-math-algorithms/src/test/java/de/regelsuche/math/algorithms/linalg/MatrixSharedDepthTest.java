package de.regelsuche.math.algorithms.linalg;

import static de.regelsuche.math.algorithms.linalg.ExactMatrixExpression.*;
import static org.junit.jupiter.api.Assertions.*;

import de.regelsuche.math.algorithms.equivalence.Polynomial;
import de.regelsuche.math.algorithms.equivalence.Rational;
import de.regelsuche.math.algorithms.linalg.SymbolicLinearSystem.PolynomialMatrix;
import java.util.List;
import org.junit.jupiter.api.Test;

class MatrixSharedDepthTest {
    private static Matrix zero(int size) {
        return new Matrix("zero", new PolynomialMatrix(java.util.Collections.nCopies(size,
            java.util.Collections.nCopies(size, Polynomial.zero()))));
    }
    private static ExactMatrixExpression deep(int levels, int size) {
        ExactMatrixExpression value = new Identity(size);
        Matrix zero = zero(size);
        for (int i = 0; i < levels; i++) value = new Sum(value, zero);
        return value;
    }
    @Test void deeperSharedOccurrenceCannotHideBehindFreshEvaluatorCache() {
        ExactMatrixExpression d = deep(30, 12);
        var c = new Matrix("column", new PolynomialMatrix(java.util.Collections.nCopies(12,
            List.of(Polynomial.constant(Rational.ONE)))));
        var source = new Product(new Product(d, new Identity(12)), new Product(d, c));
        var candidate = new Product(d, new Product(new Identity(12), new Product(d, c)));
        assertDoesNotThrow(() -> new ExactMatrixAlgebra(new ExactMatrixAlgebra.Work(1_000_000)).evaluate(source));
        var algebra = new ExactMatrixAlgebra(new ExactMatrixAlgebra.Work(1_000_000));
        var rejected = assertThrows(ExactMatrixAlgebra.Unsupported.class, () -> algebra.evaluate(candidate));
        assertTrue(rejected.getMessage().contains("DEPTH"), rejected.getMessage());
    }
    @Test void orderedChainCannotProposeTheOverDeepSharedFactorRegrouping() {
        ExactMatrixExpression d = deep(30, 12);
        var c = new Matrix("column", new PolynomialMatrix(java.util.Collections.nCopies(12,
            List.of(Polynomial.constant(Rational.ONE)))));
        var source = new Product(new Product(d, new Identity(12)), new Product(d, c));
        var work = new ExactMatrixAlgebra.Work(1_000_000);
        var algebra = new ExactMatrixAlgebra(work);
        algebra.evaluate(source);
        assertTrue(OrderedMatrixChain.propose(source, algebra, work).isEmpty());
    }
    @Test void aPreviouslyCachedRootMustBeRevalidatedAtItsNewDepth() {
        ExactMatrixExpression d = deep(32, 1);
        var algebra = new ExactMatrixAlgebra(new ExactMatrixAlgebra.Work(100_000));
        algebra.evaluate(d);
        assertThrows(ExactMatrixAlgebra.Unsupported.class, () -> algebra.evaluate(new Product(d, new Identity(1))));
    }
    @Test void sharedOccurrencesAtTheBoundaryRemainAccepted() {
        ExactMatrixExpression d = deep(30, 1);
        var source = new Product(new Product(d, new Identity(1)), new Product(d, new Identity(1)));
        var algebra = new ExactMatrixAlgebra(new ExactMatrixAlgebra.Work(100_000));
        assertEquals(algebra.identity(1), algebra.evaluate(source));
    }
    @Test void cachedDepthChecksConsumeTheSameWorkLedger() {
        ExactMatrixExpression d = deep(30, 1);
        var measured = new ExactMatrixAlgebra.Work(100_000);
        new ExactMatrixAlgebra(measured).evaluate(d);
        int firstWork = measured.ledger().consumedWorkUnits();
        var bounded = new ExactMatrixAlgebra.Work(firstWork + 1);
        var algebra = new ExactMatrixAlgebra(bounded);
        algebra.evaluate(d);
        assertThrows(ExactMatrixAlgebra.Exhausted.class, () -> algebra.evaluate(d));
        assertTrue(bounded.ledger().consumedWorkUnits() <= firstWork + 1);
    }
}
