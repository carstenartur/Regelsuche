package de.regelsuche.transform;

import static org.junit.jupiter.api.Assertions.*;

import de.regelsuche.ast.NumberExpr;
import de.regelsuche.polynomial.PolynomialWorkAuthority;
import de.regelsuche.polynomial.PolynomialWorkLedger;
import de.regelsuche.scalar.ExactRational;
import java.math.BigInteger;
import org.junit.jupiter.api.Test;

class MeasuredUnivariatePolynomialTest {
    @Test void actualBigIntegerDivisionIsAdmittedTogetherWithItsOperandBits() {
        var authority = new MeasuredRulePreparationPlannerTest.Authority(Long.MAX_VALUE);
        var dividend = UnivariatePolynomial.of(new NumberExpr(ExactRational.integer(new DivisionProbe("42"))), authority);
        var divisor = UnivariatePolynomial.of(new NumberExpr(2), authority);
        DivisionProbe.calls = 0;
        var quotient = dividend.divideExactly(divisor);
        assertNotNull(quotient);
        assertEquals(new NumberExpr(21), quotient.toExpression());
        assertEquals(1, DivisionProbe.calls);
        assertEquals(1, authority.ledger().units("integer.divide-remainder"));
        assertEquals(10, authority.ledger().units("integer.divide-remainder.operand-bits"));
    }

    @Test void refusedAtomicDivisionChargePerformsNoBigIntegerDivision() {
        var authority = new MeasuredRulePreparationPlannerTest.Authority(Long.MAX_VALUE) {
            @Override public void consume(PolynomialWorkLedger charge) {
                if (charge.units("integer.divide-remainder") > 0) throw new LimitReached();
                super.consume(charge);
            }
        };
        var dividend = UnivariatePolynomial.of(new NumberExpr(ExactRational.integer(new DivisionProbe("42"))), authority);
        var divisor = UnivariatePolynomial.of(new NumberExpr(2), authority);
        DivisionProbe.calls = 0;
        long before = authority.used;
        assertThrows(PolynomialWorkAuthority.LimitReached.class, () -> dividend.divideExactly(divisor));
        assertEquals(0, DivisionProbe.calls);
        assertEquals(0, authority.ledger().units("integer.divide-remainder"));
        assertEquals(0, authority.ledger().units("integer.divide-remainder.operand-bits"));
        assertTrue(authority.used > before, "executed preflight/copy/width work must remain visible");
    }

    /** An actual BigInteger call probe, retained through the original rational constructor. */
    private static final class DivisionProbe extends BigInteger {
        static int calls;
        DivisionProbe(String value) { super(value); }
        DivisionProbe(byte[] value) { super(value); }
        @Override public BigInteger divide(BigInteger divisor) { return new DivisionProbe(super.divide(divisor).toByteArray()); }
        @Override public BigInteger[] divideAndRemainder(BigInteger divisor) {
            calls++;
            return super.divideAndRemainder(divisor);
        }
    }
}
