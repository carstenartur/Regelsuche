package de.regelsuche.search.incremental;

import de.regelsuche.ast.*;
import java.math.BigInteger;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CounterStateSynthesisTest {
    private static final Expr COUNT = new VariableExpr("count");
    private static final Expr SUPPORT = new FunctionExpr("positive", COUNT);
    private static final Expr PARITY = new FunctionExpr("mod", List.of(COUNT, new NumberExpr(2)));

    private CounterStateSynthesis.Specification spec(Expr expression, int bound, boolean deletes) {
        return new CounterStateSynthesis.Specification(expression, bound, deletes);
    }

    @Test void derivesSupportStatesFromAllowedChanges() {
        var inserts = CounterStateSynthesis.derive(spec(SUPPORT, 9, false));
        var reversible = CounterStateSynthesis.derive(spec(SUPPORT, 9, true));
        assertEquals(2, inserts.plan().outputs().length);
        assertEquals(10, reversible.plan().outputs().length);
        assertTrue(inserts.verification().accepted());
        assertTrue(reversible.verification().accepted());
        assertTrue(reversible.refinementWork() > 0);
    }

    @Test void derivesLargestReversibleSupportWithinLinearWorkBudget() {
        int bound = CounterStateSynthesis.MAXIMUM_COUNT;
        var result = CounterStateSynthesis.derive(spec(SUPPORT, bound, true), 20L * (bound + 1));
        assertEquals(bound + 1, result.plan().outputs().length);
        assertTrue(result.totalWork() <= 20L * (bound + 1));
        assertTrue(result.verification().accepted());
    }

    @Test void producesMinimalStatesForEverySmallBinaryObservationWord() {
        for (int length = 2; length <= 7; length++) {
            for (int bits = 0; bits < 1 << length; bits++) {
                int[] word = new int[length];
                Expr expression = new NumberExpr(0);
                for (int i = 0; i < length; i++) {
                    word[i] = bits >>> i & 1;
                    if (word[i] == 0) continue;
                    // Indicator for count == i, expressed in the public AST.
                    Expr first = new FunctionExpr("positive", new BinaryExpr(COUNT, BinaryOperator.SUB, new NumberExpr(i - 1)));
                    Expr second = new FunctionExpr("positive", new BinaryExpr(COUNT, BinaryOperator.SUB, new NumberExpr(i)));
                    expression = new BinaryExpr(expression, BinaryOperator.ADD, new BinaryExpr(first, BinaryOperator.SUB, second));
                }
                for (boolean deletes : new boolean[] {false, true}) {
                    var source = spec(expression, length - 1, deletes);
                    var result = CounterStateSynthesis.derive(source);
                    assertTrue(result.verification().accepted());
                    assertEquals(minimumLassoSize(word, deletes), result.plan().outputs().length,
                        "word=" + java.util.Arrays.toString(word) + ", deletes=" + deletes);
                }
            }
        }
    }

    private int minimumLassoSize(int[] word, boolean deletes) {
        int best = word.length;
        for (int prefix = 0; prefix < (deletes ? 1 : word.length); prefix++) {
            for (int period = 1; prefix + period <= word.length; period++) {
                boolean valid = true;
                for (int i = prefix; i < word.length; i++) {
                    if (word[i] != word[prefix + (i - prefix) % period]) { valid = false; break; }
                }
                if (valid) best = Math.min(best, prefix + period);
            }
        }
        return best;
    }

    @Test void discoversParityWithoutAParityTransitionTemplate() {
        var source = spec(PARITY, 13, true);
        var result = CounterStateSynthesis.derive(source);
        assertEquals(2, result.plan().outputs().length);
        var prepared = CounterStateSynthesis.prepareVerified(source, result.plan());
        var state = prepared.<String>newAccumulator();
        state.insert("red"); state.insert("blue");
        assertEquals(BigInteger.TWO, state.value());
        state.insert("red");
        assertEquals(BigInteger.ONE, state.value());
        state.delete("red"); state.delete("blue");
        assertEquals(BigInteger.ONE, state.value());
        state.delete("red");
        assertEquals(BigInteger.ZERO, state.value());
    }

    @Test void groupedArithmeticAgreesForEveryLegalTransition() {
        Expr square = new BinaryExpr(COUNT, BinaryOperator.MUL, COUNT);
        for (Expr expression : List.of(COUNT, SUPPORT, PARITY, square)) {
            var source = spec(expression, 8, true);
            var plan = CounterStateSynthesis.derive(source).plan();
            var prepared = CounterStateSynthesis.prepareVerified(source, plan);
            for (int initial = 0; initial <= 8; initial++) {
                var state = prepared.<Integer>newAccumulator();
                for (int i = 0; i < initial; i++) state.insert(1);
                BigInteger expected = expression == square ? BigInteger.valueOf(initial * initial)
                    : expression == SUPPORT ? BigInteger.valueOf(initial == 0 ? 0 : 1)
                    : expression == PARITY ? BigInteger.valueOf(initial % 2) : BigInteger.valueOf(initial);
                assertEquals(expected, state.value());
                if (initial < 8) { state.insert(1); state.delete(1); assertEquals(expected, state.value()); }
                if (initial > 0) { state.delete(1); state.insert(1); assertEquals(expected, state.value()); }
            }
        }
    }

    @Test void checkerRejectsCorruptionAndBindsTheRequestedSpecification() {
        var source = spec(SUPPORT, 5, true);
        var plan = CounterStateSynthesis.derive(source).plan();
        int[] plus = plan.onInsert(); plus[plan.statesByCount()[0]] = plan.statesByCount()[0];
        var corrupt = new CounterStateSynthesis.Plan(plan.statesByCount(), plan.outputs(), plus, plan.onDelete());
        assertFalse(CounterStateSynthesis.verify(source, corrupt).accepted());
        assertThrows(IllegalArgumentException.class, () -> CounterStateSynthesis.prepareVerified(source, corrupt));
        assertFalse(CounterStateSynthesis.verify(spec(COUNT, 5, true), plan).accepted());
        assertFalse(CounterStateSynthesis.verify(source,
            new CounterStateSynthesis.Plan(new int[] {0}, new BigInteger[] {BigInteger.ZERO}, new int[] {-1}, new int[] {-1})).accepted());
        int[] copy = plan.statesByCount(); copy[0] = 99;
        assertTrue(CounterStateSynthesis.verify(source, plan).accepted());
    }

    @Test void checksMultiplicityBeforeMutationAndKeepsSessionsIndependent() {
        var source = spec(SUPPORT, 2, true);
        var prepared = CounterStateSynthesis.prepareVerified(source, CounterStateSynthesis.derive(source).plan());
        var first = prepared.<String>newAccumulator();
        var second = prepared.<String>newAccumulator();
        first.insert("same"); first.insert("same");
        assertThrows(IllegalStateException.class, () -> first.insert("same"));
        assertThrows(IllegalStateException.class, () -> second.delete("same"));
        assertEquals(BigInteger.ZERO, second.value());
        first.delete("same"); assertEquals(BigInteger.ONE, first.value());
        first.delete("same"); assertEquals(BigInteger.ZERO, first.value());
        assertThrows(IllegalStateException.class, () -> first.delete("same"));
        var inserts = spec(SUPPORT, 2, false);
        var insertOnly = CounterStateSynthesis.prepareVerified(inserts, CounterStateSynthesis.derive(inserts).plan()).newAccumulator();
        insertOnly.insert("a");
        assertThrows(IllegalStateException.class, () -> insertOnly.delete("a"));
    }

    @Test void refusesUnsupportedExpressionsAndExhaustedWorkRatherThanReturningAPlan() {
        assertThrows(IllegalArgumentException.class, () -> CounterStateSynthesis.derive(spec(new VariableExpr("other"), 4, true)));
        assertThrows(IllegalArgumentException.class, () -> CounterStateSynthesis.derive(spec(new FunctionExpr("sin", COUNT), 4, true)));
        assertThrows(IllegalArgumentException.class, () -> CounterStateSynthesis.derive(spec(SUPPORT, 8193, true)));
        assertThrows(CounterStateSynthesis.BudgetExceededException.class,
            () -> CounterStateSynthesis.derive(spec(SUPPORT, 10, true), 2));
        Expr constant = new NumberExpr(1);
        var source = spec(constant, 3, true);
        var prepared = CounterStateSynthesis.prepareVerified(source, CounterStateSynthesis.derive(source).plan());
        assertThrows(IllegalArgumentException.class, prepared::newAccumulator);
    }
}
