package de.regelsuche.mining;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.canonical.ExpressionCanonicalizer;
import de.regelsuche.inventory.InMemoryRuleInventoryRepository;
import de.regelsuche.scoring.ExpressionScorer;
import de.regelsuche.search.SearchHeuristic;
import de.regelsuche.search.strategy.BestFirstSearchStrategy;
import de.regelsuche.search.strategy.SearchProblem;
import de.regelsuche.transform.AstRewriteTransformationEngine;
import org.junit.jupiter.api.Test;

class ReciprocalMacroExecutionTest {
    @Test
    void actuallyMinedReciprocalRuleExecutesOnTheQuotientWithoutAnExpertFallback() {
        var inventory = new InMemoryRuleInventoryRepository();
        var learned = DiscoveryDemos.promoteRationalSimplification(inventory);
        assertFalse(learned.touchedRules().isEmpty());
        assertTrue(learned.touchedRules().stream()
            .noneMatch(rule -> rule.id().equals("rational_cancel_common_factor")),
            "This control must exercise the mined rule, never the demo's expert fallback");
        var engine = new MacroMoveTransformationEngine(new AstRewriteTransformationEngine(),
            new GoalAwareMacroMoveSelector(inventory));
        var problem = new SearchProblem("(x*y)/(x*z)", engine, new ExpressionScorer(),
            new ExpressionCanonicalizer(), new SearchHeuristic(1, 80, 1, 4, 80, 20));
        assertTrue(new BestFirstSearchStrategy().search(problem).stream()
            .anyMatch(state -> state.expression().equals("y / z") && state.depth() == 1));
    }
    @Test
    void originalInverseSpellingAndRepeatedBindingChecksRemainAvailable() {
        var inventory = new InMemoryRuleInventoryRepository();
        DiscoveryDemos.promoteRationalSimplification(inventory);
        var engine = new MacroMoveTransformationEngine(new AstRewriteTransformationEngine(),
            new GoalAwareMacroMoveSelector(inventory));
        var parser = new de.regelsuche.parse.ExpressionParser();
        assertTrue(engine.transform("(a*b)^-1*a*x").stream()
            .filter(move -> move.rule().startsWith("macro_"))
            .anyMatch(move -> parser.parseTerm(move.transformedExpression()).equals(parser.parseTerm("b^-1*x"))));
        assertTrue(engine.transform("(a*x)/(a*b)").stream()
            .filter(move -> move.rule().startsWith("macro_"))
            .anyMatch(move -> parser.parseTerm(move.transformedExpression()).equals(parser.parseTerm("x/b"))));
        assertTrue(engine.transform("(a*x)/(c*b)").stream()
            .noneMatch(move -> move.rule().startsWith("macro_")), "unrelated factors must not match");
    }

    @Test
    void quotientViewRetainsItsNonzeroDomainInTheMoveAndExpansion() {
        var inventory = new InMemoryRuleInventoryRepository();
        DiscoveryDemos.promoteRationalSimplification(inventory);
        var engine = new MacroMoveTransformationEngine(new AstRewriteTransformationEngine(),
            new GoalAwareMacroMoveSelector(inventory));
        String source = "(a*x)/(a*b)";
        var move = engine.transform(source).stream().filter(item -> item.rule().startsWith("macro_"))
            .findFirst().orElseThrow();
        assertTrue(move.assumptions().contains("a * b != 0"), move.assumptions().toString());
        var expansion = engine.expansionFor(source, move.transformedExpression(), move.rule()).orElseThrow();
        assertTrue(expansion.assumptions().containsAll(move.assumptions()));
    }

    @Test
    void quotientViewNeverCancelsAnExplicitZeroFactor() {
        var inventory = new InMemoryRuleInventoryRepository();
        DiscoveryDemos.promoteRationalSimplification(inventory);
        var engine = new MacroMoveTransformationEngine(new AstRewriteTransformationEngine(),
            new GoalAwareMacroMoveSelector(inventory));
        assertTrue(engine.transform("(0*x)/(0*b)").stream()
            .noneMatch(move -> move.rule().startsWith("macro_")));
    }

}
