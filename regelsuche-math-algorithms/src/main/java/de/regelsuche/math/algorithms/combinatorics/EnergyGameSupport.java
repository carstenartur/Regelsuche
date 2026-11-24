package de.regelsuche.math.algorithms.combinatorics;

import de.regelsuche.math.algorithms.certificates.CertificateWork;

/** Input-shape and numeric accounting, shared without solver/verifier decisions. */
final class EnergyGameSupport {
    private EnergyGameSupport() {}
    static long inspect(EnergyGame.Request request, CertificateWork work) {
        long cells = request.graph().owners().size() + 3L * request.graph().edges().size() + 1;
        work.cells(cells); work.charge(cells); work.check(request.maximumCredit());
        for (var edge : request.graph().edges()) work.check(edge.weight());
        return cells;
    }
}
