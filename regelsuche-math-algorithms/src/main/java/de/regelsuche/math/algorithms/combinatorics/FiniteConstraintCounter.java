package de.regelsuche.math.algorithms.combinatorics;

import de.regelsuche.math.algorithms.certificates.CertificateWork;
import de.regelsuche.math.algorithms.iteration.IterationLimits;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeSet;
import java.util.function.BooleanSupplier;

/** Bounded exact weighted counting by Boolean variable elimination. */
public final class FiniteConstraintCounter {
    private FiniteConstraintCounter() {}

    /** Bit i of a table index is the value of variables().get(i); weights are nonnegative. */
    public record Factor(List<Integer> variables, List<BigInteger> values) {
        public Factor {
            variables = List.copyOf(variables);
            values = List.copyOf(values);
            if (variables.size() > FiniteConstraintSupport.MAX_WIDTH || values.size() != (1 << variables.size()))
                throw new IllegalArgumentException("INVALID_FACTOR_SHAPE");
            int previous = -1;
            for (int variable : variables) {
                if (variable <= previous) throw new IllegalArgumentException("UNSORTED_FACTOR_VARIABLES");
                previous = variable;
            }
            for (BigInteger value : values)
                if (value.signum() < 0) throw new IllegalArgumentException("NEGATIVE_WEIGHT");
        }
    }

    public record Problem(int variableCount, List<Factor> factors, Map<Integer, Boolean> fixed) {
        public Problem {
            if (variableCount < 0) throw new IllegalArgumentException("NEGATIVE_VARIABLE_COUNT");
            factors = List.copyOf(factors);
            fixed = ImmutableIntegerMap.copyOf(fixed);
            for (Factor factor : factors)
                for (int variable : factor.variables()) checkVariable(variable, variableCount);
            for (int variable : fixed.keySet()) checkVariable(variable, variableCount);
        }
    }

    /** Output factor id is source.factors().size() plus the step index. */
    public record Step(int variable, List<Integer> inputs, Factor output) {
        public Step { inputs = List.copyOf(inputs); Objects.requireNonNull(output); }
    }

    public record Certificate(Problem source, List<Step> steps, BigInteger total,
            Optional<Map<Integer, Boolean>> witness) {
        public Certificate {
            Objects.requireNonNull(source);
            steps = List.copyOf(steps);
            Objects.requireNonNull(total);
            witness = Objects.requireNonNull(witness).map(ImmutableIntegerMap::copyOf);
        }
    }

    public enum Status { EXACT, UNAVAILABLE }

    public record Result(Status status, Optional<BigInteger> count, Optional<Certificate> certificate,
            String reason, long work) {}

    public static Result count(Problem problem, IterationLimits limits, BooleanSupplier cancellation) {
        Objects.requireNonNull(problem);
        var work = new CertificateWork(limits, cancellation);
        try {
            long retained = FiniteConstraintSupport.inspect(problem, work);
            var active = new LinkedHashMap<Integer, Factor>();
            var history = new ArrayList<Factor>();
            retained = condition(problem, active, history, retained, work);
            var steps = new ArrayList<Step>();
            retained = eliminate(problem, active, history, steps, retained, work);
            BigInteger total = BigInteger.ONE;
            for (Factor factor : active.values()) total = FiniteConstraintSupport.multiply(total, factor.values().get(0), work);
            Optional<Map<Integer, Boolean>> witness = findWitness(problem, total, history, steps, retained, work);
            work.cells(retained + steps.size() + 2L * witness.map(Map::size).orElse(0));
            work.charge(steps.size());
            var certificate = new Certificate(problem, steps, total, witness);
            var verification = FiniteConstraintVerifier.verify(problem, certificate, work.remainingLimits(), cancellation);
            work.charge(verification.work());
            if (verification.status() == FiniteConstraintVerifier.Status.UNAVAILABLE) throw new CertificateWork.Stop(verification.reason());
            if (!verification.valid()) throw new IllegalStateException("COUNT_CERTIFICATE_REJECTED: " + verification.reason());
            work.charge(0);
            return new Result(Status.EXACT, Optional.of(total), Optional.of(certificate), "VERIFIED", work.used());
        } catch (CertificateWork.Stop stop) {
            return new Result(Status.UNAVAILABLE, Optional.empty(), Optional.empty(), stop.getMessage(), work.used());
        }
    }

    private static long condition(Problem problem, Map<Integer, Factor> active, List<Factor> history,
            long retained, CertificateWork work) {
        for (Factor source : problem.factors()) {
            var scope = new ArrayList<Integer>();
            for (int variable : source.variables()) {
                work.charge(1);
                if (!problem.fixed().containsKey(variable)) {
                    work.cells(retained + scope.size() + 1L);
                    scope.add(variable);
                }
            }
            int size = FiniteConstraintSupport.tableSize(scope.size(), work);
            retained += size + scope.size() + 2L; // conditioned factor, active entry and historical reference
            work.cells(retained + size + scope.size());
            var values = new ArrayList<BigInteger>(size);
            for (int mask = 0; mask < size; mask++) {
                int sourceIndex = 0, freeIndex = 0;
                for (int i = 0; i < source.variables().size(); i++) {
                    work.charge(1);
                    Boolean fixed = problem.fixed().get(source.variables().get(i));
                    boolean bit = fixed == null ? ((mask >>> freeIndex++) & 1) != 0 : fixed;
                    if (bit) sourceIndex |= 1 << i;
                }
                work.charge(1); values.add(source.values().get(sourceIndex));
            }
            work.charge(2L * (size + scope.size()));
            Factor conditioned = new Factor(scope, values);
            work.charge(0);
            active.put(history.size(), conditioned); history.add(conditioned);
        }
        return retained;
    }

    private static long eliminate(Problem problem, Map<Integer, Factor> active, List<Factor> history,
            List<Step> steps, long retained, CertificateWork work) {
        // Stable ascending order makes the exact certificate deterministic. Width is explicitly bounded.
        for (int variable = 0; variable < problem.variableCount(); variable++) {
            work.charge(1);
            if (problem.fixed().containsKey(variable)) continue;
            var inputs = new ArrayList<Integer>();
            var union = new TreeSet<Integer>();
            collectInputs(variable, active, inputs, union, retained, work);
            int size = FiniteConstraintSupport.tableSize(union.size(), work);
            retained += size + union.size() + inputs.size() + 3L;
            work.cells(retained + size + union.size() + inputs.size());
            work.charge(union.size());
            var scope = List.copyOf(union);
            Factor output = sumOut(variable, inputs, scope, active, size, work);
            for (int id : inputs) {
                work.charge(1);
                active.remove(id);
            }
            active.put(history.size(), output); history.add(output);
            steps.add(new Step(variable, inputs, output));
            work.charge(0);
        }
        return retained;
    }

    private static void collectInputs(int variable, Map<Integer, Factor> active, List<Integer> inputs,
            TreeSet<Integer> union, long retained, CertificateWork work) {
        for (var entry : active.entrySet()) {
            work.charge(1L + entry.getValue().variables().size());
            if (entry.getValue().variables().contains(variable)) {
                work.cells(retained + inputs.size() + union.size() + 1L);
                inputs.add(entry.getKey());
                for (int member : entry.getValue().variables()) {
                    work.charge(1);
                    if (member != variable && !union.contains(member)) {
                        FiniteConstraintSupport.requireWidth(union.size() + 1);
                        work.cells(retained + inputs.size() + union.size() + 1L);
                        union.add(member);
                    }
                }
            }
        }
    }

    private static Factor sumOut(int variable, List<Integer> inputs, List<Integer> scope,
            Map<Integer, Factor> active, int size, CertificateWork work) {
        var values = new ArrayList<BigInteger>(size);
        for (int mask = 0; mask < size; mask++) {
            BigInteger sum = BigInteger.ZERO;
            for (int bit = 0; bit < 2; bit++) {
                BigInteger product = BigInteger.ONE;
                for (int id : inputs) {
                    Factor factor = active.get(id);
                    int index = 0;
                    for (int i = 0; i < factor.variables().size(); i++) {
                        work.charge(1);
                        int v = factor.variables().get(i);
                        int position = v == variable ? -1 : java.util.Collections.binarySearch(scope, v);
                        int value = v == variable ? bit : (mask >>> position) & 1;
                        index |= value << i;
                    }
                    product = FiniteConstraintSupport.multiply(product, factor.values().get(index), work);
                }
                sum = FiniteConstraintSupport.add(sum, product, work);
            }
            values.add(sum);
        }
        work.charge(2L * size + scope.size() + inputs.size());
        return new Factor(scope, values);
    }

    private static Optional<Map<Integer, Boolean>> findWitness(Problem problem, BigInteger total,
            List<Factor> history, List<Step> steps, long retained, CertificateWork work) {
        if (total.signum() <= 0) return Optional.empty();
        work.cells(retained + 4L * problem.variableCount());
        var assignment = new LinkedHashMap<Integer, Boolean>();
        for (var entry : problem.fixed().entrySet()) {
            work.charge(1);
            assignment.put(entry.getKey(), entry.getValue());
        }
        for (int i = steps.size() - 1; i >= 0; i--) {
            Step step = steps.get(i);
            boolean found = false;
            for (boolean bit : new boolean[] {false, true}) {
                work.charge(1); assignment.put(step.variable(), bit);
                boolean possible = true;
                for (int id : step.inputs()) {
                    work.charge(1);
                    Factor factor = history.get(id);
                    int index = 0;
                    for (int j = 0; j < factor.variables().size(); j++) {
                        work.charge(1);
                        if (assignment.get(factor.variables().get(j))) index |= 1 << j;
                    }
                    if (factor.values().get(index).signum() == 0) { possible = false; break; }
                }
                if (possible) { found = true; break; }
            }
            if (!found) throw new IllegalStateException("INCONSISTENT_COUNT_TRACE");
        }
        return Optional.of(ImmutableIntegerMap.copyOf(assignment, work));
    }

    private static void checkVariable(int variable, int count) {
        if (variable < 0 || variable >= count) throw new IllegalArgumentException("VARIABLE_OUT_OF_RANGE");
    }
}
