package de.regelsuche.math.algorithms.iteration;

import de.regelsuche.ast.iteration.OrderedReduction.Bound;
import de.regelsuche.ast.iteration.OrderedReduction.KeyInterval;
import de.regelsuche.math.algorithms.iteration.OrderedReductionPlan.Lookup;
import java.math.BigInteger;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

import static de.regelsuche.math.algorithms.iteration.IterationPolynomial.Unavailable;
import static de.regelsuche.math.algorithms.iteration.IterationPolynomial.Work;
import static de.regelsuche.math.algorithms.iteration.RowMoments.bounded;

/** Exact scalar, point, and interval aggregates with shared sorted key coordinates. */
final class OrderedSummaries {
    private static final int MAX_INDEX_CELLS = 4_000_000;

    private final List<BigInteger[]> rows;
    private final Work work;
    private final Map<Integer, List<BigInteger>> coordinates = new HashMap<>();
    private int cells;
    private long accesses;

    OrderedSummaries(List<BigInteger[]> rows, Work work) {
        this.rows = rows;
        this.work = work;
    }

    long accesses() { return accesses; }

    Summary create(Lookup lookup) {
        return switch (lookup.access()) {
            case SCALAR -> new Scalar();
            case HASH -> new Point((KeyInterval) lookup.restriction());
            case RANGE -> {
                var interval = (KeyInterval) lookup.restriction();
                var keys = coordinates.computeIfAbsent(interval.leftColumn(), this::sortedKeys);
                reserve(keys.size() + 1);
                yield new Range(interval, keys);
            }
        };
    }

    private List<BigInteger> sortedKeys(int column) {
        var keys = new TreeSet<BigInteger>((left, right) -> {
            work.charge(1);
            return left.compareTo(right);
        });
        for (var row : rows) {
            work.charge(1);
            keys.add(row[column]);
            if (keys.size() >= MAX_INDEX_CELLS - cells) throw new Unavailable("INDEX_CAPACITY_LIMIT");
        }
        work.charge(keys.size());
        return List.copyOf(keys);
    }

    private void reserve(int needed) {
        if (needed > MAX_INDEX_CELLS - cells) throw new Unavailable("INDEX_CAPACITY_LIMIT");
        work.charge(needed);
        cells += needed;
    }

    interface Summary {
        void add(BigInteger[] row, BigInteger value);
        BigInteger query(BigInteger[] row);
    }

    private class Scalar implements Summary {
        BigInteger total = BigInteger.ZERO;

        @Override public void add(BigInteger[] row, BigInteger value) {
            total = bounded(total.add(value), work);
        }

        @Override public BigInteger query(BigInteger[] row) {
            work.charge(1);
            return total;
        }
    }

    private final class Point extends Scalar {
        private final KeyInterval interval;
        private final Map<BigInteger, BigInteger> byKey = new HashMap<>();

        Point(KeyInterval interval) {
            this.interval = interval;
        }

        @Override public void add(BigInteger[] row, BigInteger value) {
            work.charge(1);
            if (value.signum() == 0) return;
            if (interval.complement()) super.add(row, value);
            var key = row[interval.leftColumn()];
            var previous = byKey.get(key);
            var sum = bounded(previous == null ? value : previous.add(value), work);
            if (sum.signum() == 0) {
                byKey.remove(key);
                cells--;
            } else {
                if (previous == null) reserve(1);
                byKey.put(key, sum);
            }
        }

        @Override public BigInteger query(BigInteger[] row) {
            var key = bound(row[interval.rightColumn()], interval.lower().orElseThrow());
            var exact = byKey.getOrDefault(key, BigInteger.ZERO);
            return interval.complement() ? bounded(total.subtract(exact), work) : exact;
        }
    }

    private final class Range extends Scalar {
        private final KeyInterval interval;
        private final List<BigInteger> keys;
        private final BigInteger[] tree;

        Range(KeyInterval interval, List<BigInteger> keys) {
            this.interval = interval;
            this.keys = keys;
            tree = new BigInteger[keys.size() + 1];
        }

        @Override public void add(BigInteger[] row, BigInteger value) {
            work.charge(1);
            if (value.signum() == 0) return;
            if (interval.complement()) super.add(row, value);
            int start = rank(row[interval.leftColumn()], false) + 1;
            for (int index = start; index < tree.length; index += index & -index) {
                var next = bounded(cell(index).add(value), work);
                tree[index] = next.signum() == 0 ? null : next;
            }
        }

        @Override public BigInteger query(BigInteger[] row) {
            work.charge(1);
            var key = row[interval.rightColumn()];
            int from = interval.lower().map(endpoint -> rank(bound(key, endpoint), !endpoint.inclusive())).orElse(0);
            int to = interval.upper().map(endpoint -> rank(bound(key, endpoint), endpoint.inclusive())).orElse(keys.size());
            var inside = from < to ? bounded(prefix(to).subtract(prefix(from)), work) : BigInteger.ZERO;
            return interval.complement() ? bounded(total.subtract(inside), work) : inside;
        }

        private BigInteger prefix(int end) {
            BigInteger total = BigInteger.ZERO;
            for (int index = end; index > 0; index -= index & -index) {
                total = bounded(total.add(cell(index)), work);
            }
            return total;
        }

        private BigInteger cell(int index) {
            accesses++;
            var value = tree[index];
            return value == null ? BigInteger.ZERO : value;
        }

        private int rank(BigInteger target, boolean includeEqual) {
            int low = 0, high = keys.size();
            while (low < high) {
                work.charge(1);
                int mid = low + (high - low) / 2;
                int comparison = keys.get(mid).compareTo(target);
                if (comparison < 0 || (includeEqual && comparison == 0)) low = mid + 1;
                else high = mid;
            }
            return low;
        }
    }

    private BigInteger bound(BigInteger key, Bound endpoint) {
        return bounded(key.add(endpoint.offset()), work);
    }
}
