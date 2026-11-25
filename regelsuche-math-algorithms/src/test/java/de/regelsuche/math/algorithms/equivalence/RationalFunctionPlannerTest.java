package de.regelsuche.math.algorithms.equivalence;

import static org.junit.jupiter.api.Assertions.*;

import de.regelsuche.math.algorithms.iteration.IterationLimits;
import java.util.AbstractList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class RationalFunctionPlannerTest {
    private static final IterationLimits LIMITS = new IterationLimits(2_000_000, 20_000, 10_000);

    @Test
    void cancelsAnExpandedFactorAndPreservesTheOriginalHole() {
        var result = simplify("(x^2-1)/(x-1)", List.of("x != 1"));
        assertEquals(RationalFunctionPlanner.Status.VERIFIED, result.status(), result.reason());
        assertEquals("x + 1", result.candidate());
        assertEquals(List.of("x - 1 != 0"), result.requiredConditions());
        assertTrue(result.work() > 0);
        assertTrue(verify(result.source(), result.certificate().orElseThrow()).valid());
    }

    @Test
    void aSimplificationIsOnlyConditionalWithoutTheMissingAssumptions() {
        var result = simplify("(x^2-1)/(x-1)", List.of());
        assertEquals(RationalFunctionPlanner.Status.REQUIRES_ASSUMPTIONS, result.status());
        assertEquals("x + 1", result.candidate());
        assertTrue(result.certificate().isEmpty());
        var wrong = simplify("(x^2-1)/(x-1)", List.of("x != -1"));
        assertEquals(RationalFunctionPlanner.Status.REQUIRES_ASSUMPTIONS, wrong.status());
    }

    @Test
    void nestedDivisionRetainsAllOriginalPoles() {
        var result = simplify("(1/(x+1))/(1/(x-1))", List.of("x != -1", "x != 1"));
        assertEquals(RationalFunctionPlanner.Status.VERIFIED, result.status(), result.reason());
        assertEquals(List.of("x + 1 != 0", "x - 1 != 0"), result.requiredConditions());
        assertTrue(verify(result.source(), result.certificate().orElseThrow()).valid());
        assertEquals(RationalFunctionPlanner.Status.REQUIRES_ASSUMPTIONS,
            simplify(result.source(), List.of("x != -1")).status());
    }

    @Test
    void cancelsMultivariateVisibleDivisorsWithoutASeparateRepresentation() {
        var result = simplify("(x^2-a^2)/(x-a)", List.of("x != a"));
        assertEquals(RationalFunctionPlanner.Status.VERIFIED, result.status(), result.reason());
        assertTrue(verify(result.source(), result.certificate().orElseThrow()).valid());
        assertFalse(result.candidate().contains("/"));
    }

    @Test
    void verifierBindsSourceCandidateAndEveryAssumption() {
        var good = simplify("(x^2-1)/(x-1)", List.of("x != 1")).certificate().orElseThrow();
        assertFalse(verify("(x^2-4)/(x-1)", good).valid());
        assertFalse(verify(good.source(), new RationalFunctionPlanner.Certificate(
            good.source(), "x + 2", good.assumptions())).valid());
        assertFalse(verify(good.source(), new RationalFunctionPlanner.Certificate(
            good.source(), good.candidate(), List.of())).valid());
        assertFalse(verify("x", new RationalFunctionPlanner.Certificate(
            "x", "(x*y)/y", List.of())).valid());
    }

    @Test
    void zeroDivisorsUnsupportedFunctionsAndResourceStopsNeverCertify() {
        for (String source : List.of("x/0", "x/(x-x)", "sin(x)/x", "0^0")) {
            var result = simplify(source, List.of());
            assertEquals(RationalFunctionPlanner.Status.UNAVAILABLE, result.status(), source);
            assertTrue(result.certificate().isEmpty());
        }
        assertEquals("CANCELLED", RationalFunctionPlanner.simplify("x/x", List.of("x != 0"),
            LIMITS, () -> true).reason());
        var exhausted = RationalFunctionPlanner.simplify("(x^2-1)/(x-1)", List.of("x != 1"),
            new IterationLimits(2, 20_000, 10_000), () -> false);
        assertEquals(RationalFunctionPlanner.Status.UNAVAILABLE, exhausted.status());
        assertTrue(exhausted.work() <= 2);
        assertEquals(RationalFunctionPlanner.Status.UNAVAILABLE,
            simplify("((x+y+z)^12)^12", List.of()).status());
    }

    @Test
    void exactDecimalCoefficientsStayExact() {
        var result = simplify("(0.3*x+0.6)/0.1", List.of());
        assertEquals(RationalFunctionPlanner.Status.VERIFIED, result.status(), result.reason());
        assertEquals("3*x + 6", result.candidate());
    }

    @Test
    void anExpandedDenominatorDoesNotAddAnUndeclaredCondition() {
        var result = simplify("1/(x*(x+1))", List.of("x != 0", "x != -1"));
        assertEquals(RationalFunctionPlanner.Status.VERIFIED, result.status(), result.reason());
        assertEquals(List.of("x != 0", "x + 1 != 0"), result.requiredConditions());
        assertTrue(verify(result.source(), result.certificate().orElseThrow()).valid());
    }

    @Test
    void expandingAFactoredNumeratorCannotDisplaceTheCheaperSource() {
        String source = "((a+b)*(c+d))/x";
        var result = simplify(source, List.of("x != 0"));
        assertEquals(RationalFunctionPlanner.Status.VERIFIED, result.status(), result.reason());
        assertEquals(source, result.candidate());
    }

    @Test
    void rationalNonzeroConditionsUseTheirActualConstantDenominators() {
        var result = simplify("x/(3*x+1)", List.of("x != -1/3"));
        assertEquals(RationalFunctionPlanner.Status.VERIFIED, result.status(), result.reason());
        assertEquals(List.of("x + 1/3 != 0"), result.requiredConditions());
        assertTrue(verify(result.source(), result.certificate().orElseThrow()).valid());
        assertEquals(RationalFunctionPlanner.Status.REQUIRES_ASSUMPTIONS,
            simplify(result.source(), List.of("x != -1/2")).status());
    }

    @Test
    void aReceiptCannotAuthorizeANewCandidateHole() {
        assertFalse(verify("x", new RationalFunctionPlanner.Certificate(
            "x", "(x*y)/y", List.of("y != 0"))).valid());
        assertFalse(verify("1", new RationalFunctionPlanner.Certificate(
            "1", "y^0", List.of("y != 0"))).valid());
    }

    @Test
    void zeroPowerRequiresADefinedNonzeroBase() {
        var missing = simplify("x^0", List.of());
        assertEquals(RationalFunctionPlanner.Status.REQUIRES_ASSUMPTIONS, missing.status());
        assertEquals(List.of("x != 0"), missing.requiredConditions());
        assertFalse(verify("x^0", new RationalFunctionPlanner.Certificate("x^0", "1", List.of())).valid());
        assertTrue(verify("x^0", new RationalFunctionPlanner.Certificate("x^0", "1", missing.requiredConditions())).valid());
        var nested = simplify("(x/y)^0", List.of("y != 0"));
        assertEquals(RationalFunctionPlanner.Status.REQUIRES_ASSUMPTIONS, nested.status());
        assertEquals(List.of("x != 0", "y != 0"), nested.requiredConditions());
        assertEquals(RationalFunctionPlanner.Status.VERIFIED, simplify(nested.source(), nested.requiredConditions()).status());
        assertEquals(RationalFunctionPlanner.Status.VERIFIED, simplify("2^0", List.of()).status());
        assertEquals(RationalFunctionPlanner.Status.UNAVAILABLE, simplify("0^0", List.of()).status());
    }

    @Test
    void aFoldedCoefficientBeyondTheParserLiteralLimitReturnsTheSource() {
        String number = "9".repeat(600);
        String source = number + "*" + number;
        var result = simplify(source, List.of());
        assertEquals(RationalFunctionPlanner.Status.VERIFIED, result.status(), result.reason());
        assertEquals(source, result.candidate());
        assertTrue(verify(source, result.certificate().orElseThrow()).valid());
    }

    @Test
    void rawAssumptionsAreBoundedBeforeWhitespaceIsDiscarded() {
        List<String> longRaw = List.of(" ".repeat(30_000) + "x != 0");
        assertEquals("SOURCE_LIMIT", simplify("x/x", longRaw).reason());
        assertEquals("SOURCE_LIMIT", verify("x/x", new RationalFunctionPlanner.Certificate("x/x", "1", longRaw)).reason());
    }

    @Test
    void rawAssumptionCharactersConsumeWorkEvenIfDuplicatesOrBlank() {
        var padded = " ".repeat(1_000) + "x != 0";
        var result = RationalFunctionPlanner.simplify("x/x", List.of(padded, padded, " ".repeat(1_000)),
            new IterationLimits(1_500, 20_000, 10_000), () -> false);
        assertEquals("WORK_LIMIT", result.reason());
    }

    @Test
    void oversizedAssumptionCountIsRejectedBeforeAnyEntryIsRead() {
        var list = new AbstractList<String>() {
            public int size() { return 25_000; }
            public String get(int i) { throw new AssertionError("unadmitted entry read"); }
        };
        assertEquals("TERM_LIMIT", simplify("x", list).reason());
    }

    @Test
    void cancellationDuringRawAdmissionStopsBeforeTheRestOfTheListIsCopied() {
        var reads = new AtomicInteger();
        var list = new AbstractList<String>() {
            public int size() { return 100; }
            public String get(int i) { reads.incrementAndGet(); return "x != 0"; }
        };
        var result = RationalFunctionPlanner.simplify("x/x", list, LIMITS, () -> reads.get() > 0);
        assertEquals("CANCELLED", result.reason());
        assertEquals(1, reads.get());
    }

    private static RationalFunctionPlanner.Result simplify(String source, List<String> assumptions) {
        return RationalFunctionPlanner.simplify(source, assumptions, LIMITS, () -> false);
    }

    private static RationalFunctionPlanner.Verification verify(String source,
            RationalFunctionPlanner.Certificate certificate) {
        return RationalFunctionPlanner.verify(source, certificate, LIMITS, () -> false);
    }
}
