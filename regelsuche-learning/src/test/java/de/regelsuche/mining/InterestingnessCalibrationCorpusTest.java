package de.regelsuche.mining;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.mining.InterestingnessCalibrationCorpus.CorpusCase;
import de.regelsuche.mining.InterestingnessCalibrationCorpus.CorpusSplit;
import de.regelsuche.mining.InterestingnessCalibrationCorpus.CorpusStatus;
import de.regelsuche.mining.InterestingnessEvidence.ControlClassification;
import de.regelsuche.mining.InterestingnessReviewConsensus.ConsensusStatus;
import de.regelsuche.mining.InterestingnessReviewConsensus.ReviewLabel;
import de.regelsuche.mining.InterestingnessReviewConsensus.ReviewSource;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class InterestingnessCalibrationCorpusTest {
    private final InterestingnessReviewConsensus consensus =
        new InterestingnessReviewConsensus();
    private final InterestingnessCalibrationCorpus corpus =
        new InterestingnessCalibrationCorpus();

    @Test
    void independentBlindExpertReviewsProduceConsensus() {
        var report = consensus.evaluate(reviews("candidate-a", 780, 720));
        var candidate = report.requireCandidate("candidate-a");

        assertEquals(ConsensusStatus.CONSENSUS, candidate.status());
        assertEquals(2, candidate.countedExpertReviews());
        assertEquals(2, candidate.blindExpertReviews());
        assertEquals(750, candidate.consensusRelevancePermille());
        assertEquals(60, candidate.spreadPermille());
        assertTrue(report.contentHash().matches("sha256:[0-9a-f]{64}"));
    }

    @Test
    void disagreementAndFixtureLabelsCannotMasqueradeAsConsensus() {
        var uncertain = consensus.evaluate(reviews("candidate-a", 900, 300));
        var development = consensus.evaluate(List.of(new ReviewLabel(
            "fixture-review",
            "candidate-b",
            sha("fixture-runner"),
            ReviewSource.TEST_FIXTURE,
            true,
            900,
            1000,
            List.of("fixture-only"))));

        assertEquals(
            ConsensusStatus.UNCERTAIN,
            uncertain.requireCandidate("candidate-a").status());
        assertEquals(
            ConsensusStatus.DEVELOPMENT_ONLY,
            development.requireCandidate("candidate-b").status());
    }

    @Test
    void duplicateExpertReviewerForOneCandidateIsRejected() {
        String reviewer = sha("reviewer-1");
        List<ReviewLabel> labels = List.of(
            label("review-a", "candidate-a", reviewer, 700),
            label("review-b", "candidate-a", reviewer, 750));

        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> consensus.evaluate(labels));

        assertTrue(exception.getMessage().contains("duplicate expert reviewer"));
    }

    @Test
    void freezesDisjointReviewedCorpusAndSeparatesPredictiveFromLabelHashes()
            throws Exception {
        List<CorpusCase> cases = validCases();
        var firstConsensus = consensus.evaluate(validReviews(700));
        var changedTestLabels = consensus.evaluate(validReviews(620));

        var first = corpus.freeze(cases, firstConsensus);
        var changed = corpus.freeze(cases, changedTestLabels);
        var reversed = corpus.freeze(
            List.of(cases.get(3), cases.get(1), cases.get(2), cases.get(0)),
            firstConsensus);

        Path directory = Path.of(
            "build", "reports", "interestingness-calibration");
        Path consensusOutput = directory.resolve("review-consensus.json");
        Path corpusOutput = directory.resolve("corpus.json");
        firstConsensus.write(consensusOutput);
        first.write(corpusOutput);

        assertEquals(CorpusStatus.FROZEN, first.status());
        assertEquals(2, first.split(CorpusSplit.CALIBRATION).size());
        assertEquals(2, first.split(CorpusSplit.TEST).size());
        assertEquals(first.predictiveCorpusHash(), changed.predictiveCorpusHash());
        assertNotEquals(first.labeledEvaluationHash(), changed.labeledEvaluationHash());
        assertEquals(first, reversed);
        assertEquals(firstConsensus.toCanonicalJson(), Files.readString(consensusOutput));
        assertEquals(first.toCanonicalJson(), Files.readString(corpusOutput));
    }

    @Test
    void familyOrStructuralLeakageAcrossSplitsIsRejected() {
        List<CorpusCase> familyLeak = new ArrayList<>(validCases());
        CorpusCase test = familyLeak.get(2);
        familyLeak.set(2, new CorpusCase(
            test.caseId(),
            test.candidateId(),
            test.split(),
            "calibration-algebra",
            test.structuralSignatureHash(),
            test.assessmentContentHash(),
            test.controlClassification()));
        List<CorpusCase> signatureLeak = new ArrayList<>(validCases());
        CorpusCase calibration = signatureLeak.get(0);
        CorpusCase heldOut = signatureLeak.get(2);
        signatureLeak.set(2, new CorpusCase(
            heldOut.caseId(),
            heldOut.candidateId(),
            heldOut.split(),
            heldOut.candidateFamily(),
            calibration.structuralSignatureHash(),
            heldOut.assessmentContentHash(),
            heldOut.controlClassification()));
        var labels = consensus.evaluate(validReviews(700));

        assertThrows(
            IllegalArgumentException.class,
            () -> corpus.freeze(familyLeak, labels));
        assertThrows(
            IllegalArgumentException.class,
            () -> corpus.freeze(signatureLeak, labels));
    }

    @Test
    void nonConsensusReviewCannotEnterFrozenCorpus() {
        List<ReviewLabel> labels = new ArrayList<>(validReviews(700));
        labels.removeIf(label ->
            label.candidateId().equals("test-bridge")
                && label.reviewerHash().equals(sha("reviewer-2")));
        var incomplete = consensus.evaluate(labels);

        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> corpus.freeze(validCases(), incomplete));

        assertTrue(exception.getMessage().contains("lacks expert consensus"));
    }

    private static List<CorpusCase> validCases() {
        return List.of(
            corpusCase(
                "calibration-bridge-case",
                "calibration-bridge",
                CorpusSplit.CALIBRATION,
                "calibration-algebra",
                "calibration-bridge-shape",
                ControlClassification.NONE),
            corpusCase(
                "calibration-control-case",
                "calibration-control",
                CorpusSplit.CALIBRATION,
                "calibration-normalization",
                "calibration-control-shape",
                ControlClassification.GENERIC_NORMALIZATION),
            corpusCase(
                "test-bridge-case",
                "test-bridge",
                CorpusSplit.TEST,
                "test-functional",
                "test-bridge-shape",
                ControlClassification.NONE),
            corpusCase(
                "test-control-case",
                "test-control",
                CorpusSplit.TEST,
                "test-formatting",
                "test-control-shape",
                ControlClassification.FORMAT_ONLY));
    }

    private static CorpusCase corpusCase(
        String caseId,
        String candidateId,
        CorpusSplit split,
        String family,
        String signatureSeed,
        ControlClassification control
    ) {
        return new CorpusCase(
            caseId,
            candidateId,
            split,
            family,
            sha(signatureSeed),
            sha("assessment-" + candidateId),
            control);
    }

    private static List<ReviewLabel> validReviews(int testBridgeLabel) {
        List<ReviewLabel> labels = new ArrayList<>();
        labels.addAll(reviews("calibration-bridge", 850, 800));
        labels.addAll(reviews("calibration-control", 150, 100));
        labels.addAll(reviews("test-bridge", testBridgeLabel, testBridgeLabel - 40));
        labels.addAll(reviews("test-control", 120, 80));
        return List.copyOf(labels);
    }

    private static List<ReviewLabel> reviews(
        String candidateId,
        int firstRelevance,
        int secondRelevance
    ) {
        return List.of(
            label(
                "review-1-" + candidateId,
                candidateId,
                sha("reviewer-1"),
                firstRelevance),
            label(
                "review-2-" + candidateId,
                candidateId,
                sha("reviewer-2"),
                secondRelevance));
    }

    private static ReviewLabel label(
        String reviewId,
        String candidateId,
        String reviewerHash,
        int relevance
    ) {
        return new ReviewLabel(
            reviewId,
            candidateId,
            reviewerHash,
            ReviewSource.EXPERT_REVIEW,
            true,
            relevance,
            850,
            List.of("structural-reuse", "readable"));
    }

    private static String sha(String material) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(material.getBytes(StandardCharsets.UTF_8));
            return "sha256:" + java.util.HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }
}
