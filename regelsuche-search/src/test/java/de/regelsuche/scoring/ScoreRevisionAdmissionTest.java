package de.regelsuche.scoring;

import static org.junit.jupiter.api.Assertions.*;

import de.regelsuche.json.JsonWriter;
import org.junit.jupiter.api.Test;

class ScoreRevisionAdmissionTest {
    private static final String SCHEMA = "test.score-replay/v2";

    @Test void currentRevisionIsAdmittedWithoutReinterpretingNumbers() {
        assertDoesNotThrow(() -> ScoreRevision.requireReplay(json(ScoreRevision.CURRENT), SCHEMA, ScoreRevision.CURRENT));
    }

    @Test void missingAndLegacyRevisionsAreRejected() {
        assertThrows(IllegalArgumentException.class,
            () -> ScoreRevision.requireReplay("{\"schema\":\"test.score-replay/v2\"}", SCHEMA, ScoreRevision.CURRENT));
        assertThrows(IllegalArgumentException.class,
            () -> ScoreRevision.requireReplay(json("legacy/v1"), SCHEMA, ScoreRevision.CURRENT));
    }

    @Test void explicitCustomRevisionIsNotAutomaticallyTheBuiltInMetric() {
        assertDoesNotThrow(() -> ScoreRevision.requireReplay(json("custom/v1"), SCHEMA, "custom/v1"));
        assertThrows(IllegalArgumentException.class,
            () -> ScoreRevision.requireReplay(json("custom/v1"), SCHEMA, ScoreRevision.CURRENT));
        assertThrows(IllegalArgumentException.class, () -> ScoreRevision.requireCurrent("custom/v1"));
    }

    @Test void unspecifiedOrMissingExpectedRevisionCannotAuthorizeReplay() {
        for (String value : new String[] {null, "", " ", ScoreRevision.UNSPECIFIED}) {
            assertThrows(IllegalArgumentException.class,
                () -> ScoreRevision.requireReplay(json(ScoreRevision.UNSPECIFIED), SCHEMA, value));
        }
    }

    @Test void oldSchemaCannotBeMadeCurrentByAddingOnlyARevision() {
        assertThrows(IllegalArgumentException.class,
            () -> ScoreRevision.requireReplay(json(ScoreRevision.CURRENT).replace("/v2", "/v1"), SCHEMA, ScoreRevision.CURRENT));
    }

    private static String json(String revision) {
        return new JsonWriter().beginObject().property("schema", SCHEMA)
            .property("scoringRevision", revision).endObject().toString();
    }
}
