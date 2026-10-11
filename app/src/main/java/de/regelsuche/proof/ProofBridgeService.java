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
 * <p>Every proof confirmation is fresh and bound to the current goal and assumptions.
 * Incoming candidate flags and custom process success cannot authorize FORMALLY_PROVED.
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
        CandidateProofStatus next = cap(attempt.status());
        if (executor != null) {
            if (executor.checksMathematicalEvidence()) {
                try {
                    var expected = ProofObligationAdapter.equality(candidate.leftPattern(), candidate.rightPattern(), assumptions);
                    execution = executor.executeBound(attempt.artifact(), expected);
                } catch (IllegalArgumentException unsupported) {
                    execution = new ProverExecutionResult(ProverExecutionResult.Status.PROVER_FAILED,
                        -1, "", unsupported.getMessage(), 0, attempt.tool());
                }
            } else {
                execution = executor.execute(attempt.artifact());
            }
            if (executor.checksMathematicalEvidence()
                    && execution.status() == ProverExecutionResult.Status.PROVER_CONFIRMED)
                next = CandidateProofStatus.FORMALLY_PROVED;
        }
        // A prior higher numeric status carries no goal/premise-bound evidence.
        // Preserve lesser discovery information, never an unbound formal confirmation.
        CandidateProofStatus previous = candidate.proofStatus();
        if (previous != null && previous.ordinal() <= CandidateProofStatus.SYMBOLICALLY_VERIFIED.ordinal()
                && previous.ordinal() > next.ordinal()) next = previous;

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

    private static CandidateProofStatus cap(CandidateProofStatus status) {
        return status == CandidateProofStatus.FORMALLY_PROVED ? CandidateProofStatus.FORMALLY_PROVABLE : status;
    }

    private Path writeArtifact(RuleCandidate candidate, ProofBridge.ProofAttempt attempt) {
        try {
            Files.createDirectories(artifactDirectory);
            String suffix = switch (attempt.tool()) {
                case "lean4" -> ".lean";
                case "smtlib2" -> ".smt2";
                default -> ".txt";
            };
            Path run = Files.createTempDirectory(artifactDirectory, "attempt-");
            Path target = run.resolve("proof" + suffix);
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
