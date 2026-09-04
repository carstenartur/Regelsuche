package de.regelsuche.sdk.discovery;

import de.regelsuche.discovery.domain.DiscoveryDomain.CounterexampleStatus;
import de.regelsuche.discovery.domain.DomainDiscoveryEvidence;
import de.regelsuche.discovery.domain.DomainDiscoveryEvidence.Outcome;
import de.regelsuche.discovery.domain.DomainDiscoveryEvidence.Resource;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Typed result view over the canonical evidence of one discovery run. */
public record DiscoveryRun<C, K>(
    Optional<C> selectedCandidate,
    Optional<K> selectedCertificate,
    DomainDiscoveryEvidence evidence
) {
    public DiscoveryRun {
        selectedCandidate = selectedCandidate == null
            ? Optional.empty()
            : selectedCandidate;
        selectedCertificate = selectedCertificate == null
            ? Optional.empty()
            : selectedCertificate;
        Objects.requireNonNull(evidence, "evidence");
        if (evidence.outcome() == Outcome.CONFIRMED
                && (selectedCandidate.isEmpty() || selectedCertificate.isEmpty())) {
            throw new IllegalArgumentException(
                "confirmed run requires candidate and certificate objects"
            );
        }
    }

    /** Terminal execution status. */
    public Outcome outcome() {
        return evidence.outcome();
    }

    /** Whether the domain evaluator issued a certificate. */
    public boolean isConfirmed() {
        return outcome() == Outcome.CONFIRMED;
    }

    /** Concrete counterexample witnesses retained during candidate search. */
    public List<String> counterexamples() {
        return evidence.candidateAttempts().stream()
            .filter(attempt ->
                attempt.counterexampleStatus() == CounterexampleStatus.FOUND)
            .map(DomainDiscoveryEvidence.CandidateAttempt::counterexampleWitness)
            .toList();
    }

    /** Executed work by canonical resource dimension. */
    public Map<Resource, Integer> executedWork() {
        LinkedHashMap<Resource, Integer> work = new LinkedHashMap<>();
        evidence.resources().forEach(line ->
            work.put(line.resource(), line.executed()));
        return java.util.Collections.unmodifiableMap(work);
    }

    /** Canonical JSON including the evidence content hash. */
    public String canonicalEvidence() {
        return evidence.toCanonicalJson();
    }
}
