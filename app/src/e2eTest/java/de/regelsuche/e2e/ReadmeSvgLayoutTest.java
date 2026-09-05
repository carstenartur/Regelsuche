package de.regelsuche.e2e;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Browser geometry checks, not a platform-sensitive pixel-baseline comparison. */
class ReadmeSvgLayoutTest {
    private static final List<String> FIGURES = List.of(
        "docs/assets/ast-rule-radar.svg",
        "docs/generated/autonomous-discovery-walkthrough/representative-search.svg");

    // This deliberately checks text/text intersections, viewport clipping and later-painted
    // opaque rectangles/circles. It is not a general SVG path-intersection algorithm.
    private static final String INSPECT = """
        () => {
          const svg = document.querySelector('svg');
          if (!svg) return ['missing-svg'];
          const texts = [...svg.querySelectorAll('text')];
          if (!texts.length) return ['missing-text'];
          const errors = [];
          if (!svg.querySelector('title') || !svg.querySelector('desc') ||
              svg.getAttribute('role') !== 'img') errors.push('missing-accessible-description');
          const bounds = svg.getBoundingClientRect();
          const overlap = (a, b) => a.x < b.x + b.width - 0.5 && b.x < a.x + a.width - 0.5 &&
              a.y < b.y + b.height - 0.5 && b.y < a.y + a.height - 0.5;
          for (let i = 0; i < texts.length; i++) {
            const a = texts[i].getBoundingClientRect();
            if (a.x < bounds.x - 0.5 || a.y < bounds.y - 0.5 ||
                a.right > bounds.right + 0.5 || a.bottom > bounds.bottom + 0.5)
              errors.push('clipped: ' + texts[i].textContent);
            for (let j = i + 1; j < texts.length; j++)
              if (overlap(a, texts[j].getBoundingClientRect()))
                errors.push('overlap: ' + texts[i].textContent + ' / ' + texts[j].textContent);
          }
          const elements = [...svg.querySelectorAll('text,rect,circle')];
          for (let i = 0; i < elements.length; i++) {
            if (elements[i].tagName !== 'text') continue;
            for (let j = i + 1; j < elements.length; j++) {
              const shape = elements[j];
              if (shape.tagName === 'text' || getComputedStyle(shape).fill === 'none') continue;
              if (overlap(elements[i].getBoundingClientRect(), shape.getBoundingClientRect()))
                errors.push('covered: ' + elements[i].textContent);
            }
          }
          return errors;
        }
        """;

    @Test
    void readmeFiguresRemainReadableAtNativeAndReadmeWidths() throws Exception {
        Path root = repositoryRoot();
        Path reports = root.resolve("app/build/reports/readme-svg-layout");
        Files.createDirectories(reports);
        try (Playwright playwright = Playwright.create();
                Browser browser = playwright.chromium().launch(
                    new BrowserType.LaunchOptions().setHeadless(true))) {
            Page page = browser.newPage();
            for (String figure : FIGURES) {
                for (int width : List.of(1000, 720)) {
                    page.setViewportSize(width, 1200);
                    page.setContent("<html><head><style>body{margin:0}"
                        + "svg{max-width:100%;height:auto}</style></head><body>"
                        + Files.readString(root.resolve(figure)) + "</body></html>");
                    page.evaluate("() => document.fonts.ready.then(() => true)");
                    String name = Path.of(figure).getFileName().toString().replace(".svg", "");
                    page.locator("svg").screenshot(new Locator.ScreenshotOptions()
                        .setPath(reports.resolve(name + "-" + width + ".png")));
                    assertEquals(List.of(), page.evaluate(INSPECT), figure + " at " + width + "px");
                }
            }
        }
    }

    @Test
    void detectorRejectsOverlappingClippedAndCoveredText() {
        try (Playwright playwright = Playwright.create();
                Browser browser = playwright.chromium().launch(
                    new BrowserType.LaunchOptions().setHeadless(true))) {
            Page page = browser.newPage();
            page.setContent("""
                <svg xmlns="http://www.w3.org/2000/svg" width="200" height="100"
                     role="img" aria-labelledby="title desc">
                  <title id="title">Negative fixture</title><desc id="desc">Deliberate defects</desc>
                  <text x="10" y="30">first</text><text x="10" y="30">second</text>
                  <rect x="5" y="5" width="190" height="50" fill="white"/>
                  <text x="195" y="90">outside the viewport</text>
                </svg>
                """);
            List<?> errors = (List<?>) page.evaluate(INSPECT);
            for (String category : List.of("overlap:", "clipped:", "covered:")) {
                assertTrue(errors.stream().anyMatch(error -> error.toString().startsWith(category)),
                    "The detector must reject " + category);
            }
        }
    }

    private static Path repositoryRoot() {
        Path candidate = Path.of("").toAbsolutePath().normalize();
        for (int index = 0; index < 6 && candidate != null; index++) {
            if (Files.isRegularFile(candidate.resolve("settings.gradle"))
                    && Files.isRegularFile(candidate.resolve("README.md"))) {
                return candidate;
            }
            candidate = candidate.getParent();
        }
        throw new IllegalStateException("Could not locate the repository root");
    }
}
