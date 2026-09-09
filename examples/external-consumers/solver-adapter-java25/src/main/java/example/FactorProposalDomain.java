package example;

import de.regelsuche.discovery.domain.DiscoveryDomain;
import de.regelsuche.discovery.domain.DiscoveryDomain.*;
import de.regelsuche.sdk.discovery.*;
import java.math.BigInteger;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/** An untrusted external solver supplies n:factor; exact arithmetic checks it. */
public final class FactorProposalDomain implements DiscoveryDomainProvider {
    public record Proposal(BigInteger n, BigInteger factor) {
        public String canonical() { return n + ":" + factor; }
    }
    public String id() { return "exact-factor-verifier"; }
    public Collection<DiscoveryDomain<?, ?, ?>> domains() { return List.of(domain()); }
    public static DiscoveryDomain<Proposal, Proposal, String> domain() {
        return DiscoveryDomainBuilder.<Proposal, Proposal, String>domain("factor-proposal", "v1")
            .generator(seed -> {
                String[] parts = seed.payload().trim().split(":", -1);
                if (parts.length != 2) throw new IllegalArgumentException("expected n:factor");
                return List.of(new Proposal(new BigInteger(parts[0]), new BigInteger(parts[1])));
            })
            .stateCodec(Proposal::canonical)
            .invariant("nontrivial-factor", p -> p.factor().compareTo(BigInteger.ONE) > 0
                && p.factor().compareTo(p.n()) < 0 ? InvariantResult.pass()
                : InvariantResult.fail("factor must satisfy 1 < factor < n"))
            .operator("single-proposal", p -> List.of())
            .objective(p -> new ObjectiveAssessment(0, true, Map.of()))
            .candidate(context -> context.currentState(), Proposal::canonical)
            .counterexamples((p, budget) -> CounterexampleResult.noneFound(0, Map.of()))
            .evaluator(p -> {
                var division = p.n().divideAndRemainder(p.factor());
                if (division[1].signum() != 0) return Evaluation.refuted(
                    "independent exact division has remainder " + division[1], Map.of());
                return Evaluation.confirmed(p.n() + "=" + p.factor() + "*" + division[0],
                    "exact nontrivial factorization witness", Map.of());
            })
            .certificate("EXACT_FACTOR_WITNESS", value -> value, value -> value).build();
    }
    public static void main(String[] args) {
        var entry = DiscoveryDomainCatalog.load().find("factor-proposal", "v1").orElseThrow();
        for (String proposal : List.of("15:5", "15:4")) {
            var run = RegelsucheDiscovery.forRegistration(entry).campaign("solver-adapter")
                .seed("proposal", proposal, "untrusted-solver").run();
            System.out.println("outcome=" + run.outcome());
            System.out.println(run.canonicalEvidence());
        }
    }
}
