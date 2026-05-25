package de.regelsuche.proof;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.assumption.Assumption;
import de.regelsuche.validation.CandidateProofStatus;
import de.regelsuche.mining.RuleCandidate;
import de.regelsuche.mining.RuleStatus;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProofBridgeServiceTest {

    private RuleCandidate baseline() {
        return new RuleCandidate(
            "A + 0",
            "A",
            3,
            1.0,
            2,
            true,
            true,
            true,
            List.of(),
            RuleStatus.NEW,
            CandidateProofStatus.VALIDATED_BY_EXAMPLES,
            "hash_xyz",
            List.of()
        );
    }

    @Test
    void liftsStatusAndWritesArtifact(@TempDir Path tempDir) throws IOException {
        ProofBridgeService service = new ProofBridgeService(new LeanProofBridge(), tempDir);
        RuleCandidate result = service.attempt(baseline(), List.of(Assumption.nonZero("b")));
        assertEquals(CandidateProofStatus.FORMALLY_PROVABLE, result.proofStatus());
        // Artifact written
        try (var stream = Files.list(tempDir)) {
            assertTrue(stream.findAny().isPresent(), "expected at least one artifact file");
        }
    }

    @Test
    void doesNotLowerExistingProvedStatus() {
        ProofBridgeService service = new ProofBridgeService(new SmtProofBridge());
        RuleCandidate provedCandidate = new RuleCandidate(
            "A + 0",
            "A",
            3,
            1.0,
            2,
            true,
            true,
            true,
            List.of(),
            RuleStatus.NEW,
            CandidateProofStatus.FORMALLY_PROVED,
            "hash",
            List.of()
        );
        RuleCandidate result = service.attempt(provedCandidate, List.of());
        assertEquals(CandidateProofStatus.FORMALLY_PROVED, result.proofStatus());
    }
}
