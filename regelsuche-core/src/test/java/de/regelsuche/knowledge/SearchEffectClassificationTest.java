package de.regelsuche.knowledge;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
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

    @Test
    void missingSearchEffectsAreRejectedForExternalRules() throws Exception {
        Path temp = Files.createTempFile("pack", ".rules.yaml");
        Files.writeString(
                temp,
                """
                packId: test-pack
                displayName: Test Pack
                sourceProject: Test
                license: MIT
                sourceUrl: https://example.org
                sourceVersion: v1
                sourceReference: ref
                enabledByDefault: true
                rules:
                  - id: test_rule
                    derivationType: GENERATED
                    status: REVIEWED
                    rule:
                      from: a+b
                      to: b+a
                """);

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> new KnowledgePackLoader().load(temp));
        assertTrue(exception.getMessage().contains("must declare at least one SearchEffect"));
    }
}
