package de.regelsuche.math.algorithms.iteration;

import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.NumberExpr;
import de.regelsuche.ast.VariableExpr;
import de.regelsuche.ast.iteration.OrderedReduction;
import de.regelsuche.symbol.SymbolId;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.stream.IntStream;

import static de.regelsuche.math.algorithms.iteration.OrderedBlockReductionTest.row;
import static de.regelsuche.math.algorithms.iteration.OrderedBlockReductionTest.source;
import static org.junit.jupiter.api.Assertions.*;

class OrderedBlockReductionComparisonTest {
    private static final IterationLimits SEARCH = new IterationLimits(100_000, 256, 10_000);
    private static final IterationLimits EXECUTE = new IterationLimits(100_000_000, 256, 30_000);
    private static final BooleanSupplier NEVER = () -> false;

    @Test
    void updatesHundredThousandRowsAgainstALinearPairDelta() {
        var input = source("(p1-p0)*(x0-x1)^2", 2);
        var indexed = OrderedReductionSearch.search(input, SEARCH, 4, NEVER).verified().orElseThrow();
        var plan = OrderedReductionSearch.searchPointUpdates(input, SEARCH, 4, NEVER).verified().orElseThrow();
        int size = 100_000;
        int[] updates = { 0, 74, 99_999, 65_535, 28_791, 65_536, 0, 99_999 };
        var rows = new ArrayList<>(IntStream.range(0, size).mapToObj(i -> row(i % 17 - 8, i)).toList());
        var initial = indexed.evaluate(rows, EXECUTE, NEVER);
        var tree = plan.newAccumulator(rows, EXECUTE, NEVER);
        var expected = initial.sums().getFirst();
        assertEquals(expected, tree.value());

        long updateWork = 0;
        long combinations = 0;
        long neighborReads = 0;
        for (int step = 0; step < updates.length; step++) {
            int index = updates[step];
            long before = rows.get(index).getFirst().longValueExact();
            long after = 13 - 3L * step;
            long delta = 0;
            for (int j = 0; j < size; j++) {
                if (j == index) continue;
                long other = rows.get(j).getFirst().longValueExact();
                delta += Math.abs(index - j) * (after - before) * (after + before - 2 * other);
                neighborReads++;
            }
            expected = expected.add(BigInteger.valueOf(delta));
            rows.set(index, row(after, index));
            var result = tree.replace(index, rows.get(index), EXECUTE, NEVER);
            assertEquals(expected, result.value());
            assertEquals(17, result.blockCombinations());
            updateWork += result.work();
            combinations += result.blockCombinations();
        }
        var finalReference = indexed.evaluate(rows, EXECUTE, NEVER);
        assertEquals(expected, finalReference.sums().getFirst());
        assertEquals(799_992, neighborReads);
        System.out.printf("Point updates: build work=%d, update work=%d, combinations=%d, linear neighbor reads=%d, "
                + "indexed initial/final work=%d/%d%n",
            tree.initializationWork(), updateWork, combinations, neighborReads, initial.work(), finalReference.work());
    }

    @Test
    void transfersAnExactlyCheckedLemmaToHeldOutTriplesWithAllAcquisitionWorkCharged() {
        // These sources are created independently of the learner and are never supplied to it.
        var heldOut = List.of(
            "x1*(x0-x2)^2",
            "p1^2*(p0-p2)^2",
            "(x0-x2)^2*(1+x1*p1)",
            "(p1-p0)*(x0-x2)^2+(p2-p1)*(x1-x2)^2",
            "(x2-x0)^2+(p0-p1)^2*x2",
            "(x0-x2)^2*(p0+p2-2*p1)",
            "(x0-x1)^2*(p1-p2)^2",
            "(p0-p2)^2*(x1^2-x0*x2)",
            "(x0-x2)^2-(p0-p2)^2+p1",
            "(p0-p1)^2*x2^2+(p1-p2)^2*x0^2",
            "x0*p1*x2+1",       // No matching power.
            "(x0-x2)^3*p1",    // A different exponent.
            "(x0-x0)^2*p1+x2", // Identifying two distinct parameters must not reuse the lemma.
            "x0/x1"             // Unsupported source must still fail.
        ).stream().map(formula -> source(formula, 3)).toList();
        var input = source("(p1-p0)*(x0-x1)^2", 2);
        var example = OrderedReductionSearch.searchPointUpdates(input, SEARCH, 4, NEVER);
        var acquisition = OrderedReductionKnowledge.learn(example.verified().orElseThrow(), SEARCH, NEVER);
        var knowledge = acquisition.knowledge().orElseThrow(() -> new AssertionError(acquisition.reason()));
        var rows = List.of(row(5, -1), row(1, 2), row(13, 1), row(-4, 3), row(4, -2));
        var changedRows = new ArrayList<>(rows);
        changedRows.set(1, row(6, 2));
        long cold = work(example);
        long warm = cold + acquisition.work();
        int solved = 0;
        for (var target : heldOut) {
            var ordinary = OrderedReductionSearch.searchPointUpdates(target, SEARCH, 4, NEVER);
            var reusable = OrderedReductionSearch.searchPointUpdates(target, knowledge, SEARCH, 4, NEVER);
            cold += work(ordinary);
            warm += work(reusable);
            assertEquals(ordinary.reason(), reusable.reason());
            if (ordinary.verified().isEmpty()) {
                assertTrue(reusable.verified().isEmpty());
                continue;
            }
            var checked = OrderedReductionSearch.verifyPointUpdates(target,
                reusable.verified().orElseThrow().plan(), SEARCH, NEVER).verified().orElseThrow();
            var a = ordinary.verified().orElseThrow().newAccumulator(rows, EXECUTE, NEVER);
            var b = checked.newAccumulator(rows, EXECUTE, NEVER);
            var before = evaluateTriples(target, rows);
            var after = evaluateTriples(target, changedRows);
            assertEquals(before, a.value());
            assertEquals(before, b.value());
            assertEquals(after, a.replace(1, changedRows.get(1), EXECUTE, NEVER).value());
            assertEquals(after, b.replace(1, changedRows.get(1), EXECUTE, NEVER).value());
            solved++;
        }
        assertEquals(13, solved);
        System.out.printf("Triple transfer: training work=%d, acquisition work=%d, cold=%d, warm=%d, verified=%d/%d%n",
            work(example), acquisition.work(), cold, warm, solved, heldOut.size());
        assertTrue(warm < cold, "Reuse should save work across the recorded stream, including acquisition");
    }

    @Test
    void learnedProposalStillPaysForBothOriginalChecks() {
        var seed = source("(p1-p0)*(x0-x1)^2", 2);
        var example = OrderedReductionSearch.searchPointUpdates(seed, SEARCH, 4, NEVER).verified().orElseThrow();
        var knowledge = OrderedReductionKnowledge.learn(example, SEARCH, NEVER).knowledge().orElseThrow();
        var target = source("(x0-x2)^2*p1", 3);
        var ordinary = OrderedReductionSearch.searchPointUpdates(target, SEARCH, 4, NEVER);
        var reusable = OrderedReductionSearch.searchPointUpdates(target, knowledge, SEARCH, 4, NEVER);
        assertTrue(reusable.verified().isPresent());
        assertTrue(reusable.metrics().primitiveWork() < ordinary.metrics().primitiveWork());
        assertEquals(ordinary.metrics().verificationWork(), reusable.metrics().verificationWork());
    }

    @Test
    void learnedLemmaHonorsAnExhaustedTermBudget() {
        var training = OrderedReductionSearch.searchPointUpdates(source("(p1-p0)*(x0-x1)^2", 2),
            SEARCH, 4, NEVER).verified().orElseThrow();
        var knowledge = OrderedReductionKnowledge.learn(training, SEARCH, NEVER).knowledge().orElseThrow();
        var target = source("(x0-x2)^2*p1", 3);
        var tight = new IterationLimits(100_000, 2, 10_000);
        assertEquals("TERM_LIMIT", OrderedReductionSearch.searchPointUpdates(target, knowledge, tight, 4, NEVER).reason());
        assertTrue(OrderedReductionKnowledge.learn(training, SEARCH, () -> true).knowledge().isEmpty());
    }

    private static long work(OrderedReductionSearch.PointUpdateResult result) {
        return result.metrics().totalWork();
    }

    private static BigInteger evaluateTriples(OrderedReduction input, List<List<BigInteger>> rows) {
        BigInteger total = BigInteger.ZERO;
        for (int i = 0; i < rows.size(); i++) {
            for (int j = i + 1; j < rows.size(); j++) {
                for (int k = j + 1; k < rows.size(); k++) {
                    var bindings = new HashMap<SymbolId, BigInteger>();
                    int[] positions = {i, j, k};
                    for (int slot = 0; slot < positions.length; slot++) {
                        for (int column = 0; column < input.columnCount(); column++) {
                            bindings.put(input.slots().get(slot).get(column), rows.get(positions[slot]).get(column));
                        }
                    }
                    total = total.add(evaluate(input.summand(), bindings));
                }
            }
        }
        return total;
    }

    private static BigInteger evaluate(Expr expression, Map<SymbolId, BigInteger> bindings) {
        return switch (expression) {
            case NumberExpr number -> number.value().numerator();
            case VariableExpr variable -> bindings.get(variable.symbol().orElseThrow());
            case BinaryExpr binary -> {
                var left = evaluate(binary.left(), bindings);
                var right = evaluate(binary.right(), bindings);
                yield switch (binary.operator()) {
                    case ADD -> left.add(right);
                    case SUB -> left.subtract(right);
                    case MUL -> left.multiply(right);
                    case POW -> left.pow(right.intValueExact());
                    default -> throw new AssertionError(binary.operator());
                };
            }
            default -> throw new AssertionError(expression);
        };
    }
}
