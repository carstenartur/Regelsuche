package de.regelsuche.evolution;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.BinaryOperator;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.NumberExpr;
import de.regelsuche.ast.VariableExpr;
import de.regelsuche.math.algorithms.equivalence.ExactPolynomialAnalysis;
import de.regelsuche.scalar.ExactRational;
import de.regelsuche.transform.PatternExpr;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** Shared bounded representation checks. None of the public JSON is an authority. */
final class CheckedSchemaSupport {
    static final ObjectMapper JSON = new ObjectMapper(JsonFactory.builder()
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(140)
            .maxStringLength(262_144).maxNameLength(128).maxNumberLength(20).build()).build())
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    static final int MAXIMUM_JSON_CHARACTERS = 1_048_576;

    private CheckedSchemaSupport() {}

    static final class Work {
        long units;
        void add(long count) { units = Math.addExact(units, count); }
    }

    static JsonNode read(String value) {
        if (value == null || value.isEmpty() || value.length() > MAXIMUM_JSON_CHARACTERS) {
            throw new IllegalArgumentException("checked schema JSON size limit");
        }
        try { return JSON.readTree(value); }
        catch (JsonProcessingException exception) { throw new IllegalArgumentException("invalid checked schema JSON", exception); }
    }

    static String write(JsonNode value) {
        try { return JSON.writeValueAsString(value); }
        catch (JsonProcessingException exception) { throw new IllegalArgumentException("cannot encode checked schema", exception); }
    }

    static void fields(JsonNode value, String... expected) {
        if (value == null || !value.isObject()) throw new IllegalArgumentException("checked schema object required");
        var found = new HashSet<String>();
        value.fieldNames().forEachRemaining(found::add);
        if (!found.equals(Set.of(expected))) throw new IllegalArgumentException("unknown or missing checked schema fields");
    }

    static String text(JsonNode value, String name) {
        JsonNode field = value.get(name);
        if (field == null || !field.isTextual() || field.textValue().length() > 262_144) {
            throw new IllegalArgumentException("invalid checked schema text: " + name);
        }
        return field.textValue();
    }

    static long number(JsonNode value, String name) {
        JsonNode field = value.get(name);
        if (field == null || !field.isIntegralNumber() || !field.canConvertToLong() || field.longValue() < 0) {
            throw new IllegalArgumentException("invalid checked schema count: " + name);
        }
        return field.longValue();
    }

    static void array(JsonNode value, int maximum) {
        if (value == null || !value.isArray() || value.size() > maximum) {
            throw new IllegalArgumentException("checked schema array limit");
        }
    }

    static void hash(String value) {
        if (value == null || !value.matches("sha256:[0-9a-f]{64}")) {
            throw new IllegalArgumentException("checked schema inventory/proof hash required");
        }
    }

    static ArrayNode pattern(PatternExpr value) {
        var node = JSON.createArrayNode();
        switch (value) {
            case PatternExpr.Placeholder placeholder -> node.add("P").add(placeholder.name());
            case PatternExpr.LiteralNumber number -> node.add("N").add(number.value().canonicalText());
            case PatternExpr.Operation operation -> {
                node.add(operation.operator().name());
                node.add(pattern(operation.left()));
                node.add(pattern(operation.right()));
            }
            default -> throw new IllegalArgumentException("schema must use scalar polynomial placeholders");
        }
        return node;
    }

    static PatternExpr pattern(JsonNode value, CheckedLearnedSchemaModel.Bounds bounds, Work work) {
        return pattern(value, bounds, work, 0, new int[1]);
    }

    private static PatternExpr pattern(JsonNode value, CheckedLearnedSchemaModel.Bounds bounds, Work work,
            int depth, int[] nodes) {
        work.add(1);
        if (++nodes[0] > bounds.maximumPatternNodes() || depth > bounds.maximumDepth()) {
            throw new IllegalArgumentException("checked pattern structure limit");
        }
        array(value, 3);
        if (value.isEmpty() || !value.get(0).isTextual()) throw new IllegalArgumentException("invalid checked pattern tag");
        String tag = value.get(0).textValue();
        if ((tag.equals("P") || tag.equals("N")) && value.size() == 2 && value.get(1).isTextual()) {
            String leaf = value.get(1).textValue();
            if (leaf.length() > 256) throw new IllegalArgumentException("checked pattern leaf limit");
            if (tag.equals("P")) {
                if (!leaf.matches("P[0-9]{1,2}")) throw new IllegalArgumentException("noncanonical schema placeholder");
                return PatternExpr.var(leaf);
            }
            return PatternExpr.num(leaf);
        }
        if (value.size() != 3) throw new IllegalArgumentException("invalid checked pattern operation");
        return PatternExpr.op(BinaryOperator.valueOf(tag), pattern(value.get(1), bounds, work, depth + 1, nodes),
            pattern(value.get(2), bounds, work, depth + 1, nodes));
    }

    /** Distinct polynomial indeterminates prove the universal statement, never a training substitution. */
    static String prove(PatternExpr source, PatternExpr target, CheckedLearnedSchemaModel.Bounds bounds, Work work) {
        var symbols = new TreeMap<String, Expr>();
        collect(source, symbols, bounds, work);
        var targetSymbols = new TreeMap<String, Expr>();
        collect(target, targetSymbols, bounds, work);
        if (symbols.isEmpty() || symbols.size() > 16 || !symbols.keySet().containsAll(targetSymbols.keySet()) || source.equals(target)) {
            throw new IllegalArgumentException("nontrivial source-bound polynomial schema required");
        }
        int index = 0;
        for (var name : symbols.keySet()) symbols.put(name, new VariableExpr("schemavar" + index++));
        Expr left = source.instantiate(symbols);
        Expr right = target.instantiate(symbols);
        domain(left, bounds, work);
        domain(right, bounds, work);
        String sourceText = polynomialText(left);
        String targetText = polynomialText(right);
        work.add(sourceText.length() + targetText.length());
        new ExactPolynomialAnalysis(work::add).requireEquivalent(sourceText, targetText);
        return CheckedLearnedSchemaModel.CHECKER_REVISION + ":" + sourceText + "=" + targetText;
    }

    private record PatternNode(PatternExpr value, int depth) {}
    private static void collect(PatternExpr root, Map<String, Expr> symbols,
            CheckedLearnedSchemaModel.Bounds bounds, Work work) {
        var pending = new ArrayDeque<PatternNode>();
        pending.push(new PatternNode(root, 0));
        int count = 0;
        while (!pending.isEmpty()) {
            var node = pending.pop();
            work.add(1);
            if (++count > bounds.maximumPatternNodes() || node.depth() > bounds.maximumDepth()) {
                throw new IllegalArgumentException("checked pattern structure limit");
            }
            switch (node.value()) {
                case PatternExpr.Placeholder placeholder -> {
                    if (!placeholder.name().matches("P[0-9]{1,2}")) throw new IllegalArgumentException("noncanonical schema placeholder");
                    symbols.putIfAbsent(placeholder.name(), new VariableExpr("placeholder"));
                }
                case PatternExpr.LiteralNumber number -> literal(number.value(), bounds);
                case PatternExpr.Operation operation -> {
                    pending.push(new PatternNode(operation.right(), node.depth() + 1));
                    pending.push(new PatternNode(operation.left(), node.depth() + 1));
                }
                default -> throw new IllegalArgumentException("schema must use scalar polynomial placeholders");
            }
        }
    }

    private record Node(Expr value, int depth) {}
    /** Total rational polynomial syntax: no functions, variable denominators or variable/negative powers. */
    static void domain(Expr root, CheckedLearnedSchemaModel.Bounds bounds, Work work) {
        var pending = new ArrayDeque<Node>();
        pending.push(new Node(root, 0));
        int count = 0;
        while (!pending.isEmpty()) {
            var node = pending.pop();
            work.add(1);
            if (++count > bounds.maximumExpressionNodes() || node.depth() > bounds.maximumDepth()) {
                throw new IllegalArgumentException("checked scalar polynomial structure limit");
            }
            switch (node.value()) {
                case NumberExpr number -> literal(number.value(), bounds);
                case VariableExpr variable -> {
                    if (variable.name().length() > 128) throw new IllegalArgumentException("checked symbol size limit");
                }
                case BinaryExpr binary -> {
                    if (binary.operator() == BinaryOperator.DIV && (!(binary.right() instanceof NumberExpr number)
                            || number.value().numerator().signum() == 0)) {
                        throw new IllegalArgumentException("checked scalar division needs nonzero literal denominator");
                    }
                    if (binary.operator() == BinaryOperator.POW && (!(binary.right() instanceof NumberExpr number)
                            || !number.value().isInteger() || number.value().numerator().signum() < 0
                            || number.value().numerator().compareTo(java.math.BigInteger.valueOf(bounds.maximumExponent())) > 0)) {
                        throw new IllegalArgumentException("checked scalar power needs bounded nonnegative literal exponent");
                    }
                    pending.push(new Node(binary.right(), node.depth() + 1));
                    pending.push(new Node(binary.left(), node.depth() + 1));
                }
                default -> throw new IllegalArgumentException("outside checked scalar rational polynomial domain");
            }
        }
    }

    private static void literal(ExactRational value, CheckedLearnedSchemaModel.Bounds bounds) {
        if (value.numerator().abs().bitLength() > bounds.maximumCoefficientBits()
                || value.denominator().bitLength() > bounds.maximumCoefficientBits()) {
            throw new IllegalArgumentException("checked rational literal size limit");
        }
    }

    private static String polynomialText(Expr expression) {
        return switch (expression) {
            case NumberExpr number -> "(" + number.value().canonicalText() + ")";
            case VariableExpr variable -> variable.name();
            case BinaryExpr binary -> "(" + polynomialText(binary.left()) + binary.operator().symbol()
                + polynomialText(binary.right()) + ")";
            default -> throw new IllegalArgumentException("not a symbolic polynomial");
        };
    }
}
