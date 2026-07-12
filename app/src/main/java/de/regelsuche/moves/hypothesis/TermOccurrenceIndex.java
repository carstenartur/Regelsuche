package de.regelsuche.moves.hypothesis;

import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.BinaryOperator;
import de.regelsuche.ast.Equation;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.FunctionExpr;
import de.regelsuche.moves.enumerate.TreePosition;
import de.regelsuche.value.ExprValueFactory;
import de.regelsuche.value.ExprValueFactory.ExprValue;
import de.regelsuche.value.ExprValueFactory.Projection;
import de.regelsuche.value.ExprValueFactory.ValueKey;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Collects concrete syntax occurrences, links them to shared mathematical values,
 * and preserves the existing formatted {@link TermOccurrence} API.
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
        valueOccurrences = List.copyOf(typed);

        Map<OccurrenceId, ExpressionOccurrence> byId = new LinkedHashMap<>();
        Map<ValueKey, List<ExpressionOccurrence>> byValue = new LinkedHashMap<>();
        List<TermOccurrence> compatibilityOccurrences = new ArrayList<>(typed.size());
        for (ExpressionOccurrence occurrence : typed) {
            if (byId.put(occurrence.id(), occurrence) != null) {
                throw new IllegalArgumentException("duplicate occurrence id: " + occurrence.id());
            }
            byValue.computeIfAbsent(occurrence.valueKey(), ignored -> new ArrayList<>()).add(occurrence);
            String formatted = occurrence.position().text();
            compatibilityOccurrences.add(new TermOccurrence(
                    formatted,
                    formatted,
                    occurrence.depth(),
                    1,
                    occurrence.parentOperator(),
                    occurrence.role(),
                    legacyPath(occurrence.id())));
        }
        occurrencesById = Collections.unmodifiableMap(new LinkedHashMap<>(byId));

        Map<ValueKey, List<ExpressionOccurrence>> immutableByValue = new LinkedHashMap<>();
        byValue.forEach((key, values) -> immutableByValue.put(key, List.copyOf(values)));
        occurrencesByValue = Collections.unmodifiableMap(immutableByValue);

        Map<String, Integer> counts = new LinkedHashMap<>();
        compatibilityOccurrences.forEach(
                occurrence -> counts.merge(occurrence.canonicalValue(), 1, Integer::sum));
        occurrences = compatibilityOccurrences.stream()
                .map(occurrence -> occurrence.withOccurrenceCount(
                        counts.get(occurrence.canonicalValue())))
                .sorted(TermOccurrence.CANONICAL_ORDER)
                .toList();
        countsByCanonical = Map.copyOf(counts);
    }

    /** Builds an index over one syntax root and one bounded value-factory scope. */
    public static TermOccurrenceIndex forExpression(Expr root) {
        Objects.requireNonNull(root, "root");
        ExprValueFactory factory = new ExprValueFactory();
        try {
            Projection projection = factory.project(root);
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
            Projection left = factory.project(equation.left());
            Projection right = factory.project(equation.right());
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

    public boolean contains(ValueKey key) {
        return occurrencesByValue.containsKey(Objects.requireNonNull(key, "key"));
    }

    /** One concrete occurrence per mathematical value. */
    public List<ExpressionOccurrence> distinctByValue() {
        return occurrencesByValue.values().stream().map(List::getFirst).toList();
    }

    /** Existing formatted-string distinctness view. */
    public List<TermOccurrence> distinctByCanonical() {
        Map<String, TermOccurrence> distinct = new LinkedHashMap<>();
        occurrences.forEach(
                occurrence -> distinct.putIfAbsent(occurrence.canonicalValue(), occurrence));
        return List.copyOf(distinct.values());
    }

    /** Existing repeated-composite compatibility view. */
    public List<TermOccurrence> repeatedComposites() {
        return distinctByCanonical().stream()
                .filter(occurrence -> occurrence.occurrenceCount() >= 2)
                .filter(TermOccurrence::isComposite)
                .toList();
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
            Projection projection,
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
                .collect(Collectors.joining("."));
        if (OccurrenceId.EQUATION_LEFT_ROOT.equals(id.root())) {
            return path.isEmpty() ? "L" : "L." + path;
        }
        if (OccurrenceId.EQUATION_RIGHT_ROOT.equals(id.root())) {
            return path.isEmpty() ? "R" : "R." + path;
        }
        return path;
    }

    /** Stable identity of one occurrence inside one owned syntax root. */
    public record OccurrenceId(String root, List<Integer> path) implements Comparable<OccurrenceId> {
        public static final String EXPRESSION_ROOT = "expression";
        public static final String EQUATION_LEFT_ROOT = "equation:L";
        public static final String EQUATION_RIGHT_ROOT = "equation:R";

        public OccurrenceId {
            Objects.requireNonNull(root, "root");
            Objects.requireNonNull(path, "path");
            root = root.trim();
            if (root.isEmpty()) {
                throw new IllegalArgumentException("occurrence root must not be blank");
            }
            path = List.copyOf(path);
            if (path.stream().anyMatch(index -> index == null || index < 0)) {
                throw new IllegalArgumentException("occurrence path indices must be non-negative");
            }
        }

        public static OccurrenceId expression(List<Integer> path) {
            return new OccurrenceId(EXPRESSION_ROOT, path);
        }

        public static OccurrenceId equationSide(String side, List<Integer> path) {
            return switch (Objects.requireNonNull(side, "side")) {
                case "L" -> new OccurrenceId(EQUATION_LEFT_ROOT, path);
                case "R" -> new OccurrenceId(EQUATION_RIGHT_ROOT, path);
                default -> throw new IllegalArgumentException("equation side must be L or R");
            };
        }

        public String externalForm() {
            if (path.isEmpty()) {
                return root + ":root";
            }
            return root + ":" + path.stream()
                    .map(index -> String.format("%03d", index))
                    .collect(Collectors.joining("."));
        }

        @Override
        public int compareTo(OccurrenceId other) {
            int rootComparison = root.compareTo(other.root);
            if (rootComparison != 0) {
                return rootComparison;
            }
            int common = Math.min(path.size(), other.path.size());
            for (int i = 0; i < common; i++) {
                int comparison = Integer.compare(path.get(i), other.path.get(i));
                if (comparison != 0) {
                    return comparison;
                }
            }
            return Integer.compare(path.size(), other.path.size());
        }

        @Override
        public String toString() {
            return externalForm();
        }
    }

    /** One concrete syntax use linked to its shared mathematical value. */
    public record ExpressionOccurrence(
            OccurrenceId id,
            TreePosition position,
            Expr syntax,
            ExprValue value,
            int depth,
            String parentOperator,
            TermRole role) implements Comparable<ExpressionOccurrence> {
        private static final Comparator<ExpressionOccurrence> CANONICAL_ORDER =
                Comparator.comparing(ExpressionOccurrence::id)
                        .thenComparingInt(occurrence -> occurrence.role().ordinal())
                        .thenComparing(occurrence -> occurrence.value().key());

        public ExpressionOccurrence {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(position, "position");
            Objects.requireNonNull(syntax, "syntax");
            Objects.requireNonNull(value, "value");
            Objects.requireNonNull(role, "role");
            parentOperator = parentOperator == null ? "" : parentOperator;
            if (depth < 0 || !id.path().equals(position.path())) {
                throw new IllegalArgumentException("invalid occurrence depth or path");
            }
        }

        public ValueKey valueKey() {
            return value.key();
        }

        @Override
        public boolean equals(Object other) {
            return this == other
                    || other instanceof ExpressionOccurrence occurrence && id.equals(occurrence.id);
        }

        @Override
        public int hashCode() {
            return id.hashCode();
        }

        @Override
        public int compareTo(ExpressionOccurrence other) {
            return CANONICAL_ORDER.compare(this, other);
        }
    }
}
