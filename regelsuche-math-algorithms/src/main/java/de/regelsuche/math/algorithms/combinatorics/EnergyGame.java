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
import java.util.function.BooleanSupplier;

/** Bounded credit lifting for finite, total, integer-weighted Min/Max energy games. */
public final class EnergyGame {
    private EnergyGame() {}
    public enum Owner { MIN, MAX }

    /** Edge identity is its index in Graph.edges(), so parallel edges remain distinct. */
    public record Edge(int from, int to, BigInteger weight) {
        public Edge { Objects.requireNonNull(weight); }
    }

    public record Graph(List<Owner> owners, List<Edge> edges, int start) {
        public Graph {
            owners = List.copyOf(owners); edges = List.copyOf(edges);
            if (start < 0 || start >= owners.size()) throw new IllegalArgumentException("INVALID_START");
            boolean[] outgoing = new boolean[owners.size()];
            for (Edge edge : edges) {
                if (edge.from() < 0 || edge.from() >= owners.size() || edge.to() < 0 || edge.to() >= owners.size())
                    throw new IllegalArgumentException("INVALID_EDGE_VERTEX");
                outgoing[edge.from()] = true;
            }
            for (boolean present : outgoing) if (!present) throw new IllegalArgumentException("DEAD_END_VERTEX");
        }
    }

    public record Request(Graph graph, BigInteger maximumCredit) {
        public Request {
            Objects.requireNonNull(graph); Objects.requireNonNull(maximumCredit);
            if (maximumCredit.signum() < 0) throw new IllegalArgumentException("NEGATIVE_CREDIT_BOUND");
        }
    }

    /** A closed winning region, its initial credits, and exactly one edge for each Max vertex. */
    public record Certificate(Request source, Map<Integer, BigInteger> credits, Map<Integer, Integer> strategy) {
        public Certificate {
            Objects.requireNonNull(source);
            credits = ImmutableIntegerMap.copyOf(credits);
            strategy = ImmutableIntegerMap.copyOf(strategy);
        }
    }

    public enum Status { WINNING, NO_CERTIFICATE_WITHIN_BOUND, UNAVAILABLE }
    public record Result(Status status, Optional<Certificate> certificate, String reason, long work) {}

    public static Result solve(Request request, IterationLimits limits, BooleanSupplier cancellation) {
        Objects.requireNonNull(request);
        var work = new CertificateWork(limits, cancellation);
        try {
            long sourceCells = EnergyGameSupport.inspect(request, work);
            Graph graph = request.graph();
            int n = graph.owners().size();
            long workingCells = sourceCells + 2L * n + graph.edges().size();
            work.cells(workingCells);
            var outgoing = new ArrayList<List<Integer>>(n);
            // null is top (no credit at or below the requested bound), never a returned finite credit.
            BigInteger[] credits = new BigInteger[n];
            for (int v = 0; v < n; v++) {
                work.charge(2);
                outgoing.add(new ArrayList<>());
                credits[v] = BigInteger.ZERO;
            }
            for (int i = 0; i < graph.edges().size(); i++) { work.charge(1); outgoing.get(graph.edges().get(i).from()).add(i); }
            liftCredits(request, outgoing, credits, work);
            if (credits[graph.start()] == null) {
                work.charge(0);
                return new Result(Status.NO_CERTIFICATE_WITHIN_BOUND, Optional.empty(), "CREDIT_BOUND", work.used());
            }
            var region = new LinkedHashMap<Integer, BigInteger>();
            var strategy = new LinkedHashMap<Integer, Integer>();
            for (int v = 0; v < n; v++) {
                work.charge(1);
                if (credits[v] == null) continue;
                work.cells(workingCells + 2L * (region.size() + strategy.size() + 1));
                region.put(v, credits[v]);
                if (graph.owners().get(v) == Owner.MAX) {
                    for (int id : outgoing.get(v)) {
                        work.charge(1);
                        Edge edge = graph.edges().get(id);
                        BigInteger required = need(credits[edge.to()], edge.weight(), request.maximumCredit(), work);
                        if (required != null && required.compareTo(credits[v]) <= 0) {
                            work.cells(workingCells + 2L * (region.size() + strategy.size() + 1));
                            strategy.put(v, id);
                            break;
                        }
                    }
                }
            }
            work.cells(workingCells + 4L * (region.size() + strategy.size()));
            var certificate = new Certificate(request, ImmutableIntegerMap.copyOf(region, work),
                ImmutableIntegerMap.copyOf(strategy, work));
            var verification = EnergyGameVerifier.verify(request, certificate, work.remainingLimits(), cancellation);
            work.charge(verification.work());
            if (verification.status() == EnergyGameVerifier.Status.UNAVAILABLE) throw new CertificateWork.Stop(verification.reason());
            if (!verification.valid()) throw new IllegalStateException("ENERGY_CERTIFICATE_REJECTED: " + verification.reason());
            work.charge(0);
            return new Result(Status.WINNING, Optional.of(certificate), "VERIFIED", work.used());
        } catch (CertificateWork.Stop stop) {
            return new Result(Status.UNAVAILABLE, Optional.empty(), stop.getMessage(), work.used());
        }
    }

    private static void liftCredits(Request request, List<List<Integer>> outgoing,
            BigInteger[] credits, CertificateWork work) {
        boolean changed;
        do {
            changed = false;
            for (int v = 0; v < credits.length; v++) {
                work.charge(1);
                if (credits[v] == null) continue;
                BigInteger next = liftedCredit(request, v, outgoing.get(v), credits, work);
                if (!Objects.equals(next, credits[v])) { credits[v] = next; changed = true; }
            }
        } while (changed);
    }

    private static BigInteger liftedCredit(Request request, int v, List<Integer> outgoing,
            BigInteger[] credits, CertificateWork work) {
        boolean max = request.graph().owners().get(v) == Owner.MAX;
        BigInteger next = max ? null : BigInteger.ZERO;
        for (int id : outgoing) {
            work.charge(1);
            Edge edge = request.graph().edges().get(id);
            BigInteger required = need(credits[edge.to()], edge.weight(), request.maximumCredit(), work);
            if (max) {
                if (required != null && (next == null || required.compareTo(next) < 0)) next = required;
            } else {
                if (required == null) { next = null; break; }
                if (required.compareTo(next) > 0) next = required;
            }
        }
        return next;
    }

    private static BigInteger need(BigInteger successor, BigInteger weight, BigInteger bound, CertificateWork work) {
        if (successor == null) { work.charge(1); return null; }
        work.check(successor); work.check(weight); work.charge(1);
        BigInteger required = work.check(successor.subtract(weight)).max(BigInteger.ZERO);
        return required.compareTo(bound) > 0 ? null : required;
    }
}
