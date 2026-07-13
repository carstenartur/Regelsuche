package de.regelsuche.search.telemetry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import org.junit.jupiter.api.Test;

class SearchEventTest {

    @Test
    void rewriteKindCanBeNullForNonTransformationEvents() {
        SearchEvent event = new SearchEvent(
            0L,
            SearchEventType.SEARCH_STARTED,
            "x",
            "hash",
            0,
            0,
            "",
            null,
            "",
            null,
            List.of(),
            1,
            0,
            0,
            ""
        );

        assertNull(event.rewriteKind());
        assertEquals("", event.parentExpression());
    }
}
