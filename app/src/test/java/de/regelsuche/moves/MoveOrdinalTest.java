package de.regelsuche.moves;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class MoveOrdinalTest {

    private MoveParameter placeholder(String name, String value, int index) {
        return new MoveParameter(name, MoveParameterKind.PLACEHOLDER, value, value, index, "test");
    }

    @Test
    void sameInputProducesSameOrdinals() {
        List<MoveParameter> parameters = List.of(placeholder("A", "sin(x)", 0), placeholder("B", "cos(x)", 1));
        MoveOrdinal first = MoveOrdinal.of(RewriteMoveKind.SUBSTITUTE_INTRODUCE, 3, parameters);
        MoveOrdinal second = MoveOrdinal.of(RewriteMoveKind.SUBSTITUTE_INTRODUCE, 3, parameters);
        assertEquals(first, second);
        assertEquals(first.hashCode(), second.hashCode());
    }

    @Test
    void differentParametersProduceDifferentOrdinals() {
        MoveOrdinal first = MoveOrdinal.of(
                RewriteMoveKind.SUBSTITUTE_INTRODUCE, 0, List.of(placeholder("A", "sin(x)", 0)));
        MoveOrdinal second = MoveOrdinal.of(
                RewriteMoveKind.SUBSTITUTE_INTRODUCE, 0,
                List.of(placeholder("A", "sin(x)", 0), placeholder("B", "cos(x)", 1)));
        assertNotEquals(first, second);
    }

    @Test
    void differentKindProducesDifferentRuleOrdinal() {
        MoveOrdinal normalize = MoveOrdinal.of(RewriteMoveKind.NORMALIZE, 0, List.of());
        MoveOrdinal complete = MoveOrdinal.of(RewriteMoveKind.COMPLETE_SQUARE, 0, List.of());
        assertNotEquals(normalize.ruleOrdinal(), complete.ruleOrdinal());
    }

    @Test
    void sortingIsDeterministicAndStable() {
        List<MoveOrdinal> ordinals = new ArrayList<>(List.of(
                MoveOrdinal.of(RewriteMoveKind.COMPLETE_SQUARE, 2, List.of()),
                MoveOrdinal.of(RewriteMoveKind.NORMALIZE, 5, List.of()),
                MoveOrdinal.of(RewriteMoveKind.NORMALIZE, 1, List.of()),
                MoveOrdinal.of(RewriteMoveKind.COMPLETE_SQUARE, 0, List.of())));
        List<MoveOrdinal> firstSort = new ArrayList<>(ordinals);
        firstSort.sort(MoveOrdinal.CANONICAL_ORDER);
        List<MoveOrdinal> secondSort = new ArrayList<>(ordinals);
        secondSort.sort(MoveOrdinal.CANONICAL_ORDER);
        assertEquals(firstSort, secondSort);
        // NORMALIZE has the smallest rule ordinal, so its entries come first, ordered by occurrence.
        assertEquals(RewriteMoveKind.NORMALIZE.registryOrdinal(), firstSort.get(0).ruleOrdinal());
        assertTrue(firstSort.get(0).occurrenceOrdinal() <= firstSort.get(1).occurrenceOrdinal());
    }

    @Test
    void parameterOrderingDoesNotDependOnInputOrder() {
        List<MoveParameter> ordered = List.of(placeholder("A", "a", 0), placeholder("B", "b", 1));
        List<MoveParameter> shuffled = List.of(placeholder("B", "b", 1), placeholder("A", "a", 0));
        assertEquals(
                MoveOrdinal.of(RewriteMoveKind.SUBSTITUTE_INTRODUCE, 0, ordered),
                MoveOrdinal.of(RewriteMoveKind.SUBSTITUTE_INTRODUCE, 0, shuffled));
    }
}
