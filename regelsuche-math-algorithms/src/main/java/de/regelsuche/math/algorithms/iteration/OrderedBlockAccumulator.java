package de.regelsuche.math.algorithms.iteration;

import de.regelsuche.ast.iteration.PairReduction;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.function.BooleanSupplier;

import static de.regelsuche.math.algorithms.iteration.IterationPolynomial.Unavailable;
import static de.regelsuche.math.algorithms.iteration.IterationPolynomial.Work;
import static de.regelsuche.math.algorithms.iteration.OrderedBlockReductionPlan.Layout;
import static de.regelsuche.math.algorithms.iteration.OrderedBlockReductionPlan.Split;
import static de.regelsuche.math.algorithms.iteration.PairReductionPlan.Moment;
import static de.regelsuche.math.algorithms.iteration.RowMoments.bounded;

/** A segment tree for a verified ordered reduction. Serialize calls on a shared session. */
public final class OrderedBlockAccumulator {
    private static final long MAX_STATE_CELLS = 8_000_000;

    private record Block(BigInteger value, BigInteger[] statistics) {
        BigInteger cross(Block right, Split split, Work work) {
            return bounded(statistics[split.left()].multiply(right.statistics[split.right()]), work);
        }
    }

    public record Update(BigInteger value, int blockCombinations, long work) {}

    private final int columns;
    private final List<List<Moment>> statistics;
    private final Layout layout;
    private final int size;
    private final int firstLeaf;
    private final Block[] nodes;
    private final long initializationWork;

    OrderedBlockAccumulator(int columns, List<List<Moment>> statistics, Layout layout,
            List<? extends List<BigInteger>> rows, Work work) {
        this.columns = columns;
        this.statistics = statistics;
        this.layout = layout;
        size = rows.size();
        long leaves = size <= 1 ? 1 : 2L * Integer.highestOneBit(size - 1);
        long cells = 2 * leaves * (statistics.size() + 1L);
        if (cells > MAX_STATE_CELLS) throw new Unavailable("BLOCK_STORAGE_LIMIT");
        work.charge(cells);
        firstLeaf = (int) leaves;
        nodes = new Block[2 * firstLeaf];
        int next = firstLeaf;
        for (var row : rows) {
            nodes[next++] = leaf(row, work);
        }
        Arrays.fill(nodes, next, nodes.length, empty());
        for (int i = firstLeaf - 1; i > 0; i--) {
            nodes[i] = merge(nodes[2 * i], nodes[2 * i + 1], work);
        }
        work.charge(0);
        initializationWork = work.used();
    }

    public BigInteger value() { return nodes[1].value(); }
    public long initializationWork() { return initializationWork; }

    /** Replaces one whole row. A rejected or interrupted update leaves the session unchanged. */
    public Update replace(int index, List<BigInteger> row, IterationLimits limits, BooleanSupplier cancellation) {
        Objects.checkIndex(index, size);
        var work = new Work(limits, cancellation, PairReduction.MAX_DEGREE);
        try {
            int position = firstLeaf + index;
            var updated = leaf(row, work);
            var path = new ArrayList<Block>();
            path.add(updated);
            while (position > 1) {
                var sibling = nodes[position ^ 1];
                updated = (position & 1) == 0 ? merge(updated, sibling, work) : merge(sibling, updated, work);
                position >>>= 1;
                path.add(updated);
            }
            work.charge(0);
            position = firstLeaf + index;
            for (var block : path) {
                nodes[position] = block;
                position >>>= 1;
            }
            return new Update(updated.value(), path.size() - 1, work.used());
        } catch (Unavailable unavailable) {
            throw new IllegalArgumentException(unavailable.getMessage(), unavailable);
        }
    }

    private Block empty() {
        var values = new BigInteger[statistics.size()];
        Arrays.fill(values, BigInteger.ZERO);
        return new Block(BigInteger.ZERO, values);
    }

    private Block leaf(List<BigInteger> row, Work work) {
        var inputs = RowMoments.values(row, columns, work);
        var values = new BigInteger[statistics.size()];
        for (int i = 0; i < values.length; i++) {
            work.charge(1);
            var factors = statistics.get(i);
            values[i] = factors.size() == 1 ? RowMoments.product(factors.getFirst(), inputs, work) : BigInteger.ZERO;
        }
        return new Block(BigInteger.ZERO, values);
    }

    private Block merge(Block left, Block right, Work work) {
        var values = new BigInteger[statistics.size()];
        for (int i = 0; i < values.length; i++) {
            var sum = bounded(left.statistics()[i].add(right.statistics()[i]), work);
            for (var split : layout.statistics().get(i)) {
                sum = bounded(sum.add(left.cross(right, split, work)), work);
            }
            values[i] = sum;
        }
        var total = bounded(left.value().add(right.value()), work);
        for (var product : layout.total()) {
            var weighted = bounded(product.coefficient().multiply(left.cross(right, product.split(), work)), work);
            total = bounded(total.add(weighted), work);
        }
        return new Block(total, values);
    }
}
