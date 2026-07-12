package de.regelsuche.moves.hypothesis;

import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.BinaryOperator;
import de.regelsuche.ast.Equation;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.FunctionExpr;
import de.regelsuche.moves.enumerate.TreePosition;
import de.regelsuche.value.ExprValue;
import de.regelsuche.value.ExprValueFactory;
import de.regelsuche.value.ExprValueProjection;
import de.regelsuche.value.ValueKey;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Collects every concrete syntax occurrence and links it to a shared immutable
 * mathematical value while preserving the existing {@link TermOccurrence} view.
 */
public final class TermOccurrenceIndex implements AutoCloseable {

    private final List<TermOccurrence> occurrences;
    private final Map<String, Integer> countsByCanonical;
    private final List<ExpressionOccurrence> valueOccurrences;
    private final Map<OccurrenceId, ExpressionOccurrence> occurrencesById;
    private final Map<ValueKey, List<ExpressionOccurrence>> occurrencesByValue;
    private final ExprValueFactory valueFactory;

    private TermOccurrenceIndex(
            List<ExpressionOccurrence> rawValueOccurrences,
            ExprValueFactory valueFactory) {
        this.valueFactory = Objects.requireNonNull(valueFactory, "valueFactory");

        List<ExpressionOccurrence> typed = new ArrayList<>(rawValueOccurrences);
        typed.sort(ExpressionOccurrence.CANONICAL_ORDER);
        this.valueOccurrences = List.copyOf(typed);

        Map<OccurrenceId, ExpressionOccurrence> byId = new LinkedHashMap<>();
        Map<ValueKey, List<ExpressionOccurrence>> byValue = new LinkedHashMap<>();
        List<TermOccurrence> rawCompatibilityOccurrences = new ArrayList<>(typed.size());
        for (ExpressionOccurrence occurrence : typed) {
            if (byId.put(occurrence.id(), occurrence) != null) {
                throw new IllegalArgumentException("duplicate occurrence id: " + occurrence.id());
            }
            byValue.computeIfAbsent(occurrence.valueKey(), ignored -> new ArrayList<>()).add(occurrence);
            String formatted = occurrence.position().text();
            rawCompatibilityOccurrences.add(new TermOccurrence(
                    formatted,
                    formatted,
                    occurrence.depth(),
                    1,
                    occurrence.parentOperator(),
                    occurrence.role(),
                    legacyPath(occurrence.id())));
        }
        this.occurrencesById = Map.copyOf(byId);

        Map<ValueKey, List<ExpressionOccurrence>> immutableByValue = new LinkedHashMap<>();
        for (Map.Entry<ValueKey, List<ExpressionOccurrence>> entry : byValue.entrySet()) {
            immutableByValue.put(entry.getKey(), List.copyOf(entry.getValue()));
        }
        this.occurrencesByValue = Collections.unmodifiableMap(immutableByValue);

        Map<String, Integer> counts = new LinkedHashMap<>();
        for (TermOccurrence occurrence : rawCompatibilityOccurrences) {
            counts.merge(occurrence.canonicalValue(), 1, Integer::sum);
        }
        List<TermOccurrence> enriched = new ArrayList<>(rawCompatibilityOccurrences.size());
        for (TermOccurrence occurrence : rawCompatibilityOccurrences) {
            enriched.add(occurrence.withOccurrenceCount(counts.get(occurrence.canonicalValue())));
        }
        enriched.sort(TermOccurrence.CANONICAL_ORDER);
        this.occurrences = List.copyOf(enriched);
        this.countsByCanonical = Map.copyOf(counts);
    }

    /** Builds an index over one syntax root and one bounded value-factory scope. */
    public static TermOccurrenceIndex forExpression(Expr root) {
        Objects.requireNonNull(root, "root");
        ExprValueFactory factory = new ExprValueFactory();
        try {
            ExprValueProjection projection = factory.project(root);
            List<ExpressionOccurrence> raw = new ArrayList<>();
            collect(
                    root,
                    projection,
                    OccurrenceId.EXPRESSION_ROOT,
                    TermRole.ROOT,
                    "",
                    0,
                    List.of(),
                    raw);
            return new TermOccurrenceIndex(raw, factory);
        } catch (RuntimeException exception) {
            factory.close();
            throw exception;
        }
    }

    /** Builds one index over both equation sides, sharing one bounded value scope. */
    public static TermOccurrenceIndex forEquation(Equation equation) {
        Objects.requireNonNull(equation, "equation");
        ExprValueFactory factory = new ExprValueFactory();
        try {
            ExprValueProjection left = factory.project(equation.left());
            ExprValueProjection right = factory.project(equation.right());
            List<ExpressionOccurrence> raw = new ArrayList<>();
            collect(
                    equation.left(),
                    left,
                    OccurrenceId.EQUATION_LEFT_ROOT,
                    TermRole.EQUATION_SIDE,
                    "=",
                    0,
                    List.of(),
                    raw);
            collect(
                    equation.right(),
                    right,
                    OccurrenceId.EQUATION_RIGHT_ROOT,
                    TermRole.EQUATION_SIDE,
                    "=",
                    0,
                    List.of(),
                    raw);
            return new TermOccurrenceIndex(raw, factory);
        } catch (RuntimeException exception) {
            factory.close();
            throw exception;
        }
    }

    /** Existing formatted compatibility view, deterministically ordered. */
    public List<TermOccurrence> occurrences() {
        return occurrences;
    }

    /** Concrete occurrence-to-value view, deterministically ordered. */
    public List<ExpressionOccurrence> valueOccurrences() {
        return valueOccurrences;
    }

    public Optional<ExpressionOccurrence> occurrence(OccurrenceId id) {
        return Optional.ofNullable(occurrencesById.get(Objects.requireNonNull(id, "id")));
    }

    public List<ExpressionOccurrence> occurrencesOf(ExprValue value) {
        return occurrencesOf(Objects.requireNonNull(value, "value").key());
    }

    public List<ExpressionOccurrence> occurrencesOf(ValueKey key) {
        return occurrencesByValue.getOrDefault(Objects.requireNonNull(key, "key"), List.of());
    }

    public int occurrenceCount(ValueKey key) {
        return occurrencesOf(key).size();
    }

    /** One concrete occurrence per mathematical value. */
    public List<ExpressionOccurrence> distinctByValue() {
        return occurrencesByValue.values().stream().map(List::getFirst).toList();
    }

    /** Existing formatted-string distinctness view. */
    public List<TermOccurrence> distinctByCanonical() {
        Map<String, TermOccurrence> distinct = new LinkedHashMap<>();
        for (TermOccurrence occurrence : occurrences) {
            distinct.putIfAbsent(occurrence.canonicalValue(), occurrence);
        }
        return List.copyOf(distinct.values());
    }

    /** Existing repeated-composite compatibility view. */
    public List<TermOccurrence> repeatedComposites() {
        List<TermOccurrence> result = new ArrayList<>();
        for (TermOccurrence occurrence : distinctByCanonical()) {
            if (occurrence.occurrenceCount() >= 2 && occurrence.isComposite()) {
                result.add(occurrence);
            }
        }
        return List.copyOf(result);
    }

    /** Existing formatted-string count (0 when absent). */
    public int occurrenceCount(String canonicalValue) {
        return countsByCanonical.getOrDefault(canonicalValue, 0);
    }

    /** Existing formatted-string membership check. */
    public boolean contains(String canonicalValue) {
        return countsByCanonical.containsKey(canonicalValue);
    }

    @Override
    public void close() {
        valueFactory.close();
    }

    private static void collect(
            Expr expr,
            ExprValueProjection projection,
            String root,
            TermRole role,
            String parentOperator,
            int depth,
            List<Integer> path,
            List<ExpressionOccurrence> out) {
        String formatted = HypothesisExpressions.format(expr);
        ExprValue value = projection.valueOf(expr).orElseThrow(
                () -> new IllegalStateException("syntax occurrence is missing from value projection"));
        OccurrenceId id = new OccurrenceId(root, path);
        out.add(new ExpressionOccurrence(
                id,
                new TreePosition(path, formatted),
                expr,
                value,
                depth,
                parentOperator,
                role));

        if (expr instanceof BinaryExpr binary) {
            BinaryOperator operator = binary.operator();
            collect(
                    binary.left(),
                    projection,
                    root,
                    leftRole(operator),
                    operator.symbol(),
                    depth + 1,
                    child(path, 0),
                    out);
            collect(
                    binary.right(),
                    projection,
                    root,
                    rightRole(operator),
                    operator.symbol(),
                    depth + 1,
                    child(path, 1),
                    out);
        } else if (expr instanceof FunctionExpr function) {
            String functionOperator = "fn:" + function.name();
            for (int i = 0; i < function.arguments().size(); i++) {
                collect(
                        function.arguments().get(i),
                        projection,
                        root,
                        TermRole.ARGUMENT,
                        functionOperator,
                        depth + 1,
                        child(path, i),
                        out);
            }
        }
    }

    private static TermRole leftRole(BinaryOperator operator) {
        return switch (operator) {
            case ADD, SUB -> TermRole.SUMMAND;
            case MUL, DIV -> TermRole.FACTOR;
            case POW -> TermRole.EXPONENT_BASE;
        };
    }

    private static TermRole rightRole(BinaryOperator operator) {
        return switch (operator) {
            case ADD, SUB -> TermRole.SUMMAND;
            case MUL, DIV -> TermRole.FACTOR;
            case POW -> TermRole.EXPONENT;
        };
    }

    private static List<Integer> child(List<Integer> path, int index) {
        List<Integer> child = new ArrayList<>(path.size() + 1);
        child.addAll(path);
        child.add(index);
        return List.copyOf(child);
    }

    private static String legacyPath(OccurrenceId id) {
        String path = id.path().stream()
                .map(String::valueOf)
                .reduce((left, right) -> left + "." + right)
                .orElse("");
        if (OccurrenceId.EQUATION_LEFT_ROOT.equals(id.root())) {
            return path.isEmpty() ? "L" : "L." + path;
        }
        if (OccurrenceId.EQUATION_RIGHT_ROOT.equals(id.root())) {
            return path.isEmpty() ? "R" : "R." + path;
        }
        return path;
    }
}
