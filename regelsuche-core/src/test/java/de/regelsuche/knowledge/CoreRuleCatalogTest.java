package de.regelsuche.knowledge;

import de.regelsuche.transform.AstRewriteTransformationEngine;
import de.regelsuche.transform.RewriteRule;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CoreRuleCatalogTest {

    @Test
    void everyBuiltInRuleIsAssignedToExactlyOnePack() {
        Set<String> catalogRuleIds = new LinkedHashSet<>();
        for (CoreRulePack pack : CoreRuleCatalog.packs()) {
            for (String ruleId : pack.ruleIds()) {
                assertTrue(catalogRuleIds.add(ruleId), "duplicate rule id in catalog: " + ruleId);
            }
        }
        List<String> builtInIds = ruleIds(AstRewriteTransformationEngine.allBuiltInRules());
        assertEquals(new LinkedHashSet<>(builtInIds), catalogRuleIds);
        assertEquals(builtInIds.size(), catalogRuleIds.size(), "built-in rule ids must be unique");
        for (String ruleId : builtInIds) {
            assertNotNull(CoreRuleCatalog.packIdForRule(ruleId), ruleId);
        }
    }

    @Test
    void defaultRulesAreUnchangedByTheTierSplit() {
        List<RewriteRule> defaults = AstRewriteTransformationEngine.defaultRules();
        assertEquals(ruleIds(AstRewriteTransformationEngine.allBuiltInRules()), ruleIds(defaults));
        assertEquals(
                ruleIds(defaults),
                ruleIds(AstRewriteTransformationEngine.defaultRules(
                        KnowledgePackSelection.profile(RuleProfile.FULL))));
    }

    @Test
    void minimalKernelProfileKeepsOnlyKernelPacks() {
        List<RewriteRule> kernelRules = AstRewriteTransformationEngine.defaultRules(
                KnowledgePackSelection.profile(RuleProfile.MINIMAL_KERNEL));
        List<String> expected = new ArrayList<>();
        for (CoreRulePack pack : CoreRuleCatalog.packs()) {
            if (pack.tier() == RuleTier.KERNEL) {
                expected.addAll(pack.ruleIds());
            }
        }
        assertEquals(new LinkedHashSet<>(expected), new LinkedHashSet<>(ruleIds(kernelRules)));
        assertFalse(ruleIds(kernelRules).contains("ast_square_difference_factor"));
        assertTrue(ruleIds(kernelRules).contains("ast_canonical_normalize"));
    }

    @Test
    void disablingASingleFirstPartyPackRemovesExactlyItsRules() {
        KnowledgePackSelection selection =
                KnowledgePackSelection.CORE.disablePack(CoreRuleCatalog.FACTORIZATION);
        List<String> remaining = ruleIds(AstRewriteTransformationEngine.defaultRules(selection));
        List<String> full = ruleIds(AstRewriteTransformationEngine.defaultRules());
        List<String> removed = new ArrayList<>(full);
        removed.removeAll(remaining);
        assertEquals(CoreRuleCatalog.pack(CoreRuleCatalog.FACTORIZATION).ruleIds(), removed);
    }

    @Test
    void kernelPacksCannotBeDisabled() {
        KnowledgePackSelection selection =
                KnowledgePackSelection.CORE.disablePack(CoreRuleCatalog.NORMALIZATION);
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> AstRewriteTransformationEngine.defaultRules(selection));
        assertTrue(failure.getMessage().contains(CoreRuleCatalog.NORMALIZATION));
    }

    @Test
    void minimalKernelProfileStillCanReEnableSelectedPacks() {
        KnowledgePackSelection selection =
                KnowledgePackSelection.profile(RuleProfile.MINIMAL_KERNEL)
                        .enablePack(CoreRuleCatalog.DISTRIBUTION);
        List<String> ruleIds = ruleIds(AstRewriteTransformationEngine.defaultRules(selection));
        assertTrue(ruleIds.containsAll(CoreRuleCatalog.pack(CoreRuleCatalog.DISTRIBUTION).ruleIds()));
        assertFalse(ruleIds.contains("ast_square_difference_factor"));
    }

    private static List<String> ruleIds(List<RewriteRule> rules) {
        return rules.stream().map(RewriteRule::id).toList();
    }
}
