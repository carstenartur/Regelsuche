package de.regelsuche.evolution;

import de.regelsuche.retention.RetainedGraph;
import de.regelsuche.retention.RetainedOperation;
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
    static final ObjectMapper JSON = new ObjectMapper(new de.regelsuche.retention.RetainedJson.Factory(JsonFactory.builder()
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(140)
            .maxStringLength(262_144).maxNameLength(128).maxNumberLength(20).build()).build()))
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    static final int MAXIMUM_JSON_CHARACTERS = 1_048_576;

    private CheckedSchemaSupport() {}

    static final class Work implements RetainedGraph.View {
        @Override public void retainedReferences(RetainedGraph.Visitor v){}
        long units;
        void add(long count) { units = Math.addExact(units, count); }
    }

    static JsonNode read(String value) {
        if (value == null || value.isEmpty() || value.length() > MAXIMUM_JSON_CHARACTERS) {
            throw new IllegalArgumentException("checked schema JSON size limit");
        }
        try { return de.regelsuche.retention.RetainedJson.readTree(JSON,value); }
        catch (JsonProcessingException exception) { throw new IllegalArgumentException("invalid checked schema JSON", exception); }
    }

    static String write(JsonNode value) {
        try { return de.regelsuche.retention.RetainedJson.writeString(JSON,value); }
        catch (java.io.IOException exception) { throw new IllegalArgumentException("cannot encode checked schema", exception); }
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

    private record Node(Expr value, int depth) implements RetainedGraph.View {
        @Override public void retainedReferences(RetainedGraph.Visitor visitor){visitor.reference(value);}
    }
    /** Only explicit mathematical-domain guards use this category; observers do not. */
    static final class DomainRejected extends IllegalArgumentException {
        DomainRejected(String reason){super(reason);}
    }
    /** Total rational polynomial syntax: no functions, variable denominators or variable/negative powers. */
    static void domain(Expr root, CheckedLearnedSchemaModel.Bounds bounds, Work work) {
        var pending = new ArrayDeque<Node>();
        pending.push(new Node(root, 0));
        var current = new Node[1];
        var rejection = new String[1];
        // Queue/backing, root wrapper/insertion and actual current/rejection slots.
        var retained=RetainedOperation.retainCompleted(8,root,bounds,work,pending,current,rejection);
        Throwable primary=null;
        try {
            try {
                int count = 0;
                while (!pending.isEmpty()) {
                    var node = current[0] = pending.pop();
                    // The caller owns this visited-work ledger, including a failed prefix.
                    work.add(1);
                    RetainedOperation.work(2);
                    if (++count > bounds.maximumExpressionNodes() || node.depth() > bounds.maximumDepth()) {
                        throw new DomainRejected("checked scalar polynomial structure limit");
                    }
                    switch (node.value()) {
                        case NumberExpr number -> literal(number.value(), bounds);
                        case VariableExpr variable -> {
                            if (variable.name().length() > 128) throw new DomainRejected("checked symbol size limit");
                        }
                        case BinaryExpr binary -> {
                            binaryDomain(binary,bounds);
                            pending.push(new Node(binary.right(), node.depth() + 1));
                            RetainedOperation.work(2);
                            pending.push(new Node(binary.left(), node.depth() + 1));
                            RetainedOperation.work(2);
                            RetainedOperation.checkpoint();
                        }
                        default -> throw new DomainRejected("outside checked scalar rational polynomial domain");
                    }
                }
            } catch (DomainRejected rejected) {
                // A semantic rejection cannot carry a technical failure that a caller discards.
                // Keep it as owned data until observation and release have both succeeded.
                rejection[0]=rejected.getMessage();
                RetainedOperation.work(1);
            }
            RetainedOperation.checkpoint();
        } catch (RuntimeException | Error failure) {
            primary=failure;
            try { RetainedOperation.checkpoint(); }
            catch (RuntimeException | Error observation) {
                if (observation != failure) failure.addSuppressed(observation);
            }
            throw failure;
        } finally {
            // A resource observer may throw the same object again on close. Avoid
            // try-with-resources self-suppression replacing the original failure.
            try { if(retained!=null)retained.close(); }
            catch (RuntimeException | Error cleanup) {
                if(primary==null)throw cleanup;
                if(cleanup!=primary)primary.addSuppressed(cleanup);
            }
        }
        if(rejection[0]!=null)throw new DomainRejected(rejection[0]);
    }

    private static void binaryDomain(BinaryExpr binary, CheckedLearnedSchemaModel.Bounds bounds) {
        if (binary.operator() == BinaryOperator.DIV && (!(binary.right() instanceof NumberExpr number)
                || number.value().numerator().signum() == 0)) {
            throw new DomainRejected("checked scalar division needs nonzero literal denominator");
        }
        if (binary.operator() == BinaryOperator.POW) {
            if (!(binary.right() instanceof NumberExpr number)
                    || !number.value().isInteger() || number.value().numerator().signum() < 0) {
                throw new DomainRejected("checked scalar power needs bounded nonnegative literal exponent");
            }
            var maximum = java.math.BigInteger.valueOf(bounds.maximumExponent());
            var retained = RetainedOperation.retainCompleted(1, binary, bounds, maximum);
            Throwable primary = null;
            boolean rejected;
            try {
                rejected = number.value().numerator().compareTo(maximum) > 0;
                RetainedOperation.work(1);
            } catch (RuntimeException | Error failure) {
                primary = failure;
                observeGuardFailure(failure);
                throw failure;
            } finally { closeGuard(retained, primary); }
            if (rejected) throw new DomainRejected("checked scalar power needs bounded nonnegative literal exponent");
        }
    }

    private static void literal(ExactRational value, CheckedLearnedSchemaModel.Bounds bounds) {
        var absolute = value.numerator().abs();
        var retained = RetainedOperation.retainCompleted(1, value, bounds, absolute);
        Throwable primary = null;
        boolean rejected;
        try {
            rejected = absolute.bitLength() > bounds.maximumCoefficientBits();
            RetainedOperation.work(1);
            if (!rejected) {
                rejected = value.denominator().bitLength() > bounds.maximumCoefficientBits();
                RetainedOperation.work(1);
            }
        } catch (RuntimeException | Error failure) {
            primary = failure;
            observeGuardFailure(failure);
            throw failure;
        } finally { closeGuard(retained, primary); }
        // A technical observation/release failure must never become a discarded domain rejection.
        if (rejected) throw new DomainRejected("checked rational literal size limit");
    }

    private static void observeGuardFailure(Throwable failure) {
        try { RetainedOperation.checkpoint(); }
        catch (RuntimeException | Error observation) {
            if (observation != failure) failure.addSuppressed(observation);
        }
    }

    private static void closeGuard(RetainedOperation.Frame retained, Throwable primary) {
        try { if (retained != null) retained.close(); }
        catch (RuntimeException | Error cleanup) {
            if (primary == null) throw cleanup;
            if (cleanup != primary) primary.addSuppressed(cleanup);
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
