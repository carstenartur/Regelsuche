package de.regelsuche.web;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import org.junit.jupiter.api.Test;

/** Verifies the Cytoscape revision actually packaged by both build systems. */
class CytoscapeWebAssetContractTest {
    private static final String RESOURCE =
        "web/vendor/cytoscape/cytoscape.min.js";
    private static final String EXPECTED_VERSION_ASSIGNMENT =
        "version=\"3.34.1\"";

    @Test
    void packagedAssetMatchesTheReviewedCytoscapeRevision()
            throws IOException {
        try (InputStream input = getClass().getClassLoader()
                .getResourceAsStream(RESOURCE)) {
            assertNotNull(input, () ->
                "missing packaged classpath resource " + RESOURCE);
            String javascript = new String(input.readAllBytes(), UTF_8);
            assertTrue(javascript.length() > 100_000, () ->
                RESOURCE + " is unexpectedly small: "
                    + javascript.length() + " characters");
            assertTrue(javascript.contains(EXPECTED_VERSION_ASSIGNMENT), () ->
                RESOURCE + " does not expose the reviewed Cytoscape 3.34.1 "
                    + "runtime revision");
        }
    }
}
