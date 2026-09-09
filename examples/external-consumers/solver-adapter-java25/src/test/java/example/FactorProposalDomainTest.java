package example;

import de.regelsuche.sdk.discovery.*;
import org.junit.jupiter.api.Test;

class FactorProposalDomainTest {
    @Test void verifiesAConcreteFactor() {
        DiscoveryRunAssertions.assertThat(RegelsucheDiscovery.forDomain(FactorProposalDomain.domain())
            .campaign("factor-positive").seed("proposal", "15:5", "test").run())
            .isConfirmed().certificateSatisfies(certificate -> org.junit.jupiter.api.Assertions.assertEquals("15=5*3", certificate));
    }
    @Test void noCounterexampleDoesNotAuthorizeWrongSolverOutput() {
        DiscoveryRunAssertions.assertThat(RegelsucheDiscovery.forDomain(FactorProposalDomain.domain())
            .campaign("factor-negative").seed("proposal", "15:4", "test").run()).isRefuted();
    }
}
