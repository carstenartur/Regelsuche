package de.regelsuche.search.reachability;

import static org.junit.jupiter.api.Assertions.*;

import de.regelsuche.assumption.AssumptionSignature;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.VariableExpr;
import de.regelsuche.knowledge.KnowledgePackRegistry;
import de.regelsuche.transform.*;
import java.util.List;
import org.junit.jupiter.api.Test;

class PreparationProfileTest {
    private static final String REVISION = "a".repeat(40);
    private static final AssumptionSignature NONE = AssumptionSignature.ofExpressions(List.of());
    private static final PatternTargetedLocalBridgeSearch.Budget BUDGET =
        new PatternTargetedLocalBridgeSearch.Budget(3, 128, 1024, 8, 160, 128, 32, 5000, 2500);

    @Test void stopsBeforeDisabledStagesAndPreservesTheConcreteGuard() {
        var trig = imported("sympy.trig.pythagorean");
        var coordinator = coordinator(trig);
        var exact = coordinator.analyzeProfile(PreparationProfile.DIRECT_ONLY, "cos(x)^2 + sin(x)^2", NONE).getFirst();
        assertEquals("NO_MATCH_IN_ENABLED_STAGES", exact.status());
        assertEquals(1, exact.stages().size());
        assertEquals(0, exact.stages().getFirst().executorReplays());
        var theory = coordinator.analyzeProfile(PreparationProfile.THEORY_MATCHING, "cos(x)^2 + sin(x)^2", NONE).getFirst();
        assertEquals("THEORY_MATCHING", theory.selectedStage());
        assertEquals(2, theory.stages().size());
        assertEquals("1", theory.candidate().orElseThrow().transformedExpression());
        var guarded = coordinator(imported("sympy.rational.partial_fraction.telescoping"))
            .analyzeProfile(PreparationProfile.SAFE_PREPARATION_PLUS_LOCAL_BRIDGE, "1/(n*(n+1))", NONE).getFirst();
        assertEquals("UNSUPPORTED", guarded.status());
        assertTrue(guarded.candidate().isEmpty());
        assertEquals(1, guarded.stages().size());
    }

    @Test void onlyInvokesARegisteredSpecialistAndOnlyInvokesBridgeAsLastResort() {
        String hidden = "((sin(x)*a)/a)^2 + cos(x)^2";
        var coordinator = coordinator(imported("sympy.trig.pythagorean"));
        var exact = coordinator.analyzeProfile(PreparationProfile.SAFE_EXACT_PREPARATION, hidden, NONE).getFirst();
        assertTrue(exact.candidate().isEmpty());
        assertEquals("NO_REGISTERED_SPECIALIST", exact.stages().getLast().detail());
        assertEquals(0, exact.stages().getLast().specialistDispatches());
        var local = coordinator.analyzeProfile(PreparationProfile.SAFE_PREPARATION_PLUS_LOCAL_BRIDGE, hidden, NONE).getFirst();
        assertEquals("LOCAL_BRIDGE", local.selectedStage());
        assertEquals(List.of("a != 0"), local.candidate().orElseThrow().assumptions());
        assertTrue(local.stages().getLast().bridgeWork().generatedTransitions() > 0);
        var nativeRule = AstRewriteTransformationEngine.allBuiltInRules().stream()
            .filter(rule -> rule.id().equals(PerfectSquareStructurePreparationSolver.PRINCIPAL_RULE_ID))
            .map(PatternRewriteRule.class::cast).findFirst().orElseThrow();
        var nativeResult = coordinator(nativeRule).analyzeProfile(PreparationProfile.SAFE_PREPARATION_PLUS_LOCAL_BRIDGE,
            "4*x^4*y^2-9*z^2", NONE).getFirst();
        assertEquals("EXACT_SPECIALIST", nativeResult.selectedStage());
        assertEquals(1, nativeResult.stages().getLast().specialistDispatches());
        assertTrue(nativeResult.stages().getLast().specialistResults() >= 1);
        assertFalse(nativeResult.certificateHash().isEmpty());
    }

    @Test void aSchemaMatchCannotBorrowAConcreteReplayAtAnotherOccurrence() {
        var pattern = PatternExpr.var("ANY");
        var executor = new PatternRewriteRule("match-only-x", pattern, PatternExpr.num(1)) {
            @Override public boolean matches(Expr expression) {
                return expression instanceof VariableExpr variable && variable.name().equals("x");
            }
        };
        var result = coordinator(executor).analyzeProfile(PreparationProfile.DIRECT_ONLY, "x + y", NONE).getFirst();
        assertTrue(result.candidate().isEmpty());
        assertEquals("EXECUTOR_REJECTED_SOURCE", result.detail());
        assertEquals(1, result.stages().getFirst().executorReplays());
    }

    @Test void matcherExhaustionRemainsInconclusiveWithNoHigherStageOrReplay() {
        var schema = RewriteApplicabilitySchema.fromPatternRule(imported("sympy.trig.pythagorean"));
        var tiny = new PatternTargetedLocalBridgeSearch.Budget(3, 128, 1024, 8, 160, 128, 1, 1, 1);
        var coordinator = new UnifiedRulePreparationCoordinator(List.of(schema), List.of(), REVISION, tiny);
        var result = coordinator.analyzeProfile(PreparationProfile.SAFE_PREPARATION_PLUS_LOCAL_BRIDGE,
            "cos(x)^2 + sin(x)^2", NONE).getFirst();
        assertEquals("BUDGET_INCONCLUSIVE", result.status());
        assertEquals(1, result.stages().size());
        assertFalse(result.stages().getFirst().reachedLimits().isEmpty());
        assertEquals(0, result.stages().getFirst().executorReplays());
    }

    private static PatternRewriteRule imported(String id) {
        return new KnowledgePackRegistry().allPacks().stream().flatMap(pack -> pack.rules().stream())
            .filter(rule -> rule.id().equals(id)).findFirst().orElseThrow();
    }

    private static UnifiedRulePreparationCoordinator coordinator(PatternRewriteRule principal) {
        var cancellation = AstRewriteTransformationEngine.allBuiltInRules().stream()
            .filter(rule -> rule.id().equals("ast_cancel_division_factor")).toList();
        return new UnifiedRulePreparationCoordinator(List.of(RewriteApplicabilitySchema.fromPatternRule(principal)),
            cancellation, REVISION, BUDGET);
    }
}
