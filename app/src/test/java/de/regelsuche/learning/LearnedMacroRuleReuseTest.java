package de.regelsuche.learning;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.canonical.ExpressionCanonicalizer;
import de.regelsuche.demo.DemoRuleSet;
import de.regelsuche.inventory.InMemoryRuleInventoryRepository;
import de.regelsuche.inventory.InventoryBackedRewriteRuleProvider;
import de.regelsuche.inventory.ReusableRule;
import de.regelsuche.inventory.RuleInventoryConfiguration;
import de.regelsuche.mining.CandidateProofStatus;
import de.regelsuche.mining.RuleStatus;
import de.regelsuche.scoring.ExpressionScorer;
import de.regelsuche.search.SearchHeuristic;
import de.regelsuche.search.strategy.BestFirstSearchStrategy;
import de.regelsuche.search.strategy.SearchProblem;
import de.regelsuche.search.strategy.SearchState;
import de.regelsuche.transform.AstRewriteTransformationEngine;
import de.regelsuche.transform.RewriteRule;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class LearnedMacroRuleReuseTest {

    @Test
    void learnedMacroRuleIsReusedOnNewExample() {
        // Seed inventory with a pre-learned (a+b)^2 macro rule that has the
        // confidence and occurrence threshold met. The next search on (x+7)^2
        // must be able to consume the inventory-backed rewrite rule.
        InMemoryRuleInventoryRepository inventory = new InMemoryRuleInventoryRepository();
        ReusableRule learned = new ReusableRule(
            "macro_binomial",
            "(a + b) ^ 2",
            "a ^ 2 + 2 * a * b + b ^ 2",
            List.of(),
            CandidateProofStatus.VALIDATED_BY_EXAMPLES,
            RuleStatus.NEW,
            3,
            6.0,
            Instant.now(),
            "hash-binomial",
            null,
            0,
            3,
            List.of("p1", "p2", "p3"),
            0.9
        );
        inventory.save(learned);
        inventory.setEnabled("macro_binomial", true);

        List<RewriteRule> baseRules = DemoRuleSet.rules();
        InventoryBackedRewriteRuleProvider provider =
            new InventoryBackedRewriteRuleProvider(
                inventory, RuleInventoryConfiguration.enabledDefaults(), baseRules);
        List<RewriteRule> activated = provider.activatedRules();
        assertFalse(activated.isEmpty(),
            "inventory provider must activate the learned macro rule");

        List<RewriteRule> all = new ArrayList<>(baseRules);
        all.addAll(activated);

        ExpressionCanonicalizer canonicalizer = new ExpressionCanonicalizer();
        ExpressionScorer scorer = new ExpressionScorer();
        String root = canonicalizer.canonicalize("(x+7)^2");
        SearchProblem problem = new SearchProblem(
            root,
            new AstRewriteTransformationEngine(all),
            scorer,
            canonicalizer,
            new SearchHeuristic(3, 250, 1, 4, 80, 12)
        );
        List<SearchState> states = new BestFirstSearchStrategy().search(problem);
        boolean usedMacro = false;
        for (SearchState s : states) {
            for (String rid : s.appliedRuleIds()) {
                if (rid != null && rid.contains("macro_binomial")) {
                    usedMacro = true;
                }
            }
        }
        assertTrue(usedMacro,
            "search on (x+7)^2 must consume the learned (a+b)^2 macro rule at least once");
        assertNotNull(states);
    }
}
