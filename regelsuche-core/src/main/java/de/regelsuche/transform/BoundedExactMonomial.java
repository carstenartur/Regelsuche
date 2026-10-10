package de.regelsuche.transform;

import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.BinaryOperator;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.NumberExpr;
import de.regelsuche.ast.VariableExpr;
import de.regelsuche.retention.RetainedGraph;
import de.regelsuche.retention.RetainedOperation;
import de.regelsuche.scalar.ExactRational;
import java.math.BigInteger;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Exact coefficient arithmetic for the matcher's bounded monomial fragment.
 *
 * <p>Input and inferred numeric leaves carry exact rational values. Coefficient
 * bit budgets apply before and during arithmetic. Symbolic divisors remain
 * outside this assumption-free fragment.</p>
 */
final class BoundedExactMonomial implements RetainedGraph.View {
    private final ExactRational coefficient;
    // The defensive natural-order copy never escapes or changes after construction.
    // Owning it directly also avoids hiding its backing map behind a JDK view.
    private final Map<String, Integer> powers;

    BoundedExactMonomial(ExactRational coefficient, Map<String, Integer> powers) {
        this.coefficient = coefficient;
        this.powers = coefficient.isZero() ? Map.of() : new TreeMap<>(powers);
        try (var completed = RetainedOperation.retainCompleted(
                1L + (coefficient.isZero() ? 0 : 1L + powers.size()), powers, this)) { }
    }

    ExactRational coefficient() {
        return coefficient;
    }

    @Override public void retainedReferences(RetainedGraph.Visitor visitor) {
        visitor.reference(coefficient); visitor.reference(powers);
    }

    static Optional<BoundedExactMonomial> from(Expr expression, Budget budget) {
        // Recursive visits borrow this single source owner.
        var input = RetainedOperation.retain(expression, budget);
        Throwable inputFailure = null;
        try {
            return from(expression, budget, 0);
        } catch (RuntimeException | Error failure) {
            inputFailure = failure;
            throw failure;
        } finally {
            closeFrame(input, inputFailure);
        }
    }

    private static Optional<BoundedExactMonomial> from(
        Expr expression, Budget budget, int depth
    ) {
        budget.visit(depth);
        if (expression instanceof NumberExpr number) {
            budget.coefficientBits(bits(number.value().numerator()), bits(number.value().denominator()));
            return optional(new BoundedExactMonomial(number.value(), Map.of()));
        }
        if (expression instanceof VariableExpr variable) {
            var powers = Map.of(variable.name(), 1);
            var singleton = RetainedOperation.retainCompleted(2, powers);
            Throwable singletonFailure = null;
            try {
                return optional(new BoundedExactMonomial(ExactRational.ONE, powers));
            } catch (RuntimeException | Error failure) {
                singletonFailure = failure;
                throw failure;
            } finally {
                closeFrame(singleton, singletonFailure);
            }
        }
        if (!(expression instanceof BinaryExpr binary)) {
            return Optional.empty();
        }
        return switch (binary.operator()) {
            case MUL, DIV -> product(binary, budget, depth);
            case POW -> power(binary, budget, depth);
            default -> Optional.empty();
        };
    }

    private static Optional<BoundedExactMonomial> product(
        BinaryExpr binary, Budget budget, int depth
    ) {
        var left = from(binary.left(), budget, depth + 1);
        var leftOwned = RetainedOperation.retain(left);
        Throwable leftOwnedFailure = null;
        try {
            var right = from(binary.right(), budget, depth + 1);
            var rightOwned = RetainedOperation.retain(right);
            Throwable rightOwnedFailure = null;
            try {
                if (left.isEmpty() || right.isEmpty()) {
                    return Optional.empty();
                }
                boolean divide = binary.operator() == BinaryOperator.DIV;
                // Even x/x and 0/x retain an undefined point. There is no assumption
                // context here that could authorize cancelling a symbolic denominator.
                if (divide && (right.get().coefficient.isZero() || !right.get().powers.isEmpty())) {
                    return Optional.empty();
                }
                return optional(left.get().combine(right.get(), divide, budget));
            } catch (RuntimeException | Error failure) {
                rightOwnedFailure = failure;
                throw failure;
            } finally {
                closeFrame(rightOwned, rightOwnedFailure);
            }
        } catch (RuntimeException | Error failure) {
            leftOwnedFailure = failure;
            throw failure;
        } finally {
            closeFrame(leftOwned, leftOwnedFailure);
        }
    }

    private static Optional<BoundedExactMonomial> power(
        BinaryExpr binary, Budget budget, int depth
    ) {
        if (!(binary.right() instanceof NumberExpr number)) {
            return Optional.empty();
        }
        int exponent = positiveInteger(number.value());
        if (exponent < 1) {
            return Optional.empty();
        }
        var base = from(binary.left(), budget, depth + 1);
        var baseOwned = RetainedOperation.retain(base);
        Throwable baseOwnedFailure = null;
        try {
            return base.isEmpty() ? Optional.empty() : optional(base.get().pow(exponent, budget));
        } catch (RuntimeException | Error failure) {
            baseOwnedFailure = failure;
            throw failure;
        } finally {
            closeFrame(baseOwned, baseOwnedFailure);
        }
    }

    private BoundedExactMonomial combine(
        BoundedExactMonomial other, boolean divide, Budget budget
    ) {
        ExactRational right = divide ? other.coefficient.reciprocal() : other.coefficient;
        var scalar = RetainedOperation.retainCompleted(divide ? 1 : 0, this, other, right);
        Throwable scalarFailure = null;
        try {
            budget.coefficientBits(
                (long) bits(coefficient.numerator()) + bits(right.numerator()),
                (long) bits(coefficient.denominator()) + bits(right.denominator()));
            Map<String, Integer> result = new TreeMap<>(powers);
            var accumulator = RetainedOperation.retainCompleted(1L + powers.size(), result);
            Throwable accumulatorFailure = null;
            try {
                if (!divide) {
                    for (var entry : other.powers.entrySet()) {
                        result.merge(entry.getKey(), entry.getValue(),
                            (first, second) -> checkedExponent((long) first + second));
                        try (var updated = RetainedOperation.retainCompleted(1, result)) { }
                    }
                }
                var product = coefficient.multiply(right);
                var value = RetainedOperation.retainCompleted(
                        product == coefficient || product == right || product == ExactRational.ZERO ? 0 : 1, product);
                Throwable valueFailure = null;
                try {
                    return new BoundedExactMonomial(product, result);
                } catch (RuntimeException | Error failure) {
                    valueFailure = failure;
                    throw failure;
                } finally {
                    closeFrame(value, valueFailure);
                }
            } catch (RuntimeException | Error failure) {
                accumulatorFailure = failure;
                throw failure;
            } finally {
                closeFrame(accumulator, accumulatorFailure);
            }
        } catch (RuntimeException | Error failure) {
            scalarFailure = failure;
            throw failure;
        } finally {
            closeFrame(scalar, scalarFailure);
        }
    }

    private BoundedExactMonomial pow(int exponent, Budget budget) {
        budget.coefficientBits(powerBits(coefficient.numerator(), exponent),
            powerBits(coefficient.denominator(), exponent));
        Map<String, Integer> result = new TreeMap<>();
        var accumulator = RetainedOperation.retainCompleted(1, this, result);
        Throwable accumulatorFailure = null;
        try {
            for (var entry : powers.entrySet()) {
                result.put(entry.getKey(), checkedExponent((long) entry.getValue() * exponent));
                try (var updated = RetainedOperation.retainCompleted(1, result)) { }
            }
            var powered = coefficient.pow(exponent);
            var scalar = RetainedOperation.retainCompleted(1, powered);
            Throwable scalarFailure = null;
            try {
                return new BoundedExactMonomial(powered, result);
            } catch (RuntimeException | Error failure) {
                scalarFailure = failure;
                throw failure;
            } finally {
                closeFrame(scalar, scalarFailure);
            }
        } catch (RuntimeException | Error failure) {
            accumulatorFailure = failure;
            throw failure;
        } finally {
            closeFrame(accumulator, accumulatorFailure);
        }
    }

    Optional<BoundedExactMonomial> exactRoot(int exponent, Budget budget) {
        var input = RetainedOperation.retain(this, budget);
        Throwable inputFailure = null;
        try {
            if (coefficient.signum() < 0 && exponent % 2 == 0) {
                return Optional.empty();
            }
            Map<String, Integer> result = new TreeMap<>();
            var accumulator = RetainedOperation.retainCompleted(1, result);
            Throwable accumulatorFailure = null;
            try {
                for (var entry : powers.entrySet()) {
                    if (entry.getValue() % exponent != 0) {
                        return Optional.empty();
                    }
                    result.put(entry.getKey(), entry.getValue() / exponent);
                    try (var updated = RetainedOperation.retainCompleted(1, result)) { }
                }
                var magnitude = coefficient.numerator().abs();
                var absolute = RetainedOperation.retainCompleted(
                        magnitude == coefficient.numerator() ? 0 : 1, magnitude);
                Throwable absoluteFailure = null;
                try {
                    var numerator = integerRoot(magnitude, exponent, budget);
                    var top = RetainedOperation.retain(numerator);
                    Throwable topFailure = null;
                    try {
                        var denominator = integerRoot(coefficient.denominator(), exponent, budget);
                        var bottom = RetainedOperation.retain(denominator);
                        Throwable bottomFailure = null;
                        try {
                            if (numerator.isEmpty() || denominator.isEmpty()) {
                                return Optional.empty();
                            }
                            BigInteger signed = coefficient.signum() < 0 ? numerator.get().negate() : numerator.get();
                            var sign = RetainedOperation.retainCompleted(
                                    signed == numerator.get() ? 0 : 1, signed);
                            Throwable signFailure = null;
                            try {
                                var rooted = new ExactRational(signed, denominator.get());
                                var scalar = RetainedOperation.retainCompleted(1, rooted);
                                Throwable scalarFailure = null;
                                try {
                                    return optional(new BoundedExactMonomial(rooted, result));
                                } catch (RuntimeException | Error failure) {
                                    scalarFailure = failure;
                                    throw failure;
                                } finally {
                                    closeFrame(scalar, scalarFailure);
                                }
                            } catch (RuntimeException | Error failure) {
                                signFailure = failure;
                                throw failure;
                            } finally {
                                closeFrame(sign, signFailure);
                            }
                        } catch (RuntimeException | Error failure) {
                            bottomFailure = failure;
                            throw failure;
                        } finally {
                            closeFrame(bottom, bottomFailure);
                        }
                    } catch (RuntimeException | Error failure) {
                        topFailure = failure;
                        throw failure;
                    } finally {
                        closeFrame(top, topFailure);
                    }
                } catch (RuntimeException | Error failure) {
                    absoluteFailure = failure;
                    throw failure;
                } finally {
                    closeFrame(absolute, absoluteFailure);
                }
            } catch (RuntimeException | Error failure) {
                accumulatorFailure = failure;
                throw failure;
            } finally {
                closeFrame(accumulator, accumulatorFailure);
            }
        } catch (RuntimeException | Error failure) {
            inputFailure = failure;
            throw failure;
        } finally {
            closeFrame(input, inputFailure);
        }
    }

    /** No floating-point proposal can authorize a root. */
    private static Optional<BigInteger> integerRoot(BigInteger value, int exponent, Budget budget) {
        budget.visit(0);
        if (exponent == 1 || value.compareTo(BigInteger.ONE) <= 0) {
            return RetainedOperation.produced(Optional.of(value));
        }
        if (exponent > value.bitLength()) {
            return Optional.empty();
        }
        BigInteger high = BigInteger.ONE.shiftLeft((value.bitLength() + exponent - 1) / exponent);
        var upper = RetainedOperation.retainCompleted(1, high);
        Throwable upperFailure = null;
        try {
            BigInteger[] bounds = {BigInteger.ZERO, high};
            var search = RetainedOperation.retainCompleted(1, (Object) bounds);
            Throwable searchFailure = null;
            try {
                while (true) {
                    var difference = bounds[1].subtract(bounds[0]);
                    var gap = RetainedOperation.retainCompleted(difference == bounds[1] ? 0 : 1, difference);
                    Throwable gapFailure = null;
                    try {
                        if (difference.compareTo(BigInteger.ONE) <= 0) break;
                        budget.visit(0);
                        var sum = bounds[0].add(bounds[1]);
                        var added = RetainedOperation.retainCompleted(
                                sum == bounds[0] || sum == bounds[1] ? 0 : 1, sum);
                        Throwable addedFailure = null;
                        try {
                            BigInteger middle = sum.shiftRight(1);
                            var midpoint = RetainedOperation.retainCompleted(middle == sum ? 0 : 1, middle);
                            Throwable midpointFailure = null;
                            try {
                                var powered = middle.pow(exponent);
                                var trial = RetainedOperation.retainCompleted(powered == middle ? 0 : 1, powered);
                                Throwable trialFailure = null;
                                try {
                                    int comparison = powered.compareTo(value);
                                    if (comparison == 0) {
                                        return RetainedOperation.produced(Optional.of(middle));
                                    }
                                    bounds[comparison < 0 ? 0 : 1] = middle;
                                    RetainedOperation.work(1);
                                } catch (RuntimeException | Error failure) {
                                    trialFailure = failure;
                                    throw failure;
                                } finally {
                                    closeFrame(trial, trialFailure);
                                }
                            } catch (RuntimeException | Error failure) {
                                midpointFailure = failure;
                                throw failure;
                            } finally {
                                closeFrame(midpoint, midpointFailure);
                            }
                        } catch (RuntimeException | Error failure) {
                            addedFailure = failure;
                            throw failure;
                        } finally {
                            closeFrame(added, addedFailure);
                        }
                    } catch (RuntimeException | Error failure) {
                        gapFailure = failure;
                        throw failure;
                    } finally {
                        closeFrame(gap, gapFailure);
                    }
                }
                var powered = bounds[0].pow(exponent);
                var trial = RetainedOperation.retainCompleted(powered == bounds[0] ? 0 : 1, powered);
                Throwable trialFailure = null;
                try {
                    return powered.equals(value) ? RetainedOperation.produced(Optional.of(bounds[0])) : Optional.empty();
                } catch (RuntimeException | Error failure) {
                    trialFailure = failure;
                    throw failure;
                } finally {
                    closeFrame(trial, trialFailure);
                }
            } catch (RuntimeException | Error failure) {
                searchFailure = failure;
                throw failure;
            } finally {
                closeFrame(search, searchFailure);
            }
        } catch (RuntimeException | Error failure) {
            upperFailure = failure;
            throw failure;
        } finally {
            closeFrame(upper, upperFailure);
        }
    }

    boolean equivalentTo(BoundedExactMonomial other) {
        return coefficient.equals(other.coefficient) && powers.equals(other.powers);
    }

    boolean isConstant(ExactRational expected) {
        return powers.isEmpty() && coefficient.equals(expected);
    }

    Expr toExpr() {
        Expr[] current = {null};
        var rendering = RetainedOperation.retainCompleted(1, this, current);
        Throwable renderingFailure = null;
        try {
            if (!coefficient.isOne() || powers.isEmpty()) {
                current[0] = coefficientExpression();
            }
            for (var entry : powers.entrySet()) {
                Expr factor = factorExpression(entry.getKey(), entry.getValue());
                var factorOwned = RetainedOperation.retain(factor);
                Throwable factorOwnedFailure = null;
                try {
                    current[0] = current[0] == null ? factor
                        : RetainedOperation.produced(new BinaryExpr(current[0], BinaryOperator.MUL, factor));
                    RetainedOperation.work(1);
                } catch (RuntimeException | Error failure) {
                    factorOwnedFailure = failure;
                    throw failure;
                } finally {
                    closeFrame(factorOwned, factorOwnedFailure);
                }
            }
            return current[0] == null ? RetainedOperation.produced(new NumberExpr(1)) : current[0];
        } catch (RuntimeException | Error failure) {
            renderingFailure = failure;
            throw failure;
        } finally {
            closeFrame(rendering, renderingFailure);
        }
    }

    private Expr coefficientExpression() {
        return RetainedOperation.produced(new NumberExpr(coefficient));
    }

    private static Expr factorExpression(String name, int exponent) {
        var variable = new VariableExpr(name);
        var leaf = RetainedOperation.retainCompleted(1, variable);
        Throwable leafFailure = null;
        try {
            if (exponent == 1) return variable;
            var number = new NumberExpr(exponent);
            var power = RetainedOperation.retainCompleted(1, number);
            Throwable powerFailure = null;
            try {
                return RetainedOperation.produced(new BinaryExpr(variable, BinaryOperator.POW, number));
            } catch (RuntimeException | Error failure) {
                powerFailure = failure;
                throw failure;
            } finally {
                closeFrame(power, powerFailure);
            }
        } catch (RuntimeException | Error failure) {
            leafFailure = failure;
            throw failure;
        } finally {
            closeFrame(leaf, leafFailure);
        }
    }

    private static Optional<BoundedExactMonomial> optional(BoundedExactMonomial monomial) {
        var value = RetainedOperation.retain(monomial);
        Throwable valueFailure = null;
        try {
            return RetainedOperation.produced(Optional.of(monomial));
        } catch (RuntimeException | Error failure) {
            valueFailure = failure;
            throw failure;
        } finally {
            closeFrame(value, valueFailure);
        }
    }

    static int positiveInteger(ExactRational value) {
        return value.isInteger() && value.signum() > 0
                && value.numerator().bitLength() <= 31
            ? value.intValueExact() : -1;
    }

    private static int checkedExponent(long value) {
        if (value > Integer.MAX_VALUE) {
            throw new LimitExceeded("ALGEBRAIC_EXPONENT_LIMIT");
        }
        return (int) value;
    }

    private static int bits(BigInteger value) {
        if (value.signum() >= 0) return value.bitLength();
        var magnitude = value.abs();
        var absolute = RetainedOperation.retainCompleted(1, value, magnitude);
        Throwable absoluteFailure = null;
        try {
            return magnitude.bitLength();
        } catch (RuntimeException | Error failure) {
            absoluteFailure = failure;
            throw failure;
        } finally {
            closeFrame(absolute, absoluteFailure);
        }
    }

    private static long powerBits(BigInteger value, int exponent) {
        int magnitudeBits = bits(value);
        return magnitudeBits <= 1 ? 1L : (long) magnitudeBits * exponent;
    }

    /** A sink may repeat its primary error while settling a frame's already completed release. */
    private static void closeFrame(RetainedOperation.Frame frame, Throwable primary) {
        if (frame == null) return;
        try { frame.close(); }
        catch (RuntimeException | Error cleanup) {
            if (primary == null) throw cleanup;
            if (cleanup != primary) primary.addSuppressed(cleanup);
        }
    }

    /** Shared by every inference/pre-filter in one matcher invocation. */
    static final class Budget implements RetainedGraph.View {
        private static final int MAX_VISITS = 10_000;
        private static final int MAX_DEPTH = 128;
        private static final int MAX_COEFFICIENT_BITS = 4_096;
        private int remaining = MAX_VISITS;
        @Override public void retainedReferences(RetainedGraph.Visitor visitor) { }

        private void visit(int depth) {
            if (depth > MAX_DEPTH || remaining-- <= 0) {
                throw new LimitExceeded("ALGEBRAIC_WORK_LIMIT");
            }
            // This visit is consumed before its debit can fail. It is never
            // delegated through MatchAttempt.visitedBranches or settled again.
            RetainedOperation.work(1);
        }

        private void coefficientBits(long numerator, long denominator) {
            visit(0);
            if (numerator > MAX_COEFFICIENT_BITS || denominator > MAX_COEFFICIENT_BITS) {
                throw new LimitExceeded("ALGEBRAIC_COEFFICIENT_LIMIT");
            }
        }
    }

    static final class LimitExceeded extends RuntimeException {
        private static final long serialVersionUID = 1L;
        final String code;

        private LimitExceeded(String code) {
            super(code, null, false, false);
            this.code = code;
        }
    }
}
