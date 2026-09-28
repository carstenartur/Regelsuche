package de.regelsuche.canonical;

import de.regelsuche.retention.RetainedGraph;
import de.regelsuche.retention.RetainedOperation;

import static de.regelsuche.assumption.ExpressionDefinedness.canElideWithoutDomainLoss;

import de.regelsuche.assumption.Assumption;
import de.regelsuche.assumption.AssumptionContext;
import de.regelsuche.assumption.AssumptionSignature;
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
import de.regelsuche.scalar.ExactRational;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

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
public class ExpressionCanonicalizer implements RetainedGraph.View {
    @Override public void retainedReferences(RetainedGraph.Visitor v){v.requireExact(this,ExpressionCanonicalizer.class);v.reference(parser);v.reference(polynomialNormalizer);}

    private final ExpressionParser parser = new ExpressionParser();
    private final PolynomialNormalizer polynomialNormalizer = PolynomialNormalizer.monomialOnly();

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
        return AssumptionSignature.ofAssumptions(context.snapshot()).fingerprint();
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
        try (var owned = RetainedOperation.retain(this, expression, context)) {
            return canonicalizeOwned(expression, context);
        }
    }

    private Expr canonicalizeChild(Expr expression, AssumptionContext context) {
        // Only the exact implementation can bypass virtual recursion under its known outer owner.
        return getClass() == ExpressionCanonicalizer.class
            ? canonicalizeOwned(expression, context)
            : canonicalize(expression, context);
    }

    private Expr canonicalizeOwned(Expr expression, AssumptionContext context) {
        // The public boundary owns the complete immutable input throughout private recursion.
        RetainedOperation.validation(1);
        if (expression instanceof BinaryExpr binaryExpr) {
            Optional<Expr> polynomial = polynomialNormalizer.normalize(binaryExpr);
            if (polynomial.isPresent()) {
                return polynomial.get();
            }
            return switch (binaryExpr.operator()) {
                case ADD, SUB -> canonicalizeAddition(binaryExpr, context);
                case MUL -> canonicalizeMultiplication(binaryExpr, context);
                case DIV -> canonicalizeDivision(binaryExpr, context);
                case POW -> canonicalizePower(binaryExpr, context);
            };
        }
        if (expression instanceof FunctionExpr functionExpr) {
            return canonicalizeFunction(functionExpr, context);
        }
        return expression;
    }

    private Expr canonicalizeFunction(FunctionExpr function, AssumptionContext context) {
        for (int index = 0; index < function.arguments().size(); index++) {
            Expr original = function.arguments().get(index);
            Expr normalized = canonicalizeChild(original, context);
            RetainedOperation.work(1);
            if (normalized != original) return rebuildFunction(function, context, index, normalized);
        }
        return function;
    }

    /** The first changed child makes the argument accumulator necessary; earlier siblings stay shared. */
    private Expr rebuildFunction(FunctionExpr function, AssumptionContext context, int changedIndex, Expr changed) {
        try (var changedChild = RetainedOperation.retain(changed)) {
            List<Expr> normalized = new ArrayList<>(function.arguments().size());
            RetainedOperation.work(1);
            try (var arguments = RetainedOperation.retain(normalized)) {
                for (int index = 0; index < changedIndex; index++) {
                    normalized.add(function.arguments().get(index));
                    RetainedOperation.work(1);
                }
                normalized.add(changed);
                RetainedOperation.work(1);
                RetainedOperation.checkpoint();
                for (int index = changedIndex + 1; index < function.arguments().size(); index++) {
                    normalized.add(canonicalizeChild(function.arguments().get(index), context));
                    RetainedOperation.work(1);
                    RetainedOperation.checkpoint();
                }
                var result = new FunctionExpr(function.name(), normalized);
                RetainedOperation.work(normalized.size());
                return RetainedOperation.produced(result);
            }
        }
    }

    private Expr canonicalizeAddition(BinaryExpr expression, AssumptionContext context) {
        List<SignedTerm> terms = new ArrayList<>();
        RetainedOperation.work(1);
        try (var collected = RetainedOperation.retain(terms)) {
            collectTerms(expression, 1, terms);
            Map<String, TermBucket> buckets = new LinkedHashMap<>();
            List<SignedTerm> normalizedTerms = new ArrayList<>();
            RetainedOperation.work(2);
            try (var accumulation = RetainedOperation.retain(buckets, normalizedTerms)) {
                for (SignedTerm signedTerm : terms) {
                    Expr normalized = canonicalizeChild(signedTerm.term(), context);
                    try (var rewritten = RetainedOperation.retain(normalized)) {
                        // A fresh ADD/SUB from normalization joins this same signed addition pass.
                        RetainedOperation.work(normalizedTerms.size());
                        normalizedTerms.clear();
                        collectTerms(normalized, signedTerm.sign(), normalizedTerms);
                        for (SignedTerm normalizedTerm : normalizedTerms) {
                            Coefficient coefficient = coefficientOf(normalizedTerm.term());
                            try (var extracted = RetainedOperation.retain(coefficient)) {
                                ExactRational value = normalizedTerm.sign() < 0
                                    ? coefficient.value().negate() : coefficient.value();
                                RetainedOperation.work(1);
                                try (var scalar = RetainedOperation.retain(value)) {
                                    if (value.isZero()) continue;
                                    String key = ExpressionFormatter.format(coefficient.term());
                                    try (var formatted = RetainedOperation.retain(key)) {
                                        var bucket = buckets.computeIfAbsent(key, ignored -> new TermBucket(coefficient.term()));
                                        RetainedOperation.work(1);
                                        bucket.add(value);
                                        RetainedOperation.checkpoint();
                                    }
                                }
                            }
                        }
                    }
                }
                List<TermBucket> retained = new ArrayList<>();
                RetainedOperation.work(1);
                try (var selected = RetainedOperation.retain(retained)) {
                    for (TermBucket bucket : buckets.values()) {
                        RetainedOperation.work(1);
                        if (!bucket.coefficient().isZero() || !canElideWithoutDomainLoss(bucket.term(), context)) {
                            retained.add(bucket);
                            RetainedOperation.work(1);
                            RetainedOperation.checkpoint();
                        }
                    }
                    var ordered = new ArrayList<>(retained);
                    RetainedOperation.work(retained.size() + 1L);
                    try (var sorted = RetainedOperation.retain(ordered)) {
                        ordered.sort(ExpressionCanonicalizer::compareMonomials);
                        if (ordered.isEmpty()) return RetainedOperation.produced(new NumberExpr(0));
                        List<SignedTerm> rendered = new ArrayList<>();
                        RetainedOperation.work(1);
                        try (var output = RetainedOperation.retain(rendered)) {
                            for (TermBucket bucket : ordered) {
                                if (bucket.coefficient().isZero()) {
                                    appendContributions(rendered, bucket);
                                    continue;
                                }
                                var magnitude = bucket.coefficient().abs();
                                RetainedOperation.work(1);
                                try (var scalar = RetainedOperation.retain(magnitude)) {
                                    rendered.add(new SignedTerm(bucket.coefficient().signum(), withCoefficient(bucket.term(), magnitude)));
                                    RetainedOperation.work(2);
                                    RetainedOperation.checkpoint();
                                }
                            }
                            Object[] current = {null};
                            RetainedOperation.work(1);
                            try (var result = RetainedOperation.retain(current)) {
                                for (SignedTerm renderedTerm : rendered) {
                                    Expr previous = (Expr) current[0];
                                    if (previous == null) {
                                        current[0] = renderedTerm.sign() < 0
                                            ? new BinaryExpr(new NumberExpr(0), BinaryOperator.SUB, renderedTerm.term())
                                            : renderedTerm.term();
                                    } else current[0] = new BinaryExpr(previous,
                                        renderedTerm.sign() < 0 ? BinaryOperator.SUB : BinaryOperator.ADD, renderedTerm.term());
                                    RetainedOperation.work(2);
                                    RetainedOperation.checkpoint();
                                }
                                return RetainedOperation.produced(current[0] == null ? new NumberExpr(0) : (Expr) current[0]);
                            }
                        }
                    }
                }
            }
        }
    }

    private void appendContributions(List<SignedTerm> rendered, TermBucket bucket) {
        var contributions = bucket.contributions();
        try (var sorted = RetainedOperation.retain(rendered, bucket, contributions)) {
            for (ExactRational contribution : contributions) {
                var magnitude = contribution.abs();
                RetainedOperation.work(1);
                try (var scalar = RetainedOperation.retain(magnitude)) {
                    Expr fallback = withCoefficient(bucket.term(), magnitude);
                    rendered.add(new SignedTerm(contribution.signum(), fallback));
                    RetainedOperation.work(2);
                    RetainedOperation.checkpoint();
                }
            }
        }
    }

    private Expr canonicalizeMultiplication(BinaryExpr expression, AssumptionContext context) {
        List<Expr> factors = new ArrayList<>();
        RetainedOperation.work(1);
        try (var collected = RetainedOperation.retain(factors)) {
            collectFactors(expression, factors);
            AssumptionContext factorContext = context == null ? null : new AssumptionContext();
            Object[] numeric = {ExactRational.ONE};
            Map<String, FactorBucket> buckets = new LinkedHashMap<>();
            RetainedOperation.work(3);
            try (var accumulation = RetainedOperation.retain(factorContext, numeric, buckets)) {
                for (Expr factor : factors) {
                    Expr normalized = canonicalizeChild(factor, factorContext);
                    try (var rewritten = RetainedOperation.retain(normalized)) {
                        if (normalized instanceof NumberExpr numberExpr) {
                            numeric[0] = ((ExactRational) numeric[0]).multiply(numberExpr.value());
                            RetainedOperation.work(1);
                            RetainedOperation.checkpoint();
                            continue;
                        }
                        Power power = asPower(normalized);
                        try (var extracted = RetainedOperation.retain(power)) {
                            String key = ExpressionFormatter.format(power.base());
                            try (var formatted = RetainedOperation.retain(key)) {
                                FactorBucket bucket = buckets.computeIfAbsent(key, ignored -> new FactorBucket(power.base()));
                                RetainedOperation.work(1);
                                RetainedOperation.checkpoint();
                                // Preserve the original product when the exact exponent sum leaves its range.
                                if (!bucket.add(power.exponent())) return expression;
                            }
                        }
                    }
                }
                if (context != null) {
                    var assumptions = factorContext.snapshot();
                    try (var committed = RetainedOperation.retain(assumptions)) {
                        context.addAll(assumptions);
                    }
                }
                List<Expr> ordered = new ArrayList<>();
                RetainedOperation.work(1);
                try (var output = RetainedOperation.retain(ordered)) {
                    var coefficient = (ExactRational) numeric[0];
                    if (!coefficient.isOne() || buckets.isEmpty()) {
                        ordered.add(PolynomialNormalizer.exactRationalExpression(coefficient));
                        RetainedOperation.work(1);
                        RetainedOperation.checkpoint();
                    }
                    var selected = new ArrayList<FactorBucket>();
                    RetainedOperation.work(1);
                    try (var sorted = RetainedOperation.retain(selected)) {
                        for (var bucket : buckets.values()) {
                            RetainedOperation.work(1);
                            if (bucket.exponent() != 0) {
                                selected.add(bucket);
                                RetainedOperation.work(1);
                                RetainedOperation.checkpoint();
                            }
                        }
                        selected.sort((left, right) -> compareFormatted(left.base(), right.base()));
                        for (var bucket : selected) {
                            if (bucket.exponent() == 1) {
                                ordered.add(bucket.base());
                                RetainedOperation.work(1);
                            } else {
                                var exponent = new NumberExpr(bucket.exponent());
                                RetainedOperation.work(1);
                                try (var leaf = RetainedOperation.retain(exponent)) {
                                    ordered.add(new BinaryExpr(bucket.base(), BinaryOperator.POW, exponent));
                                    RetainedOperation.work(2);
                                }
                            }
                            RetainedOperation.checkpoint();
                        }
                    }
                    if (ordered.isEmpty()) return RetainedOperation.produced(new NumberExpr(1));
                    return leftAssociate(ordered, BinaryOperator.MUL);
                }
            }
        }
    }

    private Expr canonicalizePower(BinaryExpr expression, AssumptionContext context) {
        Expr base = canonicalizeChild(expression.left(), context);
        try (var left = RetainedOperation.retain(base)) {
            Expr exponent = canonicalizeChild(expression.right(), context);
            try (var right = RetainedOperation.retain(exponent)) {
                if (isNumber(exponent, 0)) {
                    Expr retained = new BinaryExpr(base, BinaryOperator.POW, exponent);
                    RetainedOperation.work(1);
                    try (var candidate = RetainedOperation.retain(retained)) {
                        if (!canElideWithoutDomainLoss(base, context)) return retained;
                        if (base instanceof NumberExpr number) {
                            return !number.value().equalsInteger(0) ? RetainedOperation.produced(new NumberExpr(1)) : retained;
                        }
                        if (context == null) return retained;
                        String formatted = ExpressionFormatter.format(base);
                        try (var text = RetainedOperation.retain(formatted)) {
                            context.add(Assumption.nonZero(formatted));
                            RetainedOperation.work(2);
                            RetainedOperation.checkpoint();
                        }
                        return RetainedOperation.produced(new NumberExpr(1));
                    }
                }
                if (isNumber(exponent, 1)) return base;
                return RetainedOperation.produced(new BinaryExpr(base, BinaryOperator.POW, exponent));
            }
        }
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
        Expr numerator = canonicalizeChild(expression.left(), context);
        try (var left = RetainedOperation.retain(numerator)) {
            Expr denominator = canonicalizeChild(expression.right(), context);
            try (var right = RetainedOperation.retain(denominator)) {
                if (numerator instanceof NumberExpr a && denominator instanceof NumberExpr b && !b.value().isZero()) {
                    var quotient = a.value().divide(b.value());
                    RetainedOperation.work(1);
                    try (var scalar = RetainedOperation.retain(quotient)) {
                        return RetainedOperation.produced(new NumberExpr(quotient));
                    }
                }
                if (context != null && !isNumber(denominator, 0) && !isNumber(denominator, 1)) {
                    String denomText = ExpressionFormatter.format(denominator);
                    try (var text = RetainedOperation.retain(denomText)) {
                        if (isNumber(numerator, 0)) {
                            context.add(Assumption.nonZero(denomText));
                            RetainedOperation.work(1);
                            return RetainedOperation.produced(new NumberExpr(0));
                        }
                        if (numerator.equals(denominator)) {
                            context.add(Assumption.nonZero(denomText));
                            RetainedOperation.work(1);
                            return RetainedOperation.produced(new NumberExpr(1));
                        }
                        Expr cancelled = cancelDivisor(numerator, denominator);
                        try (var cancellation = RetainedOperation.retain(cancelled)) {
                            if (cancelled != null) {
                                context.add(Assumption.nonZero(denomText));
                                RetainedOperation.work(1);
                                return cancelled;
                            }
                        }
                    }
                }
                return RetainedOperation.produced(new BinaryExpr(numerator, BinaryOperator.DIV, denominator));
            }
        }
    }

    /**
     * If {@code denominator} appears as one of the factors of a canonical
     * product {@code numerator}, return the product of the remaining factors
     * (or {@link NumberExpr}{@code (1)} if no other factor remains). Returns
     * {@code null} when no cancellation is possible.
     */
    private Expr cancelDivisor(Expr numerator, Expr denominator) {
        if (!(numerator instanceof BinaryExpr binary) || binary.operator() != BinaryOperator.MUL) return null;
        List<Expr> factors = new ArrayList<>();
        RetainedOperation.work(1);
        try (var collected = RetainedOperation.retain(numerator, denominator, factors)) {
            collectFactors(binary, factors);
            boolean removed = false;
            List<Expr> remaining = new ArrayList<>();
            RetainedOperation.work(1);
            try (var result = RetainedOperation.retain(remaining)) {
                for (Expr factor : factors) {
                    RetainedOperation.work(1);
                    if (!removed && factor.equals(denominator)) {
                        removed = true;
                        continue;
                    }
                    remaining.add(factor);
                    RetainedOperation.work(1);
                    RetainedOperation.checkpoint();
                }
                if (!removed) return null;
                if (remaining.isEmpty()) return RetainedOperation.produced(new NumberExpr(1));
                return leftAssociate(remaining, BinaryOperator.MUL);
            }
        }
    }

    /**
     * Compare two monomial buckets by (descending degree, ascending lex of
     * formatted base). Falls back to lex-only for non-monomial keys so the
     * order remains stable.
     */
    private static int compareMonomials(TermBucket left, TermBucket right) {
        long leftDegree = monomialDegree(left.term());
        long rightDegree = monomialDegree(right.term());
        if (leftDegree != rightDegree) {
            return Long.compare(rightDegree, leftDegree); // higher degree first
        }
        return compareFormatted(left.term(), right.term());
    }

    private static int compareFormatted(Expr left, Expr right) {
        String leftText = ExpressionFormatter.format(left);
        try (var first = RetainedOperation.retain(leftText)) {
            String rightText = ExpressionFormatter.format(right);
            try (var second = RetainedOperation.retain(rightText)) {
                RetainedOperation.work(1);
                return leftText.compareTo(rightText);
            }
        }
    }

    /**
     * Sum of integer exponents of variable factors in a canonical monomial.
     * Returns {@code 0} for the {@code NumberExpr(1)} constant term, {@code 1}
     * for a bare variable, the explicit exponent for {@code x^k}, and the
     * accumulated exponent for products of such factors. Anything that isn't
     * recognised is treated as degree {@code 0} so the comparator stays
     * total.
     */
    static long monomialDegree(Expr expression) {
        RetainedOperation.validation(1);
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
                int exactExponent = nonNegativeInteger(exponent.value());
                return Math.max(0, exactExponent);
            }
            if (binary.operator() == BinaryOperator.MUL) {
                return monomialDegree(binary.left())
                    + monomialDegree(binary.right());
            }
        }
        return 0;
    }

    private void collectTerms(Expr expression, int sign, List<SignedTerm> terms) {
        RetainedOperation.validation(1);
        if (expression instanceof BinaryExpr binaryExpr && binaryExpr.operator() == BinaryOperator.ADD) {
            collectTerms(binaryExpr.left(), sign, terms);
            collectTerms(binaryExpr.right(), sign, terms);
        } else if (expression instanceof BinaryExpr binaryExpr && binaryExpr.operator() == BinaryOperator.SUB) {
            collectTerms(binaryExpr.left(), sign, terms);
            collectTerms(binaryExpr.right(), -sign, terms);
        } else if (!isNumber(expression, 0)) {
            terms.add(new SignedTerm(sign, expression));
            RetainedOperation.work(2);
            RetainedOperation.checkpoint();
        }
    }

    private void collectFactors(Expr expression, List<Expr> factors) {
        RetainedOperation.validation(1);
        if (expression instanceof BinaryExpr binaryExpr && binaryExpr.operator() == BinaryOperator.MUL) {
            collectFactors(binaryExpr.left(), factors);
            collectFactors(binaryExpr.right(), factors);
        } else if (!isNumber(expression, 1)) {
            factors.add(expression);
            RetainedOperation.work(1);
            RetainedOperation.checkpoint();
        }
    }

    private Coefficient coefficientOf(Expr expression) {
        if (expression instanceof BinaryExpr product
                && product.operator() == BinaryOperator.MUL
                && product.left() instanceof NumberExpr numberExpr) {
            ExactRational exact = numberExpr.value();
            if (!exact.isZero()) {
                return RetainedOperation.produced(new Coefficient(exact, product.right()));
            }
        }
        if (expression instanceof NumberExpr numberExpr) {
            return RetainedOperation.produced(new Coefficient(numberExpr.value(), new NumberExpr(1)));
        }
        return RetainedOperation.produced(new Coefficient(ExactRational.ONE, expression));
    }

    private Expr withCoefficient(Expr term, ExactRational coefficient) {
        try (var operands = RetainedOperation.retain(term, coefficient)) {
            RetainedOperation.work(1);
            if (isNumber(term, 1)) return PolynomialNormalizer.exactRationalExpression(coefficient);
            if (coefficient.isOne()) return term;
            Expr numeric = PolynomialNormalizer.exactRationalExpression(coefficient);
            try (var scalar = RetainedOperation.retain(numeric)) {
                return RetainedOperation.produced(new BinaryExpr(numeric, BinaryOperator.MUL, term));
            }
        }
    }

    private Expr leftAssociate(List<Expr> expressions, BinaryOperator operator) {
        Object[] current = {expressions.getFirst()};
        RetainedOperation.work(1);
        try (var result = RetainedOperation.retain(expressions, current)) {
            for (int i = 1; i < expressions.size(); i++) {
                current[0] = new BinaryExpr((Expr) current[0], operator, expressions.get(i));
                RetainedOperation.work(2);
                RetainedOperation.checkpoint();
            }
            return RetainedOperation.produced((Expr) current[0]);
        }
    }

    private Power asPower(Expr expression) {
        if (expression instanceof BinaryExpr power
                && power.operator() == BinaryOperator.POW
                && power.right() instanceof NumberExpr exponent) {
            int exactExponent = nonNegativeInteger(exponent.value());
            if (exactExponent > 0) {
                return RetainedOperation.produced(new Power(power.left(), exactExponent));
            }
        }
        return RetainedOperation.produced(new Power(expression, 1));
    }

    private static int nonNegativeInteger(ExactRational value) {
        return value.isInteger() && value.signum() >= 0
                && value.numerator().bitLength() <= 31
            ? value.intValueExact() : -1;
    }

    private boolean isNumber(Expr expression, int value) {
        return expression instanceof NumberExpr numberExpr
            && numberExpr.value().equalsInteger(value);
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

    private record SignedTerm(int sign, Expr term) implements RetainedGraph.View {
        @Override public void retainedReferences(RetainedGraph.Visitor visitor) { visitor.reference(term); }
    }

    private record Coefficient(ExactRational value, Expr term) implements RetainedGraph.View {
        @Override public void retainedReferences(RetainedGraph.Visitor visitor) { visitor.reference(value); visitor.reference(term); }
    }

    private record Power(Expr base, int exponent) implements RetainedGraph.View {
        @Override public void retainedReferences(RetainedGraph.Visitor visitor) { visitor.reference(base); }
    }

    private static final class TermBucket implements RetainedGraph.View {
        @Override public void retainedReferences(RetainedGraph.Visitor visitor) { visitor.reference(term); visitor.reference(contributions); visitor.reference(coefficient); }
        private final Expr term;
        private final List<ExactRational> contributions = new ArrayList<>();
        private ExactRational coefficient = ExactRational.ZERO;

        private TermBucket(Expr term) {
            this.term = term;
        }

        private void add(ExactRational value) {
            try (var operands = RetainedOperation.retain(this, value, coefficient)) {
                coefficient = coefficient.add(value);
                contributions.add(value);
                RetainedOperation.work(2);
                RetainedOperation.checkpoint();
            }
        }

        private Expr term() {
            return term;
        }

        private ExactRational coefficient() {
            return coefficient;
        }

        private List<ExactRational> contributions() {
            var sorted = new ArrayList<>(contributions);
            RetainedOperation.work(contributions.size() + 1L);
            try (var owned = RetainedOperation.retain(this, sorted)) {
                sorted.sort((left, right) -> { RetainedOperation.work(1); return left.compareTo(right); });
                return RetainedOperation.produced(sorted);
            }
        }
    }

    private static final class FactorBucket implements RetainedGraph.View {
        @Override public void retainedReferences(RetainedGraph.Visitor visitor) { visitor.reference(base); }
        private final Expr base;
        private int exponent;

        private FactorBucket(Expr base) {
            this.base = base;
        }

        private boolean add(int value) {
            RetainedOperation.work(1);
            if (value > Integer.MAX_VALUE - exponent) {
                return false;
            }
            exponent += value;
            return true;
        }

        private Expr base() {
            return base;
        }

        private int exponent() {
            return exponent;
        }
    }
}
