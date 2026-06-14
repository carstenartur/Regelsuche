package de.regelsuche.moves.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.moves.search.SearchSuccessorGenerator.SearchSuccessorState;
import java.util.List;
import org.junit.jupiter.api.Test;

class SearchSuccessorGeneratorTest {

    private final SearchSuccessorGenerator generator = new SearchSuccessorGenerator();

    @Test
    void generatesCompleteSquareAndFactorSuccessorsAtRoot() {
        List<SearchSuccessorState> successors = generator.generate("x^2 + 6*x + 5");

        assertFalse(successors.isEmpty());
        List<String> expressions = successors.stream().map(SearchSuccessorState::successorExpression).toList();
        assertTrue(expressions.contains("(x + 3) ^ 2 - 4"), expressions.toString());
        assertTrue(expressions.contains("(x + 1) * (x + 5)"), expressions.toString());
        assertEquals(
                1,
                expressions.stream().filter("(x + 3) ^ 2 - 4"::equals).count(),
                "complete-square successor should be deduplicated");
    }

    @Test
    void generatesNestedSuccessorsInsideSin() {
        List<SearchSuccessorState> successors = generator.generate("sin(x^2 + 6*x + 5)");

        List<String> expressions = successors.stream().map(SearchSuccessorState::successorExpression).toList();
        assertTrue(expressions.contains("sin((x + 3) ^ 2 - 4)"), expressions.toString());
        assertTrue(expressions.contains("sin((x + 1) * (x + 5))"), expressions.toString());
        assertTrue(successors.stream().anyMatch(successor -> "000".equals(successor.position().pathKey())));
    }
}
