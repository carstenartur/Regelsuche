package de.regelsuche.mining;

import static org.junit.jupiter.api.Assertions.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ParameterRelationMinerBivariateTest {

    @Test
    void learnsExactIndependentProductRelationWithoutDomainKnowledge() {
        Map<String, List<Integer>> values = new LinkedHashMap<>();
        values.put("N1", List.of(15, 28, 66));
        values.put("N2", List.of(3, 4, 6));
        values.put("N3", List.of(5, 7, 11));
        values.put("N4", List.of(3, 4, 6));

        var result = new ParameterRelationMiner().mine(values);

        assertFalse(result.isEmpty());
        var probe = NormalizedNode.function("probe", List.of(
            NormalizedNode.placeholder("N1"),
            NormalizedNode.placeholder("N2"),
            NormalizedNode.placeholder("N3"),
            NormalizedNode.placeholder("N4")));
        assertEquals("probe(A*A2,A,A2,A)", result.apply(probe).canonicalString());
        assertTrue(result.descriptions().contains("N1 = A*A2"));
        assertTrue(result.descriptions().contains("N2 = A"));
        assertTrue(result.descriptions().contains("N3 = A2"));
    }

    @Test
    void rejectsUnexplainedThirdIndependentSeries() {
        Map<String, List<Integer>> values = new LinkedHashMap<>();
        values.put("N1", List.of(15, 28, 66));
        values.put("N2", List.of(3, 4, 6));
        values.put("N3", List.of(5, 7, 11));
        values.put("N4", List.of(2, 9, 17));

        assertTrue(new ParameterRelationMiner().mine(values).isEmpty());
    }
}
