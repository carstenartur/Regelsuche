package de.regelsuche.math.algorithms.iteration;

import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.scalar.ExactRational;
import de.regelsuche.symbol.SymbolId;
import java.util.List;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;

class IterationPolynomialTest {
    private static final BooleanSupplier NEVER = () -> false;

    @Test
    void canonicalizesPublicMonomialConstructionForMultiplicationAndCancellation() {
        var limits = new IterationLimits(10_000, 32, 30_000);
        var work = new IterationPolynomial.Work(limits, NEVER, IterationPolynomial.MAX_DEGREE);
        var a = new SymbolId(new UUID(0, 1), 1);
        var b = new SymbolId(new UUID(0, 1), 2);
        var polynomial = IterationPolynomial.single(
            new IterationPolynomial.Monomial(List.of(b, a)), ExactRational.ONE, work);

        var multipliedByOne = polynomial.multiply(IterationPolynomial.constant(java.math.BigInteger.ONE, work));

        assertTrue(polynomial.same(multipliedByOne));
        assertTrue(polynomial.subtract(multipliedByOne).terms().isEmpty());
    }
}
