package de.regelsuche.didactic.analytics;

import static org.junit.jupiter.api.Assertions.assertEquals;

import de.regelsuche.didactic.DifficultyLevel;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class InMemoryDidacticEventStoreTest {

    @Test
    void boundedStoreKeepsMostRecentEvents() {
        InMemoryDidacticEventStore store = new InMemoryDidacticEventStore(2);
        store.record(DidacticEvent.stepCheck(Instant.ofEpochSecond(1),
            DifficultyLevel.MITTELSTUFE, true, true, null));
        store.record(DidacticEvent.stepCheck(Instant.ofEpochSecond(2),
            DifficultyLevel.MITTELSTUFE, true, true, null));
        store.record(DidacticEvent.stepCheck(Instant.ofEpochSecond(3),
            DifficultyLevel.MITTELSTUFE, true, true, null));

        assertEquals(2, store.events().size());
        assertEquals(Instant.ofEpochSecond(2), store.events().get(0).timestamp());
        assertEquals(Instant.ofEpochSecond(3), store.events().get(1).timestamp());
    }
}
