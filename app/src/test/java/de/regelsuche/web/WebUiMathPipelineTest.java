package de.regelsuche.web;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

/**
 * Static-file pin for the Stage 1 math-rendering pipeline in the web UI.
 *
 * <p>The Web-Workbench must route every mathematical expression through a
 * single {@code renderMath(root)} entry point and load <a
 * href="https://katex.org/">KaTeX</a> as the default typesetter (MathJax
 * remains loaded as a fallback). Previously the UI emitted
 * {@code <span class="latex">$...$</span>} placeholders that nothing ever
 * typeset; this test prevents that regression from sneaking back in.</p>
 */
class WebUiMathPipelineTest {

    private static Path locateAppJs() {
        Path[] candidates = {
            Path.of("src", "main", "resources", "web", "app.js"),
            Path.of("app", "src", "main", "resources", "web", "app.js")
        };
        for (Path c : candidates) {
            if (Files.isRegularFile(c)) {
                return c;
            }
        }
        return null;
    }

    @Test
    void appJsLoadsKatexAndDefinesRenderMath() throws IOException {
        Path appJs = locateAppJs();
        if (appJs == null) {
            return;
        }
        String content = Files.readString(appJs);
        assertTrue(content.contains("katex"),
            "app.js must load KaTeX from a CDN");
        assertTrue(content.contains("auto-render"),
            "app.js must load the KaTeX auto-render extension");
        assertTrue(content.contains("renderMath"),
            "app.js must expose a central renderMath() helper");
        assertTrue(content.contains("MathJax"),
            "MathJax fallback must still be wired up");
    }

    @Test
    void appJsNoLongerEmitsLegacyLatexPlaceholders() throws IOException {
        Path appJs = locateAppJs();
        if (appJs == null) {
            return;
        }
        String content = Files.readString(appJs);
        // The legacy emission style was <span class="latex">$...$</span> /
        // <div class="latex">$...$</div> that nothing ever typeset. All
        // such call sites must now emit <span class="math" data-math="..">.
        assertFalse(content.contains("class=\"latex\">$"),
            "Legacy <span class=\"latex\">$...$</span> placeholders are no longer allowed; "
                + "use class=\"math\" with a data-math attribute and call renderMath() on the container.");
        assertTrue(content.contains("class=\"math\""),
            "Math-bearing nodes must use the .math marker so renderMath() picks them up");
        assertTrue(content.contains("data-math"),
            "Math-bearing nodes must carry their raw LaTeX in data-math for the CDN-failure fallback");
    }
}
