package de.regelsuche.math.algorithms.combinatorics;

import de.regelsuche.math.algorithms.certificates.CertificateWork;
import de.regelsuche.math.algorithms.combinatorics.FiniteConstraintCnf.Certificate;
import de.regelsuche.math.algorithms.combinatorics.FiniteConstraintCnf.Source;
import de.regelsuche.math.algorithms.combinatorics.FiniteConstraintCounter.Factor;
import de.regelsuche.math.algorithms.combinatorics.FiniteConstraintCounter.Problem;
import de.regelsuche.math.algorithms.combinatorics.FiniteConstraintVerifier.Status;
import de.regelsuche.math.algorithms.combinatorics.FiniteConstraintVerifier.Verification;
import de.regelsuche.math.algorithms.iteration.IterationLimits;
import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

/** Reconstructs each required local relation from the original signed literals before factor replay. */
public final class FiniteConstraintCnfVerifier {
    private FiniteConstraintCnfVerifier() {}

    public static Verification verifyLowering(Source source, Problem problem, IterationLimits limits,
            BooleanSupplier cancellation) {
        var work = new CertificateWork(limits, cancellation);
        try {
            boolean valid = matchesTranslation(source, problem, FiniteConstraintCnf.sourceCells(source, work), work);
            work.charge(0);
            return new Verification(valid ? Status.VERIFIED : Status.INVALID,
                valid ? "VERIFIED" : "CNF_TRANSLATION", work.used());
        } catch (CertificateWork.Stop stop) {
            return new Verification(Status.UNAVAILABLE, stop.getMessage(), work.used());
        }
    }

    static Verification verify(Source source, Certificate certificate, IterationLimits limits,
            BooleanSupplier cancellation) {
        var work = new CertificateWork(limits, cancellation);
        try {
            long retained = FiniteConstraintCnf.sourceCells(source, work);
            var recorded = certificate.source();
            if (recorded != source) {
                retained += FiniteConstraintCnf.sourceCells(recorded, work);
                work.cells(retained);
                if (!matchesSource(source, recorded, work)) return invalid("CNF_SOURCE", work);
            }
            var elimination = certificate.elimination();
            if (!matchesTranslation(source, elimination.source(), retained, work))
                return invalid("CNF_TRANSLATION", work);
            var result = FiniteConstraintVerifier.verify(elimination.source(), elimination,
                work.remainingLimits(retained), cancellation);
            work.chargeCompleted(result.work());
            work.charge(0);
            return new Verification(result.status(), result.reason(), work.used());
        } catch (CertificateWork.Stop stop) {
            return new Verification(Status.UNAVAILABLE, stop.getMessage(), work.used());
        }
    }

    private static Verification invalid(String reason, CertificateWork work) {
        work.charge(0);
        return new Verification(Status.INVALID, reason, work.used());
    }

    private static boolean matchesSource(Source expected, Source recorded, CertificateWork work) {
        work.charge(1);
        if (expected.variableCount() != recorded.variableCount() || expected.clauses().size() != recorded.clauses().size()
                || !matchesFixed(expected.fixed(), recorded.fixed(), work)) return false;
        for (int i = 0; i < expected.clauses().size(); i++) {
            var clause = expected.clauses().get(i);
            var other = recorded.clauses().get(i);
            work.charge(1);
            if (clause.size() != other.size()) return false;
            for (int j = 0; j < clause.size(); j++) {
                work.charge(1);
                if (!clause.get(j).equals(other.get(j))) return false;
            }
        }
        return true;
    }

    private static boolean matchesFixed(Map<Integer, Boolean> expected, Map<Integer, Boolean> recorded,
            CertificateWork work) {
        if (expected.size() != recorded.size()) return false;
        // Both source constructors snapshot fixed assignments in ascending key order.
        var other = recorded.entrySet().iterator();
        for (var entry : expected.entrySet()) {
            work.charge(1);
            if (!entry.equals(other.next())) return false;
        }
        return true;
    }

    static boolean matchesTranslation(Source source, Problem problem, long retained, CertificateWork work) {
        long cells = FiniteConstraintSupport.inspect(problem, work);
        work.cells(retained + cells + 8);
        work.charge(1);
        if (!matchesFixed(source.fixed(), problem.fixed(), work)) return false;
        int factor = 0;
        int auxiliaries = 0;
        for (List<Integer> clause : source.clauses()) {
            int gates = Math.max(0, clause.size() - 3);
            for (int gate = 0; gate < gates; gate++) {
                int previous = source.variableCount() + auxiliaries;
                int a = gate == 0 ? clause.get(0) : previous;
                int b = clause.get(gate + 1);
                if (factor >= problem.factors().size() ||
                        !relation(problem.factors().get(factor++), List.of(a, b), previous + 1, work)) return false;
                auxiliaries++;
            }
            var tail = gates == 0 ? clause : List.of(source.variableCount() + auxiliaries,
                clause.get(clause.size() - 2), clause.getLast());
            if (factor >= problem.factors().size() || !relation(problem.factors().get(factor++), tail, 0, work))
                return false;
        }
        work.charge(0);
        return factor == problem.factors().size() && problem.variableCount() == (long) source.variableCount() + auxiliaries;
    }

    private static boolean relation(Factor factor, List<Integer> inputs, int output, CertificateWork work) {
        var scope = factor.variables();
        work.charge(1L + inputs.size() + scope.size());
        if (scope.size() > 3) return false;
        int positive = 0;
        int negative = 0;
        for (int literal : inputs) {
            int position = scope.indexOf(Math.abs(literal) - 1);
            if (position < 0) return false;
            if (literal > 0) positive |= 1 << position;
            else negative |= 1 << position;
        }
        int outputMask = 0;
        if (output != 0) {
            int position = scope.indexOf(output - 1);
            if (position < 0) return false;
            outputMask = 1 << position;
        }
        if ((positive | negative | outputMask) != (1 << scope.size()) - 1) return false;
        for (int row = 0; row < factor.values().size(); row++) {
            work.charge(1);
            boolean disjunction = ((row & positive) | (~row & negative)) != 0;
            boolean admissible = disjunction == (output == 0 || (row & outputMask) != 0);
            if (!(admissible ? BigInteger.ONE : BigInteger.ZERO).equals(factor.values().get(row))) return false;
        }
        return true;
    }
}
