package de.regelsuche.mining;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.mining.InterestingnessAcceptanceGate.Thresholds;
import de.regelsuche.mining.InterestingnessCalibrationCorpus.CorpusReport;
import de.regelsuche.mining.InterestingnessCalibrationCorpus.CorpusSplit;
import de.regelsuche.mining.InterestingnessCalibrationCorpus.CorpusStatus;
import de.regelsuche.mining.InterestingnessCalibrationCorpus.FrozenCase;
import de.regelsuche.mining.InterestingnessEvidence.ControlClassification;
import de.regelsuche.mining.InterestingnessEvidence.ProjectNoveltyStatus;
import de.regelsuche.mining.InterestingnessProfileCalibration.CalibrationReport;
import de.regelsuche.mining.InterestingnessProfileCalibration.CaseProfiles;
import de.regelsuche.validation.CandidateProofStatus;
import de.regelsuche.validation.CounterexampleSearchService;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Retains one internally consistent profile/acceptance evidence pair for CI. */
class InterestingnessAcceptanceEvidenceTest {
    @Test
    void retainedAcceptanceReferencesAProductionCalibratedProfileReport()
            throws Exception {
        CalibrationReport profileReport = profileReport();
        var decision = new InterestingnessAcceptanceGate().evaluate(
            profileReport, Thresholds.conservativeDefault());
        Path directory = Path.of(
            "build", "reports", "interestingness-calibration");
        Path profileOutput = directory.resolve("profile-report.json");
        Path acceptanceOutput = directory.resolve("acceptance.json");
        Files.createDirectories(directory);
        profileReport.write(profileOutput);
        Files.writeString(acceptanceOutput, decision.toCanonicalJson());

        assertEquals(profileReport.contentHash(), decision.calibrationReportHash());
        assertNotEquals(hash("placeholder-report"), profileReport.contentHash());
        assertTrue(Files.readString(profileOutput).contains(
            "\"contentHash\":\"" + decision.calibrationReportHash() + "\""));
        assertEquals(decision.toCanonicalJson(), Files.readString(acceptanceOutput));
        assertFalse(decision.accepted());
        assertTrue(decision.blockers().contains("test-case-count=3<4"));
    }

    private static CalibrationReport profileReport() {
        List<Prepared> prepared = List.of(
            prepared("cal-reuse", CorpusSplit.CALIBRATION, "cal-algebra", 850,
                ControlClassification.NONE, 900),
            prepared("cal-surprise", CorpusSplit.CALIBRATION, "cal-rational", 650,
                ControlClassification.NONE, 650),
            prepared("cal-control", CorpusSplit.CALIBRATION, "cal-control", 100,
                ControlClassification.GENERIC_NORMALIZATION, 0),
            prepared("test-reuse", CorpusSplit.TEST, "test-functional", 800,
                ControlClassification.NONE, 900),
            prepared("test-surprise", CorpusSplit.TEST, "test-combinatorics", 700,
                ControlClassification.NONE, 650),
            prepared("test-control", CorpusSplit.TEST, "test-control", 100,
                ControlClassification.FORMAT_ONLY, 0));
        CorpusReport corpus = new CorpusReport(
            InterestingnessCalibrationCorpus.SCHEMA,
            CorpusStatus.FROZEN,
            InterestingnessCalibrationCorpus.MIN_CASES_PER_SPLIT,
            prepared.stream().map(Prepared::frozenCase).toList(),
            hash("predictive-corpus"),
            hash("labeled-evaluation"),
            hash("review-consensus"));
        return new InterestingnessProfileCalibration().calibrate(
            corpus, prepared.stream().map(Prepared::profiles).toList());
    }

    private static Prepared prepared(
        String candidateId,
        CorpusSplit split,
        String family,
        int relevance,
        ControlClassification control,
        int pairedUtility
    ) {
        boolean trivial = control != ControlClassification.NONE;
        HypothesisCandidate candidate = candidate(candidateId, trivial);
        InterestingnessEvidence evidence = new InterestingnessEvidence(
            hash("evidence-" + candidateId),
            2, 2, 0, 0,
            2, 2, 0, 0,
            CounterexampleSearchService.Status.NO_COUNTEREXAMPLE_FOUND,
            2,
            false,
            trivial ? ProjectNoveltyStatus.DUPLICATE
                : ProjectNoveltyStatus.NOVEL_WITHIN_PROJECT,
            trivial ? 1 : 3,
            !trivial,
            trivial ? 0 : 1,
            trivial ? 0 : 1,
            true,
            pairedUtility,
            control);
        EvidenceAwareInterestingnessAssessor assessor =
            new EvidenceAwareInterestingnessAssessor();
        double similarity = trivial ? 0.95 : 0.10;
        Set<String> domains = trivial
            ? Set.of("normalization")
            : Set.of("algebra", family);
        InterestingnessAssessment theory = assessor.assess(
            candidate, similarity, domains, evidence,
            InterestingnessProfile.THEORY_DISCOVERY);
        InterestingnessAssessment reuse = assessor.assess(
            candidate, similarity, domains, evidence,
            InterestingnessProfile.SEARCH_REUSE);
        FrozenCase frozenCase = new FrozenCase(
            candidateId + "-case",
            candidateId,
            split,
            family,
            hash("shape-" + candidateId),
            theory.contentHash(),
            control,
            relevance,
            2,
            2,
            40,
            850,
            List.of("mathematical-usefulness", "structural-reuse"));
        return new Prepared(
            frozenCase,
            new CaseProfiles(
                frozenCase.caseId(), candidateId, theory, reuse));
    }

    private static HypothesisCandidate candidate(
        String candidateId,
        boolean trivial
    ) {
        String left = trivial ? "A + 0" : "A * B + A * C";
        String right = trivial ? "A" : "A * (B + C)";
        List<String> paths = trivial
            ? List.of("add-zero")
            : candidateId.contains("reuse")
                ? List.of("prepare>factor", "normalize>factor", "alternate>factor")
                : List.of("prepare>factor", "alternate>factor");
        List<HypothesisCandidate.ExpressionPair> witnesses = trivial
            ? List.of(new HypothesisCandidate.ExpressionPair("x+0", "x"))
            : List.of(
                new HypothesisCandidate.ExpressionPair("m*2+m*3", "m*(2+3)"),
                new HypothesisCandidate.ExpressionPair(
                    "(p/q)*4+(p/q)*5", "(p/q)*(4+5)"));
        return new HypothesisCandidate(
            candidateId,
            left,
            right,
            paths,
            witnesses,
            List.of(),
            trivial ? 0.05 : 0.85,
            CandidateProofStatus.VALIDATED_BY_EXAMPLES,
            Boolean.FALSE,
            CounterexampleSearchService.Status.NO_COUNTEREXAMPLE_FOUND,
            List.of("numeric-boundary-values", "symbolic-substitutions"),
            "no counterexample within the configured budget",
            List.of(),
            Map.of("A", List.of("x", "p/q")),
            Instant.parse("2026-07-14T12:00:00Z"));
    }

    private static String hash(String material) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(material.getBytes(StandardCharsets.UTF_8));
            return "sha256:" + java.util.HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private record Prepared(
        FrozenCase frozenCase,
        CaseProfiles profiles
    ) {
    }
}
