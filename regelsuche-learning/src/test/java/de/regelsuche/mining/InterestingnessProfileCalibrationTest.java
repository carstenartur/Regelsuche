package de.regelsuche.mining;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.mining.InterestingnessCalibrationCorpus.CorpusCase;
import de.regelsuche.mining.InterestingnessCalibrationCorpus.CorpusReport;
import de.regelsuche.mining.InterestingnessCalibrationCorpus.CorpusSplit;
import de.regelsuche.mining.InterestingnessEvidence.ControlClassification;
import de.regelsuche.mining.InterestingnessEvidence.ProjectNoveltyStatus;
import de.regelsuche.mining.InterestingnessProfileCalibration.CalibrationReport;
import de.regelsuche.mining.InterestingnessProfileCalibration.CaseProfiles;
import de.regelsuche.mining.InterestingnessReviewConsensus.ConsensusReport;
import de.regelsuche.mining.InterestingnessReviewConsensus.ReviewLabel;
import de.regelsuche.mining.InterestingnessReviewConsensus.ReviewSource;
import de.regelsuche.validation.CandidateProofStatus;
import de.regelsuche.validation.CounterexampleSearchService;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class InterestingnessProfileCalibrationTest {
    private final EvidenceAwareInterestingnessAssessor assessor =
        new EvidenceAwareInterestingnessAssessor();
    private final InterestingnessReviewConsensus reviewer =
        new InterestingnessReviewConsensus();
    private final InterestingnessCalibrationCorpus corpusBuilder =
        new InterestingnessCalibrationCorpus();
    private final InterestingnessProfileCalibration calibration =
        new InterestingnessProfileCalibration();

    @Test
    void testLabelsCannotChangeProfileSelectionOrSelectionHash() throws Exception {
        Dataset dataset = dataset();
        ConsensusReport originalLabels = reviewer.evaluate(reviews(false));
        ConsensusReport changedTestLabels = reviewer.evaluate(reviews(true));
        CorpusReport originalCorpus = corpusBuilder.freeze(dataset.cases(), originalLabels);
        CorpusReport changedCorpus = corpusBuilder.freeze(dataset.cases(), changedTestLabels);

        CalibrationReport original = calibration.calibrate(
            originalCorpus, dataset.profiles());
        CalibrationReport changed = calibration.calibrate(
            changedCorpus, dataset.profiles());
        Path output = Path.of(
            "build", "reports", "interestingness-calibration", "profile-report.json");
        original.write(output);

        assertEquals(
            originalCorpus.predictiveCorpusHash(),
            changedCorpus.predictiveCorpusHash());
        assertNotEquals(
            originalCorpus.labeledEvaluationHash(),
            changedCorpus.labeledEvaluationHash());
        assertEquals(original.selectedProfile(), changed.selectedProfile());
        assertEquals(original.selectionHash(), changed.selectionHash());
        assertNotEquals(original.contentHash(), changed.contentHash());
        assertEquals(original.toCanonicalJson(), Files.readString(output));
    }

    @Test
    void reportContainsHeldOutRankingParetoAndSensitivity() {
        Dataset dataset = dataset();
        CorpusReport corpus = corpusBuilder.freeze(
            dataset.cases(), reviewer.evaluate(reviews(false)));

        CalibrationReport report = calibration.calibrate(corpus, dataset.profiles());

        assertEquals(2, report.profileMetrics().size());
        assertEquals(3, report.testRanking().size());
        assertFalse(report.paretoCandidateIds().isEmpty());
        assertTrue(report.sensitivity().profileOrderAgreementPermille() >= 0);
        assertTrue(report.sensitivity().profileOrderAgreementPermille() <= 1000);
        assertTrue(report.sensitivity().leaveOneOutSelectionStabilityPermille() >= 0);
        assertTrue(report.sensitivity().leaveOneOutSelectionStabilityPermille() <= 1000);
        assertTrue(report.selectionHash().matches("sha256:[0-9a-f]{64}"));
        assertTrue(report.contentHash().matches("sha256:[0-9a-f]{64}"));
    }

    @Test
    void profilePairsMustShareTheSameRawEvidence() {
        Dataset dataset = dataset();
        CorpusReport corpus = corpusBuilder.freeze(
            dataset.cases(), reviewer.evaluate(reviews(false)));
        List<CaseProfiles> changed = new ArrayList<>(dataset.profiles());
        CaseProfiles original = changed.getFirst();
        Prepared prepared = prepared(
            original.caseId(),
            original.candidateId(),
            CorpusSplit.CALIBRATION,
            "unused-family",
            "unused-signature",
            CandidateKind.SURPRISE,
            ControlClassification.NONE,
            200);
        changed.set(0, new CaseProfiles(
            original.caseId(),
            original.candidateId(),
            original.theoryDiscovery(),
            prepared.profiles().searchReuse()));

        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> calibration.calibrate(corpus, changed));

        assertTrue(exception.getMessage().contains("identical raw evidence"));
    }

    @Test
    void missingOrExtraCaseProfilesAreRejected() {
        Dataset dataset = dataset();
        CorpusReport corpus = corpusBuilder.freeze(
            dataset.cases(), reviewer.evaluate(reviews(false)));
        List<CaseProfiles> missing = dataset.profiles().subList(
            0, dataset.profiles().size() - 1);

        assertThrows(
            IllegalArgumentException.class,
            () -> calibration.calibrate(corpus, missing));
    }

    private Dataset dataset() {
        List<Prepared> prepared = List.of(
            prepared(
                "cal-surprise-case",
                "cal-surprise",
                CorpusSplit.CALIBRATION,
                "calibration-algebra",
                "calibration-surprise-shape",
                CandidateKind.SURPRISE,
                ControlClassification.NONE,
                550),
            prepared(
                "cal-reuse-case",
                "cal-reuse",
                CorpusSplit.CALIBRATION,
                "calibration-reuse",
                "calibration-reuse-shape",
                CandidateKind.REUSE,
                ControlClassification.NONE,
                950),
            prepared(
                "cal-control-case",
                "cal-control",
                CorpusSplit.CALIBRATION,
                "calibration-control",
                "calibration-control-shape",
                CandidateKind.CONTROL,
                ControlClassification.GENERIC_NORMALIZATION,
                0),
            prepared(
                "test-surprise-case",
                "test-surprise",
                CorpusSplit.TEST,
                "test-functional",
                "test-surprise-shape",
                CandidateKind.SURPRISE,
                ControlClassification.NONE,
                650),
            prepared(
                "test-reuse-case",
                "test-reuse",
                CorpusSplit.TEST,
                "test-combinatorics",
                "test-reuse-shape",
                CandidateKind.REUSE,
                ControlClassification.NONE,
                900),
            prepared(
                "test-control-case",
                "test-control",
                CorpusSplit.TEST,
                "test-formatting",
                "test-control-shape",
                CandidateKind.CONTROL,
                ControlClassification.FORMAT_ONLY,
                0));
        return new Dataset(
            prepared.stream().map(Prepared::corpusCase).toList(),
            prepared.stream().map(Prepared::profiles).toList());
    }

    private Prepared prepared(
        String caseId,
        String candidateId,
        CorpusSplit split,
        String family,
        String signatureSeed,
        CandidateKind kind,
        ControlClassification control,
        int utilityPermille
    ) {
        HypothesisCandidate candidate = candidate(candidateId, kind);
        InterestingnessEvidence evidence = evidence(
            candidateId, kind, control, utilityPermille);
        double similarity = kind == CandidateKind.CONTROL ? 0.95
            : kind == CandidateKind.SURPRISE ? 0.05 : 0.20;
        Set<String> domains = kind == CandidateKind.SURPRISE
            ? Set.of("algebra", "rational", "functional")
            : kind == CandidateKind.REUSE
                ? Set.of("algebra", "combinatorics")
                : Set.of("normalization");
        InterestingnessAssessment theory = assessor.assess(
            candidate,
            similarity,
            domains,
            evidence,
            InterestingnessProfile.THEORY_DISCOVERY);
        InterestingnessAssessment reuse = assessor.assess(
            candidate,
            similarity,
            domains,
            evidence,
            InterestingnessProfile.SEARCH_REUSE);
        return new Prepared(
            new CorpusCase(
                caseId,
                candidateId,
                split,
                family,
                sha(signatureSeed),
                theory.contentHash(),
                control),
            new CaseProfiles(caseId, candidateId, theory, reuse));
    }

    private static HypothesisCandidate candidate(
        String candidateId,
        CandidateKind kind
    ) {
        return switch (kind) {
            case SURPRISE -> hypothesis(
                candidateId,
                "A * B + A * C",
                "A * (B + C)",
                List.of("prepare>factor-common", "normalize>factor-common"),
                List.of(
                    pair("m*2+m*3", "m*(2+3)"),
                    pair("(p/q)*4+(p/q)*5", "(p/q)*(4+5)")),
                0.90);
            case REUSE -> hypothesis(
                candidateId,
                "F(A, B)",
                "G(A, B)",
                List.of(
                    "r1>r2>r3>r4>r5>r6",
                    "s1>s2>s3>s4>s5",
                    "t1>t2>t3>t4"),
                List.of(
                    pair("f(x,y)", "g(x,y)"),
                    pair("f(a,b)", "g(a,b)"),
                    pair("f(u,v)", "g(u,v)")),
                0.45);
            case CONTROL -> hypothesis(
                candidateId,
                "A + 0",
                "A",
                List.of("add-zero"),
                List.of(pair("x+0", "x")),
                0.05);
        };
    }

    private static HypothesisCandidate hypothesis(
        String id,
        String left,
        String right,
        List<String> paths,
        List<HypothesisCandidate.ExpressionPair> witnesses,
        double novelty
    ) {
        return new HypothesisCandidate(
            id,
            left,
            right,
            paths,
            witnesses,
            List.of(),
            novelty,
            CandidateProofStatus.VALIDATED_BY_EXAMPLES,
            Boolean.FALSE,
            CounterexampleSearchService.Status.NO_COUNTEREXAMPLE_FOUND,
            List.of("numeric-boundary-values", "symbolic-substitutions"),
            "no counterexample within the configured budget",
            List.of(),
            Map.of("A", List.of("x", "a")),
            Instant.parse("2026-07-14T12:00:00Z"));
    }

    private static HypothesisCandidate.ExpressionPair pair(
        String left,
        String right
    ) {
        return new HypothesisCandidate.ExpressionPair(left, right);
    }

    private static InterestingnessEvidence evidence(
        String candidateId,
        CandidateKind kind,
        ControlClassification control,
        int utilityPermille
    ) {
        boolean heldOut = kind != CandidateKind.CONTROL;
        return new InterestingnessEvidence(
            sha("evidence-" + candidateId),
            2,
            2,
            0,
            0,
            2,
            2,
            0,
            0,
            CounterexampleSearchService.Status.NO_COUNTEREXAMPLE_FOUND,
            2,
            false,
            kind == CandidateKind.CONTROL
                ? ProjectNoveltyStatus.DUPLICATE
                : ProjectNoveltyStatus.NOVEL_WITHIN_PROJECT,
            heldOut ? 3 : 1,
            heldOut,
            heldOut ? 1 : 0,
            heldOut ? 1 : 0,
            true,
            utilityPermille,
            control);
    }

    private static List<ReviewLabel> reviews(boolean changedTestLabels) {
        List<ReviewLabel> labels = new ArrayList<>();
        addReviews(labels, "cal-surprise", 900, 860);
        addReviews(labels, "cal-reuse", 680, 640);
        addReviews(labels, "cal-control", 120, 80);
        if (changedTestLabels) {
            addReviews(labels, "test-surprise", 220, 180);
            addReviews(labels, "test-reuse", 920, 880);
            addReviews(labels, "test-control", 520, 480);
        } else {
            addReviews(labels, "test-surprise", 840, 800);
            addReviews(labels, "test-reuse", 720, 680);
            addReviews(labels, "test-control", 120, 80);
        }
        return List.copyOf(labels);
    }

    private static void addReviews(
        List<ReviewLabel> labels,
        String candidateId,
        int first,
        int second
    ) {
        labels.add(review(candidateId, "reviewer-1", first));
        labels.add(review(candidateId, "reviewer-2", second));
    }

    private static ReviewLabel review(
        String candidateId,
        String reviewer,
        int relevance
    ) {
        return new ReviewLabel(
            "review-" + reviewer + '-' + candidateId,
            candidateId,
            sha(reviewer),
            ReviewSource.EXPERT_REVIEW,
            true,
            relevance,
            850,
            List.of("mathematical-usefulness", "structural-reuse"));
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

    private enum CandidateKind {
        SURPRISE,
        REUSE,
        CONTROL
    }

    private record Prepared(CorpusCase corpusCase, CaseProfiles profiles) {
    }

    private record Dataset(
        List<CorpusCase> cases,
        List<CaseProfiles> profiles
    ) {
    }
}
