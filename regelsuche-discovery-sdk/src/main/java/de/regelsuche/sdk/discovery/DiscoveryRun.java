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

/** Typed, read-only result view over the canonical evidence of one SDK run. */
public final class DiscoveryRun<C, K> {
    private final Optional<C> selectedCandidate;
    private final Optional<K> selectedCertificate;
    private final DomainDiscoveryEvidence evidence;

    DiscoveryRun(
        Optional<C> selectedCandidate,
        Optional<K> selectedCertificate,
        DomainDiscoveryEvidence evidence
    ) {
        this.selectedCandidate = selectedCandidate == null
            ? Optional.empty()
            : selectedCandidate;
        this.selectedCertificate = selectedCertificate == null
            ? Optional.empty()
            : selectedCertificate;
        this.evidence = Objects.requireNonNull(evidence, "evidence");
        boolean objectsPresent = this.selectedCandidate.isPresent()
            || this.selectedCertificate.isPresent();
        if (evidence.outcome() == Outcome.CONFIRMED) {
            if (this.selectedCandidate.isEmpty()
                    || this.selectedCertificate.isEmpty()) {
                throw new IllegalArgumentException(
                    "confirmed run requires candidate and certificate objects"
                );
            }
        } else if (objectsPresent) {
            throw new IllegalArgumentException(
                "non-confirmed run must not expose selected objects"
            );
        }
    }

    /** Selected candidate only for a confirmed run. */
    public Optional<C> selectedCandidate() {
        return selectedCandidate;
    }

    /** Selected certificate only for a confirmed run. */
    public Optional<K> selectedCertificate() {
        return selectedCertificate;
    }

    /** Canonical immutable evidence produced by the core runner. */
    public DomainDiscoveryEvidence evidence() {
        return evidence;
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
