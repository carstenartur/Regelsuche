package de.regelsuche.docs;

import de.regelsuche.transform.CompleteSquareBridgeOperator;
import de.regelsuche.transform.DifferenceOfSquaresPreparationOperator;
import de.regelsuche.transform.TelescopingFractionHypothesisOperator;
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
                        "telescoping_fraction",
                        TelescopingFractionHypothesisOperator::new,
                        List.of(TelescopingFractionHypothesisOperator.RULE_ID)));
    }
}
