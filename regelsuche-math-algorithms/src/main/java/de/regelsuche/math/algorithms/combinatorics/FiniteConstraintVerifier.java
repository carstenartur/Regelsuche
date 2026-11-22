package de.regelsuche.math.algorithms.combinatorics;

import de.regelsuche.math.algorithms.certificates.CertificateWork;
import de.regelsuche.math.algorithms.combinatorics.FiniteConstraintCounter.Certificate;
import de.regelsuche.math.algorithms.combinatorics.FiniteConstraintCounter.Factor;
import de.regelsuche.math.algorithms.combinatorics.FiniteConstraintCounter.Problem;
import de.regelsuche.math.algorithms.combinatorics.FiniteConstraintCounter.Step;
import de.regelsuche.math.algorithms.iteration.IterationLimits;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BooleanSupplier;

/** Replays every local summation and checks source binding, total and a positive-weight witness. */
public final class FiniteConstraintVerifier {
    private FiniteConstraintVerifier() {}
    public enum Status { VERIFIED, INVALID, UNAVAILABLE }
    public record Verification(Status status, String reason, long work) {
        public boolean valid() { return status == Status.VERIFIED; }
    }

    public static Verification verify(Problem source, Certificate certificate, IterationLimits limits,
            BooleanSupplier cancellation) {
        Objects.requireNonNull(source); Objects.requireNonNull(certificate);
        var work = new CertificateWork(limits, cancellation);
        try {
            long retained = FiniteConstraintSupport.inspect(source, work);
            retained += FiniteConstraintSupport.inspect(certificate.source(), work); work.cells(retained);
            if (!matchesSource(source, certificate.source(), work)) return invalid("SOURCE_MISMATCH", work);
            if (certificate.steps().size() != source.variableCount() - source.fixed().size()) return invalid("STEP_COUNT", work);
            work.check(certificate.total());
            if (certificate.total().signum() < 0) return invalid("NEGATIVE_TOTAL", work);
            retained = inspectCertificate(certificate, retained, work);
            var active = new LinkedHashMap<Integer, Factor>();
            retained = conditionSource(source, active, retained, work);
            String failure = replay(source, certificate, active, retained, work);
            if (failure != null) return invalid(failure, work);
            failure = checkResult(source, certificate, active, work);
            if (failure != null) return invalid(failure, work);
            work.charge(0);
            return new Verification(Status.VERIFIED, "VERIFIED", work.used());
        } catch (CertificateWork.Stop stop) {
            return new Verification(Status.UNAVAILABLE, stop.getMessage(), work.used());
        }
    }

    private static long inspectCertificate(Certificate certificate, long retained, CertificateWork work) {
        retained += certificate.steps().size() + 2L * certificate.witness().map(java.util.Map::size).orElse(0);
        work.cells(retained);
        for (var step : certificate.steps()) {
            retained += step.inputs().size() + step.output().variables().size() + step.output().values().size();
            work.cells(retained); work.charge(1L + step.inputs().size() + step.output().variables().size());
            for (BigInteger value : step.output().values()) work.check(value);
        }
        return retained;
    }

    private static long conditionSource(Problem source, Map<Integer, Factor> active,
            long retained, CertificateWork work) {
        // Independently condition each source factor by visiting its original table.
        for (int id = 0; id < source.factors().size(); id++) {
            Factor original = source.factors().get(id);
            var free = new ArrayList<Integer>();
            for (int variable : original.variables()) {
                work.charge(1);
                if (!source.fixed().containsKey(variable)) {
                    work.cells(retained + free.size() + 1L);
                    free.add(variable);
                }
            }
            int size = FiniteConstraintSupport.tableSize(free.size(), work);
            retained += size + free.size() + 1L;
            work.cells(retained + size + free.size());
            work.charge(size);
            var values = new ArrayList<BigInteger>(Collections.nCopies(size, BigInteger.ZERO));
            for (int index = 0; index < original.values().size(); index++) {
                int reduced = 0, position = 0;
                boolean matches = true;
                for (int bit = 0; bit < original.variables().size(); bit++) {
                    work.charge(1);
                    int variable = original.variables().get(bit);
                    boolean value = ((index >>> bit) & 1) != 0;
                    if (source.fixed().containsKey(variable)) {
                        if (value != source.fixed().get(variable)) matches = false;
                    } else {
                        if (value) reduced |= 1 << position;
                        position++;
                    }
                }
                work.charge(1);
                if (matches) values.set(reduced, original.values().get(index));
            }
            work.charge(2L * (size + free.size()));
            active.put(id, new Factor(free, values));
            work.charge(0);
        }
        return retained;
    }

    private static String replay(Problem source, Certificate certificate, Map<Integer, Factor> active,
            long retained, CertificateWork work) {
        var eliminated = new HashSet<Integer>();
        for (int index = 0; index < certificate.steps().size(); index++) {
            var step = certificate.steps().get(index);
            int variable = step.variable(); work.charge(1);
            if (variable < 0 || variable >= source.variableCount() || source.fixed().containsKey(variable)
                    || eliminated.contains(variable)) return "ELIMINATION_VARIABLE";
            retained += 2; // eliminated-variable entry and active output entry
            work.cells(retained);
            eliminated.add(variable);
            String failure = checkBucket(step, active, retained, work);
            if (failure != null) return failure;
            failure = checkSum(step, active, work);
            if (failure != null) return failure;
            for (int id : step.inputs()) {
                work.charge(1);
                active.remove(id);
            }
            active.put(source.factors().size() + index, step.output());
        }
        return null;
    }

    private static String checkBucket(Step step, Map<Integer, Factor> active,
            long retained, CertificateWork work) {
        int variable = step.variable();
        var scope = step.output().variables();
        work.cells(retained + 3L * scope.size() + 2);
        boolean[] seen = new boolean[scope.size()];
        int nextInput = 0, seenCount = 0;
        for (var entry : active.entrySet()) {
            work.charge(1L + entry.getValue().variables().size());
            if (!entry.getValue().variables().contains(variable)) continue;
            if (nextInput == step.inputs().size() || !entry.getKey().equals(step.inputs().get(nextInput++))) {
                return "BUCKET_MISMATCH";
            }
            for (int member : entry.getValue().variables()) {
                work.charge(1);
                if (member == variable) continue;
                int position = Collections.binarySearch(scope, member);
                if (position < 0) return "OUTPUT_SCOPE";
                if (!seen[position]) {
                    seen[position] = true;
                    seenCount++;
                }
            }
        }
        if (nextInput != step.inputs().size()) return "BUCKET_MISMATCH";
        if (seenCount != scope.size()) return "OUTPUT_SCOPE";
        return null;
    }

    private static String checkSum(Step step, Map<Integer, Factor> active, CertificateWork work) {
        int variable = step.variable();
        var scope = step.output().variables();
        int size = FiniteConstraintSupport.tableSize(scope.size(), work);
        var assignment = new HashMap<Integer, Boolean>();
        for (int mask = 0; mask < size; mask++) {
            int position = 0;
            for (int v : scope) { work.charge(1); assignment.put(v, ((mask >>> position++) & 1) != 0); }
            BigInteger expected = BigInteger.ZERO;
            for (boolean bit : new boolean[] {false, true}) {
                assignment.put(variable, bit);
                BigInteger term = BigInteger.ONE;
                for (int id : step.inputs()) {
                    Factor factor = active.get(id);
                    int row = 0;
                    for (int k = 0; k < factor.variables().size(); k++) {
                        work.charge(1);
                        if (assignment.get(factor.variables().get(k))) row |= 1 << k;
                    }
                    term = FiniteConstraintSupport.multiply(term, factor.values().get(row), work);
                }
                expected = FiniteConstraintSupport.add(expected, term, work);
            }
            work.charge(1);
            if (!expected.equals(step.output().values().get(mask))) return "LOCAL_SUM_MISMATCH";
        }
        return null;
    }

    private static String checkResult(Problem source, Certificate certificate,
            Map<Integer, Factor> active, CertificateWork work) {
        BigInteger total = BigInteger.ONE;
        for (Factor factor : active.values()) {
            if (!factor.variables().isEmpty()) return "REMAINING_VARIABLE";
            total = FiniteConstraintSupport.multiply(total, factor.values().get(0), work);
        }
        if (!total.equals(certificate.total())) return "TOTAL_MISMATCH";
        if (total.signum() == 0) {
            if (certificate.witness().isPresent()) return "ZERO_WITH_WITNESS";
        } else {
            if (certificate.witness().isEmpty()) return "MISSING_WITNESS";
            var assignment = certificate.witness().orElseThrow();
            if (assignment.size() != source.variableCount()) return "WITNESS_SHAPE";
            for (int v = 0; v < source.variableCount(); v++) {
                work.charge(1);
                if (!assignment.containsKey(v) || (source.fixed().containsKey(v)
                        && !assignment.get(v).equals(source.fixed().get(v)))) return "WITNESS_DOMAIN";
            }
            for (Factor factor : source.factors()) {
                work.charge(1);
                int row = 0;
                for (int k = 0; k < factor.variables().size(); k++) {
                    work.charge(1);
                    if (assignment.get(factor.variables().get(k))) row |= 1 << k;
                }
                if (factor.values().get(row).signum() == 0) return "WITNESS_ZERO_WEIGHT";
            }
        }
        return null;
    }

    private static boolean matchesSource(Problem expected, Problem supplied, CertificateWork work) {
        work.charge(1);
        if (expected == supplied) return true;
        if (expected.variableCount() != supplied.variableCount() || expected.fixed().size() != supplied.fixed().size()
                || expected.factors().size() != supplied.factors().size()) return false;

        // The defensive snapshots iterate integer keys in sorted order.
        var recordedFixed = supplied.fixed().entrySet().iterator();
        for (var fixed : expected.fixed().entrySet()) {
            work.charge(1);
            if (!fixed.equals(recordedFixed.next())) return false;
        }
        for (int id = 0; id < expected.factors().size(); id++) {
            work.charge(1);
            Factor a = expected.factors().get(id);
            Factor b = supplied.factors().get(id);
            work.charge(a.variables().size());
            if (!a.variables().equals(b.variables())) return false;
            for (int row = 0; row < a.values().size(); row++) {
                if (!work.check(a.values().get(row)).equals(work.check(b.values().get(row)))) return false;
            }
        }
        return true;
    }

    private static Verification invalid(String reason, CertificateWork work) {
        work.charge(0);
        return new Verification(Status.INVALID, reason, work.used());
    }
}
