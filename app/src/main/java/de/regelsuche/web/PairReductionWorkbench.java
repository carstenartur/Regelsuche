package de.regelsuche.web;

import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.FunctionExpr;
import de.regelsuche.ast.NumberExpr;
import de.regelsuche.ast.VariableExpr;
import de.regelsuche.ast.iteration.PairReduction;
import de.regelsuche.json.JsonWriter;
import de.regelsuche.math.algorithms.iteration.IterationLimits;
import de.regelsuche.math.algorithms.iteration.PairReductionPlan;
import de.regelsuche.math.algorithms.iteration.PairReductionSearch;
import de.regelsuche.symbol.SymbolId;
import de.regelsuche.symbol.SymbolScope;
import de.regelsuche.symbol.SymbolicExpression;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** Workbench adapter for the exact mathematical source, certificate and execution. */
final class PairReductionWorkbench {
    private static final Set<String> FIELDS = Set.of("columns", "summand", "rowCount", "rows");
    private static final IterationLimits LIMITS = new IterationLimits(500_000, 128, 2_000);
    private static final BooleanSupplier NEVER_CANCELLED = () -> false;
    private static final Pattern INTEGER = Pattern.compile("-?[0-9]{1,256}");

    private PairReductionWorkbench() {}

    static String analyze(Map<String, Object> input) {
        if (!FIELDS.containsAll(input.keySet()) || !(input.get("summand") instanceof String summand)
                || summand.isBlank() || summand.length() > 4096) {
            throw new IllegalArgumentException("provide columns, summand, rowCount and optionally rows");
        }
        var names = columnNames(input.get("columns"));
        int count = rowCount(input.get("rowCount"));
        var sample = input.containsKey("rows") ? sample(input.get("rows"), names.size()) : null;

        var scope = new SymbolScope();
        var columns = names.stream().map(name -> new PairReduction.Column(
            scope.declare(name + "_i"), scope.declare(name + "_j"))).toList();
        var symbolic = SymbolicExpression.parse(summand, scope);
        PairReduction source;
        try {
            source = new PairReduction(columns, symbolic.expression());
        } catch (IllegalArgumentException invalidScope) {
            return unavailable(invalidScope.getMessage());
        }
        var search = PairReductionSearch.search(source, LIMITS, 3, NEVER_CANCELLED);
        if (search.verified().isEmpty()) {
            return unavailable(search.reason());
        }
        var checked = search.verified().orElseThrow();
        var result = new JsonWriter().beginObject().property("status", "VERIFIED")
            .property("rowCount", count).property("originalPairs", PairReduction.originalPairs(count).toString())
            .property("expandedSummand", expansion(checked.expandedSummand(), symbolic.displayNames()))
            .property("synthesisWork", search.metrics().primitiveWork())
            .property("verificationWork", search.metrics().verificationWork())
            .array("checkedRules", array -> search.witness().forEach(step -> array.value(step.ruleId())));
        writePlan(result, checked.plan(), names);
        if (sample != null) {
            try {
                var evaluation = checked.evaluate(sample, LIMITS, NEVER_CANCELLED);
                result.property("sampleResult", evaluation.value().toString()).property("sampleRows", evaluation.rows());
            } catch (IllegalArgumentException exception) {
                result.property("sampleUnavailable", exception.getMessage());
            }
        }
        return result.endObject().toString();
    }

    private static String unavailable(String reason) {
        return new JsonWriter().beginObject().property("status", "NO_PLAN").property("reason", reason)
            .endObject().toString();
    }

    private static List<String> columnNames(Object input) {
        if (!(input instanceof List<?> list) || list.isEmpty() || list.size() > PairReduction.MAX_COLUMNS) {
            throw new IllegalArgumentException("declare one to eight columns");
        }
        var names = new ArrayList<String>();
        for (Object item : list) {
            if (!(item instanceof String name) || name.length() > 24) {
                throw new IllegalArgumentException("invalid column name");
            }
            names.add(SymbolScope.requireName(name));
        }
        if (new HashSet<>(names).size() != names.size()) {
            throw new IllegalArgumentException("column names must be distinct");
        }
        return names;
    }

    private static int rowCount(Object value) {
        if (!(value instanceof Integer || value instanceof Long)
                || ((Number) value).longValue() < 0 || ((Number) value).longValue() > 1_000_000_000) {
            throw new IllegalArgumentException("rowCount must be an integer between 0 and 1000000000");
        }
        return ((Number) value).intValue();
    }

    private static List<List<BigInteger>> sample(Object value, int width) {
        if (!(value instanceof List<?> list) || list.size() > 256) {
            throw new IllegalArgumentException("provide at most 256 sample rows");
        }
        var rows = new ArrayList<List<BigInteger>>();
        for (Object item : list) {
            if (!(item instanceof List<?> row) || row.size() != width) {
                throw new IllegalArgumentException("sample rows must match the number of columns");
            }
            var values = new ArrayList<BigInteger>();
            for (Object cell : row) {
                if (!(cell instanceof String number) || !INTEGER.matcher(number).matches()) {
                    throw new IllegalArgumentException("sample cells must be decimal integer strings");
                }
                values.add(new BigInteger(number));
            }
            rows.add(values);
        }
        return rows;
    }

    private static String expansion(Expr expression, Map<SymbolId, String> names) {
        var text = new StringBuilder();
        appendExpansion(expression, names, text);
        return text.toString().replace(" + -", " - ");
    }

    // Normal form is a sum of products with integer leaves. Presentation may
    // include larger coefficients than the textual input grammar accepts.
    private static void appendExpansion(Expr expression, Map<SymbolId, String> names, StringBuilder text) {
        switch (expression) {
            case NumberExpr number -> text.append(number.value().numerator());
            case VariableExpr variable -> text.append(names.get(variable.symbol().orElseThrow()));
            case BinaryExpr binary -> {
                appendExpansion(binary.left(), names, text);
                text.append(' ').append(binary.operator().symbol()).append(' ');
                appendExpansion(binary.right(), names, text);
            }
            case FunctionExpr ignored -> throw new IllegalStateException("unexpected function in polynomial normal form");
        }
    }

    private static void writePlan(JsonWriter result, PairReductionPlan plan, List<String> names) {
        var labels = new LinkedHashMap<PairReductionPlan.Moment, String>();
        labels.put(new PairReductionPlan.Moment(List.of()), "n");
        for (var moment : plan.accumulators()) {
            labels.put(moment, "S" + labels.size());
        }
        result.array("accumulators", array -> plan.accumulators().forEach(moment -> {
            String product = moment.columns().stream().map(names::get).collect(Collectors.joining(" · "));
            array.objectValue(item -> item.property("name", labels.get(moment)).property("rowProduct", product));
        }));
        var formula = new StringBuilder();
        for (var term : plan.terms()) {
            var coefficient = term.coefficient();
            if (formula.isEmpty()) {
                if (coefficient.signum() < 0) formula.append('−');
            } else {
                formula.append(coefficient.signum() < 0 ? " − " : " + ");
            }
            if (!coefficient.abs().equals(BigInteger.ONE)) {
                formula.append(coefficient.abs()).append(" · ");
            }
            formula.append(term.factors().stream().map(labels::get).collect(Collectors.joining(" · ")));
        }
        String numerator = formula.isEmpty() ? "0" : formula.toString();
        result.property("formula", plan.divisor() == 1 ? numerator : "(" + numerator + ") / 2");
    }
}
