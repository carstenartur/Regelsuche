package de.regelsuche.search.reachability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.assumption.AssumptionSignature;
import de.regelsuche.ast.BinaryOperator;
import de.regelsuche.transform.PatternExpr;
import de.regelsuche.transform.PatternRewriteRule;
import de.regelsuche.transform.RecognitionProfile;
import de.regelsuche.transform.RequiredAssumptionTemplate;
import de.regelsuche.transform.RewriteApplicabilitySchema;
import de.regelsuche.transform.RewriteKind;
import java.util.List;
import org.junit.jupiter.api.Test;

class SharedPreparedPrincipalReplayTest {
    private static final String REVISION =
        "0123456789abcdef0123456789abcdef01234567";

    @Test
    void replaysEachPrincipalSeparatelyAfterOneSharedPreparationPath() {
        PatternRewriteRule preparation = rule(
            "shared_replay_factor_difference_squares",
            expandedDifferenceOfSquares(),
            factoredDifferenceOfSquares(),
            RewriteKind.FACTOR);
        PatternRewriteRule first = rule(
            "shared_replay_first_principal",
            factoredDifferenceOfSquares(),
            expandedDifferenceOfSquares(),
            RewriteKind.EXPAND);
        PatternRewriteRule second = rule(
            "shared_replay_second_principal",
            factoredDifferenceOfSquares(),
            expandedDifferenceOfSquares(),
            RewriteKind.EXPAND);
        List<RewriteApplicabilitySchema> schemas = List.of(
            RewriteApplicabilitySchema.fromPatternRule(first),
            RewriteApplicabilitySchema.fromPatternRule(second));
        var assumptions = AssumptionSignature.ofExpressions(List.of());
        var shared = new SharedMultiPrincipalPreparationTraversal(
            schemas,
            List.of(preparation),
            budget()).analyze("x^2-y^2", assumptions);
        SharedPreparationGuardFacts guards = new SharedPreparationGuardFacts();
        SharedPreparedPrincipalReplay replay = new SharedPreparedPrincipalReplay(
            List.of(preparation), REVISION, budget(), guards);

        RulePreparationCoordinator.Outcome firstOutcome = replay.replay(
            schemas.get(0),
            shared,
            shared.outcome(first.id()).orElseThrow());
        RulePreparationCoordinator.Outcome secondOutcome = replay.replay(
            schemas.get(1),
            shared,
            shared.outcome(second.id()).orElseThrow());

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
            secondOutcome.bridgeCertificateHash(),
            "principal identity must remain part of the shared certificate");
        assertEquals(
            "UNIFIED_SHARED_PREPARATION_REPLAYED",
            firstOutcome.detailCode());
        assertEquals(2, guards.work().requests());
        assertEquals(1, guards.work().uniqueFacts());
        assertEquals(1, guards.work().cacheHits(),
            "identical guard semantics should be physically evaluated once");
        assertEquals(
            shared.work().generatedTransitions(),
            firstOutcome.work().generatedTransitions());
        assertEquals(firstOutcome.work(), secondOutcome.work(),
            "per-principal outcomes reference the same shared physical ledger");
    }

    @Test
    void terminalGuardRemainsFailClosedBeforeConcretePrincipalReplay() {
        PatternRewriteRule preparation = rule(
            "guarded_replay_factor_difference_squares",
            expandedDifferenceOfSquares(),
            factoredDifferenceOfSquares(),
            RewriteKind.FACTOR);
        PatternRewriteRule principal = rule(
            "guarded_replay_principal",
            factoredDifferenceOfSquares(),
            expandedDifferenceOfSquares(),
            RewriteKind.EXPAND);
        RewriteApplicabilitySchema schema = new RewriteApplicabilitySchema(
            "guarded-replay-principal/v1",
            principal,
            factoredDifferenceOfSquares(),
            RecognitionProfile.exact(),
            List.of(RequiredAssumptionTemplate.nonZero(PatternExpr.var("A"))));

        RulePreparationCoordinator.Outcome missing = execute(
            schema,
            preparation,
            AssumptionSignature.ofExpressions(List.of()));
        assertEquals(
            PatternTargetedLocalBridgeSearch.Status.UNSUPPORTED,
            missing.status());
        assertEquals("REQUIRED_ASSUMPTION_UNKNOWN", missing.detailCode());

        RulePreparationCoordinator.Outcome known = execute(
            schema,
            preparation,
            AssumptionSignature.ofExpressions(List.of("x != 0")));
        assertTrue(known.prepared());
        assertEquals(
            List.of("x != 0"),
            known.candidate().orElseThrow().assumptions());
    }

    private static RulePreparationCoordinator.Outcome execute(
        RewriteApplicabilitySchema schema,
        PatternRewriteRule preparation,
        AssumptionSignature assumptions
    ) {
        var shared = new SharedMultiPrincipalPreparationTraversal(
            List.of(schema),
            List.of(preparation),
            budget()).analyze("x^2-y^2", assumptions);
        SharedPreparedPrincipalReplay replay = new SharedPreparedPrincipalReplay(
            List.of(preparation),
            REVISION,
            budget(),
            new SharedPreparationGuardFacts());
        return replay.replay(
            schema,
            shared,
            shared.outcome(schema.ruleId()).orElseThrow());
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
