package de.regelsuche.math.algorithms.modular;

import de.regelsuche.math.algorithms.iteration.IterationLimits;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

import static java.math.BigInteger.ONE;
import static java.math.BigInteger.ZERO;
import static org.junit.jupiter.api.Assertions.*;

class CongruenceCoverageTest {
    private static final IterationLimits LIMITS = new IterationLimits(2_000_000, 256, 30_000);

    @Test
    void handlesNoncoprimeModuliSignedOriginsAndDelayedActivation() {
        var request = request(-17, 6, 15, rule(4, -1, -10), rule(9, 1, 10));
        var result = CongruenceCoverage.search(request, LIMITS, () -> false);
        var certificate = result.certificate().orElseThrow();
        assertEquals(CongruenceCoverage.Status.FOUND_UNCOVERED, result.status());
        assertEquals(integer(8), certificate.coveredCount());
        assertEquals(Optional.of(ZERO), certificate.leastIndex());
        assertEquals(Optional.of(integer(-17)), result.leastUncovered());
        assertTrue(CongruenceCoverage.verify(request, certificate, LIMITS, () -> false).valid());
    }

    @Test
    void clipsUnitModulusAtItsInclusiveActivationThreshold() {
        var request = request(-10, 3, 20, rule(1, -42, 2), rule(6, 2, -10));
        var result = CongruenceCoverage.search(request, LIMITS, () -> false);
        assertEquals(integer(18), result.certificate().orElseThrow().coveredCount());
        assertEquals(Optional.of(ONE), result.certificate().orElseThrow().leastIndex());
        assertEquals(Optional.of(integer(-7)), result.leastUncovered());
        assertTrue(CongruenceCoverage.verify(request, result.certificate().orElseThrow(), LIMITS, () -> false).valid());
    }

    @Test
    void distinguishesEmptyQueriesEmptyRulesAndIncompatibleCongruences() {
        var empty = CongruenceCoverage.search(request(7, 3, 0), LIMITS, () -> false);
        assertEquals(CongruenceCoverage.Status.COMPLETE_COVER, empty.status());
        assertEquals(ZERO, empty.certificate().orElseThrow().coveredCount());
        assertTrue(empty.leastUncovered().isEmpty());
        assertTrue(CongruenceCoverage.verify(empty.certificate().orElseThrow().source(),
            empty.certificate().orElseThrow(), LIMITS, () -> false).valid());
        var uncovered = CongruenceCoverage.search(request(2, 6, 12, rule(4, 1, -100)), LIMITS, () -> false);
        assertEquals(ZERO, uncovered.certificate().orElseThrow().coveredCount());
        assertEquals(Optional.of(integer(2)), uncovered.leastUncovered());
        var noRules = CongruenceCoverage.search(request(-3, 7, 4), LIMITS, () -> false);
        assertEquals(Optional.of(integer(-3)), noRules.leastUncovered());
        var inactive = CongruenceCoverage.search(request(0, 1, 10, rule(1, 0, 10)), LIMITS, () -> false);
        assertEquals(ZERO, inactive.certificate().orElseThrow().coveredCount());
    }

    @Test
    void certifiesTrillionPointSchedulesWithoutEnumeratingThem() {
        var count = integer(1_000_000_000_000L);
        var origin = BigInteger.TEN.pow(40);
        var step = integer(7);
        var rules = new ArrayList<CongruenceCoverage.Rule>();
        // These disjoint dyadic classes leave exactly k = 2^39 - 1 in this query.
        for (int exponent = 1; exponent <= 39; exponent++) {
            var period = ONE.shiftLeft(exponent);
            var residueIndex = ONE.shiftLeft(exponent - 1).subtract(ONE);
            rules.add(new CongruenceCoverage.Rule(step.multiply(period),
                origin.add(step.multiply(residueIndex)), origin));
        }
        var request = new CongruenceCoverage.Request(new CongruenceCoverage.Progression(origin, step, count), rules);
        var result = CongruenceCoverage.search(request, LIMITS, () -> false);
        var expectedIndex = ONE.shiftLeft(39).subtract(ONE);
        var certificate = result.certificate().orElseThrow();
        assertEquals(CongruenceCoverage.Status.FOUND_UNCOVERED, result.status());
        assertEquals(count.subtract(ONE), certificate.coveredCount());
        assertEquals(Optional.of(expectedIndex), certificate.leastIndex());
        assertEquals(Optional.of(origin.add(step.multiply(expectedIndex))), result.leastUncovered());
        assertEquals(39, certificate.terms().size());
        assertTrue(result.work() < LIMITS.maximumWork());
        assertTrue(CongruenceCoverage.verify(request, certificate, LIMITS, () -> false).valid());

        var full = new CongruenceCoverage.Request(new CongruenceCoverage.Progression(origin, step, count),
            List.of(new CongruenceCoverage.Rule(integer(2), ZERO, origin),
                new CongruenceCoverage.Rule(integer(2), ONE, origin)));
        var covered = CongruenceCoverage.search(full, LIMITS, () -> false);
        assertEquals(CongruenceCoverage.Status.COMPLETE_COVER, covered.status());
        assertEquals(count, covered.certificate().orElseThrow().coveredCount());
        assertTrue(covered.leastUncovered().isEmpty());
        assertTrue(CongruenceCoverage.verify(full, covered.certificate().orElseThrow(), LIMITS, () -> false).valid());
    }

    @Test
    void matchesIndependentDirectMembershipOnSeededFiniteQueries() {
        var random = new Random(0xC0FFEE);
        for (int example = 0; example < 300; example++) {
            int first = random.nextInt(41) - 20;
            int step = random.nextInt(9) + 1;
            int count = random.nextInt(46);
            var rules = new ArrayList<CongruenceCoverage.Rule>();
            int ruleCount = random.nextInt(7);
            for (int j = 0; j < ruleCount; j++) {
                rules.add(rule(random.nextInt(12) + 1, random.nextInt(41) - 20, random.nextInt(76) - 25));
            }
            var request = new CongruenceCoverage.Request(new CongruenceCoverage.Progression(
                integer(first), integer(step), integer(count)), rules);
            long covered = 0;
            Optional<BigInteger> leastIndex = Optional.empty();
            for (int index = 0; index < count; index++) {
                var value = integer(first + (long) step * index);
                boolean isCovered = rules.stream().anyMatch(rule -> value.compareTo(rule.activeFrom()) >= 0
                    && value.subtract(rule.residue()).mod(rule.modulus()).equals(ZERO));
                if (isCovered) covered++;
                else if (leastIndex.isEmpty()) leastIndex = Optional.of(integer(index));
            }
            var result = CongruenceCoverage.search(request, LIMITS, () -> false);
            var certificate = result.certificate().orElseThrow(() -> new AssertionError(result.reason()));
            assertEquals(integer(covered), certificate.coveredCount(), "count example " + example);
            assertEquals(leastIndex, certificate.leastIndex(), "least example " + example);
            assertEquals(covered == count ? CongruenceCoverage.Status.COMPLETE_COVER
                : CongruenceCoverage.Status.FOUND_UNCOVERED, result.status());
            assertTrue(CongruenceCoverage.verify(request, certificate, LIMITS, () -> false).valid(),
                "replay example " + example);
        }
    }

    @Test
    void coversTenToTheHundredSlotsAndCanonicalizesFiniteSingletons() {
        var enormous = new CongruenceCoverage.Request(new CongruenceCoverage.Progression(
            integer(-5), ONE, BigInteger.TEN.pow(100)), List.of(rule(2, 0, -5), rule(2, 1, -5)));
        var result = CongruenceCoverage.search(enormous, LIMITS, () -> false);
        assertEquals(CongruenceCoverage.Status.COMPLETE_COVER, result.status());
        assertEquals(BigInteger.TEN.pow(100), result.certificate().orElseThrow().coveredCount());
        assertTrue(CongruenceCoverage.verify(enormous, result.certificate().orElseThrow(), LIMITS, () -> false).valid());
        var singleton = request(0, 1, 10, rule(23, 9, 0), rule(29, 9, 0), rule(31, 40, 0));
        var singleResult = CongruenceCoverage.search(singleton, LIMITS, () -> false);
        assertEquals(List.of(new CongruenceCoverage.Term(integer(9), integer(10), ONE)),
            singleResult.certificate().orElseThrow().terms());
        assertEquals(ONE, singleResult.certificate().orElseThrow().coveredCount());
        assertTrue(CongruenceCoverage.verify(singleton, singleResult.certificate().orElseThrow(), LIMITS, () -> false).valid());
    }

    @Test
    void rejectsChangedSourcesTermsCountsAndNonminimalWitnesses() {
        var request = request(0, 1, 15, rule(2, 0, 0), rule(3, 0, 4));
        var certificate = CongruenceCoverage.search(request, LIMITS, () -> false).certificate().orElseThrow();
        assertFalse(CongruenceCoverage.verify(request(0, 1, 16, rule(2, 0, 0), rule(3, 0, 4)),
            certificate, LIMITS, () -> false).valid());
        assertFalse(CongruenceCoverage.verify(request, new CongruenceCoverage.Certificate(request,
            certificate.terms(), certificate.coveredCount().add(ONE), certificate.leastIndex()), LIMITS, () -> false).valid());
        assertFalse(CongruenceCoverage.verify(request, new CongruenceCoverage.Certificate(request,
            certificate.terms(), certificate.coveredCount(), Optional.of(integer(5))), LIMITS, () -> false).valid());
        assertFalse(CongruenceCoverage.verify(request, new CongruenceCoverage.Certificate(request,
            certificate.terms(), certificate.coveredCount(), Optional.empty()), LIMITS, () -> false).valid());
        var changed = new ArrayList<>(certificate.terms());
        var first = changed.getFirst();
        changed.set(0, new CongruenceCoverage.Term(first.firstIndex(), first.period(), first.coefficient().negate()));
        assertFalse(CongruenceCoverage.verify(request, new CongruenceCoverage.Certificate(request,
            changed, certificate.coveredCount(), certificate.leastIndex()), LIMITS, () -> false).valid());
        assertFalse(CongruenceCoverage.verify(request, new CongruenceCoverage.Certificate(request,
            List.of(), certificate.coveredCount(), certificate.leastIndex()), LIMITS, () -> false).valid());
        for (var malformed : List.of(new CongruenceCoverage.Term(integer(-1), first.period(), first.coefficient()),
                new CongruenceCoverage.Term(first.firstIndex(), ZERO, first.coefficient()),
                new CongruenceCoverage.Term(first.firstIndex(), first.period(), ZERO))) {
            changed.set(0, malformed);
            assertFalse(CongruenceCoverage.verify(request, new CongruenceCoverage.Certificate(request,
                changed, certificate.coveredCount(), certificate.leastIndex()), LIMITS, () -> false).valid());
        }
        assertFalse(CongruenceCoverage.verify(request, new CongruenceCoverage.Certificate(request,
            certificate.terms(), certificate.coveredCount().negate(), certificate.leastIndex()), LIMITS, () -> false).valid());
        assertFalse(CongruenceCoverage.verify(request, new CongruenceCoverage.Certificate(request,
            certificate.terms(), certificate.coveredCount(), Optional.of(integer(-1))), LIMITS, () -> false).valid());
    }

    @Test
    void failsClosedOnWorkTermsIntegerSizeAndCancellation() {
        var request = request(0, 1, 15, rule(2, 0, 0), rule(3, 0, 4));
        var successful = CongruenceCoverage.search(request, LIMITS, () -> false);
        var certificate = successful.certificate().orElseThrow();
        var exact = CongruenceCoverage.search(request, LIMITS.withMaximumWork(successful.work()), () -> false);
        assertEquals(successful.certificate(), exact.certificate());
        assertEquals(successful.work(), exact.work());
        assertUnavailable(CongruenceCoverage.search(request,
            LIMITS.withMaximumWork(successful.work() - 1), () -> false), "WORK_LIMIT");
        assertUnavailable(CongruenceCoverage.search(request,
            new IterationLimits(100_000, 1, 30_000), () -> false), "TERM_LIMIT");
        assertUnavailable(CongruenceCoverage.search(request, LIMITS, () -> true), "CANCELLED");
        var checks = new AtomicInteger();
        assertUnavailable(CongruenceCoverage.search(request, LIMITS, () -> checks.incrementAndGet() > 8), "CANCELLED");
        var oversized = new CongruenceCoverage.Request(new CongruenceCoverage.Progression(
            ONE.shiftLeft(IterationLimits.MAX_RUNTIME_BITS), ONE, ONE), List.of());
        assertUnavailable(CongruenceCoverage.search(oversized, LIMITS, () -> false), "RUNTIME_BIT_LIMIT");
        // Each input fits; the CRT intersection would require an oversized joint modulus.
        var oversizedIntersection = new CongruenceCoverage.Request(new CongruenceCoverage.Progression(
            ZERO, ONE, ONE.shiftLeft(IterationLimits.MAX_RUNTIME_BITS - 1)), List.of(
                new CongruenceCoverage.Rule(ONE.shiftLeft(40_000).add(ONE), ZERO, ZERO),
                new CongruenceCoverage.Rule(ONE.shiftLeft(40_001).add(ONE), ZERO, ZERO)));
        assertUnavailable(CongruenceCoverage.search(oversizedIntersection,
            LIMITS.withMaximumWork(10_000_000), () -> false), "RUNTIME_BIT_LIMIT");
        var oversizedTerms = new ArrayList<>(certificate.terms());
        var firstTerm = oversizedTerms.getFirst();
        oversizedTerms.set(0, new CongruenceCoverage.Term(firstTerm.firstIndex(), firstTerm.period(),
            ONE.shiftLeft(IterationLimits.MAX_RUNTIME_BITS)));
        var oversizedCertificate = new CongruenceCoverage.Certificate(request, oversizedTerms,
            certificate.coveredCount(), certificate.leastIndex());
        assertEquals("RUNTIME_BIT_LIMIT", CongruenceCoverage.verify(request,
            oversizedCertificate, LIMITS, () -> false).reason());
        assertFalse(CongruenceCoverage.verify(request, certificate, LIMITS.withMaximumWork(1), () -> false).valid());
        assertEquals("CANCELLED", CongruenceCoverage.verify(request, certificate, LIMITS, () -> true).reason());
        var verified = CongruenceCoverage.verify(request, certificate, LIMITS, () -> false);
        assertFalse(CongruenceCoverage.verify(request, certificate,
            LIMITS.withMaximumWork(verified.work() - 1), () -> false).valid());
    }

    @Test
    void validatesStructuralInputsAndDefensivelyCopiesCollections() {
        assertThrows(IllegalArgumentException.class, () -> rule(0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> rule(-2, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> request(0, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> request(0, -1, 1));
        assertThrows(IllegalArgumentException.class, () -> request(0, 1, -1));
        var rules = new ArrayList<>(List.of(rule(2, -1, -100)));
        var request = new CongruenceCoverage.Request(new CongruenceCoverage.Progression(ZERO, ONE, integer(10)), rules);
        rules.clear();
        assertEquals(1, request.rules().size());
        var result = CongruenceCoverage.search(request, LIMITS, () -> false);
        assertEquals(integer(5), result.certificate().orElseThrow().coveredCount());
        assertThrows(UnsupportedOperationException.class, () -> request.rules().clear());
        assertThrows(UnsupportedOperationException.class, () -> result.certificate().orElseThrow().terms().clear());
    }

    private static void assertUnavailable(CongruenceCoverage.Result result, String reason) {
        assertEquals(CongruenceCoverage.Status.UNAVAILABLE, result.status());
        assertEquals(reason, result.reason());
        assertTrue(result.certificate().isEmpty());
        assertTrue(result.leastUncovered().isEmpty());
    }

    private static CongruenceCoverage.Request request(long first, long step, long count,
            CongruenceCoverage.Rule... rules) {
        return new CongruenceCoverage.Request(new CongruenceCoverage.Progression(
            integer(first), integer(step), integer(count)), List.of(rules));
    }

    private static CongruenceCoverage.Rule rule(long modulus, long residue, long activeFrom) {
        return new CongruenceCoverage.Rule(integer(modulus), integer(residue), integer(activeFrom));
    }

    private static BigInteger integer(long value) { return BigInteger.valueOf(value); }
}
