package de.regelsuche.docs;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class DiscoveryOperatorRegistryTest {
    @Test
    void listsDefaultOperatorsAndSupportsEnableDisableById() {
        DiscoveryOperatorRegistry registry = new DiscoveryOperatorRegistry()
                .register(new DefaultDiscoveryOperatorProvider());

        assertTrue(registry.availableOperatorIds().contains("complete_square_bridge"));
        assertTrue(registry.availableOperatorIds().contains("sophie_germain_bridge"));
        assertTrue(registry.availableOperatorIds().contains("telescoping_fraction"));
        assertTrue(registry.availableOperatorIds().contains("repeated_subexpression_factorization"));
        assertTrue(registry.availableOperatorIds().contains("rational_normalization"));

        assertFalse(registry.operatorsFor(new DiscoveryOperatorRegistry.OperatorProfile(List.of("complete_square_bridge"))).isEmpty());
        registry.disable("complete_square_bridge");
        assertTrue(registry.operatorsFor(new DiscoveryOperatorRegistry.OperatorProfile(List.of("complete_square_bridge"))).isEmpty());
        registry.enable("complete_square_bridge");
        assertFalse(registry.operatorsFor(new DiscoveryOperatorRegistry.OperatorProfile(List.of("complete_square_bridge"))).isEmpty());
    }

    @Test
    void rejectsNullProviderAndNullProviderOperatorList() {
        DiscoveryOperatorRegistry registry = new DiscoveryOperatorRegistry();
        assertThrows(IllegalArgumentException.class, () -> registry.register(null));
        assertThrows(IllegalArgumentException.class, () -> registry.register(new DiscoveryOperatorProvider() {
            @Override
            public String id() {
                return "broken";
            }

            @Override
            public List<DiscoveryOperatorDefinition> operators() {
                return null;
            }
        }));
    }

    @Test
    void benchmarkExecutorRejectsNullConstructorDependencies() {
        assertThrows(IllegalArgumentException.class, () -> new DiscoveryBenchmarkExecutor(null, new DiscoveryOperatorRegistry()));
        assertThrows(IllegalArgumentException.class, () -> new DiscoveryBenchmarkExecutor(new DiscoveryBenchmarkScenarioLoader(), null));
    }
}
