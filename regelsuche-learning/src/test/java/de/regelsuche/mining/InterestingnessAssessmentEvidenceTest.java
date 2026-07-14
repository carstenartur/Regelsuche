package de.regelsuche.mining;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.mining.InterestingnessEvidence.ControlClassification;
import de.regelsuche.mining.InterestingnessEvidence.ProjectNoveltyStatus;
import de.regelsuche.validation.CandidateProofStatus;
import de.regelsuche.validation.CounterexampleSearchService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class InterestingnessAssessmentEvidenceTest {
    @Test
    void writesByteStableAssessmentArtifact() throws Exception {
        HypothesisCandidate candidate = new HypothesisCandidate(
            "interestingness-reference-bridge",
            "A * B + A * C",
            "A * (B + C)",
            List.of("factor-path-1>normalize-1", "factor-path-2>normalize-2"),
            List.of(
                new HypothesisCandidate.ExpressionPair("x*2+x*3", "x*(2+3)"),
                new HypothesisCandidate.ExpressionPair("p*4+p*5", "p*(4+5)")),
            List.of(),
            0.8,
            CandidateProofStatus.VALIDATED_BY_EXAMPLES,
            Boolean.FALSE,
            List.of(),
            Map.of("A", List.of("p", "x")),
            Instant.EPOCH);
        InterestingnessEvidence evidence = new InterestingnessEvidence(
            "interestingness-reference-evidence",
            4,
            4,
            0,
            0,
            4,
            4,
            0,
            0,
            CounterexampleSearchService.Status.NO_COUNTEREXAMPLE_FOUND,
            3,
            false,
            ProjectNoveltyStatus.NOVEL_WITHIN_PROJECT,
            3,
            true,
            1,
            1,
            true,
            500,
            ControlClassification.NONE);
        EvidenceAwareInterestingnessAssessor assessor =
            new EvidenceAwareInterestingnessAssessor();

        InterestingnessAssessment first = assessor.assess(
            candidate,
            0.15,
            Set.of("algebra", "functional", "rational"),
            evidence,
            InterestingnessProfile.THEORY_DISCOVERY);
        InterestingnessAssessment second = assessor.assess(
            candidate,
            0.15,
            Set.of("rational", "algebra", "functional"),
            evidence,
            InterestingnessProfile.THEORY_DISCOVERY);
        Path output = Path.of(
            "build", "reports", "interestingness-assessment", "report.json");
        Files.createDirectories(output.getParent());
        Files.writeString(output, first.toCanonicalJson());

        assertEquals(first, second);
        assertEquals(first.toCanonicalJson(), Files.readString(output));
        assertTrue(Files.isRegularFile(output));
        assertTrue(first.toCanonicalJson().contains("\"eligibility\":\"RANKABLE_COMPLETE\""));
        assertTrue(first.toCanonicalJson().contains("\"externalNoveltyStatus\":\"NOT_EVALUATED\""));
        assertTrue(first.toCanonicalJson().contains("\"publicEvidenceStatus\":\"NOT_EVALUATED\""));
    }
}
