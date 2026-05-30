package de.regelsuche.transform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import org.junit.jupiter.api.Test;

class HypothesisOperatorRegistryTest {
    @Test
    void containsInitialOperatorsWithUniqueStableIds() {
        HypothesisOperatorRegistry registry = new HypothesisOperatorRegistry();

        List<String> ids = registry.stableIds();

        assertTrue(ids.contains(DifferenceOfSquaresPreparationOperator.RULE_ID));
        assertTrue(ids.contains(CompleteSquareHypothesisOperator.RULE_ID));
        assertEquals(ids.size(), new HashSet<>(ids).size());
    }

    @Test
    void profilesSelectExpectedOperators() {
        HypothesisOperatorRegistry registry = new HypothesisOperatorRegistry();

        assertTrue(registry.selectOperators(DiscoveryOptions.forProfile(DiscoveryProfile.PURE_REWRITE)).isEmpty());
        assertFalse(registry.selectOperators(DiscoveryOptions.forProfile(DiscoveryProfile.HYPOTHESIS_ONLY)).isEmpty());
        assertTrue(registry.selectOperators(DiscoveryOptions.forProfile(DiscoveryProfile.MACRO_REUSE_ONLY)).isEmpty());
        assertFalse(registry.selectOperators(DiscoveryOptions.forProfile(DiscoveryProfile.FULL_DISCOVERY)).isEmpty());
    }

    @Test
    void maxCandidateBudgetIsPropagatedToOperators() {
        HypothesisOperatorRegistry registry = new HypothesisOperatorRegistry();
        DiscoveryOptions zeroBudget = new DiscoveryOptions(true, false, false, false, 0, 4, 160,
            DiscoveryProfile.HYPOTHESIS_ONLY);

        List<Transformation> candidates = registry.selectOperators(zeroBudget).stream()
            .flatMap(operator -> operator.generateCandidates("x^4 + 4").stream())
            .toList();

        assertTrue(candidates.isEmpty());
    }
}
