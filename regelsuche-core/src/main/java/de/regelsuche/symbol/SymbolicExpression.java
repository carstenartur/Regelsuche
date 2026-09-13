package de.regelsuche.symbol;

import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.FunctionExpr;
import de.regelsuche.ast.VariableExpr;
import de.regelsuche.parse.ExactParsedTerm;
import de.regelsuche.parse.ExpressionFormatter;
import de.regelsuche.parse.ExpressionParser;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * Explicit scoped input document. Original parser evidence, identity-bound
 * expression and presentation are separate; this document grants no proof authority.
 */
public final class SymbolicExpression {
    public static final int MAXIMUM_SOURCE_LENGTH = 16_384;
    public static final int MAXIMUM_BINDINGS = 128;
    private static final int MAXIMUM_SYNTAX_MARKERS = 128;
    private static final int MAXIMUM_NODES = 512;
    private static final int MAXIMUM_DEPTH = 128;

    private final ExactParsedTerm original;
    private final Expr expression;
    private final Map<String, SymbolId> sourceBindings;
    private final Map<SymbolId, String> displayNames;
    private final Map<Expr, Expr> originalByScopedOccurrence;

    private SymbolicExpression(ExactParsedTerm original, Expr expression, Map<String, SymbolId> bindings,
            Map<SymbolId, String> labels, Map<Expr, Expr> occurrences) {
        this.original = original;
        this.expression = expression;
        this.sourceBindings = bindings;
        this.displayNames = labels;
        this.originalByScopedOccurrence = occurrences;
    }

    public static SymbolicExpression parse(String source, SymbolScope scope) {
        Objects.requireNonNull(scope, "scope");
        var prepared = prepare(source);
        // All source validation precedes the allocator's atomic batch operation.
        var bindings = scope.resolveAll(prepared.names());
        Map<SymbolId, String> labels = new HashMap<>();
        for (String name : prepared.names()) labels.putIfAbsent(bindings.get(name), name);
        return project(prepared.parsed(), bindings, Map.copyOf(labels));
    }

    /** Restores explicit bindings, without allocating or deriving identity from labels. */
    public static SymbolicExpression fromBindings(String source, Map<String, SymbolId> bindings,
            Map<SymbolId, String> labels) {
        var prepared = prepare(source);
        Objects.requireNonNull(bindings, "bindings");
        if (bindings.size() > MAXIMUM_BINDINGS || !bindings.keySet().equals(new HashSet<>(prepared.names()))
                || bindings.values().stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("source symbol bindings must have exact coverage");
        }
        var copy = Map.copyOf(bindings);
        return project(prepared.parsed(), copy, validateLabels(copy, labels));
    }

    public ExactParsedTerm original() { return original; }
    public Expr expression() { return expression; }
    public Map<String, SymbolId> sourceBindings() { return sourceBindings; }
    public Map<SymbolId, String> displayNames() { return displayNames; }
    public String identityText() { return ExpressionFormatter.format(expression); }
    public String displayText() { return display(expression); }

    /** Formats a result only when every variable retains an explicitly known symbol ID. */
    public String display(Expr result) {
        inspect(result, false);
        Expr visible = map(result, variable -> {
            SymbolId id = variable.symbol().orElseThrow(() -> new IllegalArgumentException("result lost symbol identity"));
            String label = displayNames.get(id);
            if (label == null) throw new IllegalArgumentException("result contains an unknown symbol");
            return new VariableExpr(label);
        }, null);
        return ExpressionFormatter.format(visible);
    }

    public SymbolicExpression withDisplayName(SymbolId symbol, String label) {
        if (!displayNames.containsKey(symbol)) throw new IllegalArgumentException("unknown display symbol");
        var labels = new HashMap<>(displayNames);
        labels.put(symbol, label);
        return withDisplayNames(labels);
    }

    /** Changes presentation atomically, including simultaneous label swaps. */
    public SymbolicExpression withDisplayNames(Map<SymbolId, String> labels) {
        return new SymbolicExpression(original, expression, sourceBindings,
            validateLabels(sourceBindings, labels), originalByScopedOccurrence);
    }

    public Optional<ExactParsedTerm.SourceRange> sourceRangeFor(Expr occurrence) {
        Objects.requireNonNull(occurrence, "occurrence");
        Expr sourceNode = originalByScopedOccurrence.get(occurrence);
        return sourceNode == null ? Optional.empty() : original.sourceRangeFor(sourceNode);
    }

    private static SymbolicExpression project(ExactParsedTerm parsed, Map<String, SymbolId> bindings,
            Map<SymbolId, String> labels) {
        var originals = new IdentityHashMap<Expr, Expr>();
        Expr scoped = map(parsed.expression(), variable -> VariableExpr.scoped(bindings.get(variable.name())), originals);
        return new SymbolicExpression(parsed, scoped, bindings, labels, Collections.unmodifiableMap(originals));
    }

    private static Map<SymbolId, String> validateLabels(Map<String, SymbolId> bindings, Map<SymbolId, String> labels) {
        Objects.requireNonNull(labels, "labels");
        if (labels.size() > MAXIMUM_BINDINGS || !labels.keySet().equals(new HashSet<>(bindings.values()))) {
            throw new IllegalArgumentException("display labels must cover exactly the source symbols");
        }
        Set<String> unique = new HashSet<>();
        for (String label : labels.values()) {
            if (!unique.add(SymbolScope.requireName(label))) throw new IllegalArgumentException("ambiguous display labels");
        }
        return Map.copyOf(labels);
    }

    private static Prepared prepare(String source) {
        Objects.requireNonNull(source, "source");
        if (source.length() > MAXIMUM_SOURCE_LENGTH) throw new IllegalArgumentException("symbolic source is too large");
        int markers = 0;
        for (int i = 0; i < source.length(); i++) {
            char ch = source.charAt(i);
            if (Character.isSurrogate(ch)) throw new IllegalArgumentException("unsupported source character");
            if ("()+-*/^,".indexOf(ch) >= 0 && ++markers > MAXIMUM_SYNTAX_MARKERS) {
                throw new IllegalArgumentException("symbolic source exceeds parser depth/work envelope");
            }
        }
        var parsed = new ExpressionParser().parseExactTerm(source);
        return new Prepared(parsed, inspect(parsed.expression(), true));
    }

    private static List<String> inspect(Expr expression, boolean sourceNames) {
        Objects.requireNonNull(expression, "expression");
        var pending = new ArrayDeque<Visit>();
        pending.push(new Visit(expression, 1));
        var names = new LinkedHashSet<String>();
        int visited = 0;
        while (!pending.isEmpty()) {
            var visit = pending.pop();
            if (++visited > MAXIMUM_NODES || visit.depth() > MAXIMUM_DEPTH) {
                throw new IllegalArgumentException("symbolic expression exceeds node/depth envelope");
            }
            if (visit.node() instanceof VariableExpr variable && sourceNames) {
                names.add(SymbolScope.requireName(variable.name()));
            } else if (visit.node() instanceof BinaryExpr binary) {
                pending.push(new Visit(binary.right(), visit.depth() + 1));
                pending.push(new Visit(binary.left(), visit.depth() + 1));
            } else if (visit.node() instanceof FunctionExpr function) {
                if (sourceNames) SymbolScope.requireName(function.name());
                for (int i = function.arguments().size() - 1; i >= 0; i--) {
                    pending.push(new Visit(function.arguments().get(i), visit.depth() + 1));
                }
            }
        }
        if (names.size() > MAXIMUM_BINDINGS) throw new IllegalArgumentException("too many source symbols");
        return List.copyOf(names);
    }

    private static Expr map(Expr original, Function<VariableExpr, Expr> variables, Map<Expr, Expr> occurrences) {
        Expr mapped = original;
        if (original instanceof VariableExpr variable) {
            mapped = variables.apply(variable);
        } else if (original instanceof BinaryExpr binary) {
            mapped = new BinaryExpr(map(binary.left(), variables, occurrences), binary.operator(),
                map(binary.right(), variables, occurrences));
        } else if (original instanceof FunctionExpr function) {
            mapped = new FunctionExpr(function.name(), function.arguments().stream()
                .map(argument -> map(argument, variables, occurrences)).toList());
        }
        // NumberExpr instances remain the actual original, evidence-bound occurrences.
        if (occurrences != null) occurrences.put(mapped, original);
        return mapped;
    }

    private record Prepared(ExactParsedTerm parsed, List<String> names) { }
    private record Visit(Expr node, int depth) { }
}
