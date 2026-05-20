package de.regelsuche.inventory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.mining.CandidateProofStatus;
import de.regelsuche.mining.RuleStatus;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class InMemoryRuleInventoryRepositoryPersistenceTest {

    @Test
    void roundTripsRulesEnabledFlagAndTags(@TempDir Path tempDir) throws IOException {
        InMemoryRuleInventoryRepository repo = new InMemoryRuleInventoryRepository();
        ReusableRule first = new ReusableRule(
            "rule_a",
            "A + 0",
            "A",
            List.of(),
            CandidateProofStatus.SYMBOLICALLY_VERIFIED,
            RuleStatus.NEW,
            5,
            2.0,
            Instant.parse("2024-01-01T00:00:00Z")
        );
        ReusableRule second = new ReusableRule(
            "rule_b",
            "A * 1",
            "A",
            List.of(),
            CandidateProofStatus.VALIDATED_BY_EXAMPLES,
            RuleStatus.NEW,
            3,
            1.5,
            Instant.parse("2024-01-02T00:00:00Z")
        );
        repo.save(first);
        repo.save(second);
        repo.setEnabled("rule_a", false);
        repo.addTag("rule_a", "polynomial");
        repo.addTag("rule_a", "neutral_element");
        repo.addTag("rule_b", "polynomial");

        Path file = tempDir.resolve("inventory.json");
        repo.persistTo(file);
        assertTrue(Files.exists(file));

        InMemoryRuleInventoryRepository restored = InMemoryRuleInventoryRepository.loadFrom(file);
        List<ReusableRule> rules = restored.findAll();
        assertEquals(2, rules.size());
        assertFalse(restored.isEnabled("rule_a"));
        assertTrue(restored.isEnabled("rule_b"));
        assertEquals(2, restored.tagsOf("rule_a").size());
        assertTrue(restored.tagsOf("rule_a").contains("polynomial"));
        assertTrue(restored.tagsOf("rule_a").contains("neutral_element"));
        assertEquals(List.of("rule_b"), restored.findByTag("polynomial").stream()
            .filter(rule -> rule.id().equals("rule_b"))
            .map(ReusableRule::id)
            .toList());
        assertEquals(CandidateProofStatus.SYMBOLICALLY_VERIFIED, rules.get(0).proofStatus());
        assertEquals(5, rules.get(0).supportingExamples());
    }
}
