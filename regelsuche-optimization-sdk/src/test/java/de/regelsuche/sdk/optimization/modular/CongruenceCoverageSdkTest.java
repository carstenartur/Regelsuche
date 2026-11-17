package de.regelsuche.sdk.optimization.modular;

import de.regelsuche.math.algorithms.modular.CongruenceCoverage;
import de.regelsuche.sdk.optimization.CancellationToken;
import de.regelsuche.sdk.optimization.OptimizationBudget;
import java.math.BigInteger;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CongruenceCoverageSdkTest {
    private static final OptimizationBudget BUDGET = new OptimizationBudget(100_000, 256, 1, 10_000);

    @Test
    void exposesAnExactSchedulingWitnessAndSourceBoundVerification() {
        var request = schedule(20);
        var result = CongruenceCoverageSdk.search(request, BUDGET, CancellationToken.NONE);
        var certificate = result.certificate().orElseThrow();
        assertEquals(CongruenceCoverage.Status.FOUND_UNCOVERED, result.status());
        assertEquals(Optional.of(BigInteger.valueOf(8)), result.leastUncovered());
        assertEquals(new BigInteger("999999999999"), certificate.coveredCount());
        assertTrue(CongruenceCoverageSdk.verify(request, certificate, BUDGET, CancellationToken.NONE).valid());
        assertFalse(CongruenceCoverageSdk.verify(schedule(8), certificate, BUDGET, CancellationToken.NONE).valid());
        var covered = CongruenceCoverageSdk.search(schedule(8), BUDGET, CancellationToken.NONE);
        assertEquals(CongruenceCoverage.Status.COMPLETE_COVER, covered.status());
        assertTrue(covered.leastUncovered().isEmpty());
        assertTrue(CongruenceCoverageSdk.verify(schedule(8), covered.certificate().orElseThrow(),
            BUDGET, CancellationToken.NONE).valid());
    }

    @Test
    void forwardsWorkAndStateLimitsWithoutReturningPartialCertificates() {
        for (var budget : List.of(new OptimizationBudget(1, 256, 1, 10_000),
                new OptimizationBudget(100_000, 1, 1, 10_000))) {
            var result = CongruenceCoverageSdk.search(schedule(20), budget, CancellationToken.NONE);
            assertEquals(CongruenceCoverage.Status.UNAVAILABLE, result.status());
            assertTrue(result.certificate().isEmpty());
            assertTrue(result.leastUncovered().isEmpty());
        }
    }

    @Test
    void forwardsCancellationToSearchAndReplay() {
        var request = schedule(20);
        var certificate = CongruenceCoverageSdk.search(request, BUDGET, CancellationToken.NONE)
            .certificate().orElseThrow();
        var cancelled = CongruenceCoverageSdk.search(request, BUDGET, () -> true);
        assertEquals(CongruenceCoverage.Status.UNAVAILABLE, cancelled.status());
        assertTrue(cancelled.certificate().isEmpty());
        assertFalse(CongruenceCoverageSdk.verify(request, certificate, BUDGET, () -> true).valid());
    }

    private static CongruenceCoverage.Request schedule(long secondActivation) {
        return new CongruenceCoverage.Request(
            new CongruenceCoverage.Progression(BigInteger.valueOf(2), BigInteger.valueOf(6),
                new BigInteger("1000000000000")),
            List.of(rule(12, 2, 2), rule(12, 8, secondActivation), rule(4, 1, 0)));
    }

    private static CongruenceCoverage.Rule rule(long modulus, long residue, long activeFrom) {
        return new CongruenceCoverage.Rule(BigInteger.valueOf(modulus), BigInteger.valueOf(residue),
            BigInteger.valueOf(activeFrom));
    }
}
