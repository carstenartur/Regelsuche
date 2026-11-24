package de.regelsuche.evolution;

import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.BinaryOperator;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.NumberExpr;
import de.regelsuche.canonical.ExpressionCanonicalizer;
import de.regelsuche.canonical.PolynomialNormalizer;
import de.regelsuche.math.algorithms.equivalence.SparsePolynomialHorner;
import de.regelsuche.math.algorithms.equivalence.SparsePolynomialForms;
import de.regelsuche.polynomial.PolynomialWorkAuthority;
import de.regelsuche.scalar.ExactRational;
import de.regelsuche.search.moves.TypedSourceOnlySearch;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Optional;
import java.util.function.Supplier;

/** Bounded, typed endpoint proposal. The structural provider admits it only after whole-source verification. */
final class DirectPolynomialPortfolio {
    private static final int MAX_ROUNDS = 6;
    private static final long MAX_FACTOR_WORK = 3_000;
    private static final long MAX_PROPOSAL_WORK = 12_288;
    private static final NumberExpr ZERO = new NumberExpr(0);
    private static final CheckedLearnedSchemaModel.Bounds DOMAIN = CheckedLearnedSchemaModel.Bounds.defaults();
    private static final Candidate OUTSIDE_DOMAIN = new Candidate(null, 0, 0);
    private final ExpressionCanonicalizer canonicalizer = new ExpressionCanonicalizer();
    private final HashMap<Expr, Expr> results = new HashMap<>();
    private final IdentityHashMap<Expr, TypedSourceOnlySearch.Score> scores = new IdentityHashMap<>();
    private final IdentityHashMap<Expr, Candidate> candidates = new IdentityHashMap<>();
    private final long limit;
    private final boolean sharedArithmetic;
    private long work;
    private Expr incumbent;
    private int lowerBound;

    record Result(Expr expression, long work) {}

    private DirectPolynomialPortfolio(Expr source, long limit, boolean sharedArithmetic) {
        incumbent = source;
        this.limit = Math.min(limit, MAX_PROPOSAL_WORK);
        this.sharedArithmetic = sharedArithmetic;
    }

    static Result propose(Expr source, long limit, boolean sharedArithmetic) {
        var portfolio = new DirectPolynomialPortfolio(source, limit, sharedArithmetic);
        try {
            for (int round = 0; round < MAX_ROUNDS; round++) {
                var before = portfolio.incumbent;
                portfolio.considerSparseRoot(before);
                if (portfolio.score(portfolio.incumbent).value() <= portfolio.lowerBound) break;
                portfolio.rewrite(before, true);
                if (portfolio.incumbent == before) break;
            }
        } catch (Exhausted | Optimum exhausted) {
            // A completed root proposal can still be checked against the original source.
        }
        return new Result(portfolio.incumbent, portfolio.work);
    }

    private void considerSparseRoot(Expr source) {
        if (!(source instanceof BinaryExpr) || score(source).value() <= 1) return;
        long end = work + Math.min(3_300L, remaining());
        java.util.function.LongConsumer local = units -> {
            if (units > end - work) throw new SparseLimit();
            charge(units);
        };
        try {
            lowerBound = SparsePolynomialForms.proposeWithSurfaceBound(source, sharedArithmetic, local,
                candidate -> select(incumbent, candidate, true));
            if (score(incumbent).value() <= lowerBound) return;
            if (score(incumbent).value() > 1 && (incumbent == source || sharedArithmetic)) {
                SparsePolynomialHorner.propose(source, local).ifPresent(candidate -> select(incumbent, candidate, true));
            }
        } catch (SparseLimit | IllegalArgumentException unsupported) {
            // Retain completed candidates and try the other typed proposals.
        }
    }

    private Expr rewrite(Expr source, boolean atRoot) {
        charge(1);
        if (!(source instanceof BinaryExpr binary)) return source;
        // Structurally equal source fragments can reuse an already paid proposal.
        charge(score(source).work());
        var known = results.get(source);
        if (known != null) return select(source, known, atRoot);
        var left = rewrite(binary.left(), false);
        var right = rewrite(binary.right(), false);
        charge(1);
        Expr rebuilt = left == binary.left() && right == binary.right() ? binary
            : new BinaryExpr(left, binary.operator(), right);
        Expr best = improve(source, rebuilt, atRoot);
        charge(score(source).work());
        results.put(source, best);
        return best;
    }

    private Expr improve(Expr source, Expr rebuilt, boolean atRoot) {
        Expr folded = elementary((BinaryExpr) rebuilt);
        Expr best = select(source, folded, atRoot);
        if (!(folded instanceof BinaryExpr binary) || score(folded).value() <= 1) return best;
        if (binary.operator() == BinaryOperator.ADD || binary.operator() == BinaryOperator.SUB) {
            best = grouping(best, folded, atRoot);
            best = horner(best, folded, atRoot);
            if (score(best).value() <= 1) return best;
            best = expanded(best, folded, atRoot);
            if (score(best).value() >= 3 && remaining() >= 384) best = factored(best, folded, atRoot);
        }
        if (score(best).value() > 1) {
            var canonical = observed(() -> canonicalizer.canonicalize(folded), 1_800);
            if (canonical.isPresent()) {
                best = select(best, canonical.orElseThrow(), atRoot);
                best = compact(best, canonical.orElseThrow(), atRoot);
                var combined = equalIntegralPowers(canonical.orElseThrow());
                if (combined.isPresent()) best = select(best, combined.orElseThrow(), atRoot);
            }
        }
        return best;
    }

    private Expr grouping(Expr best, Expr source, boolean atRoot) {
        var grouped = observed(() -> PolynomialFactorGrouping.candidates(source), 2_200);
        if (grouped.isEmpty()) return best;
        for (Expr candidate : grouped.orElseThrow()) {
            best = select(best, candidate, atRoot);
            var canonical = observed(() -> canonicalizer.canonicalize(candidate), 1_400);
            if (canonical.isPresent()) best = compact(best, canonical.orElseThrow(), atRoot);
        }
        return best;
    }

    private Expr horner(Expr best, Expr source, boolean atRoot) {
        if (score(best).value() <= 1) return best;
        try {
            var candidate = SparsePolynomialHorner.propose(source, this::charge);
            return candidate.isPresent() ? select(best, candidate.orElseThrow(), atRoot) : best;
        } catch (IllegalArgumentException unsupported) {
            return best;
        }
    }

    private Expr expanded(Expr best, Expr source, boolean atRoot) {
        var expanded = observed(() -> new PolynomialNormalizer().normalize(source), 2_000).flatMap(value -> value);
        return expanded.isPresent() ? compact(best, expanded.orElseThrow(), atRoot) : best;
    }

    private Expr compact(Expr best, Expr source, boolean atRoot) {
        return observed(() -> PolynomialSimplificationPrograms.compact(source), 512)
            .map(candidate -> select(best, candidate, atRoot)).orElse(best);
    }

    private Expr factored(Expr best, Expr source, boolean atRoot) {
        try {
            for (var candidate : PolynomialSimplificationPrograms.factorRoot(source,
                    Math.min(MAX_FACTOR_WORK, remaining()), this::charge)) best = select(best, candidate, atRoot);
        } catch (PolynomialWorkAuthority.LimitReached localLimit) {
            // The next proposal may still fit after this bounded factor attempt.
        }
        return best;
    }

    private Expr elementary(BinaryExpr source) {
        Expr left = source.left(), right = source.right();
        charge(1);
        if (source.operator() == BinaryOperator.ADD && is(left, ExactRational.ZERO)) return right;
        if ((source.operator() == BinaryOperator.ADD || source.operator() == BinaryOperator.SUB)
                && is(right, ExactRational.ZERO)) return left;
        if (source.operator() == BinaryOperator.SUB && left == right) return ZERO;
        if (source.operator() == BinaryOperator.MUL) {
            if (is(left, ExactRational.ZERO) || is(right, ExactRational.ZERO)) return ZERO;
            if (is(left, ExactRational.ONE)) return right;
        }
        if ((source.operator() == BinaryOperator.MUL || source.operator() == BinaryOperator.DIV
                || source.operator() == BinaryOperator.POW) && is(right, ExactRational.ONE)) return left;
        if (left instanceof NumberExpr && right instanceof NumberExpr) {
            return observed(() -> canonicalizer.canonicalize(source), 512).orElse(source);
        }
        return source;
    }

    private static boolean is(Expr operand, ExactRational exact) {
        return operand instanceof NumberExpr number && number.value().equals(exact);
    }

    private Optional<Expr> equalIntegralPowers(Expr canonical) {
        charge(1);
        if (canonical instanceof BinaryExpr product && product.operator() == BinaryOperator.MUL
                && product.left() instanceof BinaryExpr left && left.operator() == BinaryOperator.POW
                && product.right() instanceof BinaryExpr right && right.operator() == BinaryOperator.POW
                && left.right() instanceof NumberExpr exponent && exponent.value().isInteger()
                && exponent.value().signum() > 0 && exponent.value().numerator().bitLength() <= 4
                && exponent.equals(right.right())) {
            charge(2);
            return Optional.of(new BinaryExpr(new BinaryExpr(left.left(), BinaryOperator.MUL, right.left()),
                BinaryOperator.POW, exponent));
        }
        return Optional.empty();
    }

    private Expr select(Expr first, Expr second, boolean atRoot) {
        if (first == second) return first;
        var candidate = candidate(second);
        if (candidate == OUTSIDE_DOMAIN || score(candidate.expression()).value() >= score(first).value()) return first;
        if (atRoot && score(candidate.expression()).value() < score(incumbent).value()) {
            incumbent = candidate.expression();
            if (score(incumbent).value() <= lowerBound) throw new Optimum();
        }
        return candidate.expression();
    }

    private record Candidate(Expr expression, int nodes, int depth) {}

    private Candidate candidate(Expr source) {
        charge(1);
        var cached = candidates.get(source);
        if (cached != null) return cached;
        var result = switch (source) {
            case NumberExpr number -> literal(number);
            case BinaryExpr binary -> binary(binary);
            case de.regelsuche.ast.VariableExpr variable -> new Candidate(variable, 1, 0);
            default -> OUTSIDE_DOMAIN;
        };
        candidates.put(source, result);
        return result;
    }

    private Candidate literal(NumberExpr number) {
        var value = number.value();
        charge(1);
        if (value.numerator().abs().bitLength() > DOMAIN.maximumCoefficientBits()
                || value.denominator().bitLength() > DOMAIN.maximumCoefficientBits()) return OUTSIDE_DOMAIN;
        if (value.isInteger()) return new Candidate(number, 1, 0);
        // The ordinary exact replay wire uses explicit integer ratios, including terminating decimals.
        charge(3);
        return new Candidate(new BinaryExpr(new NumberExpr(ExactRational.integer(value.numerator())),
            BinaryOperator.DIV, new NumberExpr(ExactRational.integer(value.denominator()))), 3, 1);
    }

    private Candidate binary(BinaryExpr source) {
        var left = candidate(source.left());
        var right = candidate(source.right());
        if (left == OUTSIDE_DOMAIN || right == OUTSIDE_DOMAIN) return OUTSIDE_DOMAIN;
        if (source.operator() == BinaryOperator.POW && !admissibleExponent(right.expression())) return OUTSIDE_DOMAIN;
        if (source.operator() == BinaryOperator.DIV) {
            if (source.right() instanceof NumberExpr denominator && !denominator.value().isInteger()) {
                return divideByRatio(left, denominator.value());
            }
            if (!(right.expression() instanceof NumberExpr denominator) || denominator.value().isZero())
                return OUTSIDE_DOMAIN;
        }
        return joined(source, left, source.operator(), right);
    }

    private static boolean admissibleExponent(Expr expression) {
        return expression instanceof NumberExpr number && number.value().isInteger() && number.value().signum() >= 0
            && number.value().numerator().compareTo(java.math.BigInteger.valueOf(DOMAIN.maximumExponent())) <= 0;
    }

    private Candidate divideByRatio(Candidate left, ExactRational divisor) {
        charge(2);
        var numerator = new Candidate(new NumberExpr(ExactRational.integer(divisor.numerator())), 1, 0);
        var denominator = new Candidate(new NumberExpr(ExactRational.integer(divisor.denominator())), 1, 0);
        var scaled = joined(null, left, BinaryOperator.MUL, denominator);
        return scaled == OUTSIDE_DOMAIN ? scaled : joined(null, scaled, BinaryOperator.DIV, numerator);
    }

    private Candidate joined(BinaryExpr original, Candidate left, BinaryOperator operator, Candidate right) {
        int nodes = 1 + left.nodes() + right.nodes();
        int depth = 1 + Math.max(left.depth(), right.depth());
        if (nodes > DOMAIN.maximumExpressionNodes() || depth > DOMAIN.maximumDepth()) return OUTSIDE_DOMAIN;
        if (original != null && left.expression() == original.left() && right.expression() == original.right())
            return new Candidate(original, nodes, depth);
        charge(1);
        return new Candidate(new BinaryExpr(left.expression(), operator, right.expression()), nodes, depth);
    }

    private TypedSourceOnlySearch.Score score(Expr candidate) {
        charge(1);
        return scores.computeIfAbsent(candidate, expression -> {
            var score = sharedArithmetic ? TypedPolynomialSurfaceCost.evaluateSharedArithmetic(expression)
                : TypedPolynomialSurfaceCost.evaluate(expression);
            charge(score.work());
            return score;
        });
    }

    private <T> Optional<T> observed(Supplier<T> procedure, long localLimit) {
        long allowance = Math.min(localLimit, remaining());
        var report = StructuralExpansionRules.observe(procedure, allowance);
        charge(Math.min(report.work(), allowance));
        return report.exhausted() ? Optional.empty() : Optional.of(report.value());
    }

    private long remaining() { return limit - work; }

    private void charge(long units) {
        if (units > remaining() || Thread.currentThread().isInterrupted()) throw new Exhausted();
        work += units;
    }

    private static final class Exhausted extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }

    private static final class Optimum extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }

    private static final class SparseLimit extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }
}
