package de.regelsuche.discovery;

import de.regelsuche.knowledge.RuleProfile;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DiscoveryKnowledgePackOptionsTest {
    @Test
    void supportsCustomEnabledAndDisabledPacks() {
        DiscoveryOptions options = DiscoveryOptions.forProfile(DiscoveryProfile.PURE_REWRITE)
                .withRuleProfile(RuleProfile.CORE_PLUS_SYMPY_POLYNOMIAL)
                .disablePack("sympy-polynomial-basic")
                .enablePack("user-local-rules");

        assertEquals(Set.of("user-local-rules"), options.enabledPacks());
        assertEquals(Set.of("sympy-polynomial-basic"), options.disabledPacks());
        assertTrue(options.knowledgePackSelection().effectiveEnabledPacks(Set.of(
                "sympy-polynomial-basic", "user-local-rules")).contains("user-local-rules"));
    }
}
