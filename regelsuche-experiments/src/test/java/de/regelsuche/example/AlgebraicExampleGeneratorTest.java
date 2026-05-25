package de.regelsuche.example;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import org.junit.jupiter.api.Test;

class AlgebraicExampleGeneratorTest {
    @Test
    void generatesDeterministicUniqueSeedCorpus() {
        List<String> examples = new AlgebraicExampleGenerator().generateSmallIntegerExamples(-1, 1);

        assertEquals(new HashSet<>(examples).size(), examples.size());
        assertTrue(examples.contains("(x + 1)^2"));
        assertTrue(examples.contains("x^2 + 2*x + 1"));
        assertTrue(examples.contains("2*x^2 + 0*x + 1"));
        assertTrue(examples.contains("y^4 + 2*y^2 + 1"));
    }
}
