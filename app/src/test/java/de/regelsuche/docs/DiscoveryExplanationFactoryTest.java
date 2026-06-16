package de.regelsuche.docs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.explanation.TransformationExplanation;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Tests that {@link DiscoveryExplanationFactory} produces structured
 * {@link TransformationExplanation} objects from {@link PromotionRecord} data
 * without any Markdown rendering.
 */
class DiscoveryExplanationFactoryTest {

    private final DiscoveryExplanationFactory factory = new DiscoveryExplanationFactory();

    @Test
    void buildTransformationExplanationExtractsInterestReasonsWithoutMarkdown() {
        PromotionRecord record = recordBuilder("candidate-reused")
            .stage(PromotionStage.REUSED)
            .measuredImprovement(true)
            .reusedMacroIds(List.of("macro.abc"))
            .oracleEvidence("some-evidence")
            .build();

        TransformationExplanation explanation = factory.buildTransformationExplanation(record);

        assertTrue(explanation.interestReasons().contains("macro reused"));
        assertTrue(explanation.interestReasons().contains("expression score improved"));
        assertTrue(explanation.interestReasons().stream().anyMatch(r -> r.contains("macro.abc")));
        assertTrue(explanation.interestReasons().stream().anyMatch(r -> r.contains("some-evidence")));
        assertFalse(explanation.interestReasons().stream().anyMatch(r -> r.contains("**")));
        assertFalse(explanation.interestReasons().stream().anyMatch(r -> r.contains("#")));
    }

    @Test
    void buildTransformationExplanationExtractsPathReasons() {
        PromotionRecord record = recordBuilder("candidate-agree")
            .oracleStatus("AGREE")
            .evidenceExists(true)
            .ablationStatus("DEGRADED")
            .build();

        TransformationExplanation explanation = factory.buildTransformationExplanation(record);

        assertTrue(explanation.pathReasons().contains("oracle agrees"));
        assertTrue(explanation.pathReasons().contains("evidence present"));
        assertTrue(explanation.pathReasons().stream().anyMatch(r -> r.contains("DEGRADED")));
    }

    @Test
    void buildTransformationExplanationExtractsTreePositionFromAssumptions() {
        PromotionRecord record = recordBuilder("candidate-position")
            .assumptions(List.of(
                "treePosition.pathKey=001",
                "treePosition.before=x^2",
                "treePosition.after=(x+1)^2 - 1"
            ))
            .build();

        TransformationExplanation explanation = factory.buildTransformationExplanation(record);

        assertEquals("001", explanation.position());
        assertEquals("x^2", explanation.before());
        assertEquals("(x+1)^2 - 1", explanation.after());
    }

    @Test
    void buildTransformationExplanationFallsBackToRecordExpressionsWhenNoPositionAssumptions() {
        PromotionRecord record = recordBuilder("candidate-fallback")
            .originalExpression("x + x")
            .discoveredStructure("2 * x")
            .build();

        TransformationExplanation explanation = factory.buildTransformationExplanation(record);

        assertEquals("root", explanation.position());
        assertEquals("x + x", explanation.before());
        assertEquals("2 * x", explanation.after());
    }

    @Test
    void buildInterestReasonsIsEmptyForNonPromotedCandidate() {
        PromotionRecord record = recordBuilder("candidate-observed")
            .stage(PromotionStage.OBSERVED)
            .build();

        List<String> reasons = factory.buildInterestReasons(record);

        assertTrue(reasons.isEmpty());
    }

    @Test
    void buildPathReasonsIsEmptyWhenOracleUnavailableAndNoEvidence() {
        PromotionRecord record = recordBuilder("candidate-no-evidence")
            .oracleStatus("UNAVAILABLE")
            .evidenceExists(false)
            .ablationStatus("N/A")
            .build();

        List<String> reasons = factory.buildPathReasons(record);

        assertTrue(reasons.isEmpty());
    }

    // ---- builder helpers -------------------------------------------------

    private static RecordBuilder recordBuilder(String candidateId) {
        return new RecordBuilder(candidateId);
    }

    private static final class RecordBuilder {
        private final String candidateId;
        private PromotionStage stage = PromotionStage.OBSERVED;
        private String originalExpression = "";
        private String discoveredStructure = "";
        private String oracleStatus = "UNAVAILABLE";
        private String oracleEvidence = "";
        private String ablationStatus = "N/A";
        private boolean evidenceExists = false;
        private boolean measuredImprovement = false;
        private List<String> reusedMacroIds = List.of();
        private List<String> assumptions = List.of();

        RecordBuilder(String candidateId) {
            this.candidateId = candidateId;
        }

        RecordBuilder stage(PromotionStage stage) { this.stage = stage; return this; }
        RecordBuilder originalExpression(String v) { this.originalExpression = v; return this; }
        RecordBuilder discoveredStructure(String v) { this.discoveredStructure = v; return this; }
        RecordBuilder oracleStatus(String v) { this.oracleStatus = v; return this; }
        RecordBuilder oracleEvidence(String v) { this.oracleEvidence = v; return this; }
        RecordBuilder ablationStatus(String v) { this.ablationStatus = v; return this; }
        RecordBuilder evidenceExists(boolean v) { this.evidenceExists = v; return this; }
        RecordBuilder measuredImprovement(boolean v) { this.measuredImprovement = v; return this; }
        RecordBuilder reusedMacroIds(List<String> v) { this.reusedMacroIds = v; return this; }
        RecordBuilder assumptions(List<String> v) { this.assumptions = v; return this; }

        PromotionRecord build() {
            return new PromotionRecord(
                candidateId,
                "test-campaign",
                "2026-01-01",
                "test-family",
                stage,
                originalExpression,
                discoveredStructure,
                oracleStatus,
                oracleEvidence,
                ablationStatus,
                "",
                "",
                assumptions,
                "",
                List.of(),
                false,
                List.of(),
                evidenceExists,
                false,
                false,
                false,
                "",
                reusedMacroIds,
                measuredImprovement,
                ""
            );
        }
    }
}
