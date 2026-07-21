package de.regelsuche.math.algorithms.equivalence;

import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.BinaryOperator;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.FunctionExpr;
import de.regelsuche.ast.NumberExpr;
import de.regelsuche.ast.VariableExpr;
import de.regelsuche.input.InputRequest;
import de.regelsuche.input.InputType;
import de.regelsuche.parse.ExpressionParser;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Deterministic equivalence and domain audit for bounded rational functions.
 *
 * <p>Supported expressions are converted exactly to a numerator/denominator
 * pair over the project polynomial representation. Equality is decided by
 * cross multiplication. Every non-constant denominator factor must be covered
 * by an explicit {@code !=} assumption. Factors and assumptions are compared
 * through monic polynomial normal forms, so equivalent conditions such as
 * {@code x != -3} and {@code x + 3 != 0} match.</p>
 *
 * <p>This service deliberately does not factor arbitrary polynomials and does
 * not infer missing assumptions. It audits the factors visible in the parsed
 * rational AST. Unsupported functions, negative or non-integral powers and
 * identically zero divisors fail closed.</p>
 */
public final class RationalFunctionNormalFormEquivalenceService {
    private static final int MAX_POWER = 12;
    private static final GradedReverseLexOrder ORDER =
        new GradedReverseLexOrder();

    private final ExpressionParser parser = new ExpressionParser();

    public Evaluation evaluate(
        String leftExpression,
        String rightExpression,
        List<String> assumptions
    ) {
        Objects.requireNonNull(leftExpression, "leftExpression");
        Objects.requireNonNull(rightExpression, "rightExpression");
        try {
            RationalFunction left = parse(leftExpression);
            RationalFunction right = parse(rightExpression);
            List<String> required = requiredFactorKeys(left, right);
            AssumptionAudit audit = auditAssumptions(assumptions, required);
            if (!audit.unsupportedAssumptions().isEmpty()) {
                return new Evaluation(
                    Status.UNSUPPORTED,
                    false,
                    left.crossLeft(right).toCanonicalString(),
                    left.crossRight(right).toCanonicalString(),
                    required,
                    audit.providedFactorKeys(),
                    audit.missingFactorKeys(),
                    audit.unsupportedAssumptions(),
                    "unsupported assumption syntax or non-polynomial assumption");
            }
            if (!audit.missingFactorKeys().isEmpty()) {
                return new Evaluation(
                    Status.MISSING_ASSUMPTION,
                    false,
                    left.crossLeft(right).toCanonicalString(),
                    left.crossRight(right).toCanonicalString(),
                    required,
                    audit.providedFactorKeys(),
                    audit.missingFactorKeys(),
                    List.of(),
                    "one or more denominator factors are not declared non-zero");
            }
            Polynomial crossLeft = left.crossLeft(right);
            Polynomial crossRight = left.crossRight(right);
            boolean equivalent = crossLeft.equals(crossRight);
            return new Evaluation(
                equivalent ? Status.CONFIRMED : Status.REFUTED,
                equivalent,
                crossLeft.toCanonicalString(),
                crossRight.toCanonicalString(),
                required,
                audit.providedFactorKeys(),
                List.of(),
                List.of(),
                equivalent
                    ? "matching cross-multiplied polynomial normal forms"
                    : "cross-multiplied polynomial normal forms differ");
        } catch (UnsupportedExpression exception) {
            return new Evaluation(
                Status.UNSUPPORTED,
                false,
                "",
                "",
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                exception.getMessage());
        }
    }

    private RationalFunction parse(String expression) {
        Expr parsed;
        try {
            parsed = parser.parse(new InputRequest(InputType.TERM, expression))
                .terms().getFirst();
        } catch (IllegalArgumentException exception) {
            throw new UnsupportedExpression(
                "cannot parse rational expression: " + exception.getMessage());
        }
        return convert(parsed);
    }

    private RationalFunction convert(Expr expression) {
        if (expression instanceof NumberExpr number) {
            return RationalFunction.polynomial(
                Polynomial.constant(Rational.fromDouble(number.value())));
        }
        if (expression instanceof VariableExpr variable) {
            return RationalFunction.polynomial(
                Polynomial.variable(variable.name()));
        }
        if (expression instanceof FunctionExpr function) {
            throw new UnsupportedExpression(
                "unsupported function in rational expression: "
                    + function.name());
        }
        if (!(expression instanceof BinaryExpr binary)) {
            throw new UnsupportedExpression(
                "unsupported rational AST node: " + expression);
        }
        RationalFunction left = convert(binary.left());
        RationalFunction right = convert(binary.right());
        return switch (binary.operator()) {
            case ADD -> left.add(right);
            case SUB -> left.subtract(right);
            case MUL -> left.multiply(right);
            case DIV -> left.divide(right);
            case POW -> power(left, binary.right());
        };
    }

    private RationalFunction power(
        RationalFunction base,
        Expr exponentExpression
    ) {
        if (!(exponentExpression instanceof NumberExpr exponentNumber)) {
            throw new UnsupportedExpression(
                "rational powers require an explicit non-negative integer");
        }
        double raw = exponentNumber.value();
        if (raw != Math.rint(raw) || raw < 0 || raw > MAX_POWER) {
            throw new UnsupportedExpression(
                "rational power exponent is outside 0.." + MAX_POWER);
        }
        return base.pow((int) raw);
    }

    private List<String> requiredFactorKeys(
        RationalFunction left,
        RationalFunction right
    ) {
        LinkedHashMap<String, String> factors = new LinkedHashMap<>();
        left.denominatorFactors().forEach(factor -> addFactor(factors, factor));
        right.denominatorFactors().forEach(factor -> addFactor(factors, factor));
        return factors.values().stream().sorted().toList();
    }

    private void addFactor(
        Map<String, String> factors,
        Polynomial polynomial
    ) {
        if (polynomial.isZero()) {
            throw new UnsupportedExpression(
                "rational expression contains an identically zero divisor");
        }
        if (polynomial.totalDegree() == 0) {
            return;
        }
        String key = factorKey(polynomial);
        factors.putIfAbsent(key, key);
    }

    private AssumptionAudit auditAssumptions(
        List<String> assumptions,
        List<String> requiredFactorKeys
    ) {
        LinkedHashMap<String, String> provided = new LinkedHashMap<>();
        List<String> unsupported = new ArrayList<>();
        if (assumptions != null) {
            assumptions.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(String::trim)
                .distinct()
                .sorted()
                .forEach(assumption -> {
                    Optional<Polynomial> factor = parseNonZeroAssumption(
                        assumption);
                    if (factor.isEmpty()) {
                        unsupported.add(assumption);
                    } else if (factor.orElseThrow().totalDegree() > 0) {
                        String key = factorKey(factor.orElseThrow());
                        provided.putIfAbsent(key, key);
                    }
                });
        }
        List<String> missing = requiredFactorKeys.stream()
            .filter(key -> !provided.containsKey(key))
            .toList();
        return new AssumptionAudit(
            provided.values().stream().sorted().toList(),
            missing,
            unsupported.stream().sorted().toList());
    }

    private Optional<Polynomial> parseNonZeroAssumption(String assumption) {
        int operator = assumption.indexOf("!=");
        if (operator <= 0 || operator + 2 >= assumption.length()
                || assumption.indexOf("!=", operator + 2) >= 0) {
            return Optional.empty();
        }
        String leftText = assumption.substring(0, operator).trim();
        String rightText = assumption.substring(operator + 2).trim();
        if (leftText.isBlank() || rightText.isBlank()) {
            return Optional.empty();
        }
        try {
            RationalFunction left = parse(leftText);
            RationalFunction right = parse(rightText);
            if (!left.isPolynomial() || !right.isPolynomial()) {
                return Optional.empty();
            }
            Polynomial difference = left.numerator()
                .subtract(right.numerator());
            if (difference.isZero()) {
                return Optional.empty();
            }
            return Optional.of(difference);
        } catch (UnsupportedExpression exception) {
            return Optional.empty();
        }
    }

    private String factorKey(Polynomial polynomial) {
        return polynomial.monic(ORDER).toCanonicalString(ORDER);
    }

    public enum Status {
        CONFIRMED,
        REFUTED,
        MISSING_ASSUMPTION,
        UNSUPPORTED
    }

    public record Evaluation(
        Status status,
        boolean equivalent,
        String leftCrossNormalForm,
        String rightCrossNormalForm,
        List<String> requiredNonZeroFactors,
        List<String> providedNonZeroFactors,
        List<String> missingNonZeroFactors,
        List<String> unsupportedAssumptions,
        String detail
    ) {
        public Evaluation {
            Objects.requireNonNull(status, "status");
            leftCrossNormalForm = leftCrossNormalForm == null
                ? "" : leftCrossNormalForm;
            rightCrossNormalForm = rightCrossNormalForm == null
                ? "" : rightCrossNormalForm;
            requiredNonZeroFactors = immutable(
                requiredNonZeroFactors, "requiredNonZeroFactors");
            providedNonZeroFactors = immutable(
                providedNonZeroFactors, "providedNonZeroFactors");
            missingNonZeroFactors = immutable(
                missingNonZeroFactors, "missingNonZeroFactors");
            unsupportedAssumptions = immutable(
                unsupportedAssumptions, "unsupportedAssumptions");
            detail = detail == null ? "" : detail;
        }

        private static List<String> immutable(
            List<String> values,
            String name
        ) {
            Objects.requireNonNull(values, name);
            if (values.stream().anyMatch(Objects::isNull)) {
                throw new IllegalArgumentException(
                    name + " must not contain null");
            }
            return List.copyOf(values);
        }
    }

    private record AssumptionAudit(
        List<String> providedFactorKeys,
        List<String> missingFactorKeys,
        List<String> unsupportedAssumptions
    ) {
    }

    private record RationalFunction(
        Polynomial numerator,
        Polynomial denominator,
        List<Polynomial> denominatorFactors
    ) {
        private RationalFunction {
            Objects.requireNonNull(numerator, "numerator");
            Objects.requireNonNull(denominator, "denominator");
            denominatorFactors = List.copyOf(denominatorFactors);
            if (denominator.isZero()) {
                throw new UnsupportedExpression(
                    "rational expression has an identically zero denominator");
            }
        }

        private static RationalFunction polynomial(Polynomial value) {
            return new RationalFunction(
                value,
                Polynomial.constant(Rational.ONE),
                List.of());
        }

        private boolean isPolynomial() {
            return denominator.equals(Polynomial.constant(Rational.ONE));
        }

        private RationalFunction add(RationalFunction other) {
            return new RationalFunction(
                numerator.multiply(other.denominator)
                    .add(other.numerator.multiply(denominator)),
                denominator.multiply(other.denominator),
                joined(denominatorFactors, other.denominatorFactors));
        }

        private RationalFunction subtract(RationalFunction other) {
            return new RationalFunction(
                numerator.multiply(other.denominator)
                    .subtract(other.numerator.multiply(denominator)),
                denominator.multiply(other.denominator),
                joined(denominatorFactors, other.denominatorFactors));
        }

        private RationalFunction multiply(RationalFunction other) {
            return new RationalFunction(
                numerator.multiply(other.numerator),
                denominator.multiply(other.denominator),
                joined(denominatorFactors, other.denominatorFactors));
        }

        private RationalFunction divide(RationalFunction other) {
            if (other.numerator.isZero()) {
                throw new UnsupportedExpression(
                    "division by an identically zero rational expression");
            }
            List<Polynomial> factors = new ArrayList<>(denominatorFactors);
            factors.addAll(other.denominatorFactors);
            factors.add(other.numerator);
            return new RationalFunction(
                numerator.multiply(other.denominator),
                denominator.multiply(other.numerator),
                factors);
        }

        private RationalFunction pow(int exponent) {
            if (exponent == 0) {
                return polynomial(Polynomial.constant(Rational.ONE));
            }
            List<Polynomial> factors = new ArrayList<>();
            for (int index = 0; index < exponent; index++) {
                factors.addAll(denominatorFactors);
            }
            return new RationalFunction(
                numerator.pow(exponent),
                denominator.pow(exponent),
                factors);
        }

        private Polynomial crossLeft(RationalFunction other) {
            return numerator.multiply(other.denominator);
        }

        private Polynomial crossRight(RationalFunction other) {
            return other.numerator.multiply(denominator);
        }

        private static List<Polynomial> joined(
            List<Polynomial> left,
            List<Polynomial> right
        ) {
            List<Polynomial> result = new ArrayList<>(left);
            result.addAll(right);
            result.sort(Comparator.comparing(Polynomial::toCanonicalString));
            return List.copyOf(result);
        }
    }

    private static final class UnsupportedExpression extends RuntimeException {
        private UnsupportedExpression(String message) {
            super(message);
        }
    }
}
