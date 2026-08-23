package de.regelsuche.transform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.ast.Expr;
import de.regelsuche.canonical.ExpressionCanonicalizer;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class SafePreparationEngineRegistryTest {
    private final ExpressionCanonicalizer canonicalizer =
        new ExpressionCanonicalizer();

    @Test
    void freezesEveryExistingExactPreparationStageInExecutionOrder() {
        assertEquals(
            List.of(
                "direct-ast-rewrite",
                "exact-polynomial-quotient",
                "ac-factor-exposure",
                "common-monomial-factor",
                "perfect-square-exposure",
                "common-denominator"),
            SafePreparationEngineRegistry.stages().stream()
                .map(SafePreparationEngineRegistry.Stage::stageId)
                .toList());
        assertEquals(
            List.of(
                RulePreparationPlanner.PRINCIPAL_RULE_ID,
                AcNormalizationPreparationSolver.PRINCIPAL_RULE_ID,
                MonomialCommonFactorPreparationSolver.PRINCIPAL_RULE_ID,
                PerfectSquareStructurePreparationSolver.PRINCIPAL_RULE_ID,
                RationalCommonDenominatorPreparationSolver.PRINCIPAL_RULE_ID),
            SafePreparationEngineRegistry.stages().stream()
                .filter(stage -> stage.kind()
                    == SafePreparationEngineRegistry.StageKind
                        .EXACT_PREPARATION)
                .map(SafePreparationEngineRegistry.Stage::principalRuleId)
                .toList());
    }

    @Test
    void delegatesToTheCompleteCertificateCarryingEngineChain() {
        SafePreparationEngineRegistry.Registration registration =
            SafePreparationEngineRegistry.production(
                AstRewriteTransformationEngine.defaultRules());

        SafePreparationEngineRegistry.Execution execution =
            registration.transform("(b * (a * c)) / a");
        Transformation candidate = execution.preparedTransformations().stream()
            .filter(value -> value.primitiveRuleIds().contains(
                AcNormalizationPreparationSolver.PREPARATION_RULE_ID))
            .findFirst()
            .orElseThrow();

        assertEquals(
            canonicalizer.stableHash("b * c"),
            canonicalizer.stableHash(candidate.transformedExpression()));
        assertEquals(List.of("a != 0"), candidate.assumptions());
        assertEquals(
            SafePreparationEngineRegistry.REGISTRY_ID,
            execution.registryId());
        assertTrue(registration.registryFingerprint()
            .matches("sha256:[0-9a-f]{64}"));
        assertTrue(registration.supportsPrincipal(
            AcNormalizationPreparationSolver.PRINCIPAL_RULE_ID));
        assertFalse(registration.supportsPrincipal(
            "sympy.poly.factor.diff_squares"));
    }

    @Test
    void registryIdentityBindsVisibleRuleOrderAndContent() {
        List<RewriteRule> original =
            AstRewriteTransformationEngine.defaultRules();
        List<RewriteRule> reversed = new ArrayList<>(original);
        java.util.Collections.reverse(reversed);

        SafePreparationEngineRegistry.Registration first =
            SafePreparationEngineRegistry.production(original);
        SafePreparationEngineRegistry.Registration second =
            SafePreparationEngineRegistry.production(reversed);

        assertEquals(
            first.ruleInventoryFingerprint(),
            second.ruleInventoryFingerprint());
        assertNotEquals(
            first.registryFingerprint(),
            second.registryFingerprint());
    }

    @Test
    void rejectsUnsafeVisibleRulesBeforeConstructingTheEngineChain() {
        RewriteRule unsafe = new RewriteRule() {
            @Override
            public String id() {
                return "unsafe-test-rule";
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
                return false;
            }

            @Override
            public boolean matches(Expr subtree) {
                return true;
            }

            @Override
            public Expr apply(Expr subtree) {
                return subtree;
            }
        };

        assertThrows(IllegalArgumentException.class,
            () -> SafePreparationEngineRegistry.production(List.of(unsafe)));
    }
}
