package de.regelsuche.transform;

import static org.junit.jupiter.api.Assertions.*;

import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.BinaryOperator;
import de.regelsuche.ast.NumberExpr;
import de.regelsuche.parse.ExpressionFormatter;
import de.regelsuche.parse.ExpressionParser;
import de.regelsuche.polynomial.PolynomialWorkAuthority;
import de.regelsuche.polynomial.PolynomialWorkLedger;
import de.regelsuche.scalar.ExactRational;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.MessageDigestSpi;
import java.security.Provider;
import java.security.Security;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MeasuredRulePreparationPlannerTest {
    private final ExpressionParser parser = new ExpressionParser();
    private final RulePreparationPlanner planner = new RulePreparationPlanner();

    @Test void exactPlanIncludesBothActualDivisionPassesAndCertificateDigests() throws Exception {
        var input = parser.parseTerm("(x^3-1)/(x-1)");
        var authority = new Authority(Long.MAX_VALUE);
        CountingDigest.calls = 0;
        Security.insertProviderAt(new CountingProvider(), 1);
        try {
            var measured = planner.planObserved(input, authority);
            assertTrue(measured.completed());
            assertSame(input, measured.input());
            assertEquals(RulePreparationPlanner.Status.PREPARED, measured.attempt().orElseThrow().status());
            assertEquals(6, unitsEnding(measured.work(), ".integer.divide-remainder"));
            assertEquals(2, unitsEnding(measured.work(), ".sha256-invocations"));
            assertEquals(2, CountingDigest.calls);
            assertEquals(measured.work(), authority.ledger());
            assertTrue(unitsEnding(measured.work(), ".integer.multiply") > 0);
            assertTrue(unitsEnding(measured.work(), ".operand-bits") > 0);
        } finally { Security.removeProvider(CountingProvider.NAME); }
    }

    @Test void negativeDivisionRetainsActualWorkAndDoesNotHashACertificate() {
        var measured = planner.planObserved(parser.parseTerm("(x^3+1)/(x-1)"), new Authority(Long.MAX_VALUE));
        assertTrue(measured.completed());
        assertEquals(RulePreparationPlanner.Status.NO_EXACT_QUOTIENT, measured.attempt().orElseThrow().status());
        assertEquals(3, unitsEnding(measured.work(), ".integer.divide-remainder"));
        assertEquals(0, unitsEnding(measured.work(), ".sha256-invocations"));
    }

    @Test void zeroAuthorityRefusesBeforeAnyDigestOrSolverExecution() {
        CountingDigest.calls = 0;
        Security.insertProviderAt(new CountingProvider(), 1);
        try {
            var measured = planner.planObserved(parser.parseTerm("(x^3-1)/(x-1)"), new Authority(0));
            assertEquals(RulePreparationPlanner.MeasurementOutcome.BUDGET_INCONCLUSIVE, measured.outcome());
            assertTrue(measured.attempt().isEmpty());
            assertEquals(0, measured.work().totalWorkUnits());
            assertEquals(0, CountingDigest.calls);
            assertTrue(measured.refusedCharge().isPresent());
        } finally { Security.removeProvider(CountingProvider.NAME); }
    }

    @Test void refusingTheSecondHashRetainsOnlyTheOneActuallyExecuted() {
        var authority = new Authority(Long.MAX_VALUE) {
            @Override public void consume(PolynomialWorkLedger charge) {
                if (unitsEnding(charge, ".sha256-invocations") > 0 && unitsEnding(ledger(), ".sha256-invocations") == 1)
                    throw new LimitReached();
                super.consume(charge);
            }
        };
        CountingDigest.calls = 0;
        Security.insertProviderAt(new CountingProvider(), 1);
        try {
            var measured = planner.planObserved(parser.parseTerm("(x^3-1)/(x-1)"), authority);
            assertFalse(measured.completed());
            assertTrue(measured.attempt().isEmpty());
            assertEquals(1, CountingDigest.calls);
            assertEquals(1, unitsEnding(measured.work(), ".sha256-invocations"));
            assertEquals(authority.ledger(), measured.work());
        } finally { Security.removeProvider(CountingProvider.NAME); }
    }

    @Test void repeatedPlansAndIndependentVerificationShareOneNonResettingLimit() {
        var input = parser.parseTerm("(x^3-1)/(x-1)");
        var control = planner.planObserved(input, new Authority(Long.MAX_VALUE));
        assertTrue(control.work().totalWorkUnits() > 0);
        var authority = new Authority(control.work().totalWorkUnits());
        var first = planner.planObserved(input, authority);
        assertTrue(first.completed());
        var second = planner.planObserved(input, authority);
        assertFalse(second.completed());
        assertEquals(0, second.work().totalWorkUnits());
        var verification = planner.verifyObserved(first.attempt().orElseThrow().application().orElseThrow(), authority);
        assertFalse(verification.completed());
        assertFalse(verification.verified());
        assertEquals(first.work(), authority.ledger());
    }

    @Test void independentVerificationHasItsOwnObservedDeltaWithoutResettingTheAuthority() {
        var authority = new Authority(Long.MAX_VALUE);
        var measured = planner.planObserved(parser.parseTerm("(x^3-1)/(x-1)"), authority);
        var verification = planner.verifyObserved(measured.attempt().orElseThrow().application().orElseThrow(), authority);
        assertTrue(verification.completed());
        assertTrue(verification.verified());
        assertEquals(3, unitsEnding(verification.work(), ".integer.divide-remainder"));
        assertEquals(1, unitsEnding(verification.work(), ".sha256-invocations"));
        assertEquals(measured.work().totalWorkUnits() + verification.work().totalWorkUnits(), authority.used);
    }

    @Test void invalidCertificateVerificationRetainsItsExecutedDivisionAndDigest() {
        var application = planner.plan(parser.parseTerm("(x^3-1)/(x-1)")).application().orElseThrow();
        var certificate = application.certificate();
        var forged = new RulePreparationPlanner.Certificate(certificate.schema(), certificate.solverId(),
            certificate.dividendExpression(), certificate.divisorExpression(), certificate.quotientExpression(),
            certificate.remainderExpression(), certificate.preparedExpression(), "0".repeat(64));
        var changed = new RulePreparationPlanner.PreparedRuleApplication(application.schema(), application.plannerId(),
            application.principalRuleId(), application.originalSubtree(), application.preparedSubtree(), application.resultSubtree(),
            application.bindings(), application.residualObligation(), application.assumptions(), application.primitiveRuleIds(),
            forged, application.work());
        var result = planner.verifyObserved(changed, new Authority(Long.MAX_VALUE));
        assertTrue(result.completed());
        assertFalse(result.verified());
        assertEquals("REJECTED", result.detailCode());
        assertEquals(3, unitsEnding(result.work(), ".integer.divide-remainder"));
        assertEquals(1, unitsEnding(result.work(), ".sha256-invocations"));
    }

    @Test void technicalFailureRetainsThePrefixAndAStableFailureClass() {
        var input = new BinaryExpr(new NumberExpr(ExactRational.integer(BigInteger.ONE.shiftLeft(10_000))),
            BinaryOperator.DIV, parser.parseTerm("x-1"));
        var measured = planner.planObserved(input, new Authority(Long.MAX_VALUE));
        assertEquals(RulePreparationPlanner.MeasurementOutcome.TECHNICAL_FAILURE, measured.outcome());
        assertTrue(measured.attempt().isEmpty());
        assertEquals(IllegalArgumentException.class.getName(), measured.failureClass());
        assertTrue(measured.work().totalWorkUnits() > 0);
    }

    @Test void decimalFallbackCannotSwallowAnAccountingFailure() {
        var authority = new Authority(Long.MAX_VALUE) {
            boolean failed;
            @Override public void consume(PolynomialWorkLedger charge) {
                if (!failed && unitsEnding(charge, ".format.numeric-text-scan-code-units") > 0) {
                    failed = true;
                    throw new ArithmeticException("private host diagnostic must not enter canonical evidence");
                }
                super.consume(charge);
            }
        };
        var measured = planner.planObserved(parser.parseTerm("1.5/(x-1)"), authority);
        assertEquals(RulePreparationPlanner.MeasurementOutcome.TECHNICAL_FAILURE, measured.outcome());
        assertEquals(ArithmeticException.class.getName(), measured.failureClass());
        assertTrue(measured.attempt().isEmpty());
        assertEquals(authority.ledger(), measured.work());
    }

    @Test void historicalCanonicalOutcomesRemainByteIdentical() throws Exception {
        for (String[] row : new String[][] {
            {"(x^3 - 1)/(x - 1)", "0c26e47d1e607a846221d58a0397dc8dd6b1fa0a97da0f44ed89ed6d6aa7773d"},
            {"(x^3 + 1)/(x - 1)", "f561997954024633b1fa74e9c6a4b2705863858e9fe1e67f54b0f1d4de7f8a9a"},
            {"(1000000000000*x+1000000000000)/(x+1)", "a07a02979bf582b2b5102a7851aa8902a04ab297b40358962ca1a10b9d19fc58"},
            {"(x*y+1)/(x-1)", "c7b05232425d8070afa9b8b55c042e01060a38a34542d82aed57941c873a1b75"},
            {"x/0", "3cbd3539c6a2428fe8d4ba034c65b23fb5012f3cd5595b42af561c2025bc9fca"},
            {"x+1", "c16a12a9e7d171b23c5201578459641da847581a564b85f5448e996158ced0b2"},
            {"((x-1)*(x+1))/(x-1)", "6d2262569d2a87063e6ad30f7a0094fab14e07706d1588f2fde4915e1beb800f"},
            {"(x+1)/2", "46b834e1866079f41d10fc6f3d5195262a4058e96ac6ad867f2777b2e8558c23"},
            {"(x/3+1)/(x-1)", "852fb1854fe01c55a60973c5123be7d32f8d6a568943302c177d74bc27d45faa"}}) {
            var input = parser.parseTerm(row[0]); var old = planner.plan(input);
            assertEquals(row[1], canonicalHash(old), row[0]);
            assertEquals(old, planner.planObserved(input, new Authority(Long.MAX_VALUE)).attempt().orElseThrow(), row[0]);
        }
    }

    @Test void callersCannotIssueMeasuredOutcomesOrReplaceTheirLedgers() {
        assertEquals(0, RulePreparationPlanner.MeasuredAttempt.class.getConstructors().length);
        assertEquals(0, RulePreparationPlanner.MeasuredVerification.class.getConstructors().length);
        var result = planner.planObserved(parser.parseTerm("x+1"), new Authority(Long.MAX_VALUE));
        assertThrows(UnsupportedOperationException.class, () -> result.work().stages().put("fake", 1L));
    }

    static long unitsEnding(PolynomialWorkLedger work, String suffix) {
        return work.stages().entrySet().stream().filter(e -> e.getKey().endsWith(suffix)).mapToLong(Map.Entry::getValue).sum();
    }
    private static String canonicalHash(RulePreparationPlanner.PlanAttempt attempt) throws Exception {
        String material = attempt.status()+"|"+attempt.work()+"|"+attempt.detail()+"|"+attempt.residualObligation()+"|"
            + attempt.application().map(a -> a.certificate()+"|"+ExpressionFormatter.format(a.preparedSubtree())+"|"
                +ExpressionFormatter.format(a.resultSubtree())+"|"+a.assumptions()+"|"+a.primitiveRuleIds()).orElse("");
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(material.getBytes(StandardCharsets.UTF_8)));
    }
    static class Authority implements PolynomialWorkAuthority {
        final long limit; long used;
        private final Map<String, Long> stages = new LinkedHashMap<>();
        Authority(long limit) { this.limit = limit; }
        @Override public void consume(PolynomialWorkLedger charge) {
            if (charge.totalWorkUnits() > limit - used) throw new LimitReached();
            used += charge.totalWorkUnits(); charge.stages().forEach((stage, units) -> stages.merge(stage, units, Math::addExact));
        }
        @Override public long remainingOpaqueWorkUnits() { return limit - used; }
        PolynomialWorkLedger ledger() { return new PolynomialWorkLedger(stages); }
    }
    public static final class CountingProvider extends Provider {
        static final String NAME = "PreparationDigestControl";
        CountingProvider() { super(NAME, "1", "Counts actual SHA-256 calls in measured preparation"); put("MessageDigest.SHA-256", CountingDigest.class.getName()); }
    }
    public static final class CountingDigest extends MessageDigestSpi {
        static int calls;
        private final MessageDigest delegate;
        public CountingDigest() { try { delegate = MessageDigest.getInstance("SHA-256", "SUN"); } catch (Exception failure) { throw new IllegalStateException(failure); } }
        @Override protected void engineUpdate(byte input) { delegate.update(input); }
        @Override protected void engineUpdate(byte[] input, int offset, int length) { delegate.update(input, offset, length); }
        @Override protected byte[] engineDigest() { calls++; return delegate.digest(); }
        @Override protected void engineReset() { delegate.reset(); }
    }
}
