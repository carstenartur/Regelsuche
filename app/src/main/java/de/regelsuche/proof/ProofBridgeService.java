package de.regelsuche.proof;

import de.regelsuche.assumption.Assumption;
import de.regelsuche.mining.CandidateProofStatus;
import de.regelsuche.mining.RuleCandidate;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * Service tying {@link ProofBridge}s together: invokes the bridge, optionally
 * writes the generated artifact to disk and returns the resulting candidate
 * with the lifted {@link CandidateProofStatus}.
 *
 * <p>The service refuses to <em>lower</em> a candidate's status — if a
 * candidate is already {@link CandidateProofStatus#FORMALLY_PROVED}, calling
 * the bridge does not regress it back to {@code FORMALLY_PROVABLE}.</p>
 */
public class ProofBridgeService {
    private final ProofBridge bridge;
    private final Path artifactDirectory;

    public ProofBridgeService(ProofBridge bridge) {
        this(bridge, null);
    }

    public ProofBridgeService(ProofBridge bridge, Path artifactDirectory) {
        this.bridge = Objects.requireNonNull(bridge, "bridge");
        this.artifactDirectory = artifactDirectory;
    }

    public RuleCandidate attempt(RuleCandidate candidate, List<Assumption> assumptions) {
        ProofBridge.ProofAttempt attempt = bridge.prove(
            candidate.leftPattern(),
            candidate.rightPattern(),
            assumptions
        );
        if (artifactDirectory != null) {
            try {
                Files.createDirectories(artifactDirectory);
                String suffix = switch (attempt.tool()) {
                    case "lean4" -> ".lean";
                    case "smtlib2" -> ".smt2";
                    default -> ".txt";
                };
                Path target = artifactDirectory.resolve(safeFileName(candidate) + suffix);
                Files.writeString(target, attempt.artifact(), StandardCharsets.UTF_8);
            } catch (IOException ex) {
                // Don't fail the candidate just because we couldn't write the artifact.
            }
        }
        CandidateProofStatus next = attempt.status();
        if (candidate.proofStatus() != null
            && candidate.proofStatus().ordinal() > next.ordinal()) {
            next = candidate.proofStatus();
        }
        return new RuleCandidate(
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
    }

    private String safeFileName(RuleCandidate candidate) {
        String base = candidate.leftPattern() + "_to_" + candidate.rightPattern();
        return base.replaceAll("[^A-Za-z0-9_-]", "_");
    }
}
