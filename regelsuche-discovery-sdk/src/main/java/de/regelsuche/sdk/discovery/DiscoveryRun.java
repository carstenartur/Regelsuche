package de.regelsuche.sdk.discovery;

import de.regelsuche.discovery.domain.DiscoveryDomain.CanonicalCodec;
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
    private final java.util.function.Supplier<DiscoveryRun<C, K>> replayRunner;

    DiscoveryRun(
        Optional<C> selectedCandidate,
        Optional<K> selectedCertificate,
        DomainDiscoveryEvidence evidence,
        CanonicalCodec<C> candidateCodec,
        CanonicalCodec<K> certificateCodec
    ) {
        this(selectedCandidate, selectedCertificate, evidence, candidateCodec, certificateCodec, null);
    }

    DiscoveryRun(
        Optional<C> selectedCandidate,
        Optional<K> selectedCertificate,
        DomainDiscoveryEvidence evidence,
        CanonicalCodec<C> candidateCodec,
        CanonicalCodec<K> certificateCodec,
        java.util.function.Supplier<DiscoveryRun<C, K>> replayRunner
    ) {
        this.replayRunner = replayRunner;
        this.selectedCandidate = Objects.requireNonNull(
            selectedCandidate,
            "selectedCandidate"
        );
        this.selectedCertificate = Objects.requireNonNull(
            selectedCertificate,
            "selectedCertificate"
        );
        this.evidence = Objects.requireNonNull(evidence, "evidence");
        Objects.requireNonNull(candidateCodec, "candidateCodec");
        Objects.requireNonNull(certificateCodec, "certificateCodec");

        boolean objectsPresent = this.selectedCandidate.isPresent()
            || this.selectedCertificate.isPresent();
        if (evidence.outcome() == Outcome.CONFIRMED) {
            if (this.selectedCandidate.isEmpty()
                    || this.selectedCertificate.isEmpty()) {
                throw new IllegalArgumentException(
                    "confirmed run requires candidate and certificate objects"
                );
            }
            String candidateHash = candidateCodec.contentHash(
                this.selectedCandidate.orElseThrow()
            );
            if (!candidateHash.equals(evidence.selectedCandidateHash())) {
                throw new IllegalArgumentException(
                    "selected candidate object does not match canonical evidence"
                );
            }
            String certificateObjectHash = certificateCodec.contentHash(
                this.selectedCertificate.orElseThrow()
            );
            if (evidence.certificate() == null
                    || !certificateObjectHash.equals(
                        evidence.certificate().certificateObjectHash())) {
                throw new IllegalArgumentException(
                    "selected certificate object does not match canonical evidence"
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

    /**
     * Executes the captured domain, seed and budgets again, requiring identical
     * canonical evidence. This repeats callbacks and their work; it is a
     * determinism check, not an independent proof of arbitrary provider code.
     */
    public DiscoveryRun<C, K> replay() {
        if (replayRunner == null) throw new IllegalStateException("run has no captured replay recipe");
        DiscoveryRun<C, K> replayed = replayRunner.get();
        if (!canonicalEvidence().equals(replayed.canonicalEvidence())) {
            throw new IllegalStateException("replay evidence differs: expected " + evidence.contentHash()
                + ", observed " + replayed.evidence().contentHash());
        }
        return replayed;
    }

    /** Assumptions retained on accepted and rejected transitions, in canonical order. */
    public List<String> assumptions() {
        return evidence.transitions().stream().flatMap(trace -> trace.assumptions().stream())
            .distinct().sorted().toList();
    }

    /** Canonical evidence bytes, suitable for independent checking and retention. */
    public byte[] evidenceBytes() {
        return canonicalEvidence().getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    /** Writes evidence to a new file; never overwrites an existing artifact. */
    public java.nio.file.Path writeEvidence(java.nio.file.Path path) throws java.io.IOException {
        return java.nio.file.Files.write(path, evidenceBytes(), java.nio.file.StandardOpenOption.CREATE_NEW);
    }

    /** Canonical JSON including the evidence content hash. */
    public String canonicalEvidence() {
        return evidence.toCanonicalJson();
    }
}
