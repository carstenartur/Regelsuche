package de.regelsuche.sdk.discovery;

import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Optional;
import org.junit.jupiter.api.Test;

class DiscoveryRunConsistencyTest {
    @Test
    void rejectsSelectedObjectsForNonConfirmedEvidence() {
        var budgetRun = RegelsucheDiscovery
            .forDomain(DiscoverySdkTest.sampleDomain())
            .campaign("sdk-run-consistency-budget")
            .seed(
                "powers-of-two-budget-consistency",
                "observed=2,4,8,16;holdout=32,64",
                "sdk-test"
            )
            .budget(DiscoveryBudgets.tiny())
            .run();

        assertThrows(
            IllegalArgumentException.class,
            () -> new DiscoveryRun<>(
                Optional.of(new Object()),
                Optional.empty(),
                budgetRun.evidence()
            )
        );
    }

    @Test
    void rejectsMissingObjectsForConfirmedEvidence() {
        var confirmedRun = RegelsucheDiscovery
            .forDomain(DiscoverySdkTest.sampleDomain())
            .campaign("sdk-run-consistency-confirmed")
            .seed(
                "powers-of-two-confirmed-consistency",
                "observed=2,4,8,16;holdout=32,64",
                "sdk-test"
            )
            .budget(DiscoveryBudgets.of(6, 16, 32, 8, 8, 16))
            .run();

        assertThrows(
            IllegalArgumentException.class,
            () -> new DiscoveryRun<>(
                Optional.empty(),
                Optional.empty(),
                confirmedRun.evidence()
            )
        );
    }
}
