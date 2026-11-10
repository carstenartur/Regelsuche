package de.regelsuche.inventory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.mining.RuleStatus;
import de.regelsuche.validation.CandidateProofStatus;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class InventoryRuleRevalidationTest {
    @Test
    void storedFormalLabelCannotAuthorizeAFalseIdentity() {
        var provider = provider(rule("false", "A + 0", "A + 1", List.of(), List.of()));

        assertTrue(provider.activatedRules().isEmpty());
        assertTrue(provider.lastDecisions().getFirst().reason().contains("NOT_EQUIVALENT"));
    }

    @Test
    void assumptionsAndParameterRelationsCannotBeDropped() {
        var provider = provider(
            rule("assumed", "A / A", "1", List.of(), List.of("A != 0")),
            rule("related", "A + B", "2 * A", List.of("A = B"), List.of()));

        assertTrue(provider.activatedRules().isEmpty());
        assertEquals(2, provider.lastDecisions().size());
        assertTrue(provider.lastDecisions().stream().allMatch(d ->
            d.reason().contains("condition-aware")));
    }

    @Test
    void unsupportedAndMalformedRulesDoNotPreventLaterVerifiedRules() {
        var provider = provider(
            rule("unsupported", "sin(A)^2 + cos(A)^2", "1", List.of(), List.of()),
            rule("malformed", "A + (", "A", List.of(), List.of()),
            rule("valid", "A + 0", "A", List.of(), List.of()));

        var activated = provider.activatedRules();

        assertEquals(1, activated.size());
        assertEquals("inventory_valid", activated.getFirst().id());
        assertEquals(3, provider.lastDecisions().size());
        assertTrue(provider.lastDecisions().stream().anyMatch(d ->
            d.rule().id().equals("unsupported") && d.reason().contains("UNSUPPORTED")));
        assertTrue(provider.lastDecisions().stream().anyMatch(d ->
            d.rule().id().equals("malformed") && d.reason().contains("Invalid pattern")));
        assertTrue(activated.getFirst().isEquivalencePreservingByConstruction());
    }

    @Test
    void rejectedConditionalDuplicateDoesNotHideAnUnconditionalIdentity() {
        var provider = provider(
            rule("conditional", "A + 0", "A", List.of(), List.of("A != 0")),
            rule("unconditional", "A + 0", "A", List.of(), List.of()));

        assertEquals("inventory_unconditional", provider.activatedRules().getFirst().id());
    }

    @Test
    void unboundTargetPlaceholderIsRejectedWithoutBlockingTheFollowingValidRule() {
        var provider = provider(
            rule("a-unbound", "A + 0", "A + B - B", List.of(), List.of()),
            rule("b-valid", "A + 0", "A", List.of(), List.of()));

        var activated = provider.activatedRules();

        assertEquals(1, activated.size());
        assertEquals("inventory_b-valid", activated.getFirst().id());
        assertEquals(2, provider.lastDecisions().size());
        var rejected = provider.lastDecisions().getFirst();
        assertTrue(!rejected.activated());
        assertTrue(rejected.reason().contains("Unbound target placeholders"));
        assertTrue(rejected.reason().contains("B"));
        var parser = new de.regelsuche.parse.ExpressionParser();
        var before = parser.parseTerm("x + 0");
        assertTrue(activated.getFirst().matches(before));
        assertEquals(parser.parseTerm("x"), activated.getFirst().apply(before));
        assertTrue(!new de.regelsuche.transform.AstRewriteTransformationEngine(activated)
            .transform("x + 0").isEmpty());
    }

    @Test
    void placeholdersMayOccurOnlyOnTheLeftWhenTheIdentityEliminatesThem() {
        var provider = provider(rule("eliminate", "A + B - B", "A", List.of(), List.of()));
        var activated = provider.activatedRules();
        assertEquals(1, activated.size());
        var parser = new de.regelsuche.parse.ExpressionParser();
        var before = parser.parseTerm("x + y - y");
        assertTrue(activated.getFirst().matches(before));
        assertEquals(parser.parseTerm("x"), activated.getFirst().apply(before));
    }

    private static InventoryBackedRewriteRuleProvider provider(ReusableRule... rules) {
        var repository = new InMemoryRuleInventoryRepository();
        for (var rule : rules) repository.save(rule);
        return new InventoryBackedRewriteRuleProvider(
            repository, RuleInventoryConfiguration.enabledDefaults(), List.of());
    }

    private static ReusableRule rule(String id, String left, String right,
                                     List<String> relations, List<String> assumptions) {
        return new ReusableRule(id, left, right, relations,
            CandidateProofStatus.FORMALLY_PROVED, RuleStatus.NEW, 3, 4.0,
            Instant.EPOCH, id, null, 0, 0, List.of(), 1.0, assumptions);
    }
}
