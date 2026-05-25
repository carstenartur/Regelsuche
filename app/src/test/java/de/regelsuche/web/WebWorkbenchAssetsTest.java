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
 * {@code WebWorkbenchServer} to serve them via the bundled root-relative
 * asset paths.
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
        assertTrue(html.contains("style.css"));
        assertTrue(html.contains("app.js"));
        assertTrue(html.contains("vendor/katex/katex.min.css"));
        assertTrue(html.contains("vendor/katex/katex.min.js"));
        // Tab navigation backbone must be present so the UI is actually a SPA.
        assertTrue(html.contains("data-tab=\"workbench\""));
        assertTrue(html.contains("data-tab=\"candidates\""));
        assertTrue(html.contains("data-tab=\"inventory\""));
        // Visual-search-graph tabs (Step 6).
        assertTrue(html.contains("data-tab=\"identities\""));
        assertTrue(html.contains("data-tab=\"dashboard\""));
        assertTrue(html.contains("data-tab=\"replay\""));
        // New domain pickers introduced in the workbench overhaul.
        assertTrue(html.contains("trigonometric"));
        assertTrue(html.contains("logarithmic"));
        assertTrue(html.contains("radical"));

        String css = resourceAsString("/web/style.css");
        assertTrue(css.contains(".tab"));
        assertTrue(css.contains(".assumption"));
        assertTrue(css.contains(".dashboard-grid"));
        assertTrue(css.contains(".identity-card"));
        assertTrue(css.contains(".replay-canvas"));
        assertTrue(css.contains(".replay-macro-card"));

        String app = resourceAsString("/web/app.js");
        assertTrue(app.contains("/api/search"));
        assertTrue(app.contains("/api/inventory"));
        assertTrue(app.contains("/api/candidates"));
        assertTrue(app.contains("/api/paths"));
        assertTrue(app.contains("loadInventory"));
        // Visual-search-graph endpoints wired into the UI.
        assertTrue(app.contains("/api/identities"));
        assertTrue(app.contains("/api/search-graph"));
        assertTrue(app.contains("replay"));
        assertTrue(app.contains("macroMoveExpansion"));
    }
}
