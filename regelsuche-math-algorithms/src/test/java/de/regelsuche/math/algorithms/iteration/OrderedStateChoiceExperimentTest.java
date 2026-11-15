package de.regelsuche.math.algorithms.iteration;

import de.regelsuche.ast.iteration.OrderedReduction;
import de.regelsuche.ast.iteration.OrderedReduction.All;
import de.regelsuche.ast.iteration.OrderedReduction.Bound;
import de.regelsuche.ast.iteration.OrderedReduction.KeyInterval;
import de.regelsuche.ast.iteration.OrderedReduction.Restriction;
import de.regelsuche.math.algorithms.oracle.StateChoiceOraclePanel;
import de.regelsuche.symbol.SymbolicExpression;
import de.regelsuche.symbol.SymbolScope;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import java.util.stream.IntStream;

import static de.regelsuche.math.algorithms.oracle.StateChoiceOraclePanel.*;
import static org.junit.jupiter.api.Assertions.*;

class OrderedStateChoiceExperimentTest {
    private static final BooleanSupplier NEVER = () -> false;
    private static final BigInteger MODULUS = BigInteger.valueOf(1_000_000_007);

    @Test
    void comparesFixedPointReplacementStreamsAgainstExactIndependentValues() throws IOException {
        var panel = StateChoiceOraclePanel.load();
        var spec = object(panel.protocol().get("orderedReductions"));
        var workloads = new Workloads(
            number(spec, "changes"), number(spec, "initialStep"), number(spec, "initialStart"),
            number(spec, "initialModulus"), number(spec, "changeIndexStep"), number(spec, "changeIndexStart"),
            number(spec, "changeValueStep"), number(spec, "changeValueStart"), number(spec, "changeValueModulus"));
        var limits = new IterationLimits(number(spec, "maxWork"), number(spec, "maxTerms"), number(spec, "timeoutMillis"));
        int states = number(spec, "maxStates");
        var report = new StringBuilder();
        row(report, "case", "rows", "arm", "outcome", "budget", "synthesis", "dispatch", "proof", "build", "query",
            "observations", "total", "lastValue");
        int observed = 0;
        for (var raw : array(spec.get("cases"))) {
            var objective = object(raw);
            String id = (String) objective.get("id");
            String expected = (String) objective.get("expected");
            if ("princeton-three-sum".equals(id)) {
                assertEquals("UNREPRESENTABLE_SOURCE", expected);
                for (var arm : List.of("DIRECT", "SUPPLIED")) {
                    row(report, id, "-", arm, expected, limits.maximumWork(), 0, 0, 0, 0, 0, 0, 0, "-");
                }
                continue;
            }
            var source = source((String) objective.get("summand"), number(objective, "slots"),
                "princeton-inversions".equals(id));
            var statistics = objective.containsKey("statistics")
                ? statistics(objective.get("statistics")) : List.<List<PairReductionPlan.Moment>>of();
            if ("princeton-inversions".equals(id)) {
                var direct = OrderedReductionSearch.searchPointUpdates(source, limits, states, NEVER);
                var supplied = OrderedReductionSearch.searchWithBlockStatistics(source, statistics, limits, states, NEVER);
                assertEquals(expected, direct.reason(), id);
                assertEquals(expected, supplied.reason(), id);
                assertTrue(direct.verified().isEmpty() && supplied.verified().isEmpty(), id);
                retain(report, id, "-", "DIRECT", direct, limits, Execution.empty());
                retain(report, id, "-", "SUPPLIED", supplied, limits, Execution.empty());
                continue;
            }
            for (var size : array(spec.get("rowCounts"))) {
                var direct = OrderedReductionSearch.searchPointUpdates(source, limits, states, NEVER);
                var supplied = OrderedReductionSearch.searchWithBlockStatistics(source, statistics, limits, states, NEVER);
                assertEquals(expected, direct.reason(), id);
                assertEquals(expected, supplied.reason(), id);
                var ordinaryPlan = direct.verified().orElseThrow().plan();
                var suppliedPlan = supplied.verified().orElseThrow().plan();
                assertEquals(ordinaryPlan.reduction(), suppliedPlan.reduction(), id);
                assertEquals(new HashSet<>(ordinaryPlan.statistics()), new HashSet<>(suppliedPlan.statistics()), id);
                assertEquals(direct.metrics().verificationWork(), supplied.metrics().verificationWork(), id);
                int n = ((Number) size).intValue();
                boolean nonnegative = Boolean.TRUE.equals(objective.get("nonnegative"));
                var d = execute(id, n, nonnegative, workloads, direct, limits);
                var s = execute(id, n, nonnegative, workloads, supplied, limits);
                assertEquals(d, s, id + " n=" + n);
                retain(report, id, n, "DIRECT", direct, limits, d);
                retain(report, id, n, "SUPPLIED", supplied, limits, s);
                observed += d.observations();
            }
        }
        assertEquals(102, observed);
        panel.checkOrWrite("ordered-reductions.tsv", report.toString());
    }

    @Test
    void keepsTheModularOutputContractInTheSecondOfficialContestExample() {
        var input = source("x0*x1", 2, false);
        var budget = new IterationLimits(10_000, 64, 10_000);
        var rows = java.util.stream.LongStream.of(141421356, 17320508, 22360679, 244949)
            .mapToObj(x -> List.of(BigInteger.valueOf(x))).toList();
        var statistics = List.of(List.of(new PairReductionPlan.Moment(List.of(0))));
        for (var plan : List.of(OrderedReductionSearch.searchPointUpdates(input, budget, 4, NEVER),
                OrderedReductionSearch.searchWithBlockStatistics(input, statistics, budget, 4, NEVER))) {
            var raw = plan.verified().orElseThrow().newAccumulator(rows, budget, NEVER).value();
            assertTrue(raw.compareTo(MODULUS) > 0);
            assertEquals(BigInteger.valueOf(437235829), returnedValue("atcoder-abc177c", raw));
        }
    }

    private static Execution execute(String id, int n, boolean nonnegative, Workloads data,
            OrderedReductionSearch.PointUpdateResult search, IterationLimits limits) {
        var rows = new ArrayList<>(IntStream.range(0, n).mapToObj(i -> sample(i, data.initialStep(),
            data.initialStart(), data.initialModulus(), nonnegative)).toList());
        long remaining = limits.maximumWork() - search.metrics().totalWork();
        // One initial value read is unmetered by the executor.
        // Charge each external modular reduction separately on the contest task.
        long outputWork = "atcoder-abc177c".equals(id) ? 1 : 0;
        long queryWork = 1 + outputWork;
        assertTrue(remaining > queryWork);
        var tree = search.verified().orElseThrow().newAccumulator(rows, limits.withMaximumWork(remaining - queryWork), NEVER);
        long setup = tree.initializationWork();
        var last = returnedValue(id, tree.value());
        assertEquals(independentValue(id, rows), last, id + " initially");
        for (int i = 0; i < data.changes(); i++) {
            int index = (data.changeIndexStep() * i + data.changeIndexStart()) % n;
            var replacement = sample(i, data.changeValueStep(), data.changeValueStart(), data.changeValueModulus(), nonnegative);
            long allowance = remaining - setup - queryWork - outputWork;
            assertTrue(allowance > 0);
            var update = tree.replace(index, replacement, limits.withMaximumWork(allowance), NEVER);
            queryWork += update.work() + outputWork;
            rows.set(index, replacement);
            last = returnedValue(id, update.value());
            assertEquals(independentValue(id, rows), last, id + " after replacement " + i);
        }
        return new Execution(setup, queryWork, data.changes() + 1, last.toString());
    }

    private static BigInteger returnedValue(String id, BigInteger value) {
        return "atcoder-abc177c".equals(id) ? value.mod(MODULUS) : value;
    }

    private static BigInteger independentValue(String id, List<List<BigInteger>> rows) {
        if ("commons-int-variance".equals(id)) {
            BigInteger sum = BigInteger.ZERO;
            BigInteger squares = BigInteger.ZERO;
            for (var row : rows) {
                var value = row.getFirst();
                sum = sum.add(value);
                squares = squares.add(value.pow(2));
            }
            return squares.multiply(BigInteger.valueOf(rows.size())).subtract(sum.pow(2));
        }
        int degree = switch (id) {
            case "symja-elementary-symmetric-3" -> 3;
            case "atcoder-abc177c" -> 2;
            default -> throw new IllegalArgumentException("Unknown reduction " + id);
        };
        // Independently compute the desired coefficient of the product of (1 + x_i*t).
        var coefficients = new BigInteger[degree + 1];
        java.util.Arrays.fill(coefficients, BigInteger.ZERO);
        coefficients[0] = BigInteger.ONE;
        for (var row : rows) {
            for (int k = degree; k > 0; k--) {
                coefficients[k] = coefficients[k].add(row.getFirst().multiply(coefficients[k - 1]));
            }
        }
        return returnedValue(id, coefficients[degree]);
    }

    private static List<BigInteger> sample(int i, int step, int start, int period, boolean nonnegative) {
        return List.of(BigInteger.valueOf((step * i + start) % period - (nonnegative ? 0 : period / 2)));
    }

    private static OrderedReduction source(String formula, int arity, boolean inversions) {
        var scope = new SymbolScope();
        var slots = IntStream.range(0, arity).mapToObj(i -> List.of(scope.declare("x" + i))).toList();
        List<Restriction> conditions = inversions
            ? List.of(new KeyInterval(0, 0, Optional.of(new Bound(BigInteger.ZERO, false)), Optional.empty(), false))
            : IntStream.range(1, arity).<Restriction>mapToObj(i -> All.ROWS).toList();
        return new OrderedReduction(slots, conditions, SymbolicExpression.parse(formula, scope).expression());
    }

    private static List<List<PairReductionPlan.Moment>> statistics(Object list) {
        return array(list).stream().map(term -> array(term).stream().map(moment ->
            new PairReductionPlan.Moment(array(moment).stream().map(x -> ((Number) x).intValue()).toList())).toList()).toList();
    }

    private static void retain(StringBuilder report, String id, Object n, String arm,
            OrderedReductionSearch.PointUpdateResult search, IterationLimits limits, Execution run) {
        var m = search.metrics();
        long total = m.totalWork() + run.build() + run.query();
        assertTrue(total <= limits.maximumWork(), id + " " + arm);
        row(report, id, n, arm, search.reason(), limits.maximumWork(), m.primitiveWork(), m.searchWork(),
            m.verificationWork(), run.build(), run.query(), run.observations(), total, run.last());
    }

    private record Workloads(int changes, int initialStep, int initialStart, int initialModulus,
            int changeIndexStep, int changeIndexStart, int changeValueStep, int changeValueStart, int changeValueModulus) {}

    private record Execution(long build, long query, int observations, String last) {
        private static Execution empty() { return new Execution(0, 0, 0, "-"); }
    }
}
