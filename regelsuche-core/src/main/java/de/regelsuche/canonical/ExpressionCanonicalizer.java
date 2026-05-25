package de.regelsuche.canonical;

import de.regelsuche.assumption.Assumption;
import de.regelsuche.assumption.AssumptionContext;
import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.BinaryOperator;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.FunctionExpr;
import de.regelsuche.ast.NumberExpr;
import de.regelsuche.ast.VariableExpr;
import de.regelsuche.input.InputRequest;
import de.regelsuche.input.InputType;
import de.regelsuche.parse.ExpressionFormatter;
import de.regelsuche.parse.ExpressionParser;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Strong-canonicalization service.
 *
 * <p>Performs algebraic-normal-form rewriting on expressions so that
 * structurally distinct but mathematically equal syntactic variants collapse
 * to the same {@link #canonicalize(String) canonical string} and therefore
 * the same {@link #stableHash(String) stable hash}. This is the single
 * source-of-truth used by the {@link de.regelsuche.search.memory.TranspositionTable
 * transposition table}, the rule miner and graph deduplication.</p>
 *
 * <p>The basic API ({@link #canonicalize(Expr)}, {@link #stableHash(String)})
 * only applies <em>assumption-free</em> reductions — every rewrite is sound
 * without further side conditions. Reductions that are only correct under
 * an assumption (such as {@code x/x → 1} which needs {@code x ≠ 0}) are
 * available through the {@code …With(...)} overloads, which collect those
 * conditions into an {@link AssumptionContext} so callers can decide what to
 * do with them (record on the rule candidate, prove them, or skip the
 * reduction altogether).</p>
 */
public class ExpressionCanonicalizer {
    private final ExpressionParser parser = new ExpressionParser();

    public String canonicalize(String expression) {
        return canonicalizeWith(expression, null);
    }

    /**
     * Like {@link #canonicalize(String)} but additionally applies
     * assumption-bearing reductions (e.g. {@code x/x → 1}) and records any
     * resulting side conditions into {@code context}.
     */
    public String canonicalizeWith(String expression, AssumptionContext context) {
        try {
            Expr parsed = parser.parse(new InputRequest(InputType.TERM, expression)).terms().getFirst();
            return ExpressionFormatter.format(canonicalize(parsed, context));
        } catch (IllegalArgumentException ex) {
            return expression.trim().replaceAll("\\s+", " ");
        }
    }

    public String stableHash(String expression) {
        return sha256(canonicalize(expression));
    }

    /**
     * Assumption-aware variant of {@link #stableHash(String)}. The hash
     * embeds a fingerprint of the assumptions that {@code context} carries
     * <em>after</em> canonicalization so that an entry that was simplified
     * under {@code x ≠ 0} does not collide with one that was not.
     */
    public String stableHashWith(String expression, AssumptionContext context) {
        String canonical = canonicalizeWith(expression, context);
        String fingerprint = assumptionFingerprint(context);
        return sha256(fingerprint.isEmpty() ? canonical : (canonical + "\u0001" + fingerprint));
    }

    /** Stable fingerprint of the assumptions in {@code context}, suitable for hash composition. */
    public static String assumptionFingerprint(AssumptionContext context) {
        if (context == null || context.isEmpty()) {
            return "";
        }
        List<String> expressions = new ArrayList<>();
        for (Assumption assumption : context.snapshot()) {
            expressions.add(assumption.kind() + "|" + assumption.expression());
        }
        java.util.Collections.sort(expressions);
        return String.join(";", expressions);
    }

    public int astNodeCount(String expression) {
        try {
            Expr parsed = parser.parse(new InputRequest(InputType.TERM, expression)).terms().getFirst();
            return count(canonicalize(parsed));
        } catch (IllegalArgumentException ex) {
            return Math.max(1, expression.replaceAll("\\s+", "").length());
        }
    }

    public Expr canonicalize(Expr expression) {
        return canonicalize(expression, null);
    }

    /**
     * Recursive canonicalization with optional assumption tracking. Passing
     * {@code null} for {@code context} keeps the behavior assumption-free
     * (the default for general-purpose hashing).
     */
    public Expr canonicalize(Expr expression, AssumptionContext context) {
        if (expression instanceof BinaryExpr binaryExpr) {
            return switch (binaryExpr.operator()) {
                case ADD, SUB -> canonicalizeAddition(binaryExpr, context);
                case MUL -> canonicalizeMultiplication(binaryExpr, context);
                case DIV -> canonicalizeDivision(binaryExpr, context);
                case POW -> canonicalizePower(binaryExpr, context);
            };
        }
        if (expression instanceof FunctionExpr functionExpr) {
            List<Expr> normalised = new ArrayList<>(functionExpr.arguments().size());
            for (Expr argument : functionExpr.arguments()) {
                normalised.add(canonicalize(argument, context));
            }
            return new FunctionExpr(functionExpr.name(), normalised);
        }
        return expression;
    }

    private Expr canonicalizeAddition(BinaryExpr expression, AssumptionContext context) {
        List<SignedTerm> terms = new ArrayList<>();
        collectTerms(expression, 1, terms);
        Map<String, TermBucket> buckets = new LinkedHashMap<>();
        for (SignedTerm signedTerm : terms) {
            Expr normalized = canonicalize(signedTerm.term(), context);
            Coefficient coefficient = coefficientOf(normalized);
            int value = signedTerm.sign() * coefficient.value();
            if (value == 0) {
                continue;
            }
            String key = ExpressionFormatter.format(coefficient.term());
            buckets.computeIfAbsent(key, ignored -> new TermBucket(coefficient.term())).add(value);
        }

        List<TermBucket> ordered = buckets.values().stream()
            .filter(bucket -> bucket.coefficient() != 0)
            .sorted(ExpressionCanonicalizer::compareMonomials)
            .toList();
        if (ordered.isEmpty()) {
            return new NumberExpr(0);
        }

        Expr result = null;
        for (TermBucket bucket : ordered) {
            Expr term = withCoefficient(bucket.term(), Math.abs(bucket.coefficient()));
            if (result == null) {
                result = bucket.coefficient() < 0 ? new BinaryExpr(new NumberExpr(0), BinaryOperator.SUB, term) : term;
            } else if (bucket.coefficient() < 0) {
                result = new BinaryExpr(result, BinaryOperator.SUB, term);
            } else {
                result = new BinaryExpr(result, BinaryOperator.ADD, term);
            }
        }
        return result;
    }

    private Expr canonicalizeMultiplication(BinaryExpr expression, AssumptionContext context) {
        List<Expr> factors = new ArrayList<>();
        collectFactors(expression, factors);
        int numeric = 1;
        Map<String, FactorBucket> buckets = new LinkedHashMap<>();
        for (Expr factor : factors) {
            Expr normalized = canonicalize(factor, context);
            if (isNumber(normalized, 0)) {
                return new NumberExpr(0);
            }
            if (normalized instanceof NumberExpr numberExpr) {
                numeric *= (int) numberExpr.value();
                continue;
            }
            Power power = asPower(normalized);
            String key = ExpressionFormatter.format(power.base());
            buckets.computeIfAbsent(key, ignored -> new FactorBucket(power.base())).add(power.exponent());
        }
        if (numeric == 0) {
            return new NumberExpr(0);
        }

        List<Expr> ordered = new ArrayList<>();
        if (numeric != 1 || buckets.isEmpty()) {
            ordered.add(new NumberExpr(numeric));
        }
        buckets.values().stream()
            .filter(bucket -> bucket.exponent() != 0)
            .sorted((left, right) -> ExpressionFormatter.format(left.base()).compareTo(ExpressionFormatter.format(right.base())))
            .forEach(bucket -> ordered.add(bucket.exponent() == 1
                ? bucket.base()
                : new BinaryExpr(bucket.base(), BinaryOperator.POW, new NumberExpr(bucket.exponent()))));
        if (ordered.isEmpty()) {
            return new NumberExpr(1);
        }
        return leftAssociate(ordered, BinaryOperator.MUL);
    }

    private Expr canonicalizePower(BinaryExpr expression, AssumptionContext context) {
        Expr base = canonicalize(expression.left(), context);
        Expr exponent = canonicalize(expression.right(), context);
        if (isNumber(exponent, 0)) {
            return new NumberExpr(1);
        }
        if (isNumber(exponent, 1)) {
            return base;
        }
        return new BinaryExpr(base, BinaryOperator.POW, exponent);
    }

    /**
     * Division canonicalization. Without an {@link AssumptionContext} the
     * operator is opaque (just recurse), preserving the assumption-free
     * default. With a context, three assumption-bearing reductions fire and
     * record their side conditions:
     * <ul>
     *   <li>{@code 0 / d  →  0}            under {@code d ≠ 0}</li>
     *   <li>{@code d / d  →  1}            under {@code d ≠ 0}</li>
     *   <li>{@code (a*d) / d  →  a}        under {@code d ≠ 0}</li>
     * </ul>
     */
    private Expr canonicalizeDivision(BinaryExpr expression, AssumptionContext context) {
        Expr numerator = canonicalize(expression.left(), context);
        Expr denominator = canonicalize(expression.right(), context);
        if (context != null && !isNumber(denominator, 0) && !isNumber(denominator, 1)) {
            String denomText = ExpressionFormatter.format(denominator);
            // 0 / d -> 0 (d ≠ 0)
            if (isNumber(numerator, 0)) {
                context.add(Assumption.nonZero(denomText));
                return new NumberExpr(0);
            }
            // d / d -> 1 (d ≠ 0)
            if (numerator.equals(denominator)) {
                context.add(Assumption.nonZero(denomText));
                return new NumberExpr(1);
            }
            // (a*d) / d -> a  and  (d*a) / d -> a  (d ≠ 0)
            Expr cancelled = cancelDivisor(numerator, denominator);
            if (cancelled != null) {
                context.add(Assumption.nonZero(denomText));
                return cancelled;
            }
        }
        return new BinaryExpr(numerator, BinaryOperator.DIV, denominator);
    }

    /**
     * If {@code denominator} appears as one of the factors of a canonical
     * product {@code numerator}, return the product of the remaining factors
     * (or {@link NumberExpr}{@code (1)} if no other factor remains). Returns
     * {@code null} when no cancellation is possible.
     */
    private Expr cancelDivisor(Expr numerator, Expr denominator) {
        if (!(numerator instanceof BinaryExpr binary) || binary.operator() != BinaryOperator.MUL) {
            return null;
        }
        List<Expr> factors = new ArrayList<>();
        collectFactors(binary, factors);
        boolean removed = false;
        List<Expr> remaining = new ArrayList<>();
        for (Expr factor : factors) {
            if (!removed && factor.equals(denominator)) {
                removed = true;
                continue;
            }
            remaining.add(factor);
        }
        if (!removed) {
            return null;
        }
        if (remaining.isEmpty()) {
            return new NumberExpr(1);
        }
        return leftAssociate(remaining, BinaryOperator.MUL);
    }

    /**
     * Compare two monomial buckets by (descending degree, ascending lex of
     * formatted base). Falls back to lex-only for non-monomial keys so the
     * order remains stable.
     */
    private static int compareMonomials(TermBucket left, TermBucket right) {
        int leftDegree = monomialDegree(left.term());
        int rightDegree = monomialDegree(right.term());
        if (leftDegree != rightDegree) {
            return Integer.compare(rightDegree, leftDegree); // higher degree first
        }
        return ExpressionFormatter.format(left.term()).compareTo(ExpressionFormatter.format(right.term()));
    }

    /**
     * Sum of integer exponents of variable factors in a canonical monomial.
     * Returns {@code 0} for the {@code NumberExpr(1)} constant term, {@code 1}
     * for a bare variable, the explicit exponent for {@code x^k}, and the
     * accumulated exponent for products of such factors. Anything that isn't
     * recognised is treated as degree {@code 0} so the comparator stays
     * total.
     */
    static int monomialDegree(Expr expression) {
        if (expression instanceof NumberExpr) {
            return 0;
        }
        if (expression instanceof VariableExpr) {
            return 1;
        }
        if (expression instanceof BinaryExpr binary) {
            if (binary.operator() == BinaryOperator.POW
                && binary.left() instanceof VariableExpr
                && binary.right() instanceof NumberExpr exponent) {
                return Math.max(0, (int) exponent.value());
            }
            if (binary.operator() == BinaryOperator.MUL) {
                return monomialDegree(binary.left()) + monomialDegree(binary.right());
            }
        }
        return 0;
    }

    private void collectTerms(Expr expression, int sign, List<SignedTerm> terms) {
        if (expression instanceof BinaryExpr binaryExpr && binaryExpr.operator() == BinaryOperator.ADD) {
            collectTerms(binaryExpr.left(), sign, terms);
            collectTerms(binaryExpr.right(), sign, terms);
        } else if (expression instanceof BinaryExpr binaryExpr && binaryExpr.operator() == BinaryOperator.SUB) {
            collectTerms(binaryExpr.left(), sign, terms);
            collectTerms(binaryExpr.right(), -sign, terms);
        } else if (!isNumber(expression, 0)) {
            terms.add(new SignedTerm(sign, expression));
        }
    }

    private void collectFactors(Expr expression, List<Expr> factors) {
        if (expression instanceof BinaryExpr binaryExpr && binaryExpr.operator() == BinaryOperator.MUL) {
            collectFactors(binaryExpr.left(), factors);
            collectFactors(binaryExpr.right(), factors);
        } else if (!isNumber(expression, 1)) {
            factors.add(expression);
        }
    }

    private Coefficient coefficientOf(Expr expression) {
        if (expression instanceof BinaryExpr product && product.operator() == BinaryOperator.MUL
            && product.left() instanceof NumberExpr numberExpr) {
            return new Coefficient((int) numberExpr.value(), product.right());
        }
        if (expression instanceof NumberExpr numberExpr) {
            return new Coefficient((int) numberExpr.value(), new NumberExpr(1));
        }
        return new Coefficient(1, expression);
    }

    private Expr withCoefficient(Expr term, int coefficient) {
        if (isNumber(term, 1)) {
            return new NumberExpr(coefficient);
        }
        if (coefficient == 1) {
            return term;
        }
        return new BinaryExpr(new NumberExpr(coefficient), BinaryOperator.MUL, term);
    }

    private Expr leftAssociate(List<Expr> expressions, BinaryOperator operator) {
        Expr result = expressions.getFirst();
        for (int i = 1; i < expressions.size(); i++) {
            result = new BinaryExpr(result, operator, expressions.get(i));
        }
        return result;
    }

    private Power asPower(Expr expression) {
        if (expression instanceof BinaryExpr power && power.operator() == BinaryOperator.POW
            && power.right() instanceof NumberExpr exponent) {
            return new Power(power.left(), (int) exponent.value());
        }
        return new Power(expression, 1);
    }

    private boolean isNumber(Expr expression, int value) {
        return expression instanceof NumberExpr numberExpr && numberExpr.value() == value;
    }

    private int count(Expr expression) {
        if (expression instanceof BinaryExpr binaryExpr) {
            return 1 + count(binaryExpr.left()) + count(binaryExpr.right());
        }
        if (expression instanceof FunctionExpr functionExpr) {
            int total = 1;
            for (Expr argument : functionExpr.arguments()) {
                total += count(argument);
            }
            return total;
        }
        return 1;
    }

    private String sha256(String expression) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(expression.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder();
            for (byte b : hash) {
                builder.append(String.format("%02x", b));
            }
            return builder.toString();
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private record SignedTerm(int sign, Expr term) {
    }

    private record Coefficient(int value, Expr term) {
    }

    private record Power(Expr base, int exponent) {
    }

    private static final class TermBucket {
        private final Expr term;
        private int coefficient;

        private TermBucket(Expr term) {
            this.term = term;
        }

        private void add(int value) {
            coefficient += value;
        }

        private Expr term() {
            return term;
        }

        private int coefficient() {
            return coefficient;
        }
    }

    private static final class FactorBucket {
        private final Expr base;
        private int exponent;

        private FactorBucket(Expr base) {
            this.base = base;
        }

        private void add(int value) {
            exponent += value;
        }

        private Expr base() {
            return base;
        }

        private int exponent() {
            return exponent;
        }
    }
}
