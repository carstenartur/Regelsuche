package de.regelsuche.proof;

import de.regelsuche.assumption.Assumption;
import de.regelsuche.validation.CandidateProofStatus;
import de.regelsuche.mining.RuleCandidate;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * Service tying {@link ProofBridge}s together: invokes the bridge, optionally
 * writes the generated artifact to disk, optionally runs an external prover
 * via {@link ProverExecutor}, and returns the resulting candidate with the
 * lifted {@link CandidateProofStatus}.
 *
 * <p>The service refuses to <em>lower</em> a candidate's status — if a
 * candidate is already {@link CandidateProofStatus#FORMALLY_PROVED}, calling
 * the bridge does not regress it back to {@code FORMALLY_PROVABLE}.</p>
 *
 * <p>A candidate is only lifted to {@link CandidateProofStatus#FORMALLY_PROVED}
 * when a configured {@link ProverExecutor} actually returned
 * {@link ProverExecutionResult.Status#PROVER_CONFIRMED}. Without an executor
 * (or with a {@code SCRIPT_GENERATED} / {@code PROVER_NOT_AVAILABLE} /
 * {@code PROVER_TIMEOUT} / {@code PROVER_FAILED} outcome) the candidate
 * stays at the {@link CandidateProofStatus#FORMALLY_PROVABLE} ceiling the
 * bridge itself returned.</p>
 */
public class ProofBridgeService {
    private final ProofBridge bridge;
    private final Path artifactDirectory;
    private final ProverExecutor executor;

    public ProofBridgeService(ProofBridge bridge) {
        this(bridge, null, null);
    }

    public ProofBridgeService(ProofBridge bridge, Path artifactDirectory) {
        this(bridge, artifactDirectory, null);
    }

    public ProofBridgeService(ProofBridge bridge, Path artifactDirectory, ProverExecutor executor) {
        this.bridge = Objects.requireNonNull(bridge, "bridge");
        this.artifactDirectory = artifactDirectory;
        this.executor = executor;
    }

    public ProofAttemptOutcome attemptWithDetails(RuleCandidate candidate, List<Assumption> assumptions) {
        ProofBridge.ProofAttempt attempt = bridge.prove(
            candidate.leftPattern(),
            candidate.rightPattern(),
            assumptions
        );
        Path artifactPath = null;
        if (artifactDirectory != null) {
            artifactPath = writeArtifact(candidate, attempt);
        }

        ProverExecutionResult execution = null;
        CandidateProofStatus next = attempt.status();
        if (executor != null) {
            execution = executor.execute(attempt.artifact());
            if (execution.status() == ProverExecutionResult.Status.PROVER_CONFIRMED) {
                next = CandidateProofStatus.FORMALLY_PROVED;
            } else {
                // Cap at FORMALLY_PROVABLE — script generated but not confirmed.
                if (next.ordinal() > CandidateProofStatus.FORMALLY_PROVABLE.ordinal()) {
                    next = CandidateProofStatus.FORMALLY_PROVABLE;
                }
            }
        }

        if (candidate.proofStatus() != null
            && candidate.proofStatus().ordinal() > next.ordinal()) {
            next = candidate.proofStatus();
        }

        RuleCandidate updated = new RuleCandidate(
            candidate.leftPattern(),
            candidate.rightPattern(),
            candidate.examplesCount(),
            candidate.averageScoreImprovement(),
            candidate.maximumScoreImprovement(),
            candidate.equivalenceVerified(),
            candidate.generalizationPlausible(),
            candidate.containsFreeParameters(),
            candidate.parameterRelations(),
            candidate.status(),
            next,
            candidate.canonicalHash(),
            candidate.supportingTransformationIds()
        );
        return new ProofAttemptOutcome(updated, attempt, execution, artifactPath);
    }

    public RuleCandidate attempt(RuleCandidate candidate, List<Assumption> assumptions) {
        return attemptWithDetails(candidate, assumptions).candidate();
    }

    private Path writeArtifact(RuleCandidate candidate, ProofBridge.ProofAttempt attempt) {
        try {
            Files.createDirectories(artifactDirectory);
            String suffix = switch (attempt.tool()) {
                case "lean4" -> ".lean";
                case "smtlib2" -> ".smt2";
                default -> ".txt";
            };
            Path target = artifactDirectory.resolve(safeFileName(candidate) + suffix);
            Files.writeString(target, attempt.artifact(), StandardCharsets.UTF_8);
            return target;
        } catch (IOException ex) {
            return null;
        }
    }

    private String safeFileName(RuleCandidate candidate) {
        String base = candidate.leftPattern() + "_to_" + candidate.rightPattern();
        return base.replaceAll("[^A-Za-z0-9_-]", "_");
    }

    /** Full bundle returned to callers that need execution details. */
    public record ProofAttemptOutcome(
        RuleCandidate candidate,
        ProofBridge.ProofAttempt attempt,
        ProverExecutionResult execution,
        Path artifactPath
    ) {
    }
}
