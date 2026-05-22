package de.regelsuche.demo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Verifies the four didactic demo-gallery entries shipped in PR 17 are
 * registered with {@code domain="didactic"} so the workbench UI can
 * route them to the Didaktik panel.
 */
class DidacticDemosTest {

    private static final List<String> EXPECTED = List.of(
        "didaktik-multipath",
        "didaktik-typischer-fehler",
        "didaktik-hinweis",
        "didaktik-schrittpruefung");

    @Test
    void allDidacticDemosAreRegistered() {
        for (String id : EXPECTED) {
            DemoCatalog.Demo demo = DemoCatalog.byId(id);
            assertNotNull(demo, () -> "missing didactic demo: " + id);
            assertEquals("didactic", demo.domain(),
                () -> id + " must be tagged with domain=didactic");
            assertTrue(demo.title() != null && !demo.title().isBlank(),
                () -> id + " requires a title");
            assertTrue(demo.expression() != null && !demo.expression().isBlank(),
                () -> id + " requires an expression");
        }
    }
}
