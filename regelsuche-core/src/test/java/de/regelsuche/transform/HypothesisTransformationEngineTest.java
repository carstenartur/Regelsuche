package de.regelsuche.transform;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class HypothesisTransformationEngineTest {
    @Test
    void combinesBaseTransformationsAndHypothesisCandidates() {
        HypothesisTransformationEngine engine = new HypothesisTransformationEngine(
            new AstRewriteTransformationEngine(),
            List.of(new DifferenceOfSquaresPreparationOperator())
        );

        List<Transformation> transformations = engine.transform("x^4 + 4");

        assertTrue(transformations.stream().anyMatch(transformation ->
            transformation.rule().equals(DifferenceOfSquaresPreparationOperator.RULE_ID)));
        assertTrue(transformations.stream().allMatch(transformation -> !transformation.transformedExpression().isBlank()));
    }

    @Test
    void keepsBaseEngineBehaviourVisible() {
        HypothesisTransformationEngine engine = new HypothesisTransformationEngine(
            new AstRewriteTransformationEngine(),
            List.of(new DifferenceOfSquaresPreparationOperator())
        );

        assertTrue(engine.transform("x + 0").stream().anyMatch(transformation ->
            transformation.rule().equals("ast_add_zero_right")
                && transformation.transformedExpression().equals("x")));
    }
}
