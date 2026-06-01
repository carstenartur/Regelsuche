package de.regelsuche.knowledge;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SearchEffectClassificationTest {
    @Test
    void everyExternalRuleDeclaresSearchEffect() {
        KnowledgePackRegistry registry = new KnowledgePackRegistry();

        registry.allPacks().stream()
                .flatMap(pack -> pack.rules().stream())
                .filter(rule -> rule.descriptor().external())
                .forEach(rule -> assertFalse(rule.descriptor().searchEffects().isEmpty(),
                        () -> rule.id() + " missing SearchEffect"));
    }

    @Test
    void discoveryCandidatesAreBackedByBridgeEffects() {
        KnowledgePackRegistry registry = new KnowledgePackRegistry();

        assertTrue(registry.allPacks().stream()
                .flatMap(pack -> pack.rules().stream())
                .filter(rule -> rule.descriptor().status() == RuleStatus.DISCOVERY_CANDIDATE)
                .allMatch(rule -> rule.descriptor().searchEffects().contains(SearchEffect.BRIDGING)));
    }
}
