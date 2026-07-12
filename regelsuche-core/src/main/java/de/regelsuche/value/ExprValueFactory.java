package de.regelsuche.value;

import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.FunctionExpr;
import de.regelsuche.ast.NumberExpr;
import de.regelsuche.ast.VariableExpr;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Bounded owner-scoped interning for immutable mathematical values.
 *
 * <p>All supporting value types are nested deliberately: the API separates value,
 * syntax and occurrence identity without multiplying top-level concepts. Equal
 * values are reference-identical inside one factory; {@link ValueKey} remains the
 * stable identity across scopes and persistence.</p>
 */
public final class ExprValueFactory implements AutoCloseable {
    public static final int DEFAULT_MAXIMUM_ENTRIES = 100_000;

    private final int maximumEntries;
    private final Map<ValueKey, ExprValue> valuesByKey = new HashMap<>();
    private boolean closed;

    public ExprValueFactory() {
        this(DEFAULT_MAXIMUM_ENTRIES);
    }

    public ExprValueFactory(int maximumEntries) {
        if (maximumEntries < 1) {
            throw new IllegalArgumentException("maximumEntries must be positive");
        }
        this.maximumEntries = maximumEntries;
    }

    public synchronized VariableValue variable(String name) {
        return intern(new VariableValue(name), VariableValue.class);
    }

    public synchronized NumberValue number(double value) {
        return intern(new NumberValue(value), NumberValue.class);
    }

    public ExprValue sum(List<? extends ExprValue> operands) {
        return associativeCommutative(ValueOperator.ADD, operands);
    }

    public ExprValue product(List<? extends ExprValue> operands) {
        return associativeCommutative(ValueOperator.MUL, operands);
    }

    public synchronized ExprValue ordered(
            ValueOperator operator,
            List<? extends ExprValue> operands) {
        ensureOpen();
        Objects.requireNonNull(operator, "operator");
        if (operator.laws().supportsUnorderedNaryValue()) {
            return associativeCommutative(operator, operands);
        }
        return intern(new OrderedValue(operator, operands), OrderedValue.class);
    }

    public ExprValue function(String name, List<? extends ExprValue> arguments) {
        Objects.requireNonNull(arguments, "arguments");
        return ordered(ValueOperator.function(name, arguments.size()), arguments);
    }

    public ExprValue fromExpr(Expr expression) {
        Objects.requireNonNull(expression, "expression");
        return fromExprRecursive(expression, null);
    }

    /** Projects one syntax root and keeps links keyed by syntax-object identity. */
    public Projection project(Expr syntaxRoot) {
        Objects.requireNonNull(syntaxRoot, "syntaxRoot");
        IdentityHashMap<Expr, ExprValue> valuesBySyntax = new IdentityHashMap<>();
        ExprValue valueRoot = fromExprRecursive(syntaxRoot, valuesBySyntax);
        return new Projection(syntaxRoot, valueRoot, valuesBySyntax);
    }

    public synchronized Optional<ExprValue> find(ValueKey key) {
        ensureOpen();
        return Optional.ofNullable(valuesByKey.get(Objects.requireNonNull(key, "key")));
    }

    public synchronized int size() {
        ensureOpen();
        return valuesByKey.size();
    }

    public int maximumEntries() {
        return maximumEntries;
    }

    public synchronized void clear() {
        ensureOpen();
        valuesByKey.clear();
    }

    @Override
    public synchronized void close() {
        valuesByKey.clear();
        closed = true;
    }

    private ExprValue fromExprRecursive(
            Expr expression,
            IdentityHashMap<Expr, ExprValue> valuesBySyntax) {
        if (valuesBySyntax != null) {
            ExprValue existing = valuesBySyntax.get(expression);
            if (existing != null) {
                return existing;
            }
        }

        ExprValue value;
        if (expression instanceof VariableExpr variable) {
            value = variable(variable.name());
        } else if (expression instanceof NumberExpr number) {
            value = number(number.value());
        } else if (expression instanceof FunctionExpr function) {
            List<ExprValue> arguments = new ArrayList<>(function.arguments().size());
            for (Expr argument : function.arguments()) {
                arguments.add(fromExprRecursive(argument, valuesBySyntax));
            }
            value = function(function.name(), arguments);
        } else if (expression instanceof BinaryExpr binary) {
            ExprValue left = fromExprRecursive(binary.left(), valuesBySyntax);
            ExprValue right = fromExprRecursive(binary.right(), valuesBySyntax);
            value = switch (binary.operator()) {
                case ADD -> sum(List.of(left, right));
                case SUB -> ordered(ValueOperator.SUB, List.of(left, right));
                case MUL -> product(List.of(left, right));
                case DIV -> ordered(ValueOperator.DIV, List.of(left, right));
                case POW -> ordered(ValueOperator.POW, List.of(left, right));
            };
        } else {
            throw new IllegalArgumentException("unsupported Expr implementation: " + expression.getClass());
        }

        if (valuesBySyntax != null) {
            valuesBySyntax.put(expression, value);
        }
        return value;
    }

    private synchronized ExprValue associativeCommutative(
            ValueOperator operator,
            List<? extends ExprValue> operands) {
        ensureOpen();
        Objects.requireNonNull(operator, "operator");
        Objects.requireNonNull(operands, "operands");
        if (!operator.laws().supportsUnorderedNaryValue()) {
            throw new IllegalArgumentException("operator is not associative and commutative: " + operator.id());
        }
        if (operands.isEmpty()) {
            throw new IllegalArgumentException("AC operation needs at least one operand");
        }

        Map<ExprValue, Integer> multiplicities = new LinkedHashMap<>();
        for (ExprValue operand : operands) {
            ExprValue value = Objects.requireNonNull(operand, "operand");
            if (value instanceof AssociativeCommutativeValue nested
                    && nested.operator().equals(operator)) {
                nested.multiplicities().forEach(
                        (nestedValue, count) -> multiplicities.merge(nestedValue, count, Math::addExact));
            } else {
                multiplicities.merge(value, 1, Math::addExact);
            }
        }

        int count = multiplicities.values().stream().mapToInt(Integer::intValue).sum();
        if (count == 1) {
            return multiplicities.keySet().iterator().next();
        }
        operator.requireArity(count);
        return intern(
                new AssociativeCommutativeValue(operator, multiplicities),
                AssociativeCommutativeValue.class);
    }

    private <T extends ExprValue> T intern(T candidate, Class<T> expectedType) {
        ensureOpen();
        ExprValue existing = valuesByKey.get(candidate.key());
        if (existing != null) {
            return expectedType.cast(existing);
        }
        if (valuesByKey.size() >= maximumEntries) {
            throw new IllegalStateException(
                    "expression value factory capacity exceeded: " + maximumEntries);
        }
        valuesByKey.put(candidate.key(), candidate);
        return candidate;
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("expression value factory is closed");
        }
    }

    /** Mathematical value independent of source position and occurrence identity. */
    public abstract static sealed class ExprValue
            permits VariableValue, NumberValue, OrderedValue, AssociativeCommutativeValue {
        private final ValueKey key;

        private ExprValue(ValueKey key) {
            this.key = Objects.requireNonNull(key, "key");
        }

        public final ValueKey key() {
            return key;
        }

        public final boolean sameValue(ExprValue other) {
            return other != null && key.equals(other.key);
        }

        @Override
        public final boolean equals(Object other) {
            return this == other || other instanceof ExprValue value && key.equals(value.key);
        }

        @Override
        public final int hashCode() {
            return key.hashCode();
        }
    }

    public static final class VariableValue extends ExprValue {
        private final String name;

        private VariableValue(String name) {
            super(ValueKey.variable(requireName(name)));
            this.name = requireName(name);
        }

        public String name() {
            return name;
        }

        private static String requireName(String name) {
            Objects.requireNonNull(name, "name");
            if (name.isBlank()) {
                throw new IllegalArgumentException("variable name must not be blank");
            }
            return name;
        }

        @Override
        public String toString() {
            return name;
        }
    }

    public static final class NumberValue extends ExprValue {
        private final double value;

        private NumberValue(double value) {
            super(ValueKey.number(normalizeZero(value)));
            this.value = normalizeZero(value);
        }

        public double value() {
            return value;
        }

        private static double normalizeZero(double value) {
            return value == 0.0d ? 0.0d : value;
        }

        @Override
        public String toString() {
            return Double.toString(value);
        }
    }

    public static final class OrderedValue extends ExprValue {
        private final ValueOperator operator;
        private final List<ExprValue> operands;

        private OrderedValue(ValueOperator operator, List<? extends ExprValue> operands) {
            this(prepareOrdered(operator, operands));
        }

        private OrderedValue(PreparedOrdered prepared) {
            super(prepared.key());
            operator = prepared.operator();
            operands = prepared.operands();
        }

        public ValueOperator operator() {
            return operator;
        }

        public List<ExprValue> operands() {
            return operands;
        }

        @Override
        public String toString() {
            return operator.id() + operands;
        }
    }

    public static final class AssociativeCommutativeValue extends ExprValue {
        private final ValueOperator operator;
        private final Map<ExprValue, Integer> multiplicities;
        private final int operandCount;

        private AssociativeCommutativeValue(
                ValueOperator operator,
                Map<? extends ExprValue, Integer> multiplicities) {
            this(prepareAssociativeCommutative(operator, multiplicities));
        }

        private AssociativeCommutativeValue(PreparedAssociativeCommutative prepared) {
            super(prepared.key());
            operator = prepared.operator();
            multiplicities = prepared.multiplicities();
            operandCount = prepared.operandCount();
        }

        public ValueOperator operator() {
            return operator;
        }

        /** Unordered value-to-count mapping; iteration order is not semantic. */
        public Map<ExprValue, Integer> multiplicities() {
            return multiplicities;
        }

        public int operandCount() {
            return operandCount;
        }

        public int multiplicityOf(ExprValue value) {
            return multiplicities.getOrDefault(value, 0);
        }

        @Override
        public String toString() {
            return operator.id() + multiplicities;
        }
    }

    public record OperatorLaws(boolean associative, boolean commutative, boolean idempotent) {
        public static final OperatorLaws NONE = new OperatorLaws(false, false, false);
        public static final OperatorLaws ASSOCIATIVE_COMMUTATIVE =
                new OperatorLaws(true, true, false);

        public boolean supportsUnorderedNaryValue() {
            return associative && commutative;
        }
    }

    public record ValueOperator(
            String id,
            int minimumArity,
            int maximumArity,
            OperatorLaws laws) {
        public static final ValueOperator ADD =
                new ValueOperator("add", 2, Integer.MAX_VALUE, OperatorLaws.ASSOCIATIVE_COMMUTATIVE);
        public static final ValueOperator SUB =
                new ValueOperator("sub", 2, 2, OperatorLaws.NONE);
        public static final ValueOperator MUL =
                new ValueOperator("mul", 2, Integer.MAX_VALUE, OperatorLaws.ASSOCIATIVE_COMMUTATIVE);
        public static final ValueOperator DIV =
                new ValueOperator("div", 2, 2, OperatorLaws.NONE);
        public static final ValueOperator POW =
                new ValueOperator("pow", 2, 2, OperatorLaws.NONE);

        public ValueOperator {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(laws, "laws");
            id = id.trim();
            if (id.isEmpty()) {
                throw new IllegalArgumentException("operator id must not be blank");
            }
            if (minimumArity < 0 || maximumArity < minimumArity) {
                throw new IllegalArgumentException("invalid operator arity range");
            }
        }

        public static ValueOperator function(String name, int arity) {
            Objects.requireNonNull(name, "name");
            String normalized = name.trim();
            if (normalized.isEmpty() || arity < 0) {
                throw new IllegalArgumentException("invalid function name or arity");
            }
            return new ValueOperator("fn:" + normalized, arity, arity, OperatorLaws.NONE);
        }

        private String identityToken() {
            int lawBits = (laws.associative() ? 1 : 0)
                    | (laws.commutative() ? 2 : 0)
                    | (laws.idempotent() ? 4 : 0);
            return id + "|" + minimumArity + "|" + maximumArity + "|" + lawBits;
        }

        private void requireArity(int actualArity) {
            if (actualArity < minimumArity || actualArity > maximumArity) {
                throw new IllegalArgumentException(
                        "operator " + id + " does not accept arity " + actualArity);
            }
        }
    }

    /** Versioned structural key, authoritative outside one factory scope. */
    public record ValueKey(String encoded) implements Comparable<ValueKey> {
        public static final String FORMAT_VERSION = "regelsuche.expr-value/v1";
        private static final String PREFIX = FORMAT_VERSION + ":";

        public ValueKey {
            Objects.requireNonNull(encoded, "encoded");
            if (encoded.isEmpty()) {
                throw new IllegalArgumentException("encoded value key must not be empty");
            }
        }

        private static ValueKey variable(String name) {
            return new ValueKey(PREFIX + "V" + segment(name));
        }

        private static ValueKey number(double value) {
            return new ValueKey(
                    PREFIX + "N" + Long.toUnsignedString(Double.doubleToLongBits(value), 16));
        }

        private static ValueKey ordered(ValueOperator operator, List<ExprValue> operands) {
            StringBuilder encoded = new StringBuilder(PREFIX)
                    .append('O')
                    .append(segment(operator.identityToken()))
                    .append(operands.size())
                    .append(':');
            operands.forEach(operand -> encoded.append(segment(operand.key().encoded())));
            return new ValueKey(encoded.toString());
        }

        private static ValueKey associativeCommutative(
                ValueOperator operator,
                Map<ExprValue, Integer> multiplicities) {
            List<Map.Entry<ExprValue, Integer>> entries = new ArrayList<>(multiplicities.entrySet());
            entries.sort(Comparator.comparing(entry -> entry.getKey().key()));
            StringBuilder encoded = new StringBuilder(PREFIX)
                    .append('A')
                    .append(segment(operator.identityToken()))
                    .append(entries.size())
                    .append(':');
            entries.forEach(entry -> encoded.append(entry.getValue())
                    .append('*')
                    .append(segment(entry.getKey().key().encoded())));
            return new ValueKey(encoded.toString());
        }

        private static String segment(String text) {
            Objects.requireNonNull(text, "text");
            return text.length() + ":" + text;
        }

        @Override
        public int compareTo(ValueKey other) {
            return encoded.compareTo(other.encoded);
        }

        @Override
        public String toString() {
            return encoded;
        }
    }

    public static final class Projection {
        private final Expr syntaxRoot;
        private final ExprValue valueRoot;
        private final Map<Expr, ExprValue> valuesBySyntaxIdentity;

        private Projection(
                Expr syntaxRoot,
                ExprValue valueRoot,
                IdentityHashMap<Expr, ExprValue> valuesBySyntaxIdentity) {
            this.syntaxRoot = Objects.requireNonNull(syntaxRoot, "syntaxRoot");
            this.valueRoot = Objects.requireNonNull(valueRoot, "valueRoot");
            this.valuesBySyntaxIdentity = Collections.unmodifiableMap(
                    new IdentityHashMap<>(valuesBySyntaxIdentity));
        }

        public Expr syntaxRoot() {
            return syntaxRoot;
        }

        public ExprValue valueRoot() {
            return valueRoot;
        }

        public Optional<ExprValue> valueOf(Expr syntaxOccurrence) {
            return Optional.ofNullable(valuesBySyntaxIdentity.get(syntaxOccurrence));
        }

        public Map<Expr, ExprValue> valuesBySyntaxIdentity() {
            return valuesBySyntaxIdentity;
        }
    }

    private static PreparedOrdered prepareOrdered(
            ValueOperator operator,
            List<? extends ExprValue> operands) {
        Objects.requireNonNull(operator, "operator");
        List<ExprValue> copy = List.copyOf(Objects.requireNonNull(operands, "operands"));
        if (copy.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("operands must not contain null");
        }
        if (operator.laws().supportsUnorderedNaryValue()) {
            throw new IllegalArgumentException("AC operator requires an unordered value");
        }
        operator.requireArity(copy.size());
        return new PreparedOrdered(operator, copy, ValueKey.ordered(operator, copy));
    }

    private static PreparedAssociativeCommutative prepareAssociativeCommutative(
            ValueOperator operator,
            Map<? extends ExprValue, Integer> multiplicities) {
        Objects.requireNonNull(operator, "operator");
        Objects.requireNonNull(multiplicities, "multiplicities");
        if (!operator.laws().supportsUnorderedNaryValue()) {
            throw new IllegalArgumentException("operator is not associative and commutative");
        }

        Map<ExprValue, Integer> copy = new LinkedHashMap<>();
        int count = 0;
        for (Map.Entry<? extends ExprValue, Integer> entry : multiplicities.entrySet()) {
            ExprValue value = Objects.requireNonNull(entry.getKey(), "operand value");
            int multiplicity = Objects.requireNonNull(entry.getValue(), "operand multiplicity");
            if (multiplicity < 1) {
                throw new IllegalArgumentException("operand multiplicity must be positive");
            }
            copy.merge(value, multiplicity, Math::addExact);
            count = Math.addExact(count, multiplicity);
        }
        operator.requireArity(count);
        Map<ExprValue, Integer> immutable = Collections.unmodifiableMap(copy);
        return new PreparedAssociativeCommutative(
                operator,
                immutable,
                count,
                ValueKey.associativeCommutative(operator, immutable));
    }

    private record PreparedOrdered(
            ValueOperator operator,
            List<ExprValue> operands,
            ValueKey key) {
    }

    private record PreparedAssociativeCommutative(
            ValueOperator operator,
            Map<ExprValue, Integer> multiplicities,
            int operandCount,
            ValueKey key) {
    }
}
