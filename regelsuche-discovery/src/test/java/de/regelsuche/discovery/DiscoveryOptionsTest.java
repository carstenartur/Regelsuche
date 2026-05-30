package de.regelsuche.discovery;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class DiscoveryOptionsTest {
    @Test
    void engineProfilesDoNotEnableLearningOrPromotion() {
        assertFalse(DiscoveryOptions.forProfile(DiscoveryProfile.HYPOTHESIS_ONLY).learning().enablePromotion());
        assertFalse(DiscoveryOptions.forProfile(DiscoveryProfile.HYPOTHESIS_AND_MACRO_REUSE).learning().enableMacroLearning());
        assertFalse(DiscoveryOptions.forProfile(DiscoveryProfile.HYPOTHESIS_AND_MACRO_REUSE).learning().enablePromotion());
    }

    @Test
    void researchPipelineExplicitlyOwnsLearningAndPromotion() {
        DiscoveryOptions options = DiscoveryOptions.forProfile(DiscoveryProfile.RESEARCH_DISCOVERY_PIPELINE);

        assertTrue(options.engine().enableHypothesisOperators());
        assertTrue(options.engine().enableMacroReuse());
        assertTrue(options.learning().enableMacroLearning());
        assertTrue(options.learning().enablePromotion());
    }
}
