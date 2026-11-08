package de.regelsuche.web;

import de.regelsuche.ast.iteration.OrderedReduction;
import de.regelsuche.ast.iteration.OrderedReduction.All;
import de.regelsuche.ast.iteration.OrderedReduction.Bound;
import de.regelsuche.ast.iteration.OrderedReduction.KeyInterval;
import de.regelsuche.ast.iteration.OrderedReduction.Restriction;
import de.regelsuche.json.JsonWriter;
import de.regelsuche.math.algorithms.iteration.IterationLimits;
import de.regelsuche.math.algorithms.iteration.OrderedReductionPlan.Lookup;
import de.regelsuche.math.algorithms.iteration.OrderedReductionSearch;
import de.regelsuche.symbol.SymbolScope;
import de.regelsuche.symbol.SymbolicExpression;
import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/** HTTP presentation of the same checked plans available through the mathematical API. */
final class OrderedReductionWorkbench {
    private static final BooleanSupplier NEVER_CANCELLED = () -> false;
    private static final Pattern INTEGER = Pattern.compile("-?[0-9]{1,256}");
    private static final Set<String> FIELDS = Set.of("left", "right", "lower", "upper",
        "lowerInclusive", "upperInclusive", "complement");

    private OrderedReductionWorkbench() {}

    static String analyze(List<String> names, String summand, int count, List<List<BigInteger>> sample,
            Object conditions, int width, IterationLimits limits) {
        var restrictions = restrictions(conditions, names);
        if (width > 0 && restrictions.size() != 1) {
            throw new IllegalArgumentException("windowWidth requires two ordered row slots");
        }
        var scope = new SymbolScope();
        var slots = IntStream.rangeClosed(0, restrictions.size()).mapToObj(slot ->
            names.stream().map(name -> scope.declare(name + "_" + slot)).toList()).toList();
        var symbolic = SymbolicExpression.parse(summand, scope);
        OrderedReduction source;
        try {
            source = new OrderedReduction(slots, restrictions, symbolic.expression());
        } catch (IllegalArgumentException invalidScope) {
            return PairReductionWorkbench.unavailable(invalidScope.getMessage());
        }
        var search = width > 0 ? OrderedReductionSearch.searchWindows(source, limits, 3, NEVER_CANCELLED)
            : OrderedReductionSearch.search(source, limits, 3, NEVER_CANCELLED);
        if (search.verified().isEmpty()) return PairReductionWorkbench.unavailable(search.reason());

        var checked = search.verified().orElseThrow();
        var result = new JsonWriter().beginObject().property("status", "VERIFIED")
            .property("rowCount", count)
            .property("originalCandidates", candidates(count, width, slots.size()).toString())
            .property("synthesisWork", search.metrics().primitiveWork())
            .property("verificationWork", search.metrics().verificationWork())
            .array("checkedRules", array -> search.witness().forEach(step -> array.value(step.ruleId())))
            .array("lookups", array -> checked.plan().lookups().forEach(lookup ->
                array.objectValue(item -> writeLookup(item, lookup, names))))
            .array("terms", array -> checked.plan().terms().forEach(term -> array.objectValue(item ->
                item.property("coefficient", term.coefficient().toString()).array("factors", factors ->
                    term.factors().forEach(moment -> factors.value(moment.columns().isEmpty() ? "1"
                        : moment.columns().stream().map(names::get).collect(Collectors.joining(" · "))))))));
        checked.plan().eviction().ifPresent(lookup -> result.object("eviction", item -> writeLookup(item, lookup, names)));
        if (sample != null) {
            try {
                var evaluation = width > 0 ? checked.evaluateWindows(sample, width, limits, NEVER_CANCELLED)
                    : checked.evaluate(sample, limits, NEVER_CANCELLED);
                result.array("sampleSums", array -> evaluation.sums().forEach(value -> array.value(value.toString())))
                    .property("sampleRows", evaluation.rowReads()).property("indexAccesses", evaluation.indexAccesses());
            } catch (IllegalArgumentException unavailable) {
                result.property("sampleUnavailable", unavailable.getMessage());
            }
        }
        return result.endObject().toString();
    }

    private static List<Restriction> restrictions(Object conditions, List<String> names) {
        if (!(conditions instanceof List<?> list) || list.isEmpty() || list.size() >= OrderedReduction.MAX_ARITY) {
            throw new IllegalArgumentException("provide one restriction for each pair of adjacent row slots");
        }
        return list.stream().map(value -> restriction(value, names)).toList();
    }

    private static Restriction restriction(Object value, List<String> names) {
        if (!(value instanceof Map<?, ?> item) || !FIELDS.containsAll(item.keySet())) {
            throw new IllegalArgumentException("invalid row restriction");
        }
        if (item.isEmpty()) return All.ROWS;
        return new KeyInterval(column(item.get("left"), names), column(item.get("right"), names),
            bound(item, "lower"), bound(item, "upper"), flag(item, "complement", false));
    }

    private static int column(Object value, List<String> names) {
        if (!(value instanceof String name) || !names.contains(name)) {
            throw new IllegalArgumentException("row restriction uses an unknown column");
        }
        return names.indexOf(name);
    }

    private static Optional<Bound> bound(Map<?, ?> input, String name) {
        if (!input.containsKey(name) && !input.containsKey(name + "Inclusive")) return Optional.empty();
        if (!(input.get(name) instanceof String offset) || !INTEGER.matcher(offset).matches()) {
            throw new IllegalArgumentException("bounds must be decimal integer strings");
        }
        return Optional.of(new Bound(new BigInteger(offset), flag(input, name + "Inclusive", true)));
    }

    private static boolean flag(Map<?, ?> input, String name, boolean defaultValue) {
        if (!input.containsKey(name)) return defaultValue;
        if (input.get(name) instanceof Boolean flag) return flag;
        throw new IllegalArgumentException(name + " must be boolean");
    }

    private static void writeLookup(JsonWriter writer, Lookup lookup, List<String> names) {
        writer.property("access", lookup.access().name());
        if (lookup.restriction() instanceof KeyInterval interval) {
            writer.property("left", names.get(interval.leftColumn())).property("right", names.get(interval.rightColumn()))
                .property("complement", interval.complement());
            interval.lower().ifPresent(bound -> writeBound(writer, "lower", bound));
            interval.upper().ifPresent(bound -> writeBound(writer, "upper", bound));
        }
    }

    private static void writeBound(JsonWriter writer, String name, Bound bound) {
        writer.property(name, bound.offset().toString()).property(name + "Inclusive", bound.inclusive());
    }

    private static BigInteger candidates(int rows, int width, int arity) {
        int block = width == 0 ? rows : width;
        if (block < arity || width > rows) return BigInteger.ZERO;
        BigInteger count = BigInteger.ONE;
        for (int i = 1; i <= arity; i++) {
            count = count.multiply(BigInteger.valueOf(block - i + 1L)).divide(BigInteger.valueOf(i));
        }
        return width == 0 ? count : count.multiply(BigInteger.valueOf(rows - width + 1L));
    }
}
