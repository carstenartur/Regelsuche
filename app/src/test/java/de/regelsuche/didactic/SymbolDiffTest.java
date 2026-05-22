package de.regelsuche.didactic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class SymbolDiffTest {

    @Test
    void replayHighlightsChangedSymbols() {
        // x + 3 = 7   ->   x = 4
        // Expected: "+ 3" and "7" become removed; "4" is added; "x" and "="
        // remain unchanged.
        List<SymbolDiff.Token> tokens = SymbolDiff.diff("x + 3 = 7", "x = 4");

        long unchanged = tokens.stream()
            .filter(t -> t.change() == SymbolDiff.Change.UNCHANGED)
            .count();
        long added = tokens.stream()
            .filter(t -> t.change() == SymbolDiff.Change.ADDED)
            .count();
        long removed = tokens.stream()
            .filter(t -> t.change() == SymbolDiff.Change.REMOVED)
            .count();

        assertTrue(unchanged >= 2,
            "expected `x` and `=` to be preserved across the step");
        assertTrue(removed >= 2,
            "expected `+`, `3`, and `7` to be removed");
        assertEquals(1, added,
            "expected exactly one new symbol (`4`) to be added");
    }

    @Test
    void changesFiltersOutUnchangedTokens() {
        List<SymbolDiff.Token> changes = SymbolDiff.changes("a + b", "a + c");
        assertTrue(changes.stream().noneMatch(t -> t.change() == SymbolDiff.Change.UNCHANGED));
        assertTrue(changes.stream().anyMatch(
            t -> t.change() == SymbolDiff.Change.REMOVED && t.text().equals("b")));
        assertTrue(changes.stream().anyMatch(
            t -> t.change() == SymbolDiff.Change.ADDED && t.text().equals("c")));
    }

    @Test
    void identicalExpressionsProduceNoChanges() {
        assertTrue(SymbolDiff.changes("x + 1", "x + 1").isEmpty());
    }
}
