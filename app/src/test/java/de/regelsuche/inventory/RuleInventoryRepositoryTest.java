package de.regelsuche.inventory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.input.InputRequest;
import de.regelsuche.input.InputType;
import de.regelsuche.validation.CandidateProofStatus;
import de.regelsuche.mining.RuleStatus;
import de.regelsuche.parse.ExpressionFormatter;
import de.regelsuche.parse.ExpressionParser;
import de.regelsuche.transform.RewriteRule;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class RuleInventoryRepositoryTest {
    @Test
    void storesReusableRuleInInventory() {
        InMemoryRuleInventoryRepository repository = new InMemoryRuleInventoryRepository();
        ReusableRule rule = reusableRule("add_zero", CandidateProofStatus.VALIDATED_BY_EXAMPLES);

        repository.save(rule);

        assertEquals(List.of(rule), repository.findAll());
        assertTrue(repository.findById("add_zero").isPresent());
    }

    @Test
    void inventoryRuleCanBeLoadedAsRewriteRule() {
        InMemoryRuleInventoryRepository repository = new InMemoryRuleInventoryRepository();
        repository.save(reusableRule("add_zero", CandidateProofStatus.VALIDATED_BY_EXAMPLES));

        List<RewriteRule> rules = new InventoryBackedRewriteRuleProvider(
            repository,
            RuleInventoryConfiguration.enabledDefaults(),
            List.of()
        ).activatedRules();

        assertEquals(1, rules.size());
        var expression = new ExpressionParser().parse(new InputRequest(InputType.TERM, "x + 0")).terms().getFirst();
        assertTrue(rules.getFirst().matches(expression));
        assertEquals("x", ExpressionFormatter.format(rules.getFirst().apply(expression)));
    }

    @Test
    void doesNotActivateObservedOnlyRule() {
        InMemoryRuleInventoryRepository repository = new InMemoryRuleInventoryRepository();
        repository.save(reusableRule("observed", CandidateProofStatus.OBSERVED));

        List<RewriteRule> rules = new InventoryBackedRewriteRuleProvider(
            repository,
            RuleInventoryConfiguration.enabledDefaults(),
            List.of()
        ).activatedRules();

        assertTrue(rules.isEmpty());
    }

    @Test
    void disabledInventoryDoesNotActivateRules() {
        InMemoryRuleInventoryRepository repository = new InMemoryRuleInventoryRepository();
        repository.save(reusableRule("add_zero", CandidateProofStatus.VALIDATED_BY_EXAMPLES));

        List<RewriteRule> rules = new InventoryBackedRewriteRuleProvider(
            repository,
            RuleInventoryConfiguration.disabled(),
            List.of()
        ).activatedRules();

        assertFalse(RuleInventoryConfiguration.disabled().enabled());
        assertTrue(rules.isEmpty());
    }

    private ReusableRule reusableRule(String id, CandidateProofStatus proofStatus) {
        return new ReusableRule(
            id,
            "A + 0",
            "A",
            List.of(),
            proofStatus,
            RuleStatus.NEW,
            3,
            4.0,
            Instant.EPOCH
        );
    }
}
