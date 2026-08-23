package de.regelsuche.search.reachability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.assumption.AssumptionSignature;
import de.regelsuche.ast.Expr;
import de.regelsuche.canonical.ExpressionCanonicalizer;
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

class UnifiedRulePreparationCoordinatorTest {
    private static final String REVISION =
        "0123456789abcdef0123456789abcdef01234567";
    private static final String OTHER_REVISION =
        "89abcdef0123456789abcdef0123456789abcdef";
    private final ExpressionCanonicalizer canonicalizer =
        new ExpressionCanonicalizer();

    @Test
    void choosesCertifiedExactPreparationForItsNativePrincipal() {
        PatternRewriteRule principal = builtInPattern(
            PerfectSquareStructurePreparationSolver.PRINCIPAL_RULE_ID);
        UnifiedRulePreparationCoordinator coordinator = coordinator(
            principal,
            List.of(),
            REVISION);

        UnifiedRulePreparationCoordinator.Evaluation evaluation =
            coordinator.analyze(
                "4 * x^4 * y^2 - 9 * z^2",
                AssumptionSignature.ofExpressions(List.of()));
        RulePreparationCoordinator.Outcome outcome = evaluation.outcome(
            principal.id()).orElseThrow();

        assertTrue(outcome.prepared());
        assertEquals(
            "EXACT_REGISTRY_PREPARATION_REPLAYED",
            outcome.detailCode());
        assertTrue(outcome.candidate().orElseThrow().primitiveRuleIds()
            .contains(
                PerfectSquareStructurePreparationSolver.PREPARATION_RULE_ID));
        assertEquals(
            canonicalizer.stableHash(
                "(2 * x^2 * y - 3 * z) * (2 * x^2 * y + 3 * z)"),
            canonicalizer.stableHash(outcome.candidate().orElseThrow()
                .transformedExpression()));
        assertTrue(coordinator.verify(evaluation).valid());
        assertTrue(evaluation.aggregateWork().generatedTransitions() > 0);
    }

    @Test
    void importedEquivalentPatternDoesNotClaimNativeExactSupport() {
        PatternRewriteRule principal = imported(
            "sympy-polynomial",
            "sympy.poly.factor.diff_squares");
        UnifiedRulePreparationCoordinator coordinator = coordinator(
            principal,
            List.of(),
            REVISION);

        RulePreparationCoordinator.Outcome outcome = coordinator.analyze(
                "4 * x^4 * y^2 - 9 * z^2",
                AssumptionSignature.ofExpressions(List.of()))
            .outcome(principal.id())
            .orElseThrow();

        assertFalse(outcome.prepared());
        assertNotEquals(
            "EXACT_REGISTRY_PREPARATION_REPLAYED",
            outcome.detailCode());
    }

    @Test
    void retainsThePatternTargetedFallbackWhenNoExactSolverApplies() {
        PatternRewriteRule principal = imported(
            "sympy-trigonometry",
            "sympy.trig.pythagorean");
        UnifiedRulePreparationCoordinator coordinator = coordinator(
            principal,
            cancellationRules(),
            REVISION);

        UnifiedRulePreparationCoordinator.Evaluation evaluation =
            coordinator.analyze(
                "((sin(x) * a) / a)^2 + ((cos(x) * b) / b)^2",
                AssumptionSignature.ofExpressions(List.of()));
        RulePreparationCoordinator.Outcome outcome = evaluation.outcome(
            principal.id()).orElseThrow();

        assertTrue(outcome.prepared());
        assertNotEquals(
            "EXACT_REGISTRY_PREPARATION_REPLAYED",
            outcome.detailCode());
        assertEquals(
            List.of(
                "ast_cancel_division_factor",
                "ast_cancel_division_factor",
                "sympy.trig.pythagorean"),
            outcome.candidate().orElseThrow().primitiveRuleIds());
        assertEquals(List.of("a != 0", "b != 0"),
            outcome.candidate().orElseThrow().assumptions());
        assertTrue(coordinator.verify(evaluation).valid());
    }

    @Test
    void exactCertificatesBindTheRepositoryRevision() {
        PatternRewriteRule principal = builtInPattern(
            PerfectSquareStructurePreparationSolver.PRINCIPAL_RULE_ID);
        UnifiedRulePreparationCoordinator first = coordinator(
            principal,
            List.of(),
            REVISION);
        UnifiedRulePreparationCoordinator second = coordinator(
            principal,
            List.of(),
            OTHER_REVISION);
        String source = "4 * x^4 * y^2 - 9 * z^2";

        String firstCertificate = first.analyze(
                source,
                AssumptionSignature.ofExpressions(List.of()))
            .outcome(principal.id()).orElseThrow()
            .bridgeCertificateHash();
        String secondCertificate = second.analyze(
                source,
                AssumptionSignature.ofExpressions(List.of()))
            .outcome(principal.id()).orElseThrow()
            .bridgeCertificateHash();

        assertNotEquals(firstCertificate, secondCertificate);
    }

    @Test
    void executorExceptionsBecomeRetainedTechnicalFailures() {
        RewriteRule throwing = new RewriteRule() {
            @Override
            public String id() {
                return "throwing-principal";
            }

            @Override
            public RewriteKind kind() {
                return RewriteKind.SIMPLIFY;
            }

            @Override
            public boolean mayIncreaseComplexity() {
                return false;
            }

            @Override
            public int estimatedCostDelta() {
                return 0;
            }

            @Override
            public boolean isEquivalencePreservingByConstruction() {
                return true;
            }

            @Override
            public boolean matches(Expr subtree) {
                throw new IllegalStateException("test failure");
            }

            @Override
            public Expr apply(Expr subtree) {
                return subtree;
            }
        };
        RewriteApplicabilitySchema schema =
            new RewriteApplicabilitySchema(
                "throwing-principal/v1",
                throwing,
                PatternExpr.var("A"),
                null);
        UnifiedRulePreparationCoordinator coordinator =
            new UnifiedRulePreparationCoordinator(
                List.of(schema),
                List.of(),
                REVISION,
                budget());

        RulePreparationCoordinator.Outcome outcome = coordinator.analyze(
                "x",
                AssumptionSignature.ofExpressions(List.of()))
            .outcome(throwing.id())
            .orElseThrow();

        assertEquals(
            PatternTargetedLocalBridgeSearch.Status.TECHNICAL_FAILURE,
            outcome.status());
        assertEquals(
            "UNIFIED_DIRECT_REPLAY_TECHNICAL_FAILURE",
            outcome.detailCode());
        assertFalse(outcome.candidate().isPresent());
        assertTrue(coordinator.verify(coordinator.analyze(
            "x",
            AssumptionSignature.ofExpressions(List.of()))).valid());
    }

    @Test
    void bindsExactRegistryIdentityIntoTheCoordinatorConfiguration() {
        PatternRewriteRule principal = builtInPattern(
            PerfectSquareStructurePreparationSolver.PRINCIPAL_RULE_ID);
        UnifiedRulePreparationCoordinator coordinator = coordinator(
            principal,
            List.of(),
            REVISION);

        assertTrue(coordinator.exactRegistryFingerprint()
            .matches("sha256:[0-9a-f]{64}"));
        assertTrue(coordinator.preparationInventoryFingerprint()
            .matches("sha256:[0-9a-f]{64}"));
    }

    private static UnifiedRulePreparationCoordinator coordinator(
        PatternRewriteRule principal,
        List<? extends RewriteRule> preparationRules,
        String revision
    ) {
        return new UnifiedRulePreparationCoordinator(
            List.of(RewriteApplicabilitySchema.fromPatternRule(principal)),
            preparationRules,
            revision,
            budget());
    }

    private static PatternTargetedLocalBridgeSearch.Budget budget() {
        return new PatternTargetedLocalBridgeSearch.Budget(
            3, 128, 1_024, 8, 160, 128,
            32, 5_000, 2_500);
    }

    private static PatternRewriteRule imported(
        String packId,
        String ruleId
    ) {
        return new KnowledgePackRegistry().allPacks().stream()
            .filter(pack -> packId.equals(pack.packId()))
            .flatMap(pack -> pack.rules().stream())
            .filter(rule -> ruleId.equals(rule.id()))
            .findFirst()
            .orElseThrow();
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

    private static List<RewriteRule> cancellationRules() {
        return AstRewriteTransformationEngine.allBuiltInRules().stream()
            .filter(rule -> "ast_cancel_division_factor"
                .equals(rule.id()))
            .toList();
    }
}
