package de.regelsuche.transform;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

    @Test
    void preservesDistinctBaseTransformationsSharingApplicationKey() {
        TransformationEngine baseEngine = expression -> List.of(
            new Transformation("base_rule_1", "x", RewriteKind.NORMALIZE, false, 0, true, "same-key"),
            new Transformation("base_rule_2", "0", RewriteKind.NORMALIZE, false, 0, true, "same-key")
        );
        HypothesisTransformationEngine engine = new HypothesisTransformationEngine(baseEngine, List.of());

        List<Transformation> transformations = engine.transform("x + 0");

        assertEquals(2, transformations.size());
        assertEquals("base_rule_1", transformations.get(0).rule());
        assertEquals("base_rule_2", transformations.get(1).rule());
    }

    @Test
    void boundsAndOrdersHypothesisCandidatesDeterministically() {
        HypothesisOperator firstOperator = expression -> List.of(
            new Transformation("hyp_1", "a", RewriteKind.NORMALIZE, true, 1, true, "hyp-1"),
            new Transformation("hyp_2", "b", RewriteKind.NORMALIZE, true, 1, true, "hyp-2")
        );
        HypothesisOperator secondOperator = expression -> List.of(
            new Transformation("hyp_3", "c", RewriteKind.NORMALIZE, true, 1, true, "hyp-3")
        );
        HypothesisTransformationEngine engine = new HypothesisTransformationEngine(
            expression -> List.of(),
            List.of(firstOperator, secondOperator),
            2
        );

        List<Transformation> transformations = engine.transform("x");

        assertEquals(List.of("hyp_1", "hyp_2"), transformations.stream().map(Transformation::rule).toList());
    }
}
