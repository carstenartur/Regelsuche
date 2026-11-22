import de.regelsuche.math.algorithms.combinatorics.EnergyGame;
import de.regelsuche.sdk.optimization.CancellationToken;
import de.regelsuche.sdk.optimization.OptimizationBudget;
import de.regelsuche.sdk.optimization.combinatorics.EnergyGameSdk;
import java.math.BigInteger;
import java.util.List;

/** Requires the current 0.5.4-SNAPSHOT source SDK, not the published 0.5.3 binary. */
class EnergyCredits {
    public static void main(String[] args) {
        var budget = new OptimizationBudget(100_000, 1_000, 1, 5_000);
        var graph = new EnergyGame.Graph(List.of(EnergyGame.Owner.MAX, EnergyGame.Owner.MIN),
            List.of(new EnergyGame.Edge(0, 1, BigInteger.valueOf(-2)),
                new EnergyGame.Edge(0, 0, BigInteger.valueOf(-1)),
                new EnergyGame.Edge(1, 0, BigInteger.valueOf(3)),
                new EnergyGame.Edge(1, 1, BigInteger.ZERO)), 0);
        var request = new EnergyGame.Request(graph, BigInteger.TEN);
        var result = EnergyGameSdk.solve(request, budget, CancellationToken.NONE);
        var certificate = result.certificate().orElseThrow(() -> new IllegalStateException(result.reason()));
        if (!EnergyGameSdk.verify(request, certificate, budget, CancellationToken.NONE).valid())
            throw new IllegalStateException("Unverified energy certificate");
        System.out.println("credit(a)=" + certificate.credits().get(0) + ", credit(b)=" + certificate.credits().get(1));
        System.out.println("Max edge(a)=" + certificate.strategy().get(0));
    }
}
