package de.regelsuche.evolution;

import static org.junit.jupiter.api.Assertions.*;
import de.regelsuche.inventory.PrimitiveCapabilityLandmarks;
import de.regelsuche.inventory.RuleActivityMemory;
import de.regelsuche.parse.ExpressionFormatter;
import de.regelsuche.parse.ExpressionParser;
import de.regelsuche.search.moves.*;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CapabilityLandmarkSearchTest {
    private static String format(String source) { return ExpressionFormatter.format(new ExpressionParser().parseTerm(source)); }
    @Test void longerIntermediateUnlocksAnExecutableFactorizerAndDebtIsBounded() {
        var template = TraceStrategyTransferExample.inventory();
        var bridge = template.rewrites().stream().filter(gene -> gene.geneId().equals("add-zero")).findFirst().orElseThrow()
            .withPatterns("?A^2", "(?A^2-1)+1");
        var factor = template.rewrites().stream().filter(gene -> gene.geneId().equals("difference-product")).findFirst().orElseThrow()
            .withPatterns("?A^2-1", "(?A+1)*(?A-1)");
        var inventory = template.withRewrites(List.of(bridge, factor));
        var rules = new EvolutionGenomeCompiler().compile(inventory).rules();
        var providers = PrimitiveMoveProviders.complete(rules, inventory.contentHash());
        var factorRule = rules.stream().filter(rule -> rule.id().endsWith("_difference-product")).findFirst().orElseThrow();
        var landmarks = new PrimitiveCapabilityLandmarks(List.of(new PrimitiveCapabilityLandmarks.Registration(
            "difference-of-squares-factorizer", factorRule.id(), factorRule, 20)), providers);
        var context = MoveContext.frozen(format("(x+1)*(x-1)+1"));
        var root = MoveState.root(format("x^2"));
        var value = landmarks.evaluate(root, context); assertTrue(value.capabilities().isEmpty());
        var bridgeState = MoveState.root(format("(x^2-1)+1")); var unlocked = landmarks.evaluate(bridgeState, context);
        assertTrue(unlocked.complexity() > value.complexity());
        assertTrue(unlocked.complexity() - unlocked.value() < value.complexity() - value.value());
        assertEquals(factorRule.id(), unlocked.capabilities().get("difference-of-squares-factorizer").providerId());
        assertEquals(bridgeState.expression(), unlocked.capabilities().get("difference-of-squares-factorizer").sourceExpression());
        assertTrue(unlocked.primitiveWork() > 0, "executed applicability witnesses are charged");
        var verifier = new PrimitiveReplayMoveVerifier(inventory);
        var result = new MoveSearch().search(new MoveSearch.Problem(root.expression(), context, providers, MovePriorityPolicy.INVENTORY_ORDER,
            verifier, state -> new de.regelsuche.scoring.ExpressionScorer().score(state.expression()).weightedTotal(), MoveSearch.Mode.FAST,
            MoveSearch.Scheduling.STAGED, new MoveSearch.Budget(2, 2, 0, 100, 20000, 6), landmarks));
        assertTrue(result.reached(), result.outcome().name()); assertEquals(2, result.witness().size());
        var first = result.witness().getFirst(); assertEquals(4, first.target().complexityDebt());
        assertTrue(first.move().capabilityDelta().contains("difference-of-squares-factorizer"));
        assertEquals(2, result.witness().stream().mapToInt(step -> step.verification().receipts().size()).sum());
        var blocked = new MoveSearch().search(new MoveSearch.Problem(root.expression(), context, providers, MovePriorityPolicy.INVENTORY_ORDER,
            verifier, state -> 0, MoveSearch.Mode.COMPLETE_BOUNDED_REFERENCE, MoveSearch.Scheduling.STAGED,
            new MoveSearch.Budget(2, 2, 0, 100, 20000, 0), landmarks));
        assertFalse(blocked.reached()); assertTrue(blocked.events().stream().anyMatch(event -> event.decision() == MoveSearch.Decision.COMPLEXITY_BOUND));
        var memory = new RuleActivityMemory(providers.stream().map(provider -> provider.descriptor().id()).toList());
        memory.observe(result, MoveContext.Phase.TRAIN, Map.of());
        assertEquals(1, memory.freeze().rules().get(first.move().ruleId()).capabilityUnlocks());
        assertThrows(IllegalArgumentException.class, () -> new PrimitiveCapabilityLandmarks(List.of(
            new PrimitiveCapabilityLandmarks.Registration("imaginary", "missing-solver", factorRule, 100)), providers));
    }
}
