package de.regelsuche.polynomial;

import java.math.BigInteger;
import java.util.Map;

/** Admission of an actual operation plus its inspected signed operand widths. */
public final class PolynomialOperationAccounting {
    private PolynomialOperationAccounting() { }

    /**
     * Width inspections execute only after their own admission. The operation and
     * its operand-bit charge are then admitted atomically, before the caller invokes it.
     * Signed width is max(1, BigInteger.bitLength() + 1), not an estimated running time.
     */
    public static void before(PolynomialWorkAuthority authority, String operation, BigInteger... operands) {
        if (authority == PolynomialWorkAuthority.unbounded()) return;
        long bits = 0;
        for (BigInteger operand : operands) {
            authority.consume("measurement.integer-width-inspections", 1);
            bits = Math.addExact(bits, Math.max(1L, operand.bitLength() + 1L));
        }
        authority.consume(operands.length == 0 ? new PolynomialWorkLedger(Map.of(operation, 1L))
            : new PolynomialWorkLedger(Map.of(operation, 1L, operation + ".operand-bits", bits)));
    }
}
