import de.regelsuche.math.algorithms.iteration.IterationLimits;
import de.regelsuche.math.algorithms.modular.CongruenceCoverage;
import de.regelsuche.math.algorithms.modular.HunterCoverage;
import de.regelsuche.math.algorithms.proof.FiniteProofDag;
import java.util.List;

import static java.math.BigInteger.*;

/** Run with Java 25 against the compiled mathematical core; see the research note. */
class HunterBound {
    public static void main(String[] args) {
        var limits = new IterationLimits(100_000, 64, 10_000);
        var rules = List.of(2, 3, 5).stream()
            .map(p -> new CongruenceCoverage.Rule(valueOf(p), ZERO, ZERO)).toList();
        var request = new CongruenceCoverage.Request(new CongruenceCoverage.Progression(ZERO, ONE, valueOf(30)), rules);
        var hunter = HunterCoverage.bounds(request, limits, () -> false).certificate().orElseThrow();
        var union = CongruenceCoverage.bounds(request, 1, limits, () -> false).certificate().orElseThrow();
        var exact = CongruenceCoverage.search(request, limits, () -> false).certificate().orElseThrow();
        var proof = new FiniteProofDag.Proof(List.of(new FiniteProofDag.HunterLeaf(hunter),
            new FiniteProofDag.ExactLeaf(exact), new FiniteProofDag.Maximum(0, 1), new FiniteProofDag.Exists(2)));
        var replay = FiniteProofDag.verify(new FiniteProofDag.CoverageSource(request), proof, limits, () -> false);
        if (!(replay.conclusion().orElseThrow() instanceof FiniteProofDag.Existence))
            throw new IllegalStateException("Expected a verified finite existence claim");
        System.out.println("singleton counts = " + hunter.singletonCounts());
        System.out.println("tree = " + hunter.tree());
        System.out.println("union lower bound = " + union.lowerBound());
        System.out.println("Hunter lower bound = " + hunter.lowerBound());
        System.out.println("exact survivors = " + request.progression().count().subtract(exact.coveredCount()));
        System.out.println("finite existence = " + replay.reason());
    }
}
