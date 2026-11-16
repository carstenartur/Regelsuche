package de.regelsuche.math.algorithms.iteration;

import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.NumberExpr;
import de.regelsuche.ast.VariableExpr;
import de.regelsuche.ast.iteration.FiniteIteration;
import de.regelsuche.scalar.ExactRational;
import de.regelsuche.symbol.SymbolId;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.BinaryOperator;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;

import static de.regelsuche.ast.BinaryOperator.DIV;
import static de.regelsuche.ast.BinaryOperator.MUL;
import static de.regelsuche.ast.BinaryOperator.POW;
import static de.regelsuche.math.algorithms.iteration.IterationPolynomial.Unavailable;
import static de.regelsuche.math.algorithms.iteration.IterationPolynomial.Work;

/** Bounded search and exact replay of a weighted common-scale representation. */
public final class ProjectiveHandoff {
    public static final int MAX_WEIGHT = 12;
    public static final int MAX_COORDINATES = 64;
    private static final int MAX_POLYNOMIAL_DEGREE = 32;

    private ProjectiveHandoff() {}

    /** The affine coordinates in order are numerators[i] / scale^weights[i]. */
    public record Candidate(Expr scale, List<Integer> weights, List<Expr> numerators) {
        public Candidate {
            Objects.requireNonNull(scale);
            if (weights.size() > MAX_COORDINATES || numerators.size() > MAX_COORDINATES) {
                throw new IllegalArgumentException("PROJECTIVE_COORDINATE_LIMIT");
            }
            weights = List.copyOf(weights);
            numerators = List.copyOf(numerators);
        }
    }

    public record Result(Optional<Verified> verified, String reason, long work) {}

    /**
     * A source coordinate is an integral polynomial or one explicit division of
     * integral polynomials. Scale candidates are tried in input order, then
     * drawn from the exposed denominator products and powers.
     */
    public static Result search(List<Expr> affine, List<Expr> scaleCandidates, IterationLimits limits,
            BooleanSupplier cancellation) {
        return search(affine, scaleCandidates, limits, Integer.MAX_VALUE, cancellation);
    }

    public static Result search(List<Expr> affine, List<Expr> scaleCandidates, IterationLimits limits,
            int maximumCandidates, BooleanSupplier cancellation) {
        if (maximumCandidates < 1) throw new IllegalArgumentException("INVALID_PROJECTIVE_CANDIDATE_LIMIT");
        var work = new Work(limits, cancellation, MAX_POLYNOMIAL_DEGREE);
        try {
            var source = readSource(affine, limits, work);
            var scales = new ArrayList<Expr>();
            for (var scale : scaleCandidates) {
                work.charge(1);
                scales.add(Objects.requireNonNull(scale));
            }
            for (var expression : affine) {
                if (expression instanceof BinaryExpr binary && binary.operator() == DIV) {
                    collectScales(binary.right(), scales, work);
                }
            }
            for (int i = 0; i < scales.size(); i++) {
                if (i == maximumCandidates) return unavailable("PROJECTIVE_CANDIDATE_LIMIT", work);
                var scaleExpression = scales.get(i);
                var shape = proposedShape(scaleExpression, source, work);
                if (shape == null) continue;
                var weights = shape.coordinates().stream().map(power -> Math.max(1, power.weight())).toList();
                var numerators = new ArrayList<Expr>();
                for (int coordinate = 0; coordinate < weights.size(); coordinate++) {
                    var power = shape.coordinates().get(coordinate);
                    Expr expression = source.coordinates().get(coordinate).numeratorExpression();
                    if (power.negative()) expression = new BinaryExpr(new NumberExpr(-1), MUL, expression);
                    if (power.weight() == 0) expression = new BinaryExpr(expression, MUL, scaleExpression);
                    numerators.add(expression);
                }
                var candidate = new Candidate(scaleExpression, weights, numerators);
                return check(source, candidate, shape, work);
            }
            return unavailable("NO_SHARED_PROJECTIVE_SCALE", work);
        } catch (Unavailable unavailable) {
            return unavailable(unavailable.getMessage(), work);
        }
    }

    /** Replay consumes the source, candidate and a fresh work budget. */
    public static Result verify(List<Expr> affine, Candidate candidate, IterationLimits limits,
            BooleanSupplier cancellation) {
        var work = new Work(limits, cancellation, MAX_POLYNOMIAL_DEGREE);
        try {
            var source = readSource(affine, limits, work);
            var scale = polynomial(candidate.scale(), source.symbols(), work);
            return check(source, candidate, shape(source, scale, work), work);
        } catch (Unavailable unavailable) {
            return unavailable(unavailable.getMessage(), work);
        }
    }

    private static Result check(Source source, Candidate candidate, Shape shape, Work work) {
        if (shape == null || candidate.numerators().size() != source.coordinates().size()
                || candidate.weights().size() != source.coordinates().size()) {
            return unavailable("INVALID_PROJECTIVE_CANDIDATE", work);
        }
        for (int i = 0; i < source.coordinates().size(); i++) {
            work.charge(1);
            int weight = candidate.weights().get(i);
            if (weight < 1 || weight > MAX_WEIGHT) return unavailable("INVALID_PROJECTIVE_WEIGHT", work);
            var expected = source.coordinates().get(i).numerator();
            var actual = polynomial(candidate.numerators().get(i), source.symbols(), work);
            var sourcePower = shape.coordinates().get(i);
            if (sourcePower.negative()) expected = expected.scale(ExactRational.NEGATIVE_ONE);
            // Cancel only the checked unit powers common to both sides.
            for (int j = sourcePower.weight(); j < weight; j++) expected = expected.multiply(shape.scale());
            for (int j = weight; j < sourcePower.weight(); j++) actual = actual.multiply(shape.scale());
            if (!actual.same(expected)) return unavailable("PROJECTIVE_IDENTITY_MISMATCH", work);
        }
        return new Result(Optional.of(new Verified(candidate, source.inverseCoordinates())), "VERIFIED", work.used());
    }

    private static Source readSource(List<Expr> affine, IterationLimits limits, Work work) {
        work.charge(1);
        if (affine.isEmpty() || affine.size() > Math.min(MAX_COORDINATES, limits.maximumTerms())) {
            throw new Unavailable("PROJECTIVE_COORDINATE_LIMIT");
        }
        var symbols = new LinkedHashSet<SymbolId>();
        for (var expression : affine) collectSymbols(expression, symbols, 1, work);
        var coordinates = new ArrayList<Fraction>();
        int inverseCoordinates = 0;
        for (var expression : affine) {
            var division = expression instanceof BinaryExpr binary && binary.operator() == DIV ? binary : null;
            var numeratorExpression = division == null ? expression : division.left();
            var numerator = polynomial(numeratorExpression, symbols, work);
            var denominator = division == null ? IterationPolynomial.constant(BigInteger.ONE, work)
                : polynomial(division.right(), symbols, work);
            if (denominator.terms().isEmpty()) throw new Unavailable("ZERO_PROJECTIVE_DENOMINATOR");
            int degree = degree(denominator, work);
            if (degree > 0) inverseCoordinates++;
            coordinates.add(new Fraction(numeratorExpression, numerator, denominator, degree));
        }
        if (inverseCoordinates == 0) throw new Unavailable("NO_PROJECTIVE_DENOMINATOR");
        return new Source(coordinates, symbols, inverseCoordinates);
    }

    private static void collectSymbols(Expr expression, Set<SymbolId> symbols, int depth, Work work) {
        work.charge(1);
        if (depth > FiniteIteration.MAX_DEPTH) throw new Unavailable("EXPRESSION_LIMIT");
        if (expression instanceof VariableExpr variable) {
            symbols.add(variable.symbol().orElseThrow(() -> new Unavailable("UNBOUND_SYMBOL")));
        } else if (expression instanceof BinaryExpr binary) {
            collectSymbols(binary.left(), symbols, depth + 1, work);
            collectSymbols(binary.right(), symbols, depth + 1, work);
        }
    }

    private static IterationPolynomial polynomial(Expr expression, Set<SymbolId> symbols, Work work) {
        return IterationPolynomial.parseIntegralWithPowers(expression, symbols, work);
    }

    private static void collectScales(Expr expression, List<Expr> scales, Work work) {
        work.charge(1);
        if (expression instanceof BinaryExpr binary) {
            if (binary.operator() == POW || binary.operator() == MUL) collectScales(binary.left(), scales, work);
            if (binary.operator() == MUL) collectScales(binary.right(), scales, work);
        }
        if (!(expression instanceof NumberExpr)) scales.add(expression);
    }

    private static Shape proposedShape(Expr expression, Source source, Work work) {
        try {
            return shape(source, polynomial(expression, source.symbols(), work), work);
        } catch (Unavailable unavailable) {
            if ("CANCELLED".equals(unavailable.getMessage()) || "WORK_LIMIT".equals(unavailable.getMessage())) {
                throw unavailable;
            }
            return null;
        }
    }

    private static Shape shape(Source source, IterationPolynomial scale, Work work) {
        int scaleDegree = degree(scale, work);
        if (scaleDegree <= 0) return null;
        var unit = IterationPolynomial.constant(BigInteger.ONE, work);
        int maxWeight = 0;
        for (var coordinate : source.coordinates()) {
            int degree = coordinate.degree();
            if (degree % scaleDegree != 0 || degree / scaleDegree > MAX_WEIGHT) return null;
            maxWeight = Math.max(maxWeight, degree / scaleDegree);
        }
        var powers = new ArrayList<IterationPolynomial>(maxWeight + 1);
        powers.add(unit);
        for (int weight = 1; weight <= maxWeight; weight++) {
            powers.add(powers.getLast().multiply(scale));
        }
        var signedPowers = new ArrayList<SignedPower>();
        for (var coordinate : source.coordinates()) {
            int weight = coordinate.degree() / scaleDegree;
            var expected = powers.get(weight);
            boolean negative = !coordinate.denominator().same(expected);
            if (negative && !coordinate.denominator().same(expected.scale(ExactRational.NEGATIVE_ONE))) return null;
            signedPowers.add(new SignedPower(weight, negative));
        }
        return new Shape(scale, signedPowers);
    }

    private static int degree(IterationPolynomial polynomial, Work work) {
        int degree = -1;
        for (var term : polynomial.terms().keySet()) {
            work.charge(1);
            degree = Math.max(degree, term.symbols().size());
        }
        return degree;
    }

    private static Result unavailable(String reason, Work work) {
        return new Result(Optional.empty(), reason, work.used());
    }

    private record Fraction(Expr numeratorExpression, IterationPolynomial numerator, IterationPolynomial denominator,
            int degree) {}
    private record Source(List<Fraction> coordinates, Set<SymbolId> symbols, int inverseCoordinates) {}
    private record SignedPower(int weight, boolean negative) {}
    private record Shape(IterationPolynomial scale, List<SignedPower> coordinates) {}

    public static final class Verified {
        private final Candidate candidate;
        private final int inverseCoordinates;

        private Verified(Candidate candidate, int inverseCoordinates) {
            this.candidate = candidate;
            this.inverseCoordinates = inverseCoordinates;
        }

        public Candidate candidate() { return candidate; }

        /**
         * Bind values evaluated from this candidate in a single exact commutative
         * ring. Empty means the scale is not a unit and the caller must use the
         * original exceptional or singular path. Use a unit predicate, e.g. a
         * nonzero test only when the ring is a field.
         */
        public <T> Optional<Carried<T>> carry(T scale, List<T> numerators, Predicate<T> isUnit) {
            if (numerators.size() != candidate.weights().size()) {
                throw new IllegalArgumentException("PROJECTIVE_COORDINATE_COUNT");
            }
            Objects.requireNonNull(scale);
            var values = List.copyOf(numerators);
            return isUnit.test(scale) ? Optional.of(new Carried<>(scale, values, candidate.weights())) : Optional.empty();
        }

        private int materializationMultiplications() {
            return candidate.weights().size() + maximumWeight(candidate.weights()) - 1;
        }
    }

    /** An ordered projective state whose shared scale passed the unit guard. */
    public static final class Carried<T> {
        private final T scale;
        private final List<T> numerators;
        private final List<Integer> weights;

        private Carried(T scale, List<T> numerators, List<Integer> weights) {
            this.scale = scale;
            this.numerators = numerators;
            this.weights = weights;
        }

        public T scale() { return scale; }
        public List<T> numerators() { return numerators; }
        public List<Integer> weights() { return weights; }

        /** Materialize every coordinate with one inversion and shared inverse powers. */
        public List<T> materialize(UnaryOperator<T> invert, BinaryOperator<T> multiply) {
            var inverse = Objects.requireNonNull(invert.apply(scale));
            int maximumWeight = maximumWeight(weights);
            var powers = new ArrayList<T>(maximumWeight);
            powers.add(inverse);
            for (int weight = 2; weight <= maximumWeight; weight++) {
                powers.add(multiply.apply(powers.getLast(), inverse));
            }
            var values = new ArrayList<T>(weights.size());
            for (int i = 0; i < weights.size(); i++) {
                values.add(multiply.apply(numerators.get(i), powers.get(weights.get(i) - 1)));
            }
            return List.copyOf(values);
        }
    }

    private static int maximumWeight(List<Integer> weights) {
        return weights.stream().mapToInt(Integer::intValue).max().orElseThrow();
    }

    /**
     * The caller vouches that a projective consumer accepts the carried values
     * whenever materialize is false. Counts are for the successful unit path.
     * Extra multiplications cover preparation/transfer beyond the affine baseline.
     */
    public record ChainUse(Verified handoff, int baselineInversions, boolean materialize,
            long additionalMultiplications) {
        public ChainUse {
            Objects.requireNonNull(handoff);
            if (baselineInversions < 1 || baselineInversions > handoff.inverseCoordinates
                    || additionalMultiplications < 0) {
                throw new IllegalArgumentException("INVALID_PROJECTIVE_CHAIN_USE");
            }
        }
    }

    public record Costs(long inversion, long multiplication, long unitCheck) {
        public Costs {
            if (inversion <= 0 || multiplication <= 0 || unitCheck < 0) {
                throw new IllegalArgumentException("INVALID_PROJECTIVE_COSTS");
            }
        }
    }

    public record Estimate(long originalInversions, long remainingInversions, long additionalMultiplications,
            long unitChecks, BigInteger netSaving) {
        public long savedInversions() { return originalInversions - remainingInversions; }
        public boolean profitable() { return netSaving.signum() > 0; }
    }

    public static Estimate estimateChain(List<ChainUse> uses, Costs costs) {
        long original = 0, remaining = 0, multiplications = 0;
        for (var use : uses) {
            original = Math.addExact(original, use.baselineInversions());
            multiplications = Math.addExact(multiplications, use.additionalMultiplications());
            if (use.materialize()) {
                remaining++;
                multiplications = Math.addExact(multiplications, use.handoff().materializationMultiplications());
            }
        }
        long unitChecks = uses.size();
        var saving = cost(original - remaining, costs.inversion())
            .subtract(cost(multiplications, costs.multiplication()))
            .subtract(cost(unitChecks, costs.unitCheck()));
        return new Estimate(original, remaining, multiplications, unitChecks, saving);
    }

    private static BigInteger cost(long count, long unitCost) {
        return BigInteger.valueOf(count).multiply(BigInteger.valueOf(unitCost));
    }
}
