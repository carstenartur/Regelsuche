package de.regelsuche.inventory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReusableRuleLegacyMigrationTest {

    @Test
    void loadsLegacyJsonWithoutLearningFields(@TempDir Path tempDir) throws Exception {
        // A pre-PR rule-inventory.json that predates occurrenceCount /
        // confidenceScore / supportingPathIds must still load and yield
        // sensible defaults so old persistent stores are not lost.
        String legacyJson = "{\"rules\":[{"
            + "\"id\":\"legacy_1\","
            + "\"leftPattern\":\"x + 0\","
            + "\"rightPattern\":\"x\","
            + "\"parameterRelations\":[],"
            + "\"proofStatus\":\"VALIDATED_BY_EXAMPLES\","
            + "\"knownRuleStatus\":\"NEW\","
            + "\"supportingExamples\":4,"
            + "\"averageImprovement\":2.5,"
            + "\"createdAt\":\"2026-01-01T00:00:00Z\","
            + "\"canonicalHash\":\"abc\","
            + "\"lastUsedAt\":null,"
            + "\"usageCount\":1,"
            + "\"enabled\":true"
            + "}]}";
        Path file = tempDir.resolve("rule-inventory.json");
        Files.writeString(file, legacyJson, StandardCharsets.UTF_8);

        InMemoryRuleInventoryRepository repo = InMemoryRuleInventoryRepository.loadFrom(file);
        assertEquals(1, repo.findAll().size());
        ReusableRule rule = repo.findAll().get(0);
        assertEquals("legacy_1", rule.id());
        assertEquals(0, rule.occurrenceCount(),
            "legacy rule must default to occurrenceCount=0");
        assertEquals(0.0, rule.confidenceScore(),
            "legacy rule must default to confidenceScore=0.0");
        assertTrue(rule.supportingPathIds().isEmpty(),
            "legacy rule must default to empty supportingPathIds");
    }
}
