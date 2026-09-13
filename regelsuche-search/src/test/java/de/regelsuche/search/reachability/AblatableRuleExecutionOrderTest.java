package de.regelsuche.search.reachability;

import de.regelsuche.knowledge.KnowledgePackRegistry;
import de.regelsuche.knowledge.KnowledgePackSelection;
import de.regelsuche.knowledge.RuleInventoryFingerprint;
import de.regelsuche.knowledge.RuleProfile;
import de.regelsuche.transform.AstRewriteTransformationEngine;
import de.regelsuche.transform.PatternRewriteRule;
import de.regelsuche.transform.RewriteRule;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AblatableRuleExecutionOrderTest {
    private static final String REVISION = "0123456789abcdef0123456789abcdef01234567";

    private static List<PatternRewriteRule> principals() {
        var all = new KnowledgePackRegistry().enabledRules(KnowledgePackSelection.profile(RuleProfile.ALL));
        return List.of("sympy.trig.pythagorean", "sympy.poly.factor.diff_squares", "sympy.rational.partial_fraction.telescoping")
            .stream().map(id -> (PatternRewriteRule) all.stream().filter(rule -> rule.id().equals(id)).findFirst().orElseThrow()).toList();
    }

    @Test void tightBudgetExecutionsMustBindTheActualPrincipalOrder() {
        var rules = principals();
        var defaults = AblatableRulePreparationRunner.Budget.publicControls();
        var budget = new AblatableRulePreparationRunner.Budget(defaults.bridge(), defaults.maxNodes(), defaults.maxExactAttempts(), 25, defaults.maxExactWorkUnits());
        var forward = new AblatableRulePreparationRunner(rules, List.of(), REVISION, budget);
        var reversed = new AblatableRulePreparationRunner(rules.reversed(), List.of(), REVISION, budget);
        var source = new AblatableRulePreparationRunner.Source("sin(x)^2 + cos(x)^2", List.of());
        var profile = AblatableRulePreparationRunner.Profile.DIRECT_ONLY;
        var first = forward.analyze(profile, source);
        var second = reversed.analyze(profile, source);
        assertEquals(RuleInventoryFingerprint.contentHash(rules), RuleInventoryFingerprint.contentHash(rules.reversed()));
        assertNotEquals(AmplificationJson.read(first.canonicalJson()).get("stages"), AmplificationJson.read(second.canonicalJson()).get("stages"),
            "The real bounded execution must retain the supplied order, not silently sort it");
        assertNotEquals(first.configurationHash(), second.configurationHash(), "different budget-sensitive execution orders must not share a configuration identity");
        assertEquals(first, forward.analyze(profile, source));
        assertTrue(forward.verify(first));
        assertFalse(reversed.verify(first));
    }

    @Test void preparationOrderIsSeparateFromItsUnorderedContentInventory() {
        var builtins = AstRewriteTransformationEngine.allBuiltInRules();
        List<RewriteRule> preparation = List.of("ast_cancel_division_factor", "ast_add_zero_right").stream()
            .map(id -> builtins.stream().filter(rule -> rule.id().equals(id)).findFirst().orElseThrow()).toList();
        var forward = new AblatableRulePreparationRunner(principals(), preparation, REVISION, AblatableRulePreparationRunner.Budget.publicControls());
        var reverse = new AblatableRulePreparationRunner(principals(), preparation.reversed(), REVISION, AblatableRulePreparationRunner.Budget.publicControls());
        var profile = AblatableRulePreparationRunner.Profile.SAFE_PREPARATION_PLUS_LOCAL_BRIDGE;
        assertEquals(forward.configuration(profile).get("preparationInventory"), reverse.configuration(profile).get("preparationInventory"));
        assertNotEquals(AmplificationJson.hash(forward.configuration(profile)), AmplificationJson.hash(reverse.configuration(profile)));
        assertEquals(preparation.stream().map(RewriteRule::id).toList(), forward.configuration(profile).get("preparationExecutionOrder"));
        assertEquals(principals().stream().map(RewriteRule::id).toList(), forward.configuration(profile).get("principalExecutionOrder"));
    }
}
