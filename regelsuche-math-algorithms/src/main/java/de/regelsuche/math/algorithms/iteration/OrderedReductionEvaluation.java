package de.regelsuche.math.algorithms.iteration;

import de.regelsuche.ast.iteration.OrderedReduction;
import de.regelsuche.ast.iteration.PairReduction;
import de.regelsuche.math.algorithms.iteration.OrderedReductionPlan.Evaluation;
import de.regelsuche.math.algorithms.iteration.OrderedReductionPlan.Lookup;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

import static de.regelsuche.math.algorithms.iteration.IterationPolynomial.Unavailable;
import static de.regelsuche.math.algorithms.iteration.IterationPolynomial.Work;
import static de.regelsuche.math.algorithms.iteration.PairReductionPlan.Moment;
import static de.regelsuche.math.algorithms.iteration.RowMoments.bounded;

/** Fixed evaluation schedules used by verified indexed plans. */
final class OrderedReductionEvaluation {
    private OrderedReductionEvaluation() {}

    static Evaluation evaluate(OrderedReduction source, OrderedReductionPlan plan, List<? extends List<BigInteger>> rows,
            int width, IterationLimits limits, BooleanSupplier cancellation) {
        var work = new Work(limits, cancellation, PairReduction.MAX_DEGREE);
        try {
            work.charge(1);
            if (width > rows.size()) return new Evaluation(List.of(), 0, 0, work.used());
            var data = new ArrayList<BigInteger[]>();
            for (var row : rows) {
                work.charge(source.columnCount());
                data.add(RowMoments.values(row, source.columnCount(), work));
            }
            var indexes = new OrderedSummaries(data, work);
            var sums = width == 0 ? List.of(chains(plan, data, indexes, work))
                : windows(plan, data, width, indexes, work);
            return new Evaluation(sums, data.size(), indexes.accesses(), work.used());
        } catch (Unavailable unavailable) {
            throw new IllegalArgumentException(unavailable.getMessage(), unavailable);
        }
    }

    private static BigInteger chains(OrderedReductionPlan plan, List<BigInteger[]> rows, OrderedSummaries indexes, Work work) {
        var states = new LinkedHashMap<List<Moment>, Prefix>();
        for (var term : plan.terms()) {
            Prefix parent = null;
            for (int size = 1; size <= term.factors().size(); size++) {
                work.charge(1);
                var path = term.factors().subList(0, size);
                var node = states.get(path);
                if (node == null) {
                    var summary = size < term.factors().size() ? indexes.create(plan.lookups().get(size - 1)) : null;
                    node = new Prefix(parent, path.getLast(), summary);
                    states.put(path, node);
                }
                parent = node;
            }
            parent.coefficient = bounded(parent.coefficient.add(term.coefficient()), work);
        }
        var factors = states.values().stream().map(node -> node.factor).distinct().toList();
        BigInteger total = BigInteger.ZERO;
        for (var row : rows) {
            work.charge(1);
            var products = products(row, factors, work);
            // Parents occur before their children. Every lookup sees only earlier rows.
            for (var node : states.values()) {
                node.current = products.get(node.factor);
                if (node.parent != null) {
                    node.current = bounded(node.current.multiply(node.parent.prior), work);
                }
                if (node.summary != null) {
                    node.prior = node.summary.query(row);
                } else {
                    var contribution = bounded(node.coefficient.multiply(node.current), work);
                    total = bounded(total.add(contribution), work);
                }
            }
            for (var node : states.values()) {
                if (node.summary != null) node.summary.add(row, node.current);
            }
        }
        return total;
    }

    private static final class Prefix {
        private final Prefix parent;
        private final Moment factor;
        private final OrderedSummaries.Summary summary;
        private BigInteger coefficient = BigInteger.ZERO;
        private BigInteger current;
        private BigInteger prior;

        Prefix(Prefix parent, Moment factor, OrderedSummaries.Summary summary) {
            this.parent = parent;
            this.factor = factor;
            this.summary = summary;
        }
    }

    private static List<BigInteger> windows(OrderedReductionPlan plan, List<BigInteger[]> rows, int width,
            OrderedSummaries indexes, Work work) {
        var channels = new LinkedHashMap<Channel, OrderedSummaries.Summary>();
        var terms = new ArrayList<WindowTerm>();
        for (var term : plan.terms()) {
            work.charge(1);
            var left = term.factors().getFirst();
            var right = term.factors().getLast();
            var append = channels.computeIfAbsent(new Channel(plan.lookups().getFirst(), left),
                channel -> indexes.create(channel.lookup()));
            var remove = channels.computeIfAbsent(new Channel(plan.eviction().orElseThrow(), right),
                channel -> indexes.create(channel.lookup()));
            terms.add(new WindowTerm(term.coefficient(), left, right, append, remove));
        }
        var factors = channels.keySet().stream().map(Channel::moment).distinct().toList();
        var output = new ArrayList<BigInteger>();
        BigInteger total = BigInteger.ZERO;
        for (int index = 0; index < rows.size(); index++) {
            work.charge(1);
            if (index >= width) {
                var oldest = rows.get(index - width);
                var weights = products(oldest, factors, work);
                channels.forEach((channel, summary) -> summary.add(oldest, weights.get(channel.moment()).negate()));
                total = bounded(total.subtract(contribution(terms, oldest, weights, false, work)), work);
            }
            var newest = rows.get(index);
            var weights = products(newest, factors, work);
            total = bounded(total.add(contribution(terms, newest, weights, true, work)), work);
            channels.forEach((channel, summary) -> summary.add(newest, weights.get(channel.moment())));
            if (index >= width - 1) output.add(total);
        }
        return output;
    }

    private static BigInteger contribution(List<WindowTerm> terms, BigInteger[] row, Map<Moment, BigInteger> products,
            boolean entering, Work work) {
        var matches = new HashMap<OrderedSummaries.Summary, BigInteger>();
        BigInteger total = BigInteger.ZERO;
        for (var term : terms) {
            var summary = entering ? term.append() : term.remove();
            var factor = entering ? term.right() : term.left();
            var sum = matches.computeIfAbsent(summary, index -> index.query(row));
            var scaled = bounded(term.coefficient().multiply(sum), work);
            total = bounded(total.add(bounded(scaled.multiply(products.get(factor)), work)), work);
        }
        return total;
    }

    private static Map<Moment, BigInteger> products(BigInteger[] row, List<Moment> factors, Work work) {
        var products = new HashMap<Moment, BigInteger>();
        for (var factor : factors) products.put(factor, RowMoments.product(factor, row, work));
        return products;
    }

    private record Channel(Lookup lookup, Moment moment) {}
    private record WindowTerm(BigInteger coefficient, Moment left, Moment right,
            OrderedSummaries.Summary append, OrderedSummaries.Summary remove) {}
}
