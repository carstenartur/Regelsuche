package de.regelsuche.mining;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.mining.InterestingnessCalibrationCase.RelevanceLabel;
import de.regelsuche.mining.InterestingnessCalibrationCase.Split;
import de.regelsuche.mining.InterestingnessCalibrationReport.CalibrationStatus;
import de.regelsuche.mining.InterestingnessEvidence.ControlClassification;
import de.regelsuche.mining.InterestingnessEvidence.ProjectNoveltyStatus;
import de.regelsuche.validation.CandidateProofStatus;
import de.regelsuche.validation.CounterexampleSearchService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class InterestingnessProfileCalibrationEvaluatorTest {
    private final InterestingnessProfileCalibrationEvaluator evaluator =
        new InterestingnessProfileCalibrationEvaluator();

    @Test
    void comparesTwoProfilesOnDisjointCandidateFamiliesAndEmitsParetoEvidence() {
        InterestingnessCalibrationReport report = evaluator.evaluate(dataset(false));

        assertEquals(CalibrationStatus.EVALUATED, report.status());
        assertFalse(report.selectedProfile().equals("NOT_SELECTED"));
        assertEquals(2, report.profileMetrics().size());
        assertEquals(3, report.calibrationResults().size());
        assertEquals(3, report.testResults().size());
        assertTrue(Collections.disjoint(report.calibrationFamilies(), report.testFamilies()));
        assertTrue(report.blockers().isEmpty());
        assertTrue(report.calibrationAgreementPermille() >= 0);
        assertTrue(report.calibrationAgreementPermille() <= 1000);
        assertTrue(report.testAgreementPermille() >= 0);
        assertTrue(report.testAgreementPermille() <= 1000);
        assertEquals(3, report.testParetoFront().size());
        assertTrue(report.testParetoFront().stream().anyMatch(
            InterestingnessCalibrationReport.ParetoPoint::paretoOptimal));
        assertTrue(report.predictiveDatasetHash().startsWith("sha256:"));
        assertTrue(report.labeledEvaluationHash().startsWith("sha256:"));
        assertTrue(report.contentHash().startsWith("sha256:"));
        assertTrue(report.toCanonicalJson().contains("\"relevanceLabel\":\"HIGH\""));
    }

    @Test
    void changingOnlyTestLabelsCannotChangeProfileOrPredictiveAssessments() {
        InterestingnessCalibrationReport original = evaluator.evaluate(dataset(false));
        InterestingnessCalibrationReport relabeled = evaluator.evaluate(dataset(true));

        assertEquals(original.selectedProfile(), relabeled.selectedProfile());
        assertEquals(original.predictiveDatasetHash(), relabeled.predictiveDatasetHash());
        assertFalse(original.labeledEvaluationHash().equals(relabeled.labeledEvaluationHash()));
        assertEquals(original.calibrationAgreementPermille(),
            relabeled.calibrationAgreementPermille());
        assertEquals(assessmentHashes(original.testResults()),
            assessmentHashes(relabeled.testResults()));
        assertEquals(assessmentTotals(original.testResults()),
            assessmentTotals(relabeled.testResults()));
    }

    @Test
    void familyOrStructuralSignatureCrossingTheSplitRejectsEvaluation() {
        List<InterestingnessCalibrationCase> cases = new ArrayList<>(dataset(false));
        InterestingnessCalibrationCase calibration = cases.stream()
            .filter(item -> item.split() == Split.CALIBRATION)
            .findFirst()
            .orElseThrow();
        InterestingnessCalibrationCase test = cases.stream()
            .filter(item -> item.split() == Split.TEST)
            .findFirst()
            .orElseThrow();
        cases.set(cases.indexOf(test), new InterestingnessCalibrationCase(
            test.caseId(),
            calibration.structuralFamily(),
            calibration.structuralSignatureHash(),
            test.split(),
            test.candidate(),
            test.evidence(),
            test.knownRuleSimilarity(),
            test.domainTags(),
            test.relevanceLabel()));

        InterestingnessCalibrationReport report = evaluator.evaluate(cases);

        assertEquals(CalibrationStatus.SPLIT_REJECTED, report.status());
        assertEquals("NOT_SELECTED", report.selectedProfile());
        assertTrue(report.blockers().stream().anyMatch(
            blocker -> blocker.startsWith("family-split-leakage=")));
        assertTrue(report.blockers().stream().anyMatch(
            blocker -> blocker.startsWith("structural-signature-split-leakage=")));
        assertTrue(report.profileMetrics().isEmpty());
        assertTrue(report.testResults().isEmpty());
    }

    @Test
    void reportIsDeterministicAcrossInputOrdering() {
        List<InterestingnessCalibrationCase> ordered = dataset(false);
        List<InterestingnessCalibrationCase> reversed = new ArrayList<>(ordered);
        Collections.reverse(reversed);

        InterestingnessCalibrationReport first = evaluator.evaluate(ordered);
        InterestingnessCalibrationReport second = evaluator.evaluate(reversed);

        assertEquals(first, second);
        assertEquals(first.toCanonicalJson(), second.toCanonicalJson());
    }

    private static List<InterestingnessCalibrationCase> dataset(boolean flipTestLabels) {
        List<InterestingnessCalibrationCase> cases = new ArrayList<>();
        cases.add(calibrationCase(
            "cal-reusable",
            "calibration-search-reuse",
            "cal-reusable-signature",
            Split.CALIBRATION,
            reusableCandidate("cal-reusable"),
            completeEvidence("cal-reusable-evidence", 900, 1, 1, ControlClassification.NONE),
            0.25,
            Set.of("algebra", "rational", "functional"),
            RelevanceLabel.HIGH));
        cases.add(calibrationCase(
            "cal-theory",
            "calibration-structural-novelty",
            "cal-theory-signature",
            Split.CALIBRATION,
            theoreticalCandidate("cal-theory"),
            completeEvidence("cal-theory-evidence", 100, 1, 1, ControlClassification.NONE),
            0.0,
            Set.of("combinatorics", "algebra"),
            RelevanceLabel.MEDIUM));
        cases.add(calibrationCase(
            "cal-control",
            "calibration-neutral-control",
            "cal-control-signature",
            Split.CALIBRATION,
            controlCandidate("cal-control"),
            completeEvidence(
                "cal-control-evidence", 0, 0, 0,
                ControlClassification.GENERIC_NORMALIZATION),
            0.98,
            Set.of("algebra"),
            RelevanceLabel.CONTROL));

        cases.add(calibrationCase(
            "test-bridge",
            "test-rational-bridge",
            "test-bridge-signature",
            Split.TEST,
            reusableCandidate("test-bridge"),
            completeEvidence("test-bridge-evidence", 750, 1, 1, ControlClassification.NONE),
            0.15,
            Set.of("rational", "functional", "number-theory"),
            flipTestLabels ? RelevanceLabel.LOW : RelevanceLabel.HIGH));
        cases.add(calibrationCase(
            "test-incomplete",
            "test-functional-incomplete",
            "test-incomplete-signature",
            Split.TEST,
            theoreticalCandidate("test-incomplete"),
            incompleteEvidence("test-incomplete-evidence"),
            0.05,
            Set.of("functional", "analysis"),
            RelevanceLabel.LOW));
        cases.add(calibrationCase(
            "test-control",
            "test-format-control",
            "test-control-signature",
            Split.TEST,
            controlCandidate("test-control"),
            completeEvidence(
                "test-control-evidence", 0, 0, 0,
                ControlClassification.FORMAT_ONLY),
            0.99,
            Set.of("analysis"),
            flipTestLabels ? RelevanceLabel.HIGH : RelevanceLabel.CONTROL));
        return List.copyOf(cases);
    }

    private static InterestingnessCalibrationCase calibrationCase(
        String caseId,
        String family,
        String signatureSeed,
        Split split,
        HypothesisCandidate candidate,
        InterestingnessEvidence evidence,
        double similarity,
        Set<String> domains,
        RelevanceLabel label
    ) {
        return new InterestingnessCalibrationCase(
            caseId,
            family,
            hash(signatureSeed),
            split,
            candidate,
            evidence,
            similarity,
            domains,
            label);
    }

    private static HypothesisCandidate reusableCandidate(String id) {
        return candidate(
            id,
            "A * B + A * C",
            "A * (B + C)",
            List.of("p1>p2>p3>p4", "p5>p6>p7", "p8>p9>p10"),
            List.of(
                new HypothesisCandidate.ExpressionPair("x*2+x*3", "x*(2+3)"),
                new HypothesisCandidate.ExpressionPair("p*4+p*5", "p*(4+5)"),
                new HypothesisCandidate.ExpressionPair("m*6+m*7", "m*(6+7)")),
            0.8);
    }

    private static HypothesisCandidate theoreticalCandidate(String id) {
        return candidate(
            id,
            "A * B + C * D + E * F",
            "G(A, B, C, D, E, F)",
            List.of("t1>t2", "t3>t4"),
            List.of(
                new HypothesisCandidate.ExpressionPair("a*b+c*d+e*f", "g(a,b,c,d,e,f)"),
                new HypothesisCandidate.ExpressionPair("u*v+w*x+y*z", "g(u,v,w,x,y,z)")),
            0.95);
    }

    private static HypothesisCandidate controlCandidate(String id) {
        return candidate(
            id,
            "A + 0",
            "A",
            List.of("control"),
            List.of(new HypothesisCandidate.ExpressionPair("x + 0", "x")),
            0.1);
    }

    private static HypothesisCandidate candidate(
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
            List.of(),
            Map.of(),
            Instant.EPOCH);
    }

    private static InterestingnessEvidence completeEvidence(
        String id,
        int utilityPermille,
        int heldOutConfigured,
        int heldOutPassed,
        ControlClassification control
    ) {
        return new InterestingnessEvidence(
            id,
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
            heldOutConfigured > 0 ? 3 : 1,
            heldOutConfigured > 0,
            heldOutConfigured,
            heldOutPassed,
            true,
            utilityPermille,
            control);
    }

    private static InterestingnessEvidence incompleteEvidence(String id) {
        return new InterestingnessEvidence(
            id,
            4,
            3,
            1,
            0,
            4,
            4,
            0,
            0,
            CounterexampleSearchService.Status.INCONCLUSIVE,
            2,
            false,
            ProjectNoveltyStatus.UNKNOWN,
            2,
            true,
            1,
            0,
            false,
            0,
            ControlClassification.NONE);
    }

    private static Map<String, String> assessmentHashes(
        List<InterestingnessCalibrationReport.CaseResult> results
    ) {
        return results.stream().collect(Collectors.toMap(
            InterestingnessCalibrationReport.CaseResult::caseId,
            result -> result.assessment().contentHash()));
    }

    private static Map<String, Integer> assessmentTotals(
        List<InterestingnessCalibrationReport.CaseResult> results
    ) {
        return results.stream().collect(Collectors.toMap(
            InterestingnessCalibrationReport.CaseResult::caseId,
            result -> result.assessment().totalPermille()));
    }

    private static String hash(String value) {
        try {
            return "sha256:" + java.util.HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }
}
