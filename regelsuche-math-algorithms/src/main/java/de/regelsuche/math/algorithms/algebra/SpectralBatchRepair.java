package de.regelsuche.math.algorithms.algebra;

import de.regelsuche.math.algorithms.equivalence.Polynomial;
import de.regelsuche.math.algorithms.iteration.IterationLimits;
import de.regelsuche.math.algorithms.linalg.ExactMatrixExpression;
import de.regelsuche.math.algorithms.linalg.SymbolicLinearSystem.PolynomialMatrix;
import de.regelsuche.scalar.ExactRational;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BooleanSupplier;

import static de.regelsuche.math.algorithms.algebra.OperatorResidualPlans.*;
import static de.regelsuche.math.algorithms.algebra.OperatorChecks.need;
import static de.regelsuche.math.algorithms.algebra.SpectralBatchMath.*;

/**
 * Finite rational specialization of full-rate Hermite repair, with source-derived
 * bounded search.
 */
public final class SpectralBatchRepair {
    private SpectralBatchRepair() {}

    public record Problem(PolynomialMatrix source, PolynomialMatrix basis, PolynomialMatrix inverse, int batchWidth) {
        public Problem {
            Objects.requireNonNull(source);
            Objects.requireNonNull(basis);
            Objects.requireNonNull(inverse);
            if (batchWidth < 1)
                throw new IllegalArgumentException("INVALID_BATCH_WIDTH");
        }
    }

    public record Frequency(List<Integer> support, PolynomialMatrix repair) {
        public Frequency {
            support = List.copyOf(support);
            Objects.requireNonNull(repair);
        }
    }

    public record Certificate(Problem problem, List<ExactMatrixExpression> predictors, int syndromeWidth,
            List<ExactRational> nodes, Optional<PolynomialMatrix> checks, List<Frequency> frequencies) {
        public Certificate {
            Objects.requireNonNull(problem);
            predictors = List.copyOf(predictors);
            nodes = List.copyOf(nodes);
            Objects.requireNonNull(checks);
            frequencies = List.copyOf(frequencies);
        }
    }

    public enum Recipe {
        ZERO, IDENTITY, DIAGONAL, TRIDIAGONAL, SPECTRAL_PARTITION
    }

    public record Policy(List<Recipe> recipes, int maximumSupport) {
        public Policy {
            recipes = List.copyOf(recipes);
            if (maximumSupport < 0)
                throw new IllegalArgumentException("INVALID_SUPPORT_LIMIT");
        }

        public static Policy defaults() {
            return new Policy(List.of(Recipe.ZERO, Recipe.IDENTITY, Recipe.DIAGONAL, Recipe.TRIDIAGONAL,
                    Recipe.SPECTRAL_PARTITION), 16);
        }
    }

    public record Candidate(String recipe, int syndromeWidth, Optional<Cost> cost, String detail) {}

    public record Discovery(PreparedSpectralBatch operator, List<Candidate> candidates) {
        public Discovery {
            candidates = List.copyOf(candidates);
        }
    }

    public static Result<Problem> deriveProblem(PolynomialMatrix source, int batchWidth, IterationLimits limits,
            BooleanSupplier cancel) {
        return OperatorChecks.run(limits, cancel, c -> derive(source, batchWidth, c));
    }

    public static Result<Certificate> construct(Problem problem, List<ExactMatrixExpression> predictors,
            IterationLimits limits, BooleanSupplier cancel) {
        return OperatorChecks.run(limits, cancel, c -> {
            var result = construct(problem, predictors, c);
            SpectralBatchRepairVerifier.check(problem, result, c);
            return result;
        });
    }

    public static Result<PreparedSpectralBatch> prepare(Problem expected, Certificate certificate,
            IterationLimits limits, BooleanSupplier cancel) {
        return OperatorChecks.run(limits, cancel,
                c -> prepare(expected, certificate, PreparedSpectralBatch.Kind.REPAIR, c));
    }

    public static Result<Discovery> discover(Problem problem, Policy policy, int maximumCandidates,
            IterationLimits limits, BooleanSupplier cancel) {
        return OperatorChecks.run(limits, cancel, c -> discover(problem, policy, maximumCandidates, c));
    }

    public static Result<Discovery> discover(PolynomialMatrix source, int batchWidth, Policy policy,
            int maximumCandidates, IterationLimits limits, BooleanSupplier cancel) {
        return OperatorChecks.run(limits, cancel, c -> {
            policy(policy, maximumCandidates, c);
            return discover(derive(source, batchWidth, c), policy, maximumCandidates, c);
        });
    }

    private static PreparedSpectralBatch prepare(Problem expected, Certificate certificate, PreparedSpectralBatch.Kind kind,
            OperatorChecks c) {
        var checked = SpectralBatchRepairVerifier.check(expected, certificate, c);
        return PreparedSpectralBatch.compile(certificate, checked, kind, c);
    }

    private static Certificate construct(Problem problem, List<ExactMatrixExpression> predictors, OperatorChecks c) {
        var z = target(problem, c);
        int n = problem.source().rows(), w = problem.batchWidth();
        need(predictors != null && predictors.size() == w, "PREDICTOR_COUNT_MISMATCH");
        c.reserve(w, n);
        var p = new ArrayList<List<ExactRational>>();
        for (var expression : predictors)
            p.add(prediction(problem, expression, c));
        c.reserve(n, w);
        var supports = new ArrayList<List<Integer>>();
        int maximum = 0;
        for (int nu = 0; nu < n; nu++) {
            var support = new ArrayList<Integer>();
            for (int i = 0; i < w; i++) {
                c.work.charge(1);
                if (!p.get(i).get(nu).equals(z.get(nu)))
                    support.add(i);
            }
            maximum = Math.max(maximum, support.size());
            supports.add(List.copyOf(support));
        }
        int s = maximum;
        if (s == 0)
            return new Certificate(problem, predictors, 0, List.of(), Optional.empty(), List.of());
        c.reserve(w, 1);
        c.reserve(s, w);
        var nodes = new ArrayList<ExactRational>();
        var powers = new ExactRational[s][w];
        for (int i = 0; i < w; i++) {
            var node = ExactRational.integer(i + 1L);
            nodes.add(node);
            var power = ExactRational.ONE;
            for (int k = 0; k < s; k++) {
                powers[k][i] = power;
                power = c.work.mul(power, node);
            }
        }
        var h = c.matrix(s, w, (i, j) -> scalar(powers[i][j]));
        c.reserve(n, 1);
        var frequencies = new ArrayList<Frequency>();
        for (int nu = 0; nu < n; nu++) {
            var support = supports.get(nu);
            int j = support.size();
            PolynomialMatrix a;
            if (j == 0)
                a = c.matrix(s, s, (i, k) -> Polynomial.zero());
            else {
                var v = c.matrix(j, j, (row, column) -> h.get(row, support.get(column)));
                var inverse = inverse(v, c);
                var left = c.matrix(j, s,
                        (row, column) -> column < j ? inverse.get(row, column) : Polynomial.zero());
                int frequency = nu;
                var d = c.matrix(j, j, (row, column) -> {
                    if (!row.equals(column))
                        return Polynomial.zero();
                    var value = c.work.div(c.work.add(p.get(support.get(row)).get(frequency),
                            c.work.mul(TWO, z.get(frequency))), TWO);
                    return scalar(c.work.mul(ExactRational.NEGATIVE_ONE, value));
                });
                a = c.times(transpose(left, c), c.times(d, left));
            }
            frequencies.add(new Frequency(support, a));
        }
        return new Certificate(problem, predictors, s, nodes, Optional.of(h), frequencies);
    }

    private static void policy(Policy policy, int maximumCandidates, OperatorChecks c) {
        Objects.requireNonNull(policy);
        if (maximumCandidates < 0)
            throw new IllegalArgumentException("NEGATIVE_CANDIDATE_LIMIT");
        if (policy.recipes().size() > maximumCandidates)
            throw new OperatorChecks.Rejected(Status.STOPPED, "CANDIDATE_LIMIT");
        c.work.cells(policy.recipes().size());
    }

    private static Discovery discover(Problem problem, Policy policy, int maximumCandidates, OperatorChecks c) {
        policy(policy, maximumCandidates, c);
        var z = target(problem, c);
        int w = problem.batchWidth();
        var source = new ExactMatrixExpression.Matrix("source", problem.source());
        var direct = new Certificate(problem, Collections.nCopies(w, source), 0, List.of(), Optional.empty(), List.of());
        var best = prepare(problem, direct, PreparedSpectralBatch.Kind.DIRECT, c);
        var spectral = conjugate(problem, diagonal(z, c));
        var spectralCertificate = new Certificate(problem, Collections.nCopies(w, spectral), 0, List.of(),
                Optional.empty(), List.of());
        var alternative = prepare(problem, spectralCertificate, PreparedSpectralBatch.Kind.SPECTRAL_DIRECT, c);
        if (alternative.cost().total() < best.cost().total())
            best = alternative;
        var candidates = new ArrayList<Candidate>();
        for (var recipe : policy.recipes()) {
            c.work.charge(1);
            try {
                var proposed = construct(problem, predictors(problem, z, recipe, c), c);
                int s = proposed.syndromeWidth();
                if (s > policy.maximumSupport()) {
                    candidates.add(new Candidate(recipe.name(), s, Optional.empty(), "SUPPORT_LIMIT"));
                    continue;
                }
                var prepared = prepare(problem, proposed, PreparedSpectralBatch.Kind.REPAIR, c);
                candidates.add(new Candidate(recipe.name(), s, Optional.of(prepared.cost()), ""));
                if (prepared.cost().total() < best.cost().total())
                    best = prepared;
            } catch (OperatorChecks.Rejected rejected) {
                if (rejected.status != Status.INVALID || !rejected.getMessage().equals("NOT_COMMON_EIGENBASIS"))
                    throw rejected;
                candidates.add(new Candidate(recipe.name(), -1, Optional.empty(), rejected.getMessage()));
            }
        }
        c.work.cells(best.retainedCells());
        return new Discovery(best, candidates);
    }

    private static List<ExactMatrixExpression> predictors(Problem p, List<ExactRational> z, Recipe recipe,
            OperatorChecks c) {
        int n = p.source().rows(), w = p.batchWidth();
        c.reserve(w, 1);
        if (recipe == Recipe.SPECTRAL_PARTITION) {
            var result = new ArrayList<ExactMatrixExpression>();
            for (int i = 0; i < w; i++) {
                final int row = i;
                var d = c.matrix(n, n,
                        (a, b) -> a.equals(b) && a % w != row ? scalar(z.get(a)) : Polynomial.zero());
                result.add(conjugate(p, d));
            }
            return List.copyOf(result);
        }
        if (recipe == Recipe.IDENTITY)
            return Collections.nCopies(w, new ExactMatrixExpression.Identity(n));
        var m = c.matrix(n, n, (i, j) -> switch (recipe) {
            case ZERO -> Polynomial.zero();
            case DIAGONAL -> i.equals(j) ? p.source().get(i, j) : Polynomial.zero();
            case TRIDIAGONAL -> Math.abs(i - j) <= 1 ? p.source().get(i, j) : Polynomial.zero();
            default -> throw new IllegalArgumentException("INVALID_PREDICTOR_RECIPE");
        });
        return Collections.nCopies(w, new ExactMatrixExpression.Matrix(recipe.name(), m));
    }

    private static ExactMatrixExpression conjugate(Problem p, PolynomialMatrix d) {
        return new ExactMatrixExpression.Product(new ExactMatrixExpression.Matrix("basis", p.basis()),
                new ExactMatrixExpression.Product(new ExactMatrixExpression.Matrix("diagonal", d),
                        new ExactMatrixExpression.Matrix("inverse", p.inverse())));
    }
}
