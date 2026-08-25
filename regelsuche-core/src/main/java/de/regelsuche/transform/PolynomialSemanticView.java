package de.regelsuche.transform;

import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.BinaryOperator;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.NumberExpr;
import de.regelsuche.parse.ExactExpressionFormatter;
import de.regelsuche.parse.ExactParsedTerm;
import de.regelsuche.parse.ExpressionParser;
import de.regelsuche.polynomial.BigIntegerDomain;
import de.regelsuche.polynomial.Monomial;
import de.regelsuche.polynomial.PolynomialRing;
import de.regelsuche.polynomial.PolynomialVariable;
import de.regelsuche.polynomial.SparsePolynomial;
import de.regelsuche.scalar.ExactRational;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Converts a bounded exact expression fragment into a canonical polynomial and
 * separate source-AST bindings for its structural atoms.
 *
 * <p>The mathematical polynomial never stores display syntax or source
 * occurrences. Exact numeric coefficients and atom identities are derived from
 * parser-issued provenance, never reconstructed from {@code NumberExpr(double)}.
 * </p>
 */
public final class PolynomialSemanticView {
    public static final String VIEW_ID =
        "regelsuche.polynomial-semantic-view/v2";
    public static final String STRUCTURAL_UNIT_ID =
        "structural-unit:1";

    private static final String STRUCTURAL_ATOM_PREFIX = "ast:";

    private final ExpressionParser parser;
    private final Budget budget;

    public PolynomialSemanticView() {
        this(Budget.DEFAULT);
    }

    public PolynomialSemanticView(Budget budget) {
        this(new ExpressionParser(), budget);
    }

    PolynomialSemanticView(
        ExpressionParser parser,
        Budget budget
    ) {
        this.parser = Objects.requireNonNull(parser, "parser");
        this.budget = Objects.requireNonNull(budget, "budget");
    }

    public Analysis analyze(String expression) {
        if (expression == null || expression.isBlank()) {
            return Analysis.failure(
                Status.PARSE_ERROR,
                "EXPRESSION_BLANK");
        }
        Work work = new Work();
        try {
            ExactParsedTerm parsed = parser.parseExactTerm(expression);
            List<RawTerm> rawTerms = new ArrayList<>();
            collectAddends(
                parsed.expression(),
                BigInteger.ONE,
                rawTerms,
                work,
                parsed);
            if (rawTerms.size() > budget.maxTerms()) {
                throw new BudgetExceeded("MAX_TERMS_EXCEEDED");
            }
            return supportedAnalysis(rawTerms, work, parsed);
        } catch (BudgetExceeded exception) {
            return Analysis.failure(
                Status.BUDGET_EXCEEDED,
                exception.getMessage());
        } catch (UnsupportedExpression exception) {
            return Analysis.failure(
                Status.UNSUPPORTED,
                exception.getMessage());
        } catch (IllegalArgumentException exception) {
            return Analysis.failure(
                Status.PARSE_ERROR,
                safeMessage(exception));
        }
    }

    private Analysis supportedAnalysis(
        List<RawTerm> rawTerms,
        Work work,
        ExactParsedTerm parsed
    ) {
        List<RawTerm> effectiveTerms = rawTerms.stream()
            .filter(term -> term.coefficient().signum() != 0)
            .toList();
        Map<String, RawAtom> atomDefinitions = new LinkedHashMap<>();
        effectiveTerms.forEach(term -> term.atoms().forEach(
            atomDefinitions::putIfAbsent));
        List<String> atomKeys = atomDefinitions.keySet().stream()
            .sorted()
            .toList();
        if (atomKeys.size() > budget.maxAtoms()) {
            throw new BudgetExceeded("MAX_ATOMS_EXCEEDED");
        }

        List<StructuralAtom> atoms = atomKeys.stream()
            .map(key -> {
                RawAtom atom = atomDefinitions.get(key);
                return new StructuralAtom(
                    key,
                    atom.display(),
                    atom.expression());
            })
            .toList();
        Map<String, Integer> atomIndexes = new LinkedHashMap<>();
        for (int index = 0; index < atomKeys.size(); index++) {
            atomIndexes.put(atomKeys.get(index), index);
        }

        Map<Monomial, BigInteger> coefficients = new LinkedHashMap<>();
        for (RawTerm raw : effectiveTerms) {
            List<Integer> exponents = new ArrayList<>(
                Collections.nCopies(atomKeys.size(), 0));
            raw.atoms().forEach((key, atom) -> {
                int index = atomIndexes.get(key);
                exponents.set(index, atom.exponent());
            });
            Monomial monomial = new Monomial(exponents);
            if (monomial.totalDegree() > budget.maxDegree()) {
                throw new BudgetExceeded("MAX_DEGREE_EXCEEDED");
            }
            coefficients.merge(
                monomial,
                raw.coefficient(),
                BigInteger::add);
        }
        coefficients.entrySet().removeIf(
            entry -> entry.getValue().signum() == 0);

        PolynomialRing<BigInteger> ring = new PolynomialRing<>(
            BigIntegerDomain.INSTANCE,
            atomKeys.stream().map(PolynomialVariable::new).toList(),
            PolynomialRing.MonomialOrder.GRADED_LEXICOGRAPHIC);
        SparsePolynomial<BigInteger> polynomial =
            new SparsePolynomial<>(ring, coefficients);
        PolynomialView view = new PolynomialView(
            VIEW_ID,
            polynomial,
            atoms,
            work.visitedNodes(),
            parsed.source());
        return new Analysis(
            Status.SUPPORTED,
            "EXACT_INTEGER_POLYNOMIAL",
            view);
    }

    private void collectAddends(
        Expr expression,
        BigInteger sign,
        List<RawTerm> terms,
        Work work,
        ExactParsedTerm parsed
    ) {
        work.visit(budget);
        if (expression instanceof BinaryExpr binary
                && binary.operator() == BinaryOperator.ADD) {
            collectAddends(
                binary.left(),
                sign,
                terms,
                work,
                parsed);
            collectAddends(
                binary.right(),
                sign,
                terms,
                work,
                parsed);
            return;
        }
        if (expression instanceof BinaryExpr binary
                && binary.operator() == BinaryOperator.SUB) {
            collectAddends(
                binary.left(),
                sign,
                terms,
                work,
                parsed);
            collectAddends(
                binary.right(),
                sign.negate(),
                terms,
                work,
                parsed);
            return;
        }
        MutableTerm term = new MutableTerm(sign);
        collectProduct(expression, term, work, parsed);
        terms.add(term.freeze());
    }

    private void collectProduct(
        Expr expression,
        MutableTerm term,
        Work work,
        ExactParsedTerm parsed
    ) {
        work.visit(budget);
        if (expression instanceof BinaryExpr binary
                && binary.operator() == BinaryOperator.MUL) {
            collectProduct(binary.left(), term, work, parsed);
            collectProduct(binary.right(), term, work, parsed);
            return;
        }
        if (expression instanceof BinaryExpr binary
                && binary.operator() == BinaryOperator.DIV) {
            throw unsupported(
                "DIVISION_NOT_IN_INTEGER_POLYNOMIAL_VIEW");
        }
        if (expression instanceof NumberExpr number) {
            term.multiply(exactInteger(number, parsed));
            return;
        }
        if (expression instanceof BinaryExpr binary
                && binary.operator() == BinaryOperator.POW) {
            int exponent = exactExponent(binary.right(), parsed);
            if (binary.left() instanceof NumberExpr number) {
                BigInteger base = exactInteger(number, parsed);
                if (base.signum() == 0 && exponent == 0) {
                    throw unsupported("ZERO_TO_ZERO_POWER_UNSUPPORTED");
                }
                term.multiply(base.pow(exponent));
                return;
            }
            if (exponent > 0) {
                term.addAtom(binary.left(), exponent, parsed);
            }
            return;
        }
        term.addAtom(expression, 1, parsed);
    }

    private int exactExponent(
        Expr expression,
        ExactParsedTerm parsed
    ) {
        if (!(expression instanceof NumberExpr number)) {
            throw unsupported(
                "POWER_EXPONENT_MUST_BE_NONNEGATIVE_INTEGER");
        }
        BigInteger integer = exactInteger(number, parsed);
        if (integer.signum() < 0) {
            throw unsupported(
                "POWER_EXPONENT_MUST_BE_NONNEGATIVE_INTEGER");
        }
        if (integer.compareTo(
                BigInteger.valueOf(budget.maxDegree())) > 0) {
            throw new BudgetExceeded("MAX_DEGREE_EXCEEDED");
        }
        return integer.intValueExact();
    }

    private static BigInteger exactInteger(
        NumberExpr number,
        ExactParsedTerm parsed
    ) {
        return parsed.literalFor(number)
            .map(ExactParsedTerm.LiteralOccurrence::exactValue)
            .map(PolynomialSemanticView::requireInteger)
            .orElseGet(() -> {
                if (number.value() == 0.0d) {
                    return BigInteger.ZERO;
                }
                throw unsupported(
                    "NUMERIC_NODE_LACKS_EXACT_SOURCE_EVIDENCE");
            });
    }

    private static BigInteger requireInteger(ExactRational value) {
        if (!value.isInteger()) {
            throw unsupported(
                "COEFFICIENT_MUST_BE_EXACT_INTEGER");
        }
        return value.numerator();
    }

    private static RawAtom atom(
        Expr expression,
        int exponent,
        ExactParsedTerm parsed
    ) {
        String display = ExactExpressionFormatter.format(
            expression,
            parsed);
        return new RawAtom(
            expression,
            exponent,
            STRUCTURAL_ATOM_PREFIX + display,
            display);
    }

    private static UnsupportedExpression unsupported(
        String detailCode
    ) {
        return new UnsupportedExpression(detailCode);
    }

    private static String safeMessage(RuntimeException exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank()
            ? exception.getClass().getSimpleName()
            : message;
    }

    public enum Status {
        SUPPORTED,
        PARSE_ERROR,
        UNSUPPORTED,
        BUDGET_EXCEEDED
    }

    public record Budget(
        int maxAtoms,
        int maxDegree,
        int maxTerms,
        int maxVisitedNodes
    ) {
        public static final Budget DEFAULT = new Budget(
            4,
            12,
            64,
            512);

        public Budget {
            if (maxAtoms < 1
                    || maxDegree < 0
                    || maxTerms < 1
                    || maxVisitedNodes < 1) {
                throw new IllegalArgumentException(
                    "polynomial semantic-view budget is invalid");
            }
        }
    }

    public record Analysis(
        Status status,
        String detailCode,
        PolynomialView view
    ) {
        public Analysis {
            Objects.requireNonNull(status, "status");
            if (detailCode == null || detailCode.isBlank()) {
                throw new IllegalArgumentException(
                    "detailCode must not be blank");
            }
            if (status == Status.SUPPORTED && view == null) {
                throw new IllegalArgumentException(
                    "supported analysis requires a polynomial view");
            }
            if (status != Status.SUPPORTED && view != null) {
                throw new IllegalArgumentException(
                    "failed analysis must not expose a polynomial view");
            }
        }

        static Analysis failure(
            Status status,
            String detailCode
        ) {
            return new Analysis(status, detailCode, null);
        }

        public boolean supported() {
            return status == Status.SUPPORTED;
        }
    }

    public record StructuralAtom(
        String key,
        String display,
        Expr expression
    ) {
        public StructuralAtom {
            if (key == null
                    || key.isBlank()
                    || display == null
                    || display.isBlank()
                    || expression == null) {
                throw new IllegalArgumentException(
                    "polynomial structural atom is invalid");
            }
        }

        public boolean structuralUnit() {
            return STRUCTURAL_UNIT_ID.equals(key);
        }
    }

    public record PolynomialView(
        String viewId,
        SparsePolynomial<BigInteger> polynomial,
        List<StructuralAtom> atoms,
        int visitedNodes,
        String source
    ) {
        public PolynomialView {
            if (!VIEW_ID.equals(viewId)
                    || polynomial == null
                    || visitedNodes < 0
                    || source == null) {
                throw new IllegalArgumentException(
                    "polynomial semantic view is invalid");
            }
            atoms = List.copyOf(
                Objects.requireNonNull(atoms, "atoms"));
            if (atoms.size() != polynomial.ring().variableCount()) {
                throw new IllegalArgumentException(
                    "atom count must equal polynomial ring variable count");
            }
            for (int index = 0; index < atoms.size(); index++) {
                if (!atoms.get(index).key().equals(
                        polynomial.ring().variables().get(index).id())) {
                    throw new IllegalArgumentException(
                        "atom order must match polynomial ring variables");
                }
            }
        }

        public PolynomialView homogenizeWithUnitAtom(
            int totalDegree
        ) {
            if (atoms.size() != 1
                    || totalDegree < polynomial.totalDegree()) {
                throw new IllegalArgumentException(
                    "unit homogenization requires one atom and sufficient degree");
            }
            StructuralAtom unit = new StructuralAtom(
                STRUCTURAL_UNIT_ID,
                "1",
                new NumberExpr(1));
            return new PolynomialView(
                viewId,
                polynomial.homogenize(
                    totalDegree,
                    new PolynomialVariable(STRUCTURAL_UNIT_ID)),
                List.of(atoms.getFirst(), unit),
                visitedNodes,
                source);
        }

        public StructuralAtom atom(int index) {
            return atoms.get(index);
        }

        public String canonicalMaterial() {
            return polynomial.canonicalMaterial();
        }
    }

    private static final class MutableTerm {
        private BigInteger coefficient;
        private final Map<String, MutableAtom> atoms =
            new LinkedHashMap<>();

        private MutableTerm(BigInteger coefficient) {
            this.coefficient = coefficient;
        }

        private void multiply(BigInteger value) {
            coefficient = coefficient.multiply(value);
        }

        private void addAtom(
            Expr expression,
            int exponent,
            ExactParsedTerm parsed
        ) {
            RawAtom next = atom(expression, exponent, parsed);
            atoms.compute(next.key(), (ignored, current) ->
                current == null
                    ? new MutableAtom(
                        next.expression(),
                        next.exponent(),
                        next.display())
                    : new MutableAtom(
                        current.expression(),
                        Math.addExact(
                            current.exponent(),
                            exponent),
                        current.display()));
        }

        private RawTerm freeze() {
            Map<String, RawAtom> frozen = new LinkedHashMap<>();
            atoms.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> frozen.put(
                    entry.getKey(),
                    new RawAtom(
                        entry.getValue().expression(),
                        entry.getValue().exponent(),
                        entry.getKey(),
                        entry.getValue().display())));
            return new RawTerm(
                coefficient,
                Collections.unmodifiableMap(frozen));
        }
    }

    private record MutableAtom(
        Expr expression,
        int exponent,
        String display
    ) {
    }

    private record RawAtom(
        Expr expression,
        int exponent,
        String key,
        String display
    ) {
    }

    private record RawTerm(
        BigInteger coefficient,
        Map<String, RawAtom> atoms
    ) {
    }

    private static final class Work {
        private int visitedNodes;

        private void visit(Budget budget) {
            visitedNodes++;
            if (visitedNodes > budget.maxVisitedNodes()) {
                throw new BudgetExceeded(
                    "MAX_VISITED_NODES_EXCEEDED");
            }
        }

        private int visitedNodes() {
            return visitedNodes;
        }
    }

    private static final class UnsupportedExpression
            extends RuntimeException {
        private UnsupportedExpression(String detailCode) {
            super(detailCode);
        }
    }

    private static final class BudgetExceeded
            extends RuntimeException {
        private BudgetExceeded(String detailCode) {
            super(detailCode);
        }
    }
}
