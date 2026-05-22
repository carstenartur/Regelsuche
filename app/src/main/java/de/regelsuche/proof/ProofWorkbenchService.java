package de.regelsuche.proof;

import de.regelsuche.assumption.Assumption;
import de.regelsuche.mining.CandidateProofStatus;
import de.regelsuche.mining.RuleCandidate;
import de.regelsuche.mining.RuleStatus;
import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Thin coordinator that the workbench REST layer talks to.
 *
 * <p>Bundles together the {@link ProofJobScheduler} (for lifecycle operations),
 * the {@link ProofJobRepository} (for read-only listings) and the
 * {@link ProofArtifactRepository} (for streaming proof outputs to the UI).
 * Centralising these concerns keeps the HTTP handler dumb and lets us evolve
 * the underlying components independently.</p>
 */
public final class ProofWorkbenchService {

    private final ProofJobScheduler scheduler;
    private final ProofJobRepository jobRepository;
    private final ProofArtifactRepository artifactRepository;

    public ProofWorkbenchService(
        ProofJobScheduler scheduler,
        ProofJobRepository jobRepository,
        ProofArtifactRepository artifactRepository
    ) {
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.jobRepository = Objects.requireNonNull(jobRepository, "jobRepository");
        this.artifactRepository = Objects.requireNonNull(artifactRepository, "artifactRepository");
    }

    /** Submit a new job from the workbench / REST layer. */
    public String submit(String leftPattern, String rightPattern,
                         List<Assumption> assumptions, int priority, String workerHint) {
        RuleCandidate candidate = new RuleCandidate(
            leftPattern == null ? "" : leftPattern,
            rightPattern == null ? "" : rightPattern,
            0, 0.0, 0, false, false, false,
            List.of(), RuleStatus.NEW, CandidateProofStatus.OBSERVED,
            workerHint == null ? "" : workerHint
        );
        return scheduler.submit(candidate, assumptions == null ? List.of() : assumptions, priority);
    }

    public List<ProofJob> listJobs() {
        return jobRepository.findAll();
    }

    public Optional<ProofJob> getJob(String jobId) {
        return jobRepository.findById(jobId);
    }

    public Optional<ProofJob> cancel(String jobId) {
        return scheduler.cancel(jobId);
    }

    public List<String> listArtifacts(String jobId) {
        return artifactRepository.listJobArtifacts(jobId);
    }

    public Optional<String> readArtifact(String jobId, String name) throws IOException {
        return artifactRepository.readJobArtifact(jobId, name);
    }

    public ProofArtifactRepository artifactRepository() {
        return artifactRepository;
    }
}
