package de.regelsuche.docs;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.transform.CompleteSquareBridgeOperator;
import de.regelsuche.transform.CommonSubexpressionDiscoveryOperator;
import de.regelsuche.transform.FactorCandidateOperator;
import de.regelsuche.transform.RationalNormalizationHypothesisOperator;
import de.regelsuche.transform.RationalDiscoveryToolkitOperator;
import de.regelsuche.transform.TrigPythagoreanIdentityOperator;
import de.regelsuche.transform.SubstitutionExpansionOperator;
import de.regelsuche.transform.LogProductAssumptionOperator;
import de.regelsuche.transform.RepeatedSubexpressionFactorizationHypothesisOperator;
import java.util.List;
import org.junit.jupiter.api.Test;

class OperatorDisablementTest {
    @Test
    void disablingRequiredOperatorMakesScenarioFail() {
        DiscoveryBenchmarkScenario scenario = new DiscoveryBenchmarkScenarioLoader()
                .load("discovery-scenarios/complete-square.yaml");
        DiscoveryOperatorRegistry registry = new DiscoveryOperatorRegistry()
                .register(new DefaultDiscoveryOperatorProvider());
        registry.disable("complete_square_bridge");

        DiscoveryBenchmarkEvidence evidence =
                new DiscoveryBenchmarkExecutor(new DiscoveryBenchmarkScenarioLoader(), registry).execute(scenario);

        assertFalse(evidence.success(), evidence.failureReason());
        assertFalse(evidence.withoutMacroRun().appliedRuleIds().contains(CompleteSquareBridgeOperator.RULE_ID));
    }

    @Test
    void enablingRequiredOperatorMakesScenarioSucceed() {
        DiscoveryBenchmarkScenario scenario = new DiscoveryBenchmarkScenarioLoader()
                .load("discovery-scenarios/complete-square.yaml");
        DiscoveryOperatorRegistry registry = new DiscoveryOperatorRegistry()
                .register(new DefaultDiscoveryOperatorProvider());
        registry.disable("complete_square_bridge");
        registry.enable("complete_square_bridge");

        DiscoveryBenchmarkEvidence evidence =
                new DiscoveryBenchmarkExecutor(new DiscoveryBenchmarkScenarioLoader(), registry).execute(scenario);

        assertTrue(evidence.success(), evidence.failureReason());
        assertTrue(evidence.withoutMacroRun().appliedRuleIds().contains(CompleteSquareBridgeOperator.RULE_ID));
    }

    @Test
    void disablingRepeatedSubexpressionFactorizationMakesScenarioFail() {
        DiscoveryBenchmarkScenario scenario = new DiscoveryBenchmarkScenarioLoader()
                .load("discovery-scenarios/repeated-subexpression-factorization.yaml");
        DiscoveryOperatorRegistry registry = new DiscoveryOperatorRegistry()
                .register(new DefaultDiscoveryOperatorProvider());
        registry.disable("repeated_subexpression_factorization");

        DiscoveryBenchmarkEvidence evidence =
                new DiscoveryBenchmarkExecutor(new DiscoveryBenchmarkScenarioLoader(), registry).execute(scenario);

        assertFalse(evidence.success(), evidence.failureReason());
        assertFalse(evidence.withoutMacroRun().appliedRuleIds()
                .contains(RepeatedSubexpressionFactorizationHypothesisOperator.RULE_ID));
    }

    @Test
    void enablingRepeatedSubexpressionFactorizationMakesScenarioSucceed() {
        DiscoveryBenchmarkScenario scenario = new DiscoveryBenchmarkScenarioLoader()
                .load("discovery-scenarios/repeated-subexpression-factorization.yaml");
        DiscoveryOperatorRegistry registry = new DiscoveryOperatorRegistry()
                .register(new DefaultDiscoveryOperatorProvider());

        DiscoveryBenchmarkEvidence evidence =
                new DiscoveryBenchmarkExecutor(new DiscoveryBenchmarkScenarioLoader(), registry).execute(scenario);

        assertTrue(evidence.success(), evidence.failureReason());
        assertTrue(evidence.withoutMacroRun().appliedRuleIds()
                .contains(RepeatedSubexpressionFactorizationHypothesisOperator.RULE_ID));
    }

    @Test
    void disablingRationalNormalizationMakesScenarioFail() {
        DiscoveryBenchmarkScenario scenario = new DiscoveryBenchmarkScenarioLoader()
                .load("discovery-scenarios/rational-normalization.yaml");
        DiscoveryOperatorRegistry registry = new DiscoveryOperatorRegistry()
                .register(new DefaultDiscoveryOperatorProvider());
        registry.disable("rational_normalization");

        DiscoveryBenchmarkEvidence evidence =
                new DiscoveryBenchmarkExecutor(new DiscoveryBenchmarkScenarioLoader(), registry).execute(scenario);

        assertFalse(evidence.success(), evidence.failureReason());
        assertFalse(evidence.withoutMacroRun().appliedRuleIds()
                .contains(RationalNormalizationHypothesisOperator.RULE_ID));
    }

    @Test
    void enablingRationalNormalizationMakesScenarioSucceed() {
        DiscoveryBenchmarkScenario scenario = new DiscoveryBenchmarkScenarioLoader()
                .load("discovery-scenarios/rational-normalization.yaml");
        DiscoveryOperatorRegistry registry = new DiscoveryOperatorRegistry()
                .register(new DefaultDiscoveryOperatorProvider());

        DiscoveryBenchmarkEvidence evidence =
                new DiscoveryBenchmarkExecutor(new DiscoveryBenchmarkScenarioLoader(), registry).execute(scenario);

        assertTrue(evidence.success(), evidence.failureReason());
        assertTrue(evidence.withoutMacroRun().appliedRuleIds()
                .contains(RationalNormalizationHypothesisOperator.RULE_ID));
    }

    @Test
    void disablingFactorCandidateMakesScenarioFailAndEnablingMakesItSucceed() {
        DiscoveryBenchmarkScenario scenario = syntheticScenario(
            "factor-candidate",
            "2*x^2 + 4*x",
            "2 * (x^2 + 2*x)",
            "factor_candidate",
            FactorCandidateOperator.RULE_ID);
        DiscoveryOperatorRegistry registry = new DiscoveryOperatorRegistry()
            .register(new DefaultDiscoveryOperatorProvider());
        registry.disable("factor_candidate");

        DiscoveryBenchmarkEvidence disabled =
            new DiscoveryBenchmarkExecutor(new DiscoveryBenchmarkScenarioLoader(), registry).execute(scenario);
        assertFalse(disabled.success(), disabled.failureReason());

        registry.enable("factor_candidate");
        DiscoveryBenchmarkEvidence enabled =
            new DiscoveryBenchmarkExecutor(new DiscoveryBenchmarkScenarioLoader(), registry).execute(scenario);
        assertTrue(enabled.success(), enabled.failureReason());
        assertTrue(enabled.withoutMacroRun().appliedRuleIds().contains(FactorCandidateOperator.RULE_ID));
        assertTrue(enabled.edges().stream()
            .anyMatch(edge -> edge.ruleId().equals(FactorCandidateOperator.RULE_ID)
                && edge.source().equals("sympy-derived")));
    }

    @Test
    void disablingRationalDiscoveryToolkitMakesScenarioFailAndEnablingMakesItSucceed() {
        DiscoveryBenchmarkScenario scenario = syntheticScenario(
            "rational-discovery-toolkit",
            "1 / (n * (n + 1))",
            "1 / n - 1 / (n + 1)",
            "rational_discovery_toolkit",
            RationalDiscoveryToolkitOperator.RULE_ID);
        DiscoveryOperatorRegistry registry = new DiscoveryOperatorRegistry()
            .register(new DefaultDiscoveryOperatorProvider());
        registry.disable("rational_discovery_toolkit");

        DiscoveryBenchmarkEvidence disabled =
            new DiscoveryBenchmarkExecutor(new DiscoveryBenchmarkScenarioLoader(), registry).execute(scenario);
        assertFalse(disabled.success(), disabled.failureReason());

        registry.enable("rational_discovery_toolkit");
        DiscoveryBenchmarkEvidence enabled =
            new DiscoveryBenchmarkExecutor(new DiscoveryBenchmarkScenarioLoader(), registry).execute(scenario);
        assertTrue(enabled.success(), enabled.failureReason());
        assertTrue(enabled.withoutMacroRun().appliedRuleIds().contains(RationalDiscoveryToolkitOperator.RULE_ID));
    }

    @Test
    void commonSubexpressionDiscoveryProducesSympyDerivedShortcutEdge() {
        DiscoveryBenchmarkScenario scenario = syntheticScenario(
            "common-subexpression",
            "x * (y + 1) + z * (y + 1)",
            "(y + 1) * (x + z)",
            "common_subexpression_discovery",
            CommonSubexpressionDiscoveryOperator.RULE_ID);
        DiscoveryOperatorRegistry registry = new DiscoveryOperatorRegistry()
            .register(new DefaultDiscoveryOperatorProvider());

        DiscoveryBenchmarkEvidence evidence =
            new DiscoveryBenchmarkExecutor(new DiscoveryBenchmarkScenarioLoader(), registry).execute(scenario);

        assertTrue(evidence.success(), evidence.failureReason());
        assertTrue(evidence.edges().stream()
            .anyMatch(edge -> edge.ruleId().equals(CommonSubexpressionDiscoveryOperator.RULE_ID)
                && edge.source().equals("sympy-derived")));
    }


    @Test
    void disablingTrigPythagoreanIdentityRestoresCampaignBlocker() {
        DiscoveryBenchmarkScenario scenario = syntheticScenario(
            "trig-pythagorean",
            "sin(x)^2 + cos(x)^2",
            "1",
            "trig_pythagorean_identity",
            TrigPythagoreanIdentityOperator.RULE_ID);
        DiscoveryOperatorRegistry registry = new DiscoveryOperatorRegistry()
            .register(new DefaultDiscoveryOperatorProvider());

        DiscoveryBenchmarkEvidence enabled =
            new DiscoveryBenchmarkExecutor(new DiscoveryBenchmarkScenarioLoader(), registry).execute(scenario);
        assertTrue(enabled.success(), enabled.failureReason());

        registry.disable("trig_pythagorean_identity");
        DiscoveryBenchmarkEvidence disabled =
            new DiscoveryBenchmarkExecutor(new DiscoveryBenchmarkScenarioLoader(), registry).execute(scenario);
        assertFalse(disabled.success(), disabled.failureReason());
    }

    @Test
    void disablingLogProductAssumptionRestoresCampaignBlocker() {
        DiscoveryBenchmarkScenario scenario = syntheticScenario(
            "log-product-assumptions",
            "log(a * b)",
            "log(a) + log(b)",
            "log_product_assumption",
            LogProductAssumptionOperator.RULE_ID);
        DiscoveryOperatorRegistry registry = new DiscoveryOperatorRegistry()
            .register(new DefaultDiscoveryOperatorProvider());

        DiscoveryBenchmarkEvidence enabled =
            new DiscoveryBenchmarkExecutor(new DiscoveryBenchmarkScenarioLoader(), registry).execute(scenario);
        assertTrue(enabled.success(), enabled.failureReason());

        registry.disable("log_product_assumption");
        DiscoveryBenchmarkEvidence disabled =
            new DiscoveryBenchmarkExecutor(new DiscoveryBenchmarkScenarioLoader(), registry).execute(scenario);
        assertFalse(disabled.success(), disabled.failureReason());
    }

    @Test
    void disablingSubstitutionIntroductionBreaksHiddenStructureCase() {
        DiscoveryBenchmarkScenario scenario = new DiscoveryBenchmarkScenario(
            "substitution-hidden-structure",
            "substitution-hidden-structure",
            "(a+b)^2 + 6*(a+b) + 5",
            "((a + b) + 3) ^ 2 - 4",
            List.of(),
            List.of("substitution_introduction", "complete_square_bridge", "substitution_expansion"),
            List.of("sympy-polynomial-basic", "core"),
            List.of(),
            List.of(),
            List.of(CompleteSquareBridgeOperator.RULE_ID),
            new DiscoveryBenchmarkScenario.MacroLearning(false, null, null),
            new DiscoveryBenchmarkScenario.Budgets(8, 240, 5000),
            new DiscoveryBenchmarkScenario.Gallery(false, 1, 1)
        );
        DiscoveryOperatorRegistry registry = new DiscoveryOperatorRegistry()
            .register(new DefaultDiscoveryOperatorProvider());

        DiscoveryBenchmarkEvidence enabled =
            new DiscoveryBenchmarkExecutor(new DiscoveryBenchmarkScenarioLoader(), registry).execute(scenario);
        assertTrue(enabled.success(), enabled.failureReason());
        assertTrue(enabled.withoutMacroRun().appliedRuleIds().contains(CompleteSquareBridgeOperator.RULE_ID));

        registry.disable("substitution_introduction");
        DiscoveryBenchmarkEvidence disabled =
            new DiscoveryBenchmarkExecutor(new DiscoveryBenchmarkScenarioLoader(), registry).execute(scenario);
        assertTrue(
            !disabled.success() || !disabled.withoutMacroRun().appliedRuleIds().contains(SubstitutionExpansionOperator.RULE_ID),
            disabled.failureReason()
        );
    }

    private DiscoveryBenchmarkScenario syntheticScenario(
        String id, String input, String target, String operatorId, String ruleId
    ) {
        return new DiscoveryBenchmarkScenario(
            id,
            "synthetic operator ablation",
            input,
            target,
            List.of(),
            List.of(operatorId),
            List.of("sympy-polynomial-basic", "sympy-rational-basic"),
            List.of(),
            List.of(),
            List.of(ruleId),
            new DiscoveryBenchmarkScenario.MacroLearning(false, null, null),
            new DiscoveryBenchmarkScenario.Budgets(4, 80, 5000),
            new DiscoveryBenchmarkScenario.Gallery(false, 1, 1));
    }
}
