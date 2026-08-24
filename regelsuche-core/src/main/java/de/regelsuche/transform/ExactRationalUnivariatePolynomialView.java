package de.regelsuche.transform;

import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.BinaryOperator;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.FunctionExpr;
import de.regelsuche.ast.NumberExpr;
import de.regelsuche.ast.VariableExpr;
import de.regelsuche.parse.ExactParsedTerm;
import de.regelsuche.scalar.ExactRational;
import de.regelsuche.scalar.ExactRationalPolynomial;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Interprets a parser-issued term as a bounded exact univariate polynomial.
 *
 * <p>Numeric values are resolved only through {@link ExactParsedTerm} node
 * identity. The legacy {@link NumberExpr#value()} is never used to authorize an
 * exact coefficient. Functions, multiple variables, variable divisors and
 * unsupported exponents fail closed.</p>
 */
public final class ExactRationalUnivariatePolynomialView {
    public static final String VIEW_ID =
        "regelsuche.exact-rational-univariate-polynomial-view/v1";
    public static final int MAX_DEGREE = 64;
    public static final int MAX_COEFFICIENT_BITS = 8_192;
    public static final int MAX_VISITED_NODES = 4_096;
    public static final int MAX_ARITHMETIC_OPERATIONS = 100_000;
    public static final Budget DEFAULT_BUDGET =
        new Budget(16, 4_096, 512, 10_000);

    private final Budget budget;

    public ExactRationalUnivariatePolynomialView() {
        this(DEFAULT_BUDGET);
    }

    public ExactRationalUnivariatePolynomialView(Budget budget) {
        this.budget = Objects.requireNonNull(budget, "budget");
    }

    public Budget budget() {
        return budget;
    }

    public Analysis analyze(ExactParsedTerm parsed) {
        Objects.requireNonNull(parsed, "parsed");
        Work work = new Work(budget);
        List<LiteralBinding> literals = parsed.literals().stream()
            .map(LiteralBinding::from)
            .toList();
        try {
            PolynomialValue value = convert(
                parsed.expression(),
                parsed,
                work);
            ExactRationalPolynomial polynomial =
                new ExactRationalPolynomial(value.coefficients());
            return Analysis.supported(
                parsed.source(),
                budget,
                value.variable(),
                polynomial,
                literals,
                work.snapshot());
        } catch (BudgetExceeded exception) {
            return Analysis.failure(
                Status.BUDGET_EXCEEDED,
                exception.getMessage(),
                parsed.source(),
                budget,
                literals,
                work.snapshot());
        } catch (UnsupportedExpression exception) {
            return Analysis.failure(
                Status.UNSUPPORTED,
                exception.getMessage(),
                parsed.source(),
                budget,
                literals,
                work.snapshot());
        }
    }

    private PolynomialValue convert(
        Expr expression,
        ExactParsedTerm parsed,
        Work work
    ) {
        work.visit();
        if (expression instanceof NumberExpr number) {
            return exactNumber(number, parsed);
        }
        if (expression instanceof VariableExpr variable) {
            return PolynomialValue.variable(variable.name());
        }
        if (expression instanceof FunctionExpr) {
            throw unsupported("FUNCTION_EXPRESSION_OUTSIDE_UNIVARIATE_VIEW");
        }
        if (!(expression instanceof BinaryExpr binary)) {
            throw unsupported("EXPRESSION_KIND_OUTSIDE_UNIVARIATE_VIEW");
        }
        if (binary.operator() == BinaryOperator.SUB
                && isSyntheticUnaryZero(binary.left(), parsed)) {
            return negate(convert(binary.right(), parsed, work), work);
        }
        return switch (binary.operator()) {
            case ADD -> add(
                convert(binary.left(), parsed, work),
                convert(binary.right(), parsed, work),
                work);
            case SUB -> subtract(
                convert(binary.left(), parsed, work),
                convert(binary.right(), parsed, work),
                work);
            case MUL -> multiply(
                convert(binary.left(), parsed, work),
                convert(binary.right(), parsed, work),
                work);
            case DIV -> divideByConstant(
                convert(binary.left(), parsed, work),
                convert(binary.right(), parsed, work),
                work);
            case POW -> power(
                convert(binary.left(), parsed, work),
                exactExponent(binary.right(), parsed, work),
                work);
        };
    }

    private PolynomialValue exactNumber(
        NumberExpr number,
        ExactParsedTerm parsed
    ) {
        ExactParsedTerm.LiteralOccurrence occurrence =
            parsed.literalFor(number).orElseThrow(() -> unsupported(
                "NUMERIC_NODE_LACKS_EXACT_SOURCE_PROVENANCE"));
        return PolynomialValue.constant(
            checked(occurrence.exactValue()));
    }

    private int exactExponent(
        Expr expression,
        ExactParsedTerm parsed,
        Work work
    ) {
        work.visit();
        if (!(expression instanceof NumberExpr number)) {
            throw unsupported(
                "POWER_EXPONENT_MUST_BE_EXACT_NONNEGATIVE_INTEGER");
        }
        ExactRational value = parsed.literalFor(number)
            .orElseThrow(() -> unsupported(
                "POWER_EXPONENT_LACKS_EXACT_SOURCE_PROVENANCE"))
            .exactValue();
        if (!value.isInteger()
                || value.signum() < 0
                || value.numerator().bitLength() > 31) {
            throw unsupported(
                "POWER_EXPONENT_MUST_BE_EXACT_NONNEGATIVE_INTEGER");
        }
        int exponent = value.numerator().intValueExact();
        if (exponent > budget.maxDegree()) {
            throw budgetExceeded("MAX_DEGREE_EXCEEDED");
        }
        return exponent;
    }

    private boolean isSyntheticUnaryZero(
        Expr expression,
        ExactParsedTerm parsed
    ) {
        return expression instanceof NumberExpr number
            && number.value() == 0.0d
            && parsed.literalFor(number).isEmpty();
    }

    private PolynomialValue add(
        PolynomialValue left,
        PolynomialValue right,
        Work work
    ) {
        String variable = mergeVariable(left.variable(), right.variable());
        int size = Math.max(
            left.coefficients().size(),
            right.coefficients().size());
        List<ExactRational> result = new ArrayList<>(size);
        for (int index = 0; index < size; index++) {
            work.arithmetic();
            result.add(checked(
                left.coefficient(index).add(right.coefficient(index))));
        }
        return new PolynomialValue(variable, result);
    }

    private PolynomialValue subtract(
        PolynomialValue left,
        PolynomialValue right,
        Work work
    ) {
        return add(left, negate(right, work), work);
    }

    private PolynomialValue negate(
        PolynomialValue value,
        Work work
    ) {
        List<ExactRational> result = new ArrayList<>(
            value.coefficients().size());
        for (ExactRational coefficient : value.coefficients()) {
            work.arithmetic();
            result.add(checked(coefficient.negate()));
        }
        return new PolynomialValue(value.variable(), result);
    }

    private PolynomialValue multiply(
        PolynomialValue left,
        PolynomialValue right,
        Work work
    ) {
        String variable = mergeVariable(left.variable(), right.variable());
        int degree = left.degree() + right.degree();
        if (degree > budget.maxDegree()) {
            throw budgetExceeded("MAX_DEGREE_EXCEEDED");
        }
        List<ExactRational> result = new ArrayList<>(
            java.util.Collections.nCopies(
                degree + 1,
                ExactRational.ZERO));
        for (int leftIndex = 0;
                leftIndex < left.coefficients().size();
                leftIndex++) {
            for (int rightIndex = 0;
                    rightIndex < right.coefficients().size();
                    rightIndex++) {
                int resultIndex = leftIndex + rightIndex;
                work.arithmetic();
                ExactRational product = checked(
                    left.coefficient(leftIndex).multiply(
                        right.coefficient(rightIndex)));
                work.arithmetic();
                result.set(
                    resultIndex,
                    checked(result.get(resultIndex).add(product)));
            }
        }
        return new PolynomialValue(variable, result);
    }

    private PolynomialValue divideByConstant(
        PolynomialValue dividend,
        PolynomialValue divisor,
        Work work
    ) {
        if (!divisor.isConstant()
                || divisor.coefficient(0).isZero()) {
            throw unsupported("DIVISOR_MUST_BE_EXACT_NONZERO_CONSTANT");
        }
        ExactRational scalar = divisor.coefficient(0);
        List<ExactRational> result = new ArrayList<>(
            dividend.coefficients().size());
        for (ExactRational coefficient : dividend.coefficients()) {
            work.arithmetic();
            result.add(checked(coefficient.divide(scalar)));
        }
        return new PolynomialValue(dividend.variable(), result);
    }

    private PolynomialValue power(
        PolynomialValue base,
        int exponent,
        Work work
    ) {
        PolynomialValue result = PolynomialValue.constant(ExactRational.ONE);
        for (int step = 0; step < exponent; step++) {
            result = multiply(result, base, work);
        }
        return result;
    }

    private String mergeVariable(String left, String right) {
        if (left.isEmpty()) {
            return right;
        }
        if (right.isEmpty() || left.equals(right)) {
            return left;
        }
        throw unsupported("MULTIPLE_POLYNOMIAL_VARIABLES");
    }

    private ExactRational checked(ExactRational value) {
        if (value.numerator().abs().bitLength()
                > budget.maxCoefficientBits()
                || value.denominator().bitLength()
                    > budget.maxCoefficientBits()) {
            throw budgetExceeded("COEFFICIENT_BIT_LIMIT_EXCEEDED");
        }
        return value;
    }

    private static UnsupportedExpression unsupported(String detailCode) {
        return new UnsupportedExpression(detailCode);
    }

    private static BudgetExceeded budgetExceeded(String detailCode) {
        return new BudgetExceeded(detailCode);
    }

    public enum Status {
        SUPPORTED,
        UNSUPPORTED,
        BUDGET_EXCEEDED
    }

    public record Budget(
        int maxDegree,
        int maxCoefficientBits,
        int maxVisitedNodes,
        int maxArithmeticOperations
    ) {
        public Budget {
            if (maxDegree < 0
                    || maxDegree > MAX_DEGREE
                    || maxCoefficientBits < 1
                    || maxCoefficientBits > MAX_COEFFICIENT_BITS
                    || maxVisitedNodes < 1
                    || maxVisitedNodes > MAX_VISITED_NODES
                    || maxArithmeticOperations < 1
                    || maxArithmeticOperations
                        > MAX_ARITHMETIC_OPERATIONS) {
                throw new IllegalArgumentException(
                    "exact univariate polynomial budget is invalid");
            }
        }

        String canonicalMaterial() {
            return maxDegree + ":"
                + maxCoefficientBits + ":"
                + maxVisitedNodes + ":"
                + maxArithmeticOperations;
        }
    }

    public record WorkLedger(
        int visitedNodes,
        int arithmeticOperations
    ) {
        public WorkLedger {
            if (visitedNodes < 0 || arithmeticOperations < 0) {
                throw new IllegalArgumentException(
                    "exact univariate work ledger is invalid");
            }
        }

        String canonicalMaterial() {
            return visitedNodes + ":" + arithmeticOperations;
        }
    }

    public record LiteralBinding(
        int startInclusive,
        int endExclusive,
        String sourceLexeme,
        String canonicalValue,
        String valueId,
        String certificateHash
    ) {
        public LiteralBinding {
            if (startInclusive < 0
                    || endExclusive <= startInclusive
                    || sourceLexeme == null
                    || sourceLexeme.isEmpty()
                    || canonicalValue == null
                    || canonicalValue.isEmpty()
                    || valueId == null
                    || !valueId.matches("sha256:[0-9a-f]{64}")
                    || certificateHash == null
                    || !certificateHash.matches(
                        "sha256:[0-9a-f]{64}")) {
                throw new IllegalArgumentException(
                    "exact literal binding is invalid");
            }
        }

        private static LiteralBinding from(
            ExactParsedTerm.LiteralOccurrence occurrence
        ) {
            return new LiteralBinding(
                occurrence.startInclusive(),
                occurrence.endExclusive(),
                occurrence.sourceLexeme(),
                occurrence.evidence().canonicalValue(),
                occurrence.evidence().valueId(),
                occurrence.evidence().certificateHash());
        }

        String canonicalMaterial() {
            return startInclusive + ":"
                + endExclusive + ":"
                + sourceLexeme + ":"
                + canonicalValue + ":"
                + valueId + ":"
                + certificateHash;
        }
    }

    public record Analysis(
        Status status,
        String detailCode,
        String source,
        Budget budget,
        String variable,
        Optional<ExactRationalPolynomial> polynomial,
        List<LiteralBinding> literals,
        WorkLedger work
    ) {
        public Analysis {
            Objects.requireNonNull(status, "status");
            if (detailCode == null || detailCode.isBlank()) {
                throw new IllegalArgumentException(
                    "detailCode must not be blank");
            }
            Objects.requireNonNull(source, "source");
            Objects.requireNonNull(budget, "budget");
            Objects.requireNonNull(variable, "variable");
            polynomial = Objects.requireNonNull(
                polynomial,
                "polynomial");
            literals = List.copyOf(
                Objects.requireNonNull(literals, "literals"));
            Objects.requireNonNull(work, "work");
            if (status == Status.SUPPORTED && polynomial.isEmpty()) {
                throw new IllegalArgumentException(
                    "supported analysis requires a polynomial");
            }
            if (status != Status.SUPPORTED
                    && (polynomial.isPresent() || !variable.isEmpty())) {
                throw new IllegalArgumentException(
                    "failed analysis must not expose a polynomial");
            }
        }

        private static Analysis supported(
            String source,
            Budget budget,
            String variable,
            ExactRationalPolynomial polynomial,
            List<LiteralBinding> literals,
            WorkLedger work
        ) {
            return new Analysis(
                Status.SUPPORTED,
                "EXACT_RATIONAL_UNIVARIATE_POLYNOMIAL",
                source,
                budget,
                variable,
                Optional.of(polynomial),
                literals,
                work);
        }

        private static Analysis failure(
            Status status,
            String detailCode,
            String source,
            Budget budget,
            List<LiteralBinding> literals,
            WorkLedger work
        ) {
            return new Analysis(
                status,
                detailCode,
                source,
                budget,
                "",
                Optional.empty(),
                literals,
                work);
        }

        public boolean supported() {
            return status == Status.SUPPORTED;
        }

        public String canonicalMaterial() {
            StringBuilder result = new StringBuilder();
            append(result, VIEW_ID);
            append(result, status.name());
            append(result, detailCode);
            append(result, source);
            append(result, budget.canonicalMaterial());
            append(result, variable);
            append(result, Integer.toString(literals.size()));
            literals.forEach(literal ->
                append(result, literal.canonicalMaterial()));
            if (polynomial.isPresent()) {
                List<ExactRational> coefficients = polynomial.orElseThrow()
                    .coefficientsAscending();
                append(result, Integer.toString(coefficients.size()));
                coefficients.forEach(coefficient ->
                    append(result, coefficient.canonicalText()));
            } else {
                append(result, "0");
            }
            append(result, work.canonicalMaterial());
            return result.toString();
        }

        private static void append(StringBuilder target, String value) {
            int byteLength = value.getBytes(StandardCharsets.UTF_8).length;
            target.append(byteLength).append(':').append(value);
        }
    }

    private record PolynomialValue(
        String variable,
        List<ExactRational> coefficients
    ) {
        private PolynomialValue {
            Objects.requireNonNull(variable, "variable");
            List<ExactRational> normalized = new ArrayList<>(
                Objects.requireNonNull(coefficients, "coefficients"));
            if (normalized.isEmpty()) {
                throw new IllegalArgumentException(
                    "polynomial coefficients must not be empty");
            }
            normalized.forEach(value ->
                Objects.requireNonNull(value, "coefficient"));
            int last = normalized.size() - 1;
            while (last > 0 && normalized.get(last).isZero()) {
                last--;
            }
            coefficients = List.copyOf(normalized.subList(0, last + 1));
        }

        private static PolynomialValue constant(ExactRational value) {
            return new PolynomialValue("", List.of(value));
        }

        private static PolynomialValue variable(String name) {
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException(
                    "polynomial variable must not be blank");
            }
            return new PolynomialValue(
                name,
                List.of(ExactRational.ZERO, ExactRational.ONE));
        }

        private ExactRational coefficient(int exponent) {
            return exponent < coefficients.size()
                ? coefficients.get(exponent)
                : ExactRational.ZERO;
        }

        private int degree() {
            return coefficients.size() - 1;
        }

        private boolean isConstant() {
            return degree() == 0;
        }
    }

    private static final class Work {
        private final Budget budget;
        private int visitedNodes;
        private int arithmeticOperations;

        private Work(Budget budget) {
            this.budget = budget;
        }

        private void visit() {
            if (visitedNodes >= budget.maxVisitedNodes()) {
                throw budgetExceeded("MAX_VISITED_NODES_EXCEEDED");
            }
            visitedNodes++;
        }

        private void arithmetic() {
            if (arithmeticOperations
                    >= budget.maxArithmeticOperations()) {
                throw budgetExceeded(
                    "MAX_ARITHMETIC_OPERATIONS_EXCEEDED");
            }
            arithmeticOperations++;
        }

        private WorkLedger snapshot() {
            return new WorkLedger(visitedNodes, arithmeticOperations);
        }
    }

    private static final class UnsupportedExpression
            extends RuntimeException {
        private static final long serialVersionUID = 1L;

        private UnsupportedExpression(String detailCode) {
            super(detailCode);
        }
    }

    private static final class BudgetExceeded extends RuntimeException {
        private static final long serialVersionUID = 1L;

        private BudgetExceeded(String detailCode) {
            super(detailCode);
        }
    }
}
