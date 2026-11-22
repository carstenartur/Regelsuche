package de.regelsuche.math.algorithms.combinatorics;

import de.regelsuche.math.algorithms.certificates.CertificateWork;
import de.regelsuche.math.algorithms.iteration.IterationLimits;
import java.math.BigInteger;
import java.util.Objects;
import java.util.function.BooleanSupplier;

/** Checks an inductive energy invariant without running credit lifting. */
public final class EnergyGameVerifier {
    private EnergyGameVerifier() {}
    public enum Status { VERIFIED, INVALID, UNAVAILABLE }
    public record Verification(Status status, String reason, long work) {
        public boolean valid() { return status == Status.VERIFIED; }
    }

    public static Verification verify(EnergyGame.Request request, EnergyGame.Certificate certificate,
            IterationLimits limits, BooleanSupplier cancellation) {
        Objects.requireNonNull(request); Objects.requireNonNull(certificate);
        var work = new CertificateWork(limits, cancellation);
        try {
            long cells = EnergyGameSupport.inspect(request, work);
            cells += EnergyGameSupport.inspect(certificate.source(), work);
            cells += 2L * certificate.credits().size() + 2L * certificate.strategy().size();
            work.cells(cells);
            if (!matchesSource(request, certificate.source(), work)) return invalid("SOURCE_MISMATCH", work);
            var graph = request.graph();
            var credits = certificate.credits();
            var strategy = certificate.strategy();
            if (!credits.containsKey(graph.start())) return invalid("MISSING_START", work);
            for (var entry : credits.entrySet()) {
                work.charge(1); work.check(entry.getValue());
                if (entry.getKey() < 0 || entry.getKey() >= graph.owners().size()) return invalid("REGION_VERTEX", work);
                if (entry.getValue().signum() < 0 || entry.getValue().compareTo(request.maximumCredit()) > 0)
                    return invalid("CREDIT_RANGE", work);
                if (graph.owners().get(entry.getKey()) == EnergyGame.Owner.MAX && !strategy.containsKey(entry.getKey()))
                    return invalid("MISSING_MAX_CHOICE", work);
            }
            for (var entry : strategy.entrySet()) {
                work.charge(1);
                int vertex = entry.getKey(), id = entry.getValue();
                if (!credits.containsKey(vertex) || graph.owners().get(vertex) != EnergyGame.Owner.MAX)
                    return invalid("EXTRANEOUS_STRATEGY", work);
                if (id < 0 || id >= graph.edges().size() || graph.edges().get(id).from() != vertex)
                    return invalid("INVALID_MAX_EDGE", work);
            }
            for (int id = 0; id < graph.edges().size(); id++) {
                work.charge(1);
                var edge = graph.edges().get(id);
                if (!credits.containsKey(edge.from())) continue;
                if (graph.owners().get(edge.from()) == EnergyGame.Owner.MAX && strategy.get(edge.from()) != id) continue;
                if (!credits.containsKey(edge.to())) return invalid("REGION_NOT_CLOSED", work);
                BigInteger available = work.check(credits.get(edge.from()).add(edge.weight()));
                if (available.compareTo(credits.get(edge.to())) < 0) return invalid("ENERGY_INEQUALITY", work);
            }
            work.charge(0);
            return new Verification(Status.VERIFIED, "VERIFIED", work.used());
        } catch (CertificateWork.Stop stop) {
            return new Verification(Status.UNAVAILABLE, stop.getMessage(), work.used());
        }
    }

    private static boolean matchesSource(EnergyGame.Request expected, EnergyGame.Request supplied,
            CertificateWork work) {
        work.charge(1);
        if (expected == supplied) return true;
        var first = expected.graph();
        var second = supplied.graph();
        if (first.start() != second.start() || first.owners().size() != second.owners().size()
                || first.edges().size() != second.edges().size()
                || !work.check(expected.maximumCredit()).equals(work.check(supplied.maximumCredit()))) return false;
        for (int v = 0; v < first.owners().size(); v++) {
            work.charge(1);
            if (first.owners().get(v) != second.owners().get(v)) return false;
        }
        for (int id = 0; id < first.edges().size(); id++) {
            work.charge(1);
            var a = first.edges().get(id);
            var b = second.edges().get(id);
            if (a.from() != b.from() || a.to() != b.to()
                    || !work.check(a.weight()).equals(work.check(b.weight()))) return false;
        }
        return true;
    }

    private static Verification invalid(String reason, CertificateWork work) {
        work.charge(0);
        return new Verification(Status.INVALID, reason, work.used());
    }
}
