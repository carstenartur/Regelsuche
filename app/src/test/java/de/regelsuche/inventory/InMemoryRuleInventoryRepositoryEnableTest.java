package de.regelsuche.inventory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.mining.CandidateProofStatus;
import de.regelsuche.mining.RuleStatus;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class InMemoryRuleInventoryRepositoryEnableTest {

    @Test
    void enabledFlagAndTagsAreTracked() {
        InMemoryRuleInventoryRepository repository = new InMemoryRuleInventoryRepository();
        ReusableRule rule = new ReusableRule(
            "r1",
            "x + 0",
            "x",
            List.of(),
            CandidateProofStatus.VALIDATED_BY_EXAMPLES,
            RuleStatus.NEW,
            3,
            1.0,
            Instant.EPOCH,
            "hash-r1",
            null,
            0
        );
        repository.save(rule);
        assertTrue(repository.isEnabled("r1"));

        repository.setEnabled("r1", false);
        assertFalse(repository.isEnabled("r1"));
        assertTrue(repository.findEnabled().isEmpty());

        repository.setEnabled("r1", true);
        assertEquals(1, repository.findEnabled().size());

        repository.addTag("r1", "polynomial");
        repository.addTag("r1", "neutral");
        assertTrue(repository.tagsOf("r1").contains("polynomial"));
        assertTrue(repository.tagsOf("r1").contains("neutral"));
        assertEquals(1, repository.findByTag("polynomial").size());

        repository.removeTag("r1", "neutral");
        assertFalse(repository.tagsOf("r1").contains("neutral"));
    }
}
