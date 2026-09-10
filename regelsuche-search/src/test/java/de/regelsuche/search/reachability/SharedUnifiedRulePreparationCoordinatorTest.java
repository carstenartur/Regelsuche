package de.regelsuche.search.reachability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.assumption.AssumptionSignature;
import de.regelsuche.ast.BinaryOperator;
import de.regelsuche.knowledge.KnowledgePackRegistry;
import de.regelsuche.transform.AstRewriteTransformationEngine;
import de.regelsuche.transform.PatternExpr;
import de.regelsuche.transform.PatternRewriteRule;
import de.regelsuche.transform.PerfectSquareStructurePreparationSolver;
import de.regelsuche.transform.RewriteApplicabilitySchema;
import de.regelsuche.transform.RewriteKind;
import de.regelsuche.transform.RewriteRule;
import java.util.List;
import org.junit.jupiter.api.Test;

class SharedUnifiedRulePreparationCoordinatorTest {
    private static final String REVISION =
        "0123456789abcdef0123456789abcdef01234567";

    @Test
    void oneFallbackTraversalServesTwoDistinctPrincipalsAndCountsPhysicalWorkOnce() {
        PatternRewriteRule preparation = rule(
            "shared_unified_factor_difference_squares",
            expandedDifferenceOfSquares(),
            factoredDifferenceOfSquares(),
            RewriteKind.FACTOR);
        PatternRewriteRule first = rule(
            "shared_unified_first_principal",
            factoredDifferenceOfSquares(),
            expandedDifferenceOfSquares(),
            RewriteKind.EXPAND);
        PatternRewriteRule second = rule(
            "shared_unified_second_principal",
            factoredDifferenceOfSquares(),
            expandedDifferenceOfSquares(),
            RewriteKind.EXPAND);
        SharedUnifiedRulePreparationCoordinator coordinator =
            new SharedUnifiedRulePreparationCoordinator(
                List.of(
                    RewriteApplicabilitySchema.fromPatternRule(first),
                    RewriteApplicabilitySchema.fromPatternRule(second)),
                List.of(preparation),
                REVISION,
                budget());
        AssumptionSignature assumptions =
            AssumptionSignature.ofExpressions(List.of());

        var firstEvaluation = coordinator.analyze("x^2-y^2", assumptions);
        var secondEvaluation = coordinator.analyze("x^2-y^2", assumptions);
        var firstOutcome = firstEvaluation.outcome(first.id()).orElseThrow();
        var secondOutcome = firstEvaluation.outcome(second.id()).orElseThrow();

        assertEquals(firstEvaluation, secondEvaluation,
            "fresh per-evaluation caches must make replay independent of prior calls");
        assertTrue(coordinator.verify(firstEvaluation).valid());
        assertEquals(SharedUnifiedRulePreparationCoordinator.COORDINATOR_ID,
            firstEvaluation.coordinatorId());
        assertTrue(firstOutcome.prepared());
        assertTrue(secondOutcome.prepared());
        assertEquals(
            List.of(preparation.id(), first.id()),
            firstOutcome.candidate().orElseThrow().primitiveRuleIds());
        assertEquals(
            List.of(preparation.id(), second.id()),
            secondOutcome.candidate().orElseThrow().primitiveRuleIds());
        assertNotEquals(
            firstOutcome.bridgeCertificateHash(),
            secondOutcome.bridgeCertificateHash());

        var shared = firstEvaluation.sharedExecutionWork();
        assertTrue(shared.fallbackExecuted());
        assertEquals(2, shared.fallbackPrincipalIds().size());
        assertEquals(1, shared.fallbackWork().expandedStates());
        assertEquals(1, shared.fallbackWork().generatedTransitions());
        assertEquals(1, shared.fallbackPhysicalWork().uniqueExpansions());
        assertEquals(1,
            shared.fallbackPhysicalWork().generatedTransformations());
        assertTrue(shared.sourceAnalysisWork().analysisCacheHits() > 0,
            "identical source pattern contracts should share physical analysis");
        assertTrue(shared.guardCacheHits() > 0,
            "identical terminal guard semantics should share guard facts");

        assertEquals(1, firstOutcome.work().generatedTransitions());
        assertEquals(1, secondOutcome.work().generatedTransitions());
        assertEquals(1,
            firstEvaluation.aggregateWork().generatedTransitions(),
            "shared fallback work must be counted once, not once per principal");
        assertEquals(1, firstEvaluation.aggregateWork().expandedStates());
    }

    @Test
    void sharedV2RetainsLegacyResultsButHalvesDuplicatedFallbackWork() {
        PatternRewriteRule preparation = rule(
            "matched_work_factor_difference_squares",
            expandedDifferenceOfSquares(),
            factoredDifferenceOfSquares(),
            RewriteKind.FACTOR);
        PatternRewriteRule first = rule(
            "matched_work_first_principal",
            factoredDifferenceOfSquares(),
            expandedDifferenceOfSquares(),
            RewriteKind.EXPAND);
        PatternRewriteRule second = rule(
            "matched_work_second_principal",
            factoredDifferenceOfSquares(),
            expandedDifferenceOfSquares(),
            RewriteKind.EXPAND);
        List<RewriteApplicabilitySchema> schemas = List.of(
            RewriteApplicabilitySchema.fromPatternRule(first),
            RewriteApplicabilitySchema.fromPatternRule(second));
        AssumptionSignature assumptions =
            AssumptionSignature.ofExpressions(List.of());
        UnifiedRulePreparationCoordinator legacy =
            new UnifiedRulePreparationCoordinator(
                schemas,
                List.of(preparation),
                REVISION,
                budget());
        SharedUnifiedRulePreparationCoordinator shared =
            new SharedUnifiedRulePreparationCoordinator(
                schemas,
                List.of(preparation),
                REVISION,
                budget());

        var legacyEvaluation = legacy.analyze("x^2-y^2", assumptions);
        var sharedEvaluation = shared.analyze("x^2-y^2", assumptions);

        assertEquals(
            legacyEvaluation.outcomes().stream()
                .map(outcome -> outcome.candidate().orElseThrow()
                    .transformedExpression())
                .toList(),
            sharedEvaluation.outcomes().stream()
                .map(outcome -> outcome.candidate().orElseThrow()
                    .transformedExpression())
                .toList(),
            "shared execution must not change the mathematical results");
        assertEquals(
            legacyEvaluation.outcomes().stream()
                .map(outcome -> outcome.candidate().orElseThrow()
                    .primitiveRuleIds())
                .toList(),
            sharedEvaluation.outcomes().stream()
                .map(outcome -> outcome.candidate().orElseThrow()
                    .primitiveRuleIds())
                .toList(),
            "shared execution must retain the same primitive proof lineage");
        assertEquals(2,
            legacyEvaluation.aggregateWork().expandedStates());
        assertEquals(2,
            legacyEvaluation.aggregateWork().generatedTransitions());
        assertEquals(1,
            sharedEvaluation.aggregateWork().expandedStates());
        assertEquals(1,
            sharedEvaluation.aggregateWork().generatedTransitions());
        assertEquals(
            legacyEvaluation.aggregateWork().generatedTransitions(),
            Math.multiplyExact(
                2,
                sharedEvaluation.aggregateWork().generatedTransitions()),
            "the matched two-principal case must eliminate the duplicated physical fallback transition");
    }

    @Test
    void nativeExactSpecialistStillWinsBeforeSharedFallback() {
        PatternRewriteRule principal = builtInPattern(
            PerfectSquareStructurePreparationSolver.PRINCIPAL_RULE_ID);
        SharedUnifiedRulePreparationCoordinator coordinator =
            new SharedUnifiedRulePreparationCoordinator(
                List.of(RewriteApplicabilitySchema.fromPatternRule(principal)),
                List.of(),
                REVISION,
                budget());

        var evaluation = coordinator.analyze(
            "4 * x^4 * y^2 - 9 * z^2",
            AssumptionSignature.ofExpressions(List.of()));
        var outcome = evaluation.outcome(principal.id()).orElseThrow();

        assertTrue(outcome.prepared());
        assertEquals("EXACT_REGISTRY_PREPARATION_REPLAYED",
            outcome.detailCode());
        assertTrue(outcome.candidate().orElseThrow().primitiveRuleIds()
            .contains(PerfectSquareStructurePreparationSolver.PREPARATION_RULE_ID));
        assertTrue(evaluation.sharedExecutionWork()
            .fallbackPrincipalIds().isEmpty());
        assertEquals(0,
            evaluation.sharedExecutionWork().fallbackWork()
                .generatedTransitions());
        assertTrue(coordinator.verify(evaluation).valid());
    }

    @Test
    void onePrincipalFallbackRetainsExistingImportedRuleSemantics() {
        PatternRewriteRule principal = new KnowledgePackRegistry()
            .allPacks().stream()
            .filter(pack -> "sympy-trigonometry".equals(pack.packId()))
            .flatMap(pack -> pack.rules().stream())
            .filter(rule -> "sympy.trig.pythagorean".equals(rule.id()))
            .findFirst()
            .orElseThrow();
        List<RewriteRule> cancellationRules =
            AstRewriteTransformationEngine.allBuiltInRules().stream()
                .filter(rule -> "ast_cancel_division_factor".equals(rule.id()))
                .toList();
        SharedUnifiedRulePreparationCoordinator coordinator =
            new SharedUnifiedRulePreparationCoordinator(
                List.of(RewriteApplicabilitySchema.fromPatternRule(principal)),
                cancellationRules,
                REVISION,
                budget());

        var evaluation = coordinator.analyze(
            "((sin(x) * a) / a)^2 + ((cos(x) * b) / b)^2",
            AssumptionSignature.ofExpressions(List.of()));
        var outcome = evaluation.outcome(principal.id()).orElseThrow();

        assertTrue(outcome.prepared());
        assertEquals(
            List.of(
                "ast_cancel_division_factor",
                "ast_cancel_division_factor",
                "sympy.trig.pythagorean"),
            outcome.candidate().orElseThrow().primitiveRuleIds());
        assertEquals(List.of("a != 0", "b != 0"),
            outcome.candidate().orElseThrow().assumptions());
        assertTrue(evaluation.sharedExecutionWork().fallbackExecuted());
        assertTrue(coordinator.verify(evaluation).valid());
    }

    private static PatternRewriteRule builtInPattern(String ruleId) {
        RewriteRule rule = AstRewriteTransformationEngine
            .allBuiltInRules().stream()
            .filter(value -> ruleId.equals(value.id()))
            .findFirst()
            .orElseThrow();
        if (!(rule instanceof PatternRewriteRule pattern)) {
            throw new IllegalStateException(
                "expected declarative built-in principal: " + ruleId);
        }
        return pattern;
    }

    private static PatternRewriteRule rule(
        String id,
        PatternExpr source,
        PatternExpr target,
        RewriteKind kind
    ) {
        return new PatternRewriteRule(
            id,
            source,
            target,
            kind,
            false,
            -1,
            true);
    }

    private static PatternExpr expandedDifferenceOfSquares() {
        return PatternExpr.op(
            BinaryOperator.SUB,
            square("A"),
            square("B"));
    }

    private static PatternExpr factoredDifferenceOfSquares() {
        return PatternExpr.op(
            BinaryOperator.MUL,
            PatternExpr.op(
                BinaryOperator.SUB,
                PatternExpr.var("A"),
                PatternExpr.var("B")),
            PatternExpr.op(
                BinaryOperator.ADD,
                PatternExpr.var("A"),
                PatternExpr.var("B")));
    }

    private static PatternExpr square(String placeholder) {
        return PatternExpr.op(
            BinaryOperator.POW,
            PatternExpr.var(placeholder),
            PatternExpr.num(2));
    }

    private static PatternTargetedLocalBridgeSearch.Budget budget() {
        return new PatternTargetedLocalBridgeSearch.Budget(
            3,
            128,
            1_024,
            8,
            160,
            128,
            32,
            5_000,
            2_500);
    }
}
