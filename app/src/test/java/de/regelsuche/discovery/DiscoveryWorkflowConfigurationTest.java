package de.regelsuche.discovery;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class DiscoveryWorkflowConfigurationTest {
    @Test
    void absentMacroLearningServiceDisablesEffectiveLearning() {
        DiscoveryWorkflowConfiguration configuration = DiscoveryWorkflowConfiguration.defaults();

        assertTrue(configuration.options().learning().enableMacroLearning());
        assertFalse(configuration.macroLearningService().isPresent());
        assertFalse(configuration.macroLearningEnabled());
        assertFalse(configuration.effectiveLearningOptions().enableMacroLearning());
        assertFalse(configuration.effectiveOptions().learning().enableMacroLearning());
    }
}
