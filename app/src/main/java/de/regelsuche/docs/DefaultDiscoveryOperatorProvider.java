package de.regelsuche.docs;

import de.regelsuche.transform.CompleteSquareBridgeOperator;
import de.regelsuche.transform.CommonSubexpressionDiscoveryOperator;
import de.regelsuche.transform.DifferenceOfSquaresPreparationOperator;
import de.regelsuche.transform.ExpLogInverseOperator;
import de.regelsuche.transform.FactorCandidateOperator;
import de.regelsuche.transform.LogProductAssumptionOperator;
import de.regelsuche.transform.PowerRootAssumptionRules;
import de.regelsuche.transform.RationalDiscoveryToolkitOperator;
import de.regelsuche.transform.RationalNormalizationHypothesisOperator;
import de.regelsuche.transform.RationalizationHypothesisOperator;
import de.regelsuche.transform.RepeatedSubexpressionFactorizationHypothesisOperator;
import de.regelsuche.transform.SubstitutionExpansionOperator;
import de.regelsuche.transform.SubstitutionIntroductionOperator;
import de.regelsuche.transform.TelescopingFractionHypothesisOperator;
import de.regelsuche.transform.TrigPowerReductionOperator;
import de.regelsuche.transform.TrigPythagoreanIdentityOperator;
import java.util.List;

final class DefaultDiscoveryOperatorProvider implements DiscoveryOperatorProvider {
    @Override
    public String id() {
        return "core-default-operators";
    }

    @Override
    public List<DiscoveryOperatorDefinition> operators() {
        return List.of(
                new DiscoveryOperatorDefinition(
                        "complete_square_bridge",
                        CompleteSquareBridgeOperator::new,
                        List.of(CompleteSquareBridgeOperator.RULE_ID)),
                new DiscoveryOperatorDefinition(
                        "sophie_germain_bridge",
                        DifferenceOfSquaresPreparationOperator::new,
                        List.of(DifferenceOfSquaresPreparationOperator.RULE_ID)),
                new DiscoveryOperatorDefinition(
                        "hidden_structure_bridge",
                        DifferenceOfSquaresPreparationOperator::new,
                        List.of(DifferenceOfSquaresPreparationOperator.RULE_ID)),
                new DiscoveryOperatorDefinition(
                        "repeated_subexpression_factorization",
                        RepeatedSubexpressionFactorizationHypothesisOperator::new,
                        List.of(RepeatedSubexpressionFactorizationHypothesisOperator.RULE_ID)),
                new DiscoveryOperatorDefinition(
                        "common_subexpression_discovery",
                        CommonSubexpressionDiscoveryOperator::new,
                        List.of(CommonSubexpressionDiscoveryOperator.RULE_ID)),
                new DiscoveryOperatorDefinition(
                        "factor_candidate",
                        FactorCandidateOperator::new,
                        List.of(FactorCandidateOperator.RULE_ID)),
                new DiscoveryOperatorDefinition(
                        "telescoping_fraction",
                        TelescopingFractionHypothesisOperator::new,
                        List.of(TelescopingFractionHypothesisOperator.RULE_ID)),
                new DiscoveryOperatorDefinition(
                        "rational_normalization",
                        RationalNormalizationHypothesisOperator::new,
                        List.of(RationalNormalizationHypothesisOperator.RULE_ID)),
                new DiscoveryOperatorDefinition(
                        "rationalization",
                        RationalizationHypothesisOperator::new,
                        List.of(RationalizationHypothesisOperator.RULE_ID)),
                new DiscoveryOperatorDefinition(
                        "rational_discovery_toolkit",
                        RationalDiscoveryToolkitOperator::new,
                        List.of(RationalDiscoveryToolkitOperator.RULE_ID)),
                new DiscoveryOperatorDefinition(
                        "trig_pythagorean_identity",
                        TrigPythagoreanIdentityOperator::new,
                        List.of(TrigPythagoreanIdentityOperator.RULE_ID)),
                new DiscoveryOperatorDefinition(
                        "trig_power_reduction",
                        TrigPowerReductionOperator::new,
                        List.of(TrigPowerReductionOperator.RULE_ID)),
                new DiscoveryOperatorDefinition(
                        "log_product_assumption",
                        LogProductAssumptionOperator::new,
                        List.of(LogProductAssumptionOperator.RULE_ID)),
                new DiscoveryOperatorDefinition(
                        "exp_log_inverse",
                        ExpLogInverseOperator::new,
                        List.of(ExpLogInverseOperator.RULE_ID)),
                new DiscoveryOperatorDefinition(
                        "power_root_assumptions",
                        PowerRootAssumptionRules::new,
                        List.of(PowerRootAssumptionRules.RULE_ID)),
                new DiscoveryOperatorDefinition(
                        "substitution_introduction",
                        SubstitutionIntroductionOperator::new,
                        List.of(SubstitutionIntroductionOperator.RULE_ID)),
                new DiscoveryOperatorDefinition(
                        "substitution_expansion",
                        SubstitutionExpansionOperator::new,
                        List.of(SubstitutionExpansionOperator.RULE_ID)));
    }
}
