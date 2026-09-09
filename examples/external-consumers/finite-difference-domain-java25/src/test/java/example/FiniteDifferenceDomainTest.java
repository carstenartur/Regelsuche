package example;

import static org.junit.jupiter.api.Assertions.*;
import de.regelsuche.sdk.discovery.*;
import org.junit.jupiter.api.Test;

class FiniteDifferenceDomainTest {
    @Test void findsQuadraticAndRetainsRefutedDegrees() {
        var run = RegelsucheDiscovery.forDomain(FiniteDifferenceDomain.domain())
            .campaign("finite-difference-test").seed("zero", "0", "test").run();
        DiscoveryRunAssertions.assertThat(run).isConfirmed().hasCounterexampleCount(2);
        assertEquals(2, run.selectedCandidate().orElseThrow());
    }
    @Test void preservesInvalidSeedAndBudgetOutcomes() {
        var request = RegelsucheDiscovery.forDomain(FiniteDifferenceDomain.domain()).campaign("negative");
        DiscoveryRunAssertions.assertThat(request.seed("invalid", "3", "test").run()).isInvalidSeed();
        DiscoveryRunAssertions.assertThat(request.seed("zero", "0", "test")
            .budget(DiscoveryBudgets.tiny()).run()).isBudgetExhausted();
    }
}
