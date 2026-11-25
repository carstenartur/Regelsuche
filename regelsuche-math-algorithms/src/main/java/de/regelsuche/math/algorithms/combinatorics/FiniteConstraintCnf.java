package de.regelsuche.math.algorithms.combinatorics;

import de.regelsuche.math.algorithms.certificates.CertificateWork;
import de.regelsuche.math.algorithms.combinatorics.FiniteConstraintCounter.Factor;
import de.regelsuche.math.algorithms.combinatorics.FiniteConstraintCounter.Problem;
import de.regelsuche.math.algorithms.iteration.IterationLimits;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BooleanSupplier;

/** Count-preserving signed CNF translation, v1. Auxiliary variables are private OR-prefix values. */
public final class FiniteConstraintCnf {
    // Two truth-table copies (at most eight rows) plus temporary scopes and literal arrays.
    private static final int FACTOR_SCRATCH_CELLS = 32;

    private FiniteConstraintCnf() {}

    /** Literals use signed one-based DIMACS identifiers. Fixed assignments use zero-based factor IDs. */
    public record Source(int variableCount, List<List<Integer>> clauses, Map<Integer, Boolean> fixed) {
        public Source {
            if (variableCount < 0) throw new IllegalArgumentException("NEGATIVE_VARIABLE_COUNT");
            var snapshot = new ArrayList<List<Integer>>(clauses.size());
            for (var clause : clauses) {
                var copy = List.copyOf(clause);
                for (int literal : copy) {
                    if (literal == 0 || Math.abs((long) literal) > variableCount)
                        throw new IllegalArgumentException("LITERAL_OUT_OF_RANGE");
                }
                snapshot.add(copy);
            }
            clauses = List.copyOf(snapshot);
            fixed = ImmutableIntegerMap.copyOf(fixed);
            for (int variable : fixed.keySet()) {
                if (variable < 0 || variable >= variableCount) throw new IllegalArgumentException("FIXED_OUT_OF_RANGE");
            }
        }
    }

    public record Certificate(Source source, FiniteConstraintCounter.Certificate elimination) {
        public Certificate { Objects.requireNonNull(source); Objects.requireNonNull(elimination); }
    }

    public record Lowering(FiniteConstraintVerifier.Status status, Optional<Problem> problem, String reason, long work) {}
    public record Result(FiniteConstraintCounter.Status status, Optional<BigInteger> count,
            Optional<Certificate> certificate, String reason, long work) {}

    public static Lowering lower(Source source, IterationLimits limits, BooleanSupplier cancellation) {
        var work = new CertificateWork(limits, cancellation);
        try {
            var problem = translate(source, sourceCells(source, work), work);
            return new Lowering(FiniteConstraintVerifier.Status.VERIFIED, Optional.of(problem), "VERIFIED", work.used());
        } catch (CertificateWork.Stop stop) {
            return new Lowering(FiniteConstraintVerifier.Status.UNAVAILABLE, Optional.empty(), stop.getMessage(), work.used());
        }
    }

    public static Result count(Source source, IterationLimits limits, BooleanSupplier cancellation) {
        var work = new CertificateWork(limits, cancellation);
        try {
            long retained = sourceCells(source, work);
            Problem problem = translate(source, retained, work);
            var result = FiniteConstraintCounter.countIndexed(problem, FiniteConstraintPlanner.Strategy.MIN_DEGREE,
                work.remainingLimits(retained), cancellation);
            work.chargeCompleted(result.work());
            work.charge(0);
            return new Result(result.status(), result.count(), result.certificate().map(c -> new Certificate(source, c)),
                result.reason(), work.used());
        } catch (CertificateWork.Stop stop) {
            return new Result(FiniteConstraintCounter.Status.UNAVAILABLE, Optional.empty(), Optional.empty(),
                stop.getMessage(), work.used());
        }
    }

    public static FiniteConstraintVerifier.Verification verify(Source source, Certificate certificate,
            IterationLimits limits, BooleanSupplier cancellation) {
        return FiniteConstraintCnfVerifier.verify(source, certificate, limits, cancellation);
    }

    static long sourceCells(Source source, CertificateWork work) {
        long cells = 1L + source.clauses().size() + 2L * source.fixed().size();
        work.cells(cells + source.variableCount());
        for (var clause : source.clauses()) {
            cells += clause.size();
            work.cells(cells + source.variableCount());
            work.charge(1);
        }
        return cells;
    }

    private static Problem translate(Source source, long retained, CertificateWork work) {
        var factors = new ArrayList<Factor>();
        int next = source.variableCount();
        long factorCells = 0;
        for (var clause : source.clauses()) {
            int remaining = clause.size();
            int position = 0;
            int left = remaining == 0 ? 0 : clause.get(position++);
            // Leave at most three inputs in the final acceptance factor.
            while (remaining > 3) {
                work.cells(retained + factorCells + next + 1L + FACTOR_SCRATCH_CELLS);
                int right = clause.get(position++);
                int output = ++next; // one-based literal for a fresh, positive prefix
                Factor gate = factor(new int[] {left, right}, output, work);
                factors.add(gate);
                factorCells += 1L + gate.variables().size() + gate.values().size();
                left = output;
                remaining--;
            }
            work.cells(retained + factorCells + next + FACTOR_SCRATCH_CELLS);
            int[] tail = new int[remaining];
            if (remaining > 0) tail[0] = left;
            for (int i = 1; i < remaining; i++) tail[i] = clause.get(position++);
            var last = factor(tail, 0, work);
            factors.add(last);
            factorCells += 1L + last.variables().size() + last.values().size();
        }
        work.cells(retained + factorCells + next + factors.size());
        work.charge(1L + factorCells + source.fixed().size());
        var problem = new Problem(next, factors, source.fixed());
        if (!FiniteConstraintCnfVerifier.matchesTranslation(source, problem, retained, work))
            throw new IllegalStateException("CNF_TRANSLATION_REJECTED");
        return problem;
    }

    private static Factor factor(int[] literals, int output, CertificateWork work) {
        int[] variables = Arrays.copyOf(literals, literals.length + (output == 0 ? 0 : 1));
        if (output != 0) variables[literals.length] = output;
        for (int i = 0; i < variables.length; i++) variables[i] = Math.abs(variables[i]) - 1;
        List<Integer> scope = Arrays.stream(variables).distinct().sorted().boxed().toList();
        var values = new ArrayList<BigInteger>(1 << scope.size());
        for (int row = 0; row < 1 << scope.size(); row++) {
            work.charge(1L + scope.size() + literals.length);
            boolean satisfied = false;
            for (int literal : literals) {
                int bit = scope.indexOf(Math.abs(literal) - 1);
                satisfied |= ((row & (1 << bit)) != 0) == (literal > 0);
            }
            boolean required = output == 0 || (row & (1 << scope.indexOf(output - 1))) != 0;
            values.add(satisfied == required ? BigInteger.ONE : BigInteger.ZERO);
        }
        work.charge(2L * (values.size() + scope.size()));
        return new Factor(scope, values);
    }
}
