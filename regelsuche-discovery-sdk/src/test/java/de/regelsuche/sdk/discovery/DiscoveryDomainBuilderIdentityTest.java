package de.regelsuche.sdk.discovery;

import static org.junit.jupiter.api.Assertions.assertThrows;

import de.regelsuche.discovery.domain.DiscoveryDomain.InvariantResult;
import java.util.List;
import org.junit.jupiter.api.Test;

class DiscoveryDomainBuilderIdentityTest {
    @Test
    void rejectsDuplicateInvariantIdentityBeforeRuntimeAndDescriptorDiverge() {
        var builder = DiscoveryDomainBuilder
            .<Integer, Integer, Integer>domain("duplicate-invariant", "v1")
            .invariant("positive", ignored -> InvariantResult.pass());

        assertThrows(
            IllegalArgumentException.class,
            () -> builder.invariant(
                "positive",
                ignored -> InvariantResult.fail("different-semantics"))
        );
    }

    @Test
    void rejectsDuplicateOperatorIdentityBeforeRuntimeAndDescriptorDiverge() {
        var builder = DiscoveryDomainBuilder
            .<Integer, Integer, Integer>domain("duplicate-operator", "v1")
            .operator("advance", ignored -> List.of());

        assertThrows(
            IllegalArgumentException.class,
            () -> builder.operator("advance", ignored -> List.of())
        );
    }
}
