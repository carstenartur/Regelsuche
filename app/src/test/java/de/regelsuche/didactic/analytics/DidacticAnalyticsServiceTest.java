package de.regelsuche.didactic.analytics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.didactic.DifficultyLevel;
import de.regelsuche.didactic.HintGenerator;
import de.regelsuche.didactic.PedagogyProfile;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DidacticAnalyticsServiceTest {

    @Test
    void aggregatesStepChecksAndHints() {
        InMemoryDidacticEventStore store = new InMemoryDidacticEventStore();
        store.record(DidacticEvent.stepCheck(
            Instant.parse("2025-01-01T00:00:00Z"),
            DifficultyLevel.MITTELSTUFE, true, true, null));
        store.record(DidacticEvent.stepCheck(
            Instant.parse("2025-01-01T00:01:00Z"),
            DifficultyLevel.MITTELSTUFE, false, false, "false_cancellation_sum_in_numerator"));
        store.record(DidacticEvent.hint(
            Instant.parse("2025-01-01T00:02:00Z"),
            "p-1", PedagogyProfile.SCHOOL, HintGenerator.Strength.FULL_STEP));

        DidacticAnalyticsService.Snapshot snapshot =
            new DidacticAnalyticsService(store).snapshot();

        assertEquals(3, snapshot.totalEvents());
        assertEquals(2, snapshot.stepChecks());
        assertEquals(1, snapshot.hints());
        assertEquals(1, snapshot.correctSteps());
        assertEquals(1, snapshot.didacticallyAppropriateSteps());
        assertEquals(0.5, snapshot.accuracy(), 1e-9);
        assertEquals(0.5, snapshot.appropriateness(), 1e-9);
        assertEquals(1, snapshot.misconceptionFrequency()
            .get("false_cancellation_sum_in_numerator"));
        assertEquals(2, snapshot.stepChecksByDifficulty().get(DifficultyLevel.MITTELSTUFE));
        assertEquals(1, snapshot.hintsByStrength().get(HintGenerator.Strength.FULL_STEP));
        assertEquals(1, snapshot.hintsByProfile().get(PedagogyProfile.SCHOOL));
    }

    @Test
    void jsonFileStoreRoundtripsEventsAcrossInstances(@TempDir Path tmp) throws IOException {
        Path file = tmp.resolve("events.json");
        JsonFileDidacticEventStore first = new JsonFileDidacticEventStore(file);
        first.record(DidacticEvent.stepCheck(
            Instant.parse("2025-02-02T00:00:00Z"),
            DifficultyLevel.OBERSTUFE, true, true, null));
        first.record(DidacticEvent.hint(
            Instant.parse("2025-02-02T00:01:00Z"),
            "path-42", PedagogyProfile.EXAM_FRIENDLY, HintGenerator.Strength.STRONG));

        assertTrue(Files.size(file) > 0, "file must be persisted");

        JsonFileDidacticEventStore second = new JsonFileDidacticEventStore(file);
        assertEquals(2, second.events().size());
        DidacticEvent restored = second.events().get(1);
        assertEquals(DidacticEvent.Kind.HINT, restored.kind());
        assertEquals("path-42", restored.pathId().orElseThrow());
        assertEquals(PedagogyProfile.EXAM_FRIENDLY, restored.pedagogyProfile().orElseThrow());
        assertEquals(HintGenerator.Strength.STRONG, restored.hintStrength().orElseThrow());
    }

    @Test
    void snapshotIsEmptyWhenNoEvents() {
        DidacticAnalyticsService.Snapshot snapshot =
            new DidacticAnalyticsService(new InMemoryDidacticEventStore()).snapshot();
        assertEquals(0, snapshot.totalEvents());
        assertEquals(0.0, snapshot.accuracy(), 1e-9);
        assertEquals(0.0, snapshot.appropriateness(), 1e-9);
    }
}
