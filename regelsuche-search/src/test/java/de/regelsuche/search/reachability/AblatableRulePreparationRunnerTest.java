package de.regelsuche.search.reachability;

import de.regelsuche.knowledge.KnowledgePackRegistry;
import de.regelsuche.knowledge.KnowledgePackSelection;
import de.regelsuche.transform.AstRewriteTransformationEngine;
import de.regelsuche.transform.PatternRewriteRule;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AblatableRulePreparationRunnerTest {
    private static final String REVISION = "0123456789abcdef0123456789abcdef01234567";
    private static AblatableRulePreparationRunner runner() {
        return runner(AblatableRulePreparationRunner.Budget.publicControls());
    }
    private static AblatableRulePreparationRunner runner(AblatableRulePreparationRunner.Budget budget) {
        var principals = new KnowledgePackRegistry().enabledRules(KnowledgePackSelection.profile(de.regelsuche.knowledge.RuleProfile.ALL)).stream()
            .filter(rule -> List.of("sympy.trig.pythagorean", "sympy.poly.factor.diff_squares",
                "sympy.rational.partial_fraction.telescoping").contains(rule.id()))
            .map(PatternRewriteRule.class::cast).toList();
        var preparation = AstRewriteTransformationEngine.allBuiltInRules().stream()
            .filter(rule -> rule.id().equals("ast_cancel_division_factor")).toList();
        return new AblatableRulePreparationRunner(principals, preparation, REVISION,
            budget);
    }

    @Test void profilesExecuteDifferentAuthoritiesAndPreserveCheapestSuccess() {
        var runner = runner();
        var source = new AblatableRulePreparationRunner.Source("cos(x)^2 + sin(x)^2", List.of());
        var direct = runner.analyze(AblatableRulePreparationRunner.Profile.DIRECT_ONLY, source);
        var theory = runner.analyze(AblatableRulePreparationRunner.Profile.THEORY_MATCHING, source);
        assertEquals(0, direct.candidates().size());
        assertEquals("THEORY_MATCHING", theory.candidates().getFirst().stage());
        assertEquals("1", theory.candidates().getFirst().output());
        assertNotEquals(direct.configurationHash(), theory.configurationHash());
        var full = runner.analyze(AblatableRulePreparationRunner.Profile.SAFE_PREPARATION_PLUS_LOCAL_BRIDGE, source);
        assertEquals(theory.candidates(), full.candidates());
        assertTrue(runner.verify(theory));
    }

    @Test void exactQuotientAndBridgeAreSeparatelyAblatableAndReplayRetainedSteps() {
        var runner = runner();
        var exactSource = new AblatableRulePreparationRunner.Source(
            "sin((x^2 - 1) / (x - 1))^2 + cos(x + 1)^2", List.of("x - 1 != 0"));
        assertTrue(runner.analyze(AblatableRulePreparationRunner.Profile.THEORY_MATCHING, exactSource).candidates().isEmpty());
        var exact = runner.analyze(AblatableRulePreparationRunner.Profile.SAFE_EXACT_PREPARATION, exactSource);
        assertEquals("SAFE_EXACT_PREPARATION", exact.candidates().getFirst().stage());
        assertEquals(2, exact.candidates().getFirst().primitiveRuleIds().size());
        assertEquals(List.of("prepare_exact_polynomial_factor"), exact.candidates().getFirst().exactPreparationStepIds());
        assertEquals(2, exact.candidates().getFirst().preparationDepth());
        assertTrue(runner.verify(exact));
        var hidden = new AblatableRulePreparationRunner.Source(
            "((sin(x) * a) / a)^2 + cos(x)^2", List.of("a != 0"));
        assertTrue(runner.analyze(AblatableRulePreparationRunner.Profile.SAFE_EXACT_PREPARATION, hidden).candidates().isEmpty());
        var bridge = runner.analyze(AblatableRulePreparationRunner.Profile.SAFE_PREPARATION_PLUS_LOCAL_BRIDGE, hidden);
        assertFalse(bridge.candidates().isEmpty(), bridge.canonicalJson());
        assertEquals("SAFE_PREPARATION_PLUS_LOCAL_BRIDGE", bridge.candidates().getFirst().stage());
        assertEquals(List.of("ast_cancel_division_factor", "sympy.trig.pythagorean"), bridge.candidates().getFirst().primitiveRuleIds());
        assertTrue(bridge.canonicalJson().contains("expressionBefore"));
        assertTrue(bridge.canonicalJson().contains("certificateHash"));
        assertTrue(runner.verify(bridge));
    }

    @Test void unknownFalseAndConflictingGuardsCannotAuthorize() {
        var runner = runner();
        for (var guards : List.of(List.<String>of(), List.of("a = 0"), List.of("a = 0", "a != 0"))) {
            var result = runner.analyze(AblatableRulePreparationRunner.Profile.SAFE_PREPARATION_PLUS_LOCAL_BRIDGE,
                new AblatableRulePreparationRunner.Source("((sin(x) * a) / a)^2 + cos(x)^2", guards));
            assertTrue(result.candidates().isEmpty(), result.canonicalJson());
            assertTrue(result.canonicalJson().contains("GUARD"));
            assertTrue(runner.verify(result));
        }
        assertThrows(IllegalArgumentException.class, () -> new AblatableRulePreparationRunner.Source("matmul(A,B)", List.of()));
    }

    @Test void exactBudgetIsCumulativeAcrossOccurrencesAndIndependentVerification() {
        var source = new AblatableRulePreparationRunner.Source(
            "(t^2-1)/(t-1) + sin((x^3+x^2)/(x+1)-y^2)", List.of("t-1 != 0", "x+1 != 0"));
        var generous = runner().analyze(AblatableRulePreparationRunner.Profile.SAFE_EXACT_PREPARATION, source);
        assertFalse(generous.candidates().isEmpty(), generous.canonicalJson());
        var observed = AmplificationJson.read(generous.canonicalJson());
        var exactStage = ((List<?>) observed.get("stages")).stream().map(value -> (java.util.Map<?, ?>) value)
            .filter(value -> value.get("stage").equals("SAFE_EXACT_PREPARATION")).findFirst().orElseThrow();
        var observations = (List<?>) exactStage.get("observations");
        var first = (java.util.Map<?, ?>) observations.getFirst();
        long firstCallPrefix = ((Number) ((java.util.Map<?, ?>) first.get("authorityAfterVerification")).get("chargedUnits")).longValue();
        assertTrue(firstCallPrefix > 0);
        var defaults = AblatableRulePreparationRunner.Budget.publicControls();
        var limited = runner(new AblatableRulePreparationRunner.Budget(defaults.bridge(), defaults.maxNodes(),
            defaults.maxExactAttempts(), defaults.maxLogicalUnits(), firstCallPrefix));
        var stopped = limited.analyze(AblatableRulePreparationRunner.Profile.SAFE_PREPARATION_PLUS_LOCAL_BRIDGE, source);
        assertTrue(stopped.candidates().isEmpty(), stopped.canonicalJson());
        var prefix = (java.util.Map<?, ?>) AmplificationJson.read(stopped.canonicalJson()).get("exactPreparationWork");
        assertEquals(firstCallPrefix, ((Number) prefix.get("chargedUnits")).longValue());
        assertTrue(stopped.canonicalJson().contains("SHARED_POLYNOMIAL_WORK_AUTHORITY_EXHAUSTED"));
        assertTrue(stopped.canonicalJson().contains("refusedCharge"));
        assertFalse(stopped.canonicalJson().contains("bridgeDispatch"));
        assertTrue(limited.verify(stopped));
    }

    @Test void noBudgetAndFalseNumericGuardCannotAuthorizeOrBeReinterpretedOnReplay() {
        var defaults = AblatableRulePreparationRunner.Budget.publicControls();
        var limited = runner(new AblatableRulePreparationRunner.Budget(defaults.bridge(), defaults.maxNodes(),
            defaults.maxExactAttempts(), 0, defaults.maxExactWorkUnits()));
        var source = new AblatableRulePreparationRunner.Source("sin(x)^2+cos(x)^2", List.of());
        var stopped = limited.analyze(AblatableRulePreparationRunner.Profile.DIRECT_ONLY, source);
        assertTrue(stopped.candidates().isEmpty());
        assertTrue(stopped.canonicalJson().contains("BUDGET_INCONCLUSIVE"));
        assertFalse(runner().verify(stopped));
        var zero = runner().analyze(AblatableRulePreparationRunner.Profile.SAFE_PREPARATION_PLUS_LOCAL_BRIDGE,
            new AblatableRulePreparationRunner.Source("sin(x)^2+cos(x)^2+1/0", List.of("0 != 0")));
        assertTrue(zero.candidates().isEmpty());
        assertTrue(zero.canonicalJson().contains("GUARD_FALSE"));
    }
}
