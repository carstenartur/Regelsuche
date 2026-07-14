package de.regelsuche.mining;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.mining.InterestingnessCalibrationCase.RelevanceLabel;
import de.regelsuche.mining.InterestingnessCalibrationCase.Split;
import de.regelsuche.mining.InterestingnessEvidence.ControlClassification;
import de.regelsuche.mining.InterestingnessEvidence.ProjectNoveltyStatus;
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

class InterestingnessCalibrationEvidenceTest {
    @Test
    void writesByteStableHeldOutCalibrationReport() throws Exception {
        List<InterestingnessCalibrationCase> cases = List.of(
            item("cal-high", "cal-family-high", Split.CALIBRATION, RelevanceLabel.HIGH,
                "A * B + A * C", "A * (B + C)", ControlClassification.NONE, 800),
            item("cal-control", "cal-family-control", Split.CALIBRATION, RelevanceLabel.CONTROL,
                "A + 0", "A", ControlClassification.GENERIC_NORMALIZATION, 0),
            item("test-high", "test-family-high", Split.TEST, RelevanceLabel.HIGH,
                "A / B + A / C", "A * (1 / B + 1 / C)", ControlClassification.NONE, 600),
            item("test-control", "test-family-control", Split.TEST, RelevanceLabel.CONTROL,
                "A * 1", "A", ControlClassification.GENERIC_NORMALIZATION, 0));
        List<InterestingnessCalibrationCase> reversed = new ArrayList<>(cases);
        Collections.reverse(reversed);
        InterestingnessProfileCalibrationEvaluator evaluator =
            new InterestingnessProfileCalibrationEvaluator();

        InterestingnessCalibrationReport first = evaluator.evaluate(cases);
        InterestingnessCalibrationReport second = evaluator.evaluate(reversed);
        Path output = Path.of(
            "build", "reports", "interestingness-calibration", "report.json");
        Files.createDirectories(output.getParent());
        Files.writeString(output, first.toCanonicalJson());

        assertEquals(first, second);
        assertEquals(first.toCanonicalJson(), Files.readString(output));
        assertTrue(first.toCanonicalJson().contains(
            "\"schema\":\"regelsuche.interestingness-calibration/v1\""));
        assertTrue(first.toCanonicalJson().contains("\"status\":\"EVALUATED\""));
        assertTrue(first.toCanonicalJson().contains("\"selectedProfile\":"));
        assertTrue(Files.isRegularFile(output));
    }

    private static InterestingnessCalibrationCase item(
        String id,
        String family,
        Split split,
        RelevanceLabel label,
        String left,
        String right,
        ControlClassification control,
        int utility
    ) {
        HypothesisCandidate candidate = new HypothesisCandidate(
            id,
            left,
            right,
            control == ControlClassification.NONE
                ? List.of("p1>p2>p3", "p4>p5")
                : List.of("control"),
            control == ControlClassification.NONE
                ? List.of(
                    new HypothesisCandidate.ExpressionPair("x*2+x*3", "x*(2+3)"),
                    new HypothesisCandidate.ExpressionPair("p*4+p*5", "p*(4+5)"))
                : List.of(new HypothesisCandidate.ExpressionPair("x+0", "x")),
            List.of(),
            0.8,
            CandidateProofStatus.VALIDATED_BY_EXAMPLES,
            Boolean.FALSE,
            List.of(),
            Map.of(),
            Instant.EPOCH);
        InterestingnessEvidence evidence = new InterestingnessEvidence(
            id + "-evidence",
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
            ProjectNoveltyStatus.NOVEL_WITHIN_PROJECT,
            control == ControlClassification.NONE ? 2 : 1,
            control == ControlClassification.NONE,
            control == ControlClassification.NONE ? 1 : 0,
            control == ControlClassification.NONE ? 1 : 0,
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
            control == ControlClassification.NONE ? 0.1 : 0.95,
            control == ControlClassification.NONE
                ? Set.of("algebra", "rational")
                : Set.of("algebra"),
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
