package example;

import de.regelsuche.discovery.domain.DiscoveryDomain;
import de.regelsuche.discovery.domain.DiscoveryDomain.*;
import de.regelsuche.sdk.discovery.*;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/** Find the degree of a Newton polynomial for the finite sequence of squares. */
public final class FiniteDifferenceDomain implements DiscoveryDomainProvider {
    public String id() { return "finite-difference-example"; }
    public Collection<DiscoveryDomain<?, ?, ?>> domains() { return List.of(domain()); }

    public static DiscoveryDomain<Integer, Integer, String> domain() {
        return DiscoveryDomainBuilder.<Integer, Integer, String>domain("squares-degree", "v1")
            .generator(seed -> List.of(Integer.parseInt(seed.payload().trim())))
            .stateCodec(Object::toString)
            .invariant("bounded-degree", degree -> degree >= 0 && degree <= 2
                ? InvariantResult.pass() : InvariantResult.fail("degree outside 0..2"))
            .operator("raise-degree", degree -> degree == 2 ? List.of() : List.of(
                new Successor<>("degree-" + (degree + 1), degree + 1, 1, false, List.of(), Map.of())))
            .objective(degree -> new ObjectiveAssessment(-degree, true, Map.of()))
            .candidate(context -> context.currentState(), Object::toString)
            .counterexamples((degree, budget) -> {
                for (int n = 1; n <= Math.min(4, budget); n++) {
                    if (predict(degree, n) != n * n) return CounterexampleResult.found(n,
                        "degree=" + degree + "; n=" + n, Map.of());
                }
                return budget < 4 ? CounterexampleResult.inconclusive(budget, "TRAIN incomplete", Map.of())
                    : CounterexampleResult.noneFound(4, Map.of("train", "1..4"));
            })
            .evaluator(degree -> {
                // Independent reference computation on held-out positions, never NONE_FOUND -> confirmation.
                for (int n = 5; n <= 16; n++) {
                    if (predict(degree, n) != Math.multiplyExact(n, n)) {
                        return Evaluation.refuted("HOLDOUT mismatch at " + n, Map.of());
                    }
                }
                return Evaluation.confirmed("degree=" + degree + "; finite-range=1..16",
                    "TRAIN and HOLDOUT squares reproduced; no unbounded claim", Map.of("holdout", "5..16"));
            })
            .certificate("FINITE_SQUARE_SEQUENCE", value -> value, value -> value).build();
    }

    static int predict(int degree, int n) {
        // Forward differences of 1,4,9,16: 1,3,2 in the Newton basis.
        return 1 + (degree >= 1 ? 3 * (n - 1) : 0)
            + (degree >= 2 ? (n - 1) * (n - 2) : 0);
    }

    public static void main(String[] args) {
        var entry = DiscoveryDomainCatalog.load().find("squares-degree", "v1").orElseThrow();
        var run = RegelsucheDiscovery.forRegistration(entry).campaign("finite-difference-demo")
            .seed("degree-zero", "0", "student-example").run();
        DiscoveryRunAssertions.assertThat(run).isConfirmed().hasCounterexampleCount(2);
        System.out.println("outcome=" + run.outcome());
        System.out.println(run.canonicalEvidence());
    }
}
