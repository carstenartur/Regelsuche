package de.regelsuche.discovery.domain;

import de.regelsuche.discovery.domain.DiscoveryDomain.CounterexampleResult;
import de.regelsuche.discovery.domain.DiscoveryDomain.CounterexampleStatus;
import de.regelsuche.discovery.domain.DiscoveryDomain.Evaluation;
import de.regelsuche.discovery.domain.DiscoveryDomain.Successor;
import de.regelsuche.discovery.domain.DomainDiscoveryEvidence.CandidateAttempt;
import de.regelsuche.discovery.domain.DomainDiscoveryEvidence.StateTrace;
import de.regelsuche.discovery.domain.ScopedLogicDiscoveryDomain.Certificate;
import de.regelsuche.discovery.domain.ScopedLogicDiscoveryDomain.State;
import de.regelsuche.solver.ir.ScopedProofDag;
import de.regelsuche.solver.ir.ScopedProofExecutor;
import java.util.Objects;
import java.util.Optional;

/** Reconstructs one retained logical candidate before asking the configured receiver to check it afresh. */
public final class ScopedLogicWorkspaceCheck {
    private static final int MAX_SOURCE_CHARACTERS = 8192;
    private static final int MAX_FORMATION_WORK = 4096;
    private static final int MAX_COUNTEREXAMPLE_ATTEMPTS = 256;

    private ScopedLogicWorkspaceCheck() { }

    public enum RetainedWitness { NOT_RECORDED, REPRODUCED, DIFFERS }

    public record Source(String runId, String workspaceHash, String evidenceHash, String seedHash,
            String candidateHash, String stateHash) { }

    public static final class Result {
        private final Source source;
        private final ScopedProofDag proposal;
        private final CounterexampleResult counterexample;
        private final RetainedWitness retainedWitness;
        private final Optional<Evaluation<Certificate>> freshProof;

        private Result(Source source, ScopedProofDag proposal, CounterexampleResult counterexample,
                RetainedWitness retainedWitness, Optional<Evaluation<Certificate>> freshProof) {
            this.source = source;
            this.proposal = proposal;
            this.counterexample = counterexample;
            this.retainedWitness = retainedWitness;
            this.freshProof = freshProof;
        }

        public Source source() { return source; }
        public ScopedProofDag proposal() { return proposal; }
        public CounterexampleResult counterexample() { return counterexample; }
        public RetainedWitness retainedWitness() { return retainedWitness; }
        public Optional<Evaluation<Certificate>> freshProof() { return freshProof; }
    }

    /**
     * Rechecks the attempted candidate, including when the source asserted a historical certificate.
     * The caller supplies the trusted receiver. Read-only workspace imports never call it.
     */
    public static Result check(DomainExportWorkspace workspace, String candidateHash, ScopedProofExecutor receiver) {
        Objects.requireNonNull(workspace, "workspace");
        Objects.requireNonNull(receiver, "receiver");
        DomainCanonical.requireSha256(candidateHash, "candidateHash");
        var evidence = workspace.evidence();
        if (!workspace.scopedLogicDomain() || evidence.seed().payload().length() > MAX_SOURCE_CHARACTERS) {
            throw new IllegalArgumentException("outside bounded scoped workspace check");
        }
        var attempts = evidence.candidateAttempts().stream().filter(row -> row.candidateHash().equals(candidateHash)).toList();
        if (attempts.size() != 1) throw new IllegalArgumentException("expected one retained scoped candidate attempt");

        var domain = new ScopedLogicDiscoveryDomain(receiver, MAX_FORMATION_WORK);
        var initial = domain.generator().generate(evidence.seed()).getFirst();
        var codec = domain.stateCodec();
        String initialHash = codec.contentHash(initial);
        boolean matchesInitial = evidence.states().stream().anyMatch(row -> row.depth() == 0 && !row.candidateReady()
            && row.parentStateHash().isEmpty() && row.actionId().isEmpty() && row.stateHash().equals(initialHash)
            && row.canonicalState().equals(codec.canonicalForm(initial)));
        if (!matchesInitial) throw new IllegalArgumentException("retained initial state differs from the scoped seed");

        var proposal = domain.operators().getFirst().apply(initial).stream().filter(step -> step.state().proposal() != null)
            .filter(step -> domain.candidateCodec().contentHash(step.state().proposal()).equals(candidateHash))
            .findFirst().orElseThrow(() -> new IllegalArgumentException("cannot reconstruct scoped candidate within formation bound"));
        var candidateState = retainedState(evidence, domain, initialHash, proposal);
        ScopedProofDag dag = proposal.state().proposal();
        var counterexample = domain.counterexampleGenerator().search(dag, MAX_COUNTEREXAMPLE_ATTEMPTS);
        Optional<Evaluation<Certificate>> proof = counterexample.status() == CounterexampleStatus.NONE_FOUND
            ? Optional.of(domain.evaluator().evaluate(dag)) : Optional.empty();
        var source = new Source(workspace.runId(), workspace.contentHash(), evidence.contentHash(), evidence.seed().contentHash(),
            candidateHash, candidateState.stateHash());
        return new Result(source, dag, counterexample, witness(attempts.getFirst(), counterexample), proof);
    }

    private static StateTrace retainedState(DomainDiscoveryEvidence evidence, ScopedLogicDiscoveryDomain domain,
            String initialHash, Successor<State> proposal) {
        String stateHash = domain.stateCodec().contentHash(proposal.state());
        String canonicalState = domain.stateCodec().canonicalForm(proposal.state());
        var retained = evidence.states().stream().filter(row -> row.depth() == 1 && row.candidateReady()
            && row.stateHash().equals(stateHash) && row.canonicalState().equals(canonicalState)
            && row.parentStateHash().equals(initialHash) && row.actionId().equals(proposal.actionId())).findFirst()
            .orElseThrow(() -> new IllegalArgumentException("retained scoped candidate state differs from reconstructed proposal"));
        boolean accepted = evidence.transitions().stream().anyMatch(edge -> edge.accepted() && edge.cost() == proposal.cost()
            && edge.actionId().equals(proposal.actionId()) && edge.fromStateHash().equals(initialHash)
            && edge.toStateHash().equals(stateHash) && edge.semanticsPreserving() == proposal.semanticsPreserving()
            && edge.assumptions().equals(proposal.assumptions()));
        if (!accepted) throw new IllegalArgumentException("scoped candidate has no matching retained transition");
        return retained;
    }

    private static RetainedWitness witness(CandidateAttempt attempt, CounterexampleResult result) {
        if (attempt.counterexampleStatus() != CounterexampleStatus.FOUND) return RetainedWitness.NOT_RECORDED;
        return result.status() == CounterexampleStatus.FOUND && attempt.counterexampleWitness().equals(result.witness())
            && attempt.counterexampleAttempts() == result.attempts() ? RetainedWitness.REPRODUCED : RetainedWitness.DIFFERS;
    }
}
