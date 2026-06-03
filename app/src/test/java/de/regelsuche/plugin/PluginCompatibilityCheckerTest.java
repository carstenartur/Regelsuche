package de.regelsuche.plugin;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import org.junit.jupiter.api.Test;

class PluginCompatibilityCheckerTest {
    @Test
    void acceptsRulesCapability() {
        assertTrue(PluginCompatibilityChecker.isCompatible(plugin(Set.of("rules"))));
    }

    @Test
    void rejectsUnknownCapability() {
        assertFalse(PluginCompatibilityChecker.isCompatible(plugin(Set.of("unknown-x"))));
    }

    @Test
    void acceptsEmptyCapabilityList() {
        assertTrue(PluginCompatibilityChecker.isCompatible(plugin(Set.of())));
    }

    private RegelsuchePlugin plugin(Set<String> capabilities) {
        return new RegelsuchePlugin() {
            @Override
            public String id() {
                return "test-plugin";
            }

            @Override
            public String name() {
                return "Test Plugin";
            }

            @Override
            public String version() {
                return "1.0.0";
            }

            @Override
            public Set<String> capabilities() {
                return capabilities;
            }
        };
    }
}
