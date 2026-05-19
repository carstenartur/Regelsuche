package de.regelsuche.mining;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.ast.NumberExpr;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class FreshBindingGeneratorTest {
    @Test
    void freshBindingGeneratorProducesIndependentNonTrivialBindings() {
        List<Map<String, Integer>> bindings = new FreshBindingGenerator().generate(new LinkedHashSet<>(List.of("A", "B", "C", "N1", "N2")));

        assertTrue(bindings.size() >= 6);
        assertTrue(bindings.stream().anyMatch(binding -> binding.values().stream().anyMatch(value -> value < 0)));
        assertTrue(bindings.stream().anyMatch(binding -> binding.values().stream().anyMatch(value -> value > 0)));
        for (Map<String, Integer> binding : bindings) {
            assertEquals(5, Set.copyOf(binding.values()).size());
            assertFalse(binding.values().contains(0));
            assertTrue(binding.values().stream().noneMatch(value -> Math.abs(value) == 1));
        }
    }

    @Test
    void parameterRelationsAreEvaluatedAstBased() {
        ParameterRelationEvaluator evaluator = new ParameterRelationEvaluator();
        Map<String, de.regelsuche.ast.Expr> bindings = evaluator.completeBindings(
            new LinkedHashSet<>(List.of("A", "N1", "N2", "N3")),
            Map.of("A", 3),
            List.of("N1 = 2*A", "N2 = A^2", "N3 = -A")
        );

        assertEquals(6, ((NumberExpr) bindings.get("N1")).value());
        assertEquals(9, ((NumberExpr) bindings.get("N2")).value());
        assertEquals(-3, ((NumberExpr) bindings.get("N3")).value());
    }
}
