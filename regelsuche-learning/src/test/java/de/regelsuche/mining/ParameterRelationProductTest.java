package de.regelsuche.mining;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ParameterRelationProductTest {
    private final ParameterRelationMiner miner = new ParameterRelationMiner();

    @Test
    void learnsTwoParametersTheirProductRepeatedBindingsAndConstants() {
        var result = miner.mine(observations(List.of(15, 28, 66)));
        assertFalse(result.isEmpty());
        assertEquals("A*B", replacement(result, "N1"));
        assertEquals("A", replacement(result, "N2"));
        assertEquals("B", replacement(result, "N3"));
        assertEquals(replacement(result, "N2"), replacement(result, "N4"));
        assertEquals("17", replacement(result, "N5"));
    }

    @Test
    void everyObservationMustSatisfyTheProductRelation() {
        assertTrue(miner.mine(observations(List.of(15, 28, 67))).isEmpty());
        assertTrue(miner.mine(observations(List.of(15, 66, 28))).isEmpty());
    }

    @Test
    void fallbackIsIndependentOfMapIterationOrder() {
        var inputs = observations(List.of(15, 28, 66));
        var reversed = new LinkedHashMap<String, List<Integer>>();
        inputs.entrySet().stream().sorted(Map.Entry.<String, List<Integer>>comparingByKey().reversed())
            .forEach(entry -> reversed.put(entry.getKey(), entry.getValue()));
        assertEquals(miner.mine(inputs).descriptions(), miner.mine(reversed).descriptions());
    }

    @Test
    void wrappedIntegerMultiplicationIsNotAnExactRelation() {
        var inputs = new LinkedHashMap<String, List<Integer>>();
        inputs.put("N1", List.of(50000 * 70000, 60000 * 80000, 70000 * 90000));
        inputs.put("N2", List.of(50000, 60000, 70000));
        inputs.put("N3", List.of(70000, 80000, 90000));
        assertTrue(miner.mine(inputs).isEmpty());
    }

    @Test
    void existingOneParameterModelRetainsPrecedence() {
        var result = miner.mine(Map.of("N1", List.of(3, 6, 9),
            "N2", List.of(1, 2, 3), "N3", List.of(3, 3, 3)));
        assertFalse(result.isEmpty());
        assertTrue(result.descriptions().stream().noneMatch(text -> text.contains("B")));
    }

    @Test
    void aSingleObservationDoesNotEstablishTwoVaryingParameters() {
        assertTrue(miner.mine(Map.of("N1", List.of(15),
            "N2", List.of(3), "N3", List.of(5))).isEmpty());
    }

    @Test
    void fallbackAcceptsSignedFactorsAndZeroWithoutNarrowingArithmetic() {
        var result = BivariateProductRelations.mine(Map.of(
            "N1", List.of(0, -14, -33), "N2", List.of(0, -2, 3), "N3", List.of(5, 7, -11)));
        assertFalse(result.isEmpty());
        assertEquals("A*B", replacement(result, "N1"));
    }

    @Test
    void unsupportedOrIncompleteObservationTablesDoNotProduceARelation() {
        assertTrue(BivariateProductRelations.mine(Map.of()).isEmpty());
        assertTrue(BivariateProductRelations.mine(Map.of(
            "N1", List.of(3, 4, 6), "N2", List.of(5, 7, 11))).isEmpty());
        var incomplete = observations(List.of(15, 28, 66));
        incomplete.put("N6", List.of(3, 4));
        assertTrue(BivariateProductRelations.mine(incomplete).isEmpty());
        incomplete.put("N6", java.util.Arrays.asList(3, null, 6));
        assertTrue(BivariateProductRelations.mine(incomplete).isEmpty());
        incomplete.put("N6", null);
        assertTrue(BivariateProductRelations.mine(incomplete).isEmpty());
        assertTrue(BivariateProductRelations.mine(Map.of(
            "N1", List.of(1, 4), "N2", List.of(1, 2), "N3", List.of(1, 2))).isEmpty());
    }

    @Test
    void reservesExistingExpressionPlaceholdersBeforeNamingTheSecondParameter() {
        var result = miner.mine(observations(List.of(15, 28, 66)), java.util.Set.of("B", "C"));
        assertEquals("A*D", replacement(result, "N1"));
        assertEquals("D", replacement(result, "N3"));
    }

    @Test
    void anExhaustedBindableNamespaceDoesNotCreateAnUnboundParameter() {
        var reserved = new java.util.HashSet<String>();
        for (char name = 'B'; name <= 'Z'; name++) reserved.add(String.valueOf(name));
        assertTrue(miner.mine(observations(List.of(15, 28, 66)), reserved).isEmpty());
    }

    private static String replacement(ParameterRelationMiner.RelationResult result, String name) {
        return result.apply(NormalizedNode.placeholder(name)).canonicalString();
    }

    private static Map<String, List<Integer>> observations(List<Integer> products) {
        var rows = new LinkedHashMap<String, List<Integer>>();
        rows.put("N1", products);
        rows.put("N2", List.of(3, 4, 6));
        rows.put("N3", List.of(5, 7, 11));
        rows.put("N4", List.of(3, 4, 6));
        rows.put("N5", List.of(17, 17, 17));
        return rows;
    }
}
