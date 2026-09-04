package de.regelsuche.sdk.discovery;

import static de.regelsuche.sdk.discovery.DiscoveryRunAssertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.discovery.domain.DomainDiscoveryEvidence.Outcome;
import de.regelsuche.discovery.domain.DomainDiscoveryEvidence.Resource;
import org.junit.jupiter.api.Test;

class DiscoveryRunAssertionsTest {
    @Test
    void describesAConfirmedRunWithDomainSpecificChecks() {
        DiscoveryRun<
            DiscoverySdkTest.MultiplierCandidate,
            DiscoverySdkTest.MultiplierCertificate
        > run = confirmedRun();

        DiscoveryRunAssertions.RunAssert<
            DiscoverySdkTest.MultiplierCandidate,
            DiscoverySdkTest.MultiplierCertificate
        > assertion = assertThat(run)
            .isConfirmed()
            .hasOutcome(Outcome.CONFIRMED)
            .hasCounterexampleCount(1)
            .hasCounterexampleContaining("multiplier 1")
            .candidateSatisfies(candidate -> assertEquals(2, candidate.multiplier()))
            .certificateSatisfies(certificate ->
                assertEquals(2, certificate.multiplier()))
            .hasPositiveExecutedWork(Resource.EXPLORED_STATES)
            .hasExecutedWork(
                Resource.EXPLORED_STATES,
                run.executedWork().get(Resource.EXPLORED_STATES)
            )
            .hasContentAddressedEvidence()
            .hasNoTransitionAssumptions();

        assertSame(run, assertion.actual());
    }

    @Test
    void describesBudgetExhaustionWithoutSelectedObjects() {
        DiscoveryRun<
            DiscoverySdkTest.MultiplierCandidate,
            DiscoverySdkTest.MultiplierCertificate
        > run = budgetExhaustedRun();

        assertThat(run)
            .isBudgetExhausted()
            .hasOutcome(Outcome.BUDGET_EXHAUSTED)
            .hasNoCandidate()
            .hasNoCertificate()
            .hasCounterexampleCount(1)
            .hasCounterexampleContaining("multiplier 1")
            .hasContentAddressedEvidence();

        assertThrows(AssertionError.class, () -> assertThat(run).isConfirmed());
        assertThrows(
            AssertionError.class,
            () -> assertThat(run).candidateSatisfies(candidate -> { })
        );
        assertThrows(
            AssertionError.class,
            () -> assertThat(run).certificateSatisfies(certificate -> { })
        );
    }

    @Test
    void reportsEveryOutcomeMismatchWithoutChangingEvidence() {
        DiscoveryRun<
            DiscoverySdkTest.MultiplierCandidate,
            DiscoverySdkTest.MultiplierCertificate
        > run = confirmedRun();
        String hash = run.evidence().contentHash();

        for (Runnable mismatch : new Runnable[] {
            () -> assertThat(run).isRefuted(),
            () -> assertThat(run).isInconclusive(),
            () -> assertThat(run).isUnsupported(),
            () -> assertThat(run).isBudgetExhausted(),
            () -> assertThat(run).isInvalidSeed(),
            () -> assertThat(run).hasNoCandidate(),
            () -> assertThat(run).hasNoCertificate(),
            () -> assertThat(run).hasNoCounterexamples(),
            () -> assertThat(run).hasCounterexampleContaining("missing"),
            () -> assertThat(run).hasExecutedWork(Resource.EXPLORED_STATES, 0)
        }) {
            AssertionError failure = assertThrows(AssertionError.class, mismatch::run);
            assertTrue(failure.getMessage().contains(hash));
        }
    }

    @Test
    void rejectsInvalidAssertionArguments() {
        DiscoveryRun<
            DiscoverySdkTest.MultiplierCandidate,
            DiscoverySdkTest.MultiplierCertificate
        > run = confirmedRun();

        assertThrows(NullPointerException.class, () -> assertThat(null));
        assertThrows(
            NullPointerException.class,
            () -> assertThat(run).hasOutcome(null)
        );
        assertThrows(
            NullPointerException.class,
            () -> assertThat(run).candidateSatisfies(null)
        );
        assertThrows(
            NullPointerException.class,
            () -> assertThat(run).certificateSatisfies(null)
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> assertThat(run).hasCounterexampleCount(-1)
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> assertThat(run).hasCounterexampleContaining(" ")
        );
        assertThrows(
            NullPointerException.class,
            () -> assertThat(run).hasExecutedWork(null, 0)
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> assertThat(run).hasExecutedWork(Resource.EXPLORED_STATES, -1)
        );
        assertThrows(
            NullPointerException.class,
            () -> assertThat(run).hasPositiveExecutedWork(null)
        );
    }

    private static DiscoveryRun<
        DiscoverySdkTest.MultiplierCandidate,
        DiscoverySdkTest.MultiplierCertificate
    > confirmedRun() {
        return RegelsucheDiscovery
            .forDomain(DiscoverySdkTest.sampleDomain())
            .campaign("sdk-assertions-confirmed")
            .seed(
                "sdk-assertions-confirmed",
                "observed=2,4,8,16;holdout=32,64",
                "sdk-assertions-test"
            )
            .budget(DiscoveryBudgets.of(6, 16, 32, 8, 8, 16))
            .run();
    }

    private static DiscoveryRun<
        DiscoverySdkTest.MultiplierCandidate,
        DiscoverySdkTest.MultiplierCertificate
    > budgetExhaustedRun() {
        return RegelsucheDiscovery
            .forDomain(DiscoverySdkTest.sampleDomain())
            .campaign("sdk-assertions-budget")
            .seed(
                "sdk-assertions-budget",
                "observed=2,4,8,16;holdout=32,64",
                "sdk-assertions-test"
            )
            .budget(DiscoveryBudgets.tiny())
            .run();
    }
}
