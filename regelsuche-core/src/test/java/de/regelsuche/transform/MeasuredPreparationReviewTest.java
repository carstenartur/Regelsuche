package de.regelsuche.transform;

import static org.junit.jupiter.api.Assertions.*;

import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.BinaryOperator;
import de.regelsuche.ast.NumberExpr;
import de.regelsuche.ast.VariableExpr;
import de.regelsuche.parse.ExpressionFormatter;
import de.regelsuche.parse.ExpressionParser;
import de.regelsuche.polynomial.PolynomialWorkAuthority;
import de.regelsuche.polynomial.PolynomialWorkLedger;
import de.regelsuche.scalar.ExactRational;
import java.math.BigInteger;
import java.security.MessageDigestSpi;
import java.security.Provider;
import java.security.Security;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Independent real-operation controls; no study or caller-supplied measurement is accepted. */
class MeasuredPreparationReviewTest {
    @Test
    void widthRefusalPrecedesTheActualBigIntegerInspection() {
        var input = new BinaryExpr(number(), BinaryOperator.DIV, new VariableExpr("x"));
        var authority = new RecordingAuthority("measurement.integer-width-inspections");
        IntegerProbe.reset();

        var result = new RulePreparationPlanner().planObserved(input, authority);

        assertEquals(RulePreparationPlanner.MeasurementOutcome.BUDGET_INCONCLUSIVE, result.outcome());
        assertTrue(result.attempt().isEmpty());
        assertEquals(0, IntegerProbe.widths);
        assertEquals(0, IntegerProbe.absoluteValues);
        assertEquals(0, IntegerProbe.decimalConversions);
        assertEquals(0, unitsEnding(result.work(), "measurement.integer-width-inspections"));
        assertEquals(1, unitsEnding(result.refusedCharge().orElseThrow(), "measurement.integer-width-inspections"));
        assertTrue(result.work().totalWorkUnits() > 0);
        assertEquals(authority.ledger(), result.work());
    }

    @Test
    void decimalRefusalRetainsActualWidthAndAbsoluteValueWorkWithoutConverting() {
        var number = number();
        var authority = new RecordingAuthority("format.integer-decimal-conversions");
        IntegerProbe.reset();

        assertThrows(PolynomialWorkAuthority.LimitReached.class,
            () -> ExpressionFormatter.formatMeasured(number, authority));

        assertTrue(IntegerProbe.widths > 0);
        assertEquals(1, IntegerProbe.absoluteValues);
        assertEquals(0, IntegerProbe.decimalConversions);
        assertEquals(1, authority.ledger().units("format.integer-absolute-values"));
        assertEquals(0, authority.ledger().units("format.integer-decimal-conversions"));
        assertEquals(0, authority.ledger().units("format.integer-decimal-conversions.operand-bits"));
    }

    @Test
    void anActualDigestFailureRetainsAdmittedUtf8AndDigestWorkAcrossCalls() {
        var planner = new RulePreparationPlanner();
        var input = new ExpressionParser().parseTerm("(x^3-1)/(x-1)");
        var authority = new RecordingAuthority("");
        ThrowingDigest.calls = 0;
        ThrowingDigest.inputBytes = 0;
        assertTrue(Security.insertProviderAt(new ThrowingProvider(), 1) > 0);
        try {
            var first = planner.planObserved(input, authority);
            assertEquals(RulePreparationPlanner.MeasurementOutcome.TECHNICAL_FAILURE, first.outcome());
            assertEquals(IllegalStateException.class.getName(), first.failureClass());
            assertTrue(first.attempt().isEmpty());
            assertTrue(first.refusedCharge().isEmpty());
            assertEquals(1, ThrowingDigest.calls);
            assertEquals(1, unitsEnding(first.work(), "certificate.sha256-invocations"));
            assertEquals(ThrowingDigest.inputBytes, unitsEnding(first.work(), "certificate.sha256-input-bytes"));
            assertEquals(0, unitsEnding(first.work(), "certificate.hex-output-code-units"));
            assertEquals(authority.ledger(), first.work());

            var second = planner.planObserved(input, authority);
            assertEquals(RulePreparationPlanner.MeasurementOutcome.TECHNICAL_FAILURE, second.outcome());
            assertTrue(second.attempt().isEmpty());
            assertEquals(2, ThrowingDigest.calls);
            assertEquals(first.work().totalWorkUnits() + second.work().totalWorkUnits(), authority.used);
            assertEquals(2, unitsEnding(authority.ledger(), "certificate.sha256-invocations"));
            assertEquals(ThrowingDigest.inputBytes, unitsEnding(authority.ledger(), "certificate.sha256-input-bytes"));
        } finally {
            Security.removeProvider(ThrowingProvider.NAME);
        }
    }

    private static NumberExpr number() {
        return new NumberExpr(ExactRational.integer(new IntegerProbe("42")));
    }

    private static long unitsEnding(PolynomialWorkLedger ledger, String suffix) {
        return ledger.stages().entrySet().stream().filter(item -> item.getKey().endsWith(suffix))
            .mapToLong(Map.Entry::getValue).sum();
    }

    private static final class RecordingAuthority implements PolynomialWorkAuthority {
        private final String refusedSuffix;
        private final Map<String, Long> stages = new LinkedHashMap<>();
        private long used;
        private RecordingAuthority(String refusedSuffix) { this.refusedSuffix = refusedSuffix; }
        @Override public void consume(PolynomialWorkLedger charge) {
            if (!refusedSuffix.isEmpty() && unitsEnding(charge, refusedSuffix) > 0) throw new LimitReached();
            used = Math.addExact(used, charge.totalWorkUnits());
            charge.stages().forEach((stage, units) -> stages.merge(stage, units, Math::addExact));
        }
        @Override public long remainingOpaqueWorkUnits() { return Long.MAX_VALUE - used; }
        private PolynomialWorkLedger ledger() { return new PolynomialWorkLedger(stages); }
    }

    private static final class IntegerProbe extends BigInteger {
        private static int widths, absoluteValues, decimalConversions;
        private IntegerProbe(String value) { super(value); }
        private IntegerProbe(byte[] value) { super(value); }
        private static void reset() { widths = 0; absoluteValues = 0; decimalConversions = 0; }
        @Override public BigInteger divide(BigInteger value) {
            return new IntegerProbe(super.divide(value).toByteArray());
        }
        @Override public int bitLength() { widths++; return super.bitLength(); }
        @Override public BigInteger abs() { absoluteValues++; return super.abs(); }
        @Override public String toString() { decimalConversions++; return super.toString(); }
    }

    public static final class ThrowingProvider extends Provider {
        private static final String NAME = "PreparationReviewDigestFailure";
        private ThrowingProvider() {
            super(NAME, "1", "Independent actual digest failure control");
            put("MessageDigest.SHA-256", ThrowingDigest.class.getName());
        }
    }

    public static final class ThrowingDigest extends MessageDigestSpi {
        private static int calls;
        private static long inputBytes;
        @Override protected void engineUpdate(byte input) { inputBytes++; }
        @Override protected void engineUpdate(byte[] input, int offset, int length) { inputBytes += length; }
        @Override protected byte[] engineDigest() {
            calls++;
            throw new IllegalStateException("private provider diagnostic must not enter retained evidence");
        }
        @Override protected void engineReset() { }
    }
}
