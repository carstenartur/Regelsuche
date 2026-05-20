package de.regelsuche.web;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/**
 * Verifies that the embedded web workbench assets are present on the
 * classpath in the expected layout. The asset paths must stay stable for
 * {@code WebWorkbenchServer} to serve them via {@code /static/*}.
 */
class WebWorkbenchAssetsTest {

    private String resourceAsString(String path) throws IOException {
        try (InputStream stream = getClass().getResourceAsStream(path)) {
            assertNotNull(stream, "missing classpath resource: " + path);
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void webUiServesWorkbenchAssets() throws IOException {
        String html = resourceAsString("/web/index.html");
        assertTrue(html.contains("<title>Regelsuche Workbench</title>"));
        // Must reference the JS/CSS bundle and the static prefix used by the server.
        assertTrue(html.contains("/static/style.css"));
        assertTrue(html.contains("/static/app.js"));
        // Tab navigation backbone must be present so the UI is actually a SPA.
        assertTrue(html.contains("data-tab=\"workbench\""));
        assertTrue(html.contains("data-tab=\"candidates\""));
        assertTrue(html.contains("data-tab=\"inventory\""));
        // New domain pickers introduced in the workbench overhaul.
        assertTrue(html.contains("trigonometric"));
        assertTrue(html.contains("logarithmic"));
        assertTrue(html.contains("radical"));

        String css = resourceAsString("/web/style.css");
        assertTrue(css.contains(".tab"));
        assertTrue(css.contains(".assumption"));

        String app = resourceAsString("/web/app.js");
        assertTrue(app.contains("/api/search"));
        assertTrue(app.contains("/api/inventory"));
        assertTrue(app.contains("/api/candidates"));
        assertTrue(app.contains("/api/paths"));
        assertTrue(app.contains("loadInventory"));
    }
}
