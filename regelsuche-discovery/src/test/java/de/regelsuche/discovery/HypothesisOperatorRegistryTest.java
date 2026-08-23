package de.regelsuche.discovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.transform.ConservativeCompleteSquareHypothesisOperator;
import de.regelsuche.transform.DifferenceOfSquaresPreparationOperator;
import de.regelsuche.transform.PolynomialDecompositionSynthesisOperator;
import de.regelsuche.transform.RationalizationHypothesisOperator;
import de.regelsuche.transform.TelescopingFractionHypothesisOperator;
import de.regelsuche.transform.Transformation;
import java.util.HashSet;
import java.util.List;
import org.junit.jupiter.api.Test;

class HypothesisOperatorRegistryTest {
    @Test
    void containsInitialOperatorsWithUniqueStableMetadata() {
        HypothesisOperatorRegistry registry = new HypothesisOperatorRegistry();

        List<String> ids = registry.all().stream()
            .map(HypothesisOperatorDescriptor::id)
            .toList();

        assertTrue(ids.contains(
            PolynomialDecompositionSynthesisOperator.RULE_ID));
        assertTrue(ids.contains(DifferenceOfSquaresPreparationOperator.RULE_ID));
        assertTrue(ids.contains(
            ConservativeCompleteSquareHypothesisOperator.RULE_ID));
        assertTrue(ids.contains(TelescopingFractionHypothesisOperator.RULE_ID));
        assertTrue(ids.contains(RationalizationHypothesisOperator.RULE_ID));
        assertEquals(ids.size(), new HashSet<>(ids).size());
        assertEquals(
            "polynomial-decomposition-synthesis",
            registry.byId(PolynomialDecompositionSynthesisOperator.RULE_ID)
                .orElseThrow()
                .displayName());
        assertEquals(
            "complete-square",
            registry.byId(ConservativeCompleteSquareHypothesisOperator.RULE_ID)
                .orElseThrow()
                .displayName());
        assertEquals(
            "telescoping-fraction",
            registry.byId(TelescopingFractionHypothesisOperator.RULE_ID)
                .orElseThrow()
                .displayName());
        assertEquals(
            "rationalization",
            registry.byId(RationalizationHypothesisOperator.RULE_ID)
                .orElseThrow()
                .displayName());
    }

    @Test
    void generalSynthesisReplacesNamedBridgeAsDefaultFactorizationHypothesis() {
        HypothesisOperatorRegistry registry = new HypothesisOperatorRegistry();
        List<String> selected = registry.selectedStableIds(
            DiscoveryOptions.forProfile(DiscoveryProfile.HYPOTHESIS_ONLY));

        assertTrue(selected.contains(
            PolynomialDecompositionSynthesisOperator.RULE_ID));
        assertFalse(selected.contains(
            DifferenceOfSquaresPreparationOperator.RULE_ID));
        assertTrue(registry.byId(
                DifferenceOfSquaresPreparationOperator.RULE_ID)
            .orElseThrow()
            .tags()
            .contains("historical-control"));
    }

    @Test
    void profilesSelectExpectedOperators() {
        HypothesisOperatorRegistry registry = new HypothesisOperatorRegistry();

        assertTrue(registry.selectOperators(
            DiscoveryOptions.forProfile(DiscoveryProfile.PURE_REWRITE))
            .isEmpty());
        assertFalse(registry.selectOperators(
            DiscoveryOptions.forProfile(DiscoveryProfile.HYPOTHESIS_ONLY))
            .isEmpty());
        assertTrue(registry.selectOperators(
            DiscoveryOptions.forProfile(DiscoveryProfile.MACRO_REUSE_ONLY))
            .isEmpty());
        assertFalse(registry.selectOperators(
            DiscoveryOptions.forProfile(
                DiscoveryProfile.HYPOTHESIS_AND_MACRO_REUSE))
            .isEmpty());
    }

    @Test
    void maxCandidateBudgetIsPropagatedToOperators() {
        HypothesisOperatorRegistry registry = new HypothesisOperatorRegistry();
        DiscoveryOptions zeroBudget = new DiscoveryOptions(
            DiscoveryProfile.HYPOTHESIS_ONLY,
            new DiscoveryEngineOptions(
                true,
                false,
                0,
                4,
                160,
                DiscoveryProfile.HYPOTHESIS_ONLY),
            DiscoveryLearningOptions.disabled(),
            false
        );

        List<Transformation> candidates = registry.selectOperators(zeroBudget)
            .stream()
            .flatMap(operator -> operator.generateCandidates(
                "x^4 + 4*y^4").stream())
            .toList();

        assertTrue(candidates.isEmpty());
    }

    @Test
    void customOptionsCanEnableOperatorsIndependentOfProfile() {
        HypothesisOperatorRegistry registry = new HypothesisOperatorRegistry();
        DiscoveryOptions customOptions = new DiscoveryOptions(
            DiscoveryProfile.PURE_REWRITE,
            new DiscoveryEngineOptions(
                true,
                false,
                6,
                4,
                160,
                DiscoveryProfile.PURE_REWRITE),
            DiscoveryLearningOptions.disabled(),
            false
        );

        assertFalse(registry.selectOperators(customOptions).isEmpty());
    }
}
