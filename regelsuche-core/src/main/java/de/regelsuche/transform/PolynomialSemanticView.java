package de.regelsuche.transform;

import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.BinaryOperator;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.FunctionExpr;
import de.regelsuche.ast.NumberExpr;
import de.regelsuche.ast.VariableExpr;
import de.regelsuche.parse.ExactExpressionFormatter;
import de.regelsuche.parse.ExactParsedTerm;
import de.regelsuche.parse.ExpressionParser;
import de.regelsuche.polynomial.BigIntegerDomain;
import de.regelsuche.polynomial.Monomial;
import de.regelsuche.polynomial.PolynomialRing;
import de.regelsuche.polynomial.PolynomialVariable;
import de.regelsuche.polynomial.PolynomialWorkAuthority;
import de.regelsuche.polynomial.PolynomialWorkLedger;
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
            return analyzeParsed(parsed, work);
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

    /**
     * Inspects the supplied exact parser evidence without reparsing its source.
     * Every admitted operation is retained even when a shared authority refuses
     * a later operation. The caller's authority is never replaced or reset.
     */
    public MeasuredAnalysis analyze(ExactParsedTerm parsed, PolynomialWorkAuthority authority) {
        Objects.requireNonNull(parsed, "parsed");
        Work work = new Work(Objects.requireNonNull(authority, "authority"));
        Analysis analysis;
        try {
            analysis = analyzeParsed(parsed, work);
        } catch (BudgetExceeded | PolynomialWorkAuthority.LimitReached exception) {
            analysis = Analysis.failure(Status.BUDGET_EXCEEDED, exception.getMessage());
        } catch (UnsupportedExpression exception) {
            analysis = Analysis.failure(Status.UNSUPPORTED, exception.getMessage());
        }
        return new MeasuredAnalysis(analysis, work.ledger());
    }

    private Analysis analyzeParsed(ExactParsedTerm parsed, Work work) {
        List<RawTerm> rawTerms = new ArrayList<>();
        collectAddends(parsed.expression(), BigInteger.ONE, rawTerms, work, parsed);
        if (rawTerms.size() > budget.maxTerms()) {
            throw new BudgetExceeded("MAX_TERMS_EXCEEDED");
        }
        return supportedAnalysis(rawTerms, work, parsed);
    }

    private Analysis supportedAnalysis(
        List<RawTerm> rawTerms,
        Work work,
        ExactParsedTerm parsed
    ) {
        List<RawTerm> effectiveTerms = rawTerms.stream()
            .filter(term -> {
                work.consume("term-inspections", 1);
                return term.coefficient().signum() != 0;
            })
            .toList();
        Map<String, RawAtom> atomDefinitions = new LinkedHashMap<>();
        effectiveTerms.forEach(term -> term.atoms().forEach((key, atom) -> {
            work.consume("atom-bindings", 1);
            atomDefinitions.putIfAbsent(key, atom);
        }));
        List<String> atomKeys = atomDefinitions.keySet().stream()
            .sorted(work::compareAtomKeys)
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
                work.consume("monomial-exponent-bindings", 1);
                int index = atomIndexes.get(key);
                exponents.set(index, atom.exponent());
            });
            Monomial monomial = new Monomial(exponents);
            if (monomial.totalDegree() > budget.maxDegree()) {
                throw new BudgetExceeded("MAX_DEGREE_EXCEEDED");
            }
            work.consume("coefficient-bindings", 1);
            coefficients.merge(
                monomial,
                raw.coefficient(),
                (first, second) -> {
                    work.consume("coefficient-additions", 1);
                    return first.add(second);
                });
        }
        coefficients.entrySet().removeIf(
            entry -> {
                work.consume("coefficient-zero-inspections", 1);
                return entry.getValue().signum() == 0;
            });

        work.consume("polynomial-construction-terms", coefficients.size());
        work.consume("polynomial-construction-atoms", atomKeys.size());
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
        terms.add(term.freeze(work));
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
            term.multiply(exactInteger(number, parsed, work), work);
            return;
        }
        if (expression instanceof BinaryExpr binary
                && binary.operator() == BinaryOperator.POW) {
            int exponent = exactExponent(binary.right(), parsed, work);
            if (binary.left() instanceof NumberExpr number) {
                BigInteger base = exactInteger(number, parsed, work);
                if (base.signum() == 0 && exponent == 0) {
                    throw unsupported("ZERO_TO_ZERO_POWER_UNSUPPORTED");
                }
                work.consume("coefficient-powers", 1);
                term.multiply(base.pow(exponent), work);
                return;
            }
            if (exponent > 0) {
                term.addAtom(binary.left(), exponent, parsed, work);
            }
            return;
        }
        term.addAtom(expression, 1, parsed, work);
    }

    private int exactExponent(
        Expr expression,
        ExactParsedTerm parsed,
        Work work
    ) {
        work.consume("exponent-inspections", 1);
        if (!(expression instanceof NumberExpr number)) {
            throw unsupported(
                "POWER_EXPONENT_MUST_BE_NONNEGATIVE_INTEGER");
        }
        BigInteger integer = exactInteger(number, parsed, work);
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
        ExactParsedTerm parsed,
        Work work
    ) {
        work.consume("exact-literal-bindings", 1);
        return parsed.literalFor(number)
            .map(ExactParsedTerm.LiteralOccurrence::exactValue)
            .map(PolynomialSemanticView::requireInteger)
            .orElseGet(() -> {
                if (number.value().equalsInteger(0)) {
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
        ExactParsedTerm parsed,
        Work work
    ) {
        String display = work.measured()
            ? formatAtom(expression, parsed, 0, work)
            : ExactExpressionFormatter.format(expression, parsed);
        work.consume("atom-key-code-units", STRUCTURAL_ATOM_PREFIX.length() + (long) display.length());
        return new RawAtom(
            expression,
            exponent,
            STRUCTURAL_ATOM_PREFIX + display,
            display);
    }

    /** Same exact formatting rules as the existing formatter, with admitted recursive work. */
    private static String formatAtom(Expr expression, ExactParsedTerm parsed, int parentPrecedence, Work work) {
        work.consume("atom-format-node-visits", 1);
        if (expression instanceof NumberExpr number) {
            work.consume("atom-format-literal-conversions", 1);
            String value = ExactExpressionFormatter.format(number, parsed);
            return atomText(value, value.startsWith("-") && parentPrecedence > 0, work);
        }
        if (expression instanceof VariableExpr variable) {
            return atomText(variable.name(), false, work);
        }
        if (expression instanceof FunctionExpr function) {
            work.consume("atom-format-code-units", function.name().length() + 2L);
            StringBuilder result = new StringBuilder(function.name()).append('(');
            for (int index = 0; index < function.arguments().size(); index++) {
                if (index > 0) {
                    work.consume("atom-format-code-units", 2);
                    result.append(", ");
                }
                String argument = formatAtom(function.arguments().get(index), parsed, 0, work);
                work.consume("atom-format-code-units", argument.length());
                result.append(argument);
            }
            return result.append(')').toString();
        }
        BinaryExpr binary = (BinaryExpr) expression;
        BinaryOperator operator = binary.operator();
        int precedence = operator.precedence();
        int rightAdjust = switch (operator) {
            case POW -> 0; // Preserve lower-precedence exponent grouping.
            case DIV, SUB -> 1;
            default -> 0;
        };
        String left = formatAtom(binary.left(), parsed, precedence + (operator == BinaryOperator.POW ? 1 : 0), work);
        String right = formatAtom(binary.right(), parsed, precedence + rightAdjust, work);
        work.consume("atom-format-code-units", left.length() + (long) right.length() + 3);
        return atomText(left + " " + operator.symbol() + " " + right, precedence < parentPrecedence, work);
    }

    private static String atomText(String value, boolean parenthesized, Work work) {
        if (parenthesized) {
            work.consume("atom-format-code-units", value.length() + 2L);
            return "(" + value + ")";
        }
        work.consume("atom-format-code-units", value.length());
        return value;
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

    public record MeasuredAnalysis(Analysis analysis, PolynomialWorkLedger work) {
        public MeasuredAnalysis {
            Objects.requireNonNull(analysis, "analysis");
            Objects.requireNonNull(work, "work");
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

        private void multiply(BigInteger value, Work work) {
            work.consume("coefficient-multiplications", 1);
            coefficient = coefficient.multiply(value);
        }

        private void addAtom(
            Expr expression,
            int exponent,
            ExactParsedTerm parsed,
            Work work
        ) {
            RawAtom next = atom(expression, exponent, parsed, work);
            work.consume("atom-exponent-merges", 1);
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

        private RawTerm freeze(Work work) {
            Map<String, RawAtom> frozen = new LinkedHashMap<>();
            atoms.entrySet().stream()
                .sorted((first, second) -> work.compareAtomKeys(first.getKey(), second.getKey()))
                .forEach(entry -> {
                    work.consume("term-atom-freezes", 1);
                    frozen.put(
                        entry.getKey(),
                        new RawAtom(
                            entry.getValue().expression(),
                            entry.getValue().exponent(),
                            entry.getKey(),
                            entry.getValue().display()));
                });
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
        private final PolynomialWorkAuthority authority;
        private final Map<String, Long> stages = new LinkedHashMap<>();
        private int visitedNodes;

        private Work() { this.authority = null; }

        private Work(PolynomialWorkAuthority authority) { this.authority = authority; }

        private void visit(Budget budget) {
            consume("ast-visits", 1);
            visitedNodes++;
            if (visitedNodes > budget.maxVisitedNodes()) {
                throw new BudgetExceeded(
                    "MAX_VISITED_NODES_EXCEEDED");
            }
        }

        private int visitedNodes() {
            return visitedNodes;
        }

        private boolean measured() { return authority != null; }

        private void consume(String operation, long units) {
            if (!measured() || units == 0) return;
            String stage = "exact-parsed-view." + operation;
            authority.consume(stage, units);
            stages.merge(stage, units, Math::addExact);
        }

        private int compareAtomKeys(String first, String second) {
            consume("atom-key-comparison-code-units", first.length() + (long) second.length());
            return first.compareTo(second);
        }

        private PolynomialWorkLedger ledger() { return new PolynomialWorkLedger(stages); }
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
