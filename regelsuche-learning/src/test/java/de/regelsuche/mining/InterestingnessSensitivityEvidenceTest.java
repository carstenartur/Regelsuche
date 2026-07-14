package de.regelsuche.mining;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.mining.InterestingnessCalibrationCase.RelevanceLabel;
import de.regelsuche.mining.InterestingnessCalibrationCase.Split;
import de.regelsuche.mining.InterestingnessEvidence.ControlClassification;
import de.regelsuche.mining.InterestingnessEvidence.ProjectNoveltyStatus;
import de.regelsuche.mining.InterestingnessSensitivityReport.SensitivityStatus;
import de.regelsuche.validation.CandidateProofStatus;
import de.regelsuche.validation.CounterexampleSearchService;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class InterestingnessSensitivityEvidenceTest {
    @Test
    void writesByteStableSensitivityReport() throws Exception {
        List<InterestingnessCalibrationCase> cases = List.of(
            item("cal-high", "cal-family-high", Split.CALIBRATION, RelevanceLabel.HIGH,
                "A*B+A*C", "A*(B+C)", 900, ControlClassification.NONE),
            item("cal-medium", "cal-family-medium", Split.CALIBRATION, RelevanceLabel.MEDIUM,
                "A*B+C*D", "H(A,B,C,D)", 250, ControlClassification.NONE),
            item("cal-control", "cal-family-control", Split.CALIBRATION, RelevanceLabel.CONTROL,
                "A+0", "A", 0, ControlClassification.GENERIC_NORMALIZATION),
            item("test-high", "test-family-high", Split.TEST, RelevanceLabel.HIGH,
                "A/B+A/C", "A*(1/B+1/C)", 700, ControlClassification.NONE),
            item("test-low", "test-family-low", Split.TEST, RelevanceLabel.LOW,
                "A^2+B^2", "K(A,B)", 100, ControlClassification.NONE),
            item("test-control", "test-family-control", Split.TEST, RelevanceLabel.CONTROL,
                "A*1", "A", 0, ControlClassification.GENERIC_NORMALIZATION));
        List<InterestingnessCalibrationCase> reversed = new ArrayList<>(cases);
        Collections.reverse(reversed);
        InterestingnessProfileCalibrationEvaluator evaluator =
            new InterestingnessProfileCalibrationEvaluator();
        InterestingnessCalibrationReport baseline = evaluator.evaluate(cases);
        InterestingnessSensitivityAnalyzer analyzer =
            new InterestingnessSensitivityAnalyzer();

        InterestingnessSensitivityReport first = analyzer.analyze(cases, baseline);
        InterestingnessSensitivityReport second = analyzer.analyze(reversed, baseline);
        Path output = Path.of(
            "build", "reports", "interestingness-sensitivity", "report.json");
        Files.createDirectories(output.getParent());
        Files.writeString(output, first.toCanonicalJson());

        assertEquals(SensitivityStatus.EVALUATED, first.status());
        assertEquals(first, second);
        assertEquals(first.toCanonicalJson(), Files.readString(output));
        assertTrue(first.toCanonicalJson().contains(
            "\"schema\":\"regelsuche.interestingness-sensitivity/v1\""));
        assertTrue(first.toCanonicalJson().contains("\"leaveOneOutScenarios\":["));
        assertTrue(Files.isRegularFile(output));
    }

    private static InterestingnessCalibrationCase item(
        String id,
        String family,
        Split split,
        RelevanceLabel label,
        String left,
        String right,
        int utility,
        ControlClassification control
    ) {
        boolean substantive = control == ControlClassification.NONE;
        HypothesisCandidate candidate = new HypothesisCandidate(
            id,
            left,
            right,
            substantive ? List.of("p1>p2>p3", "p4>p5") : List.of("control"),
            substantive
                ? List.of(
                    new HypothesisCandidate.ExpressionPair("x*2+x*3", "x*(2+3)"),
                    new HypothesisCandidate.ExpressionPair("p*4+p*5", "p*(4+5)"))
                : List.of(new HypothesisCandidate.ExpressionPair("x+0", "x")),
            List.of(),
            substantive ? 0.8 : 0.1,
            CandidateProofStatus.VALIDATED_BY_EXAMPLES,
            Boolean.FALSE,
            List.of(),
            Map.of(),
            Instant.EPOCH);
        InterestingnessEvidence evidence = new InterestingnessEvidence(
            id + "-evidence",
            3,
            3,
            0,
            0,
            3,
            3,
            0,
            0,
            CounterexampleSearchService.Status.NO_COUNTEREXAMPLE_FOUND,
            2,
            false,
            ProjectNoveltyStatus.NOVEL_WITHIN_PROJECT,
            substantive ? 2 : 1,
            substantive,
            substantive ? 1 : 0,
            substantive ? 1 : 0,
            true,
            utility,
            control);
        return new InterestingnessCalibrationCase(
            id,
            family,
            hash(id + "-signature"),
            split,
            candidate,
            evidence,
            substantive ? 0.15 : 0.98,
            substantive ? Set.of("algebra", "rational") : Set.of("algebra"),
            label);
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
