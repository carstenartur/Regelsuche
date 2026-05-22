package de.regelsuche.demo;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Pins down the demo-gallery LaTeX contract: every main mathematical
 * expression in {@code docs/demo-gallery.md} must be rendered as a
 * GitHub-flavoured LaTeX math block ({@code $$ ... $$}) or inline math
 * ({@code $...$}). Inline-code/Unicode placeholders for main expressions
 * are no longer allowed; technical input stays in fenced code blocks.
 */
class DemoGalleryLatexTest {

    private static final Path REPO_ROOT = locateRepoRoot();
    private static String gallery;

    @BeforeAll
    static void loadGallery() throws IOException {
        Path md = REPO_ROOT.resolve("docs/demo-gallery.md");
        assertTrue(Files.exists(md), "docs/demo-gallery.md must exist");
        gallery = Files.readString(md, StandardCharsets.UTF_8);
    }

    @Test
    void demoGalleryUsesLatexMathBlocks() {
        // At least one display-math block ($$ ... $$) per major math demo.
        int blocks = countOccurrences(gallery, "$$");
        assertTrue(blocks >= 20,
            "Expected several LaTeX display math blocks ($$ ... $$), found " + blocks);

        // Main expressions must no longer appear as Unicode/inline-code placeholders.
        String outsideCode = stripFencedCodeBlocks(gallery);
        for (String forbidden : new String[] {
            "(x + 3)²", "(x+3)²", "x² + 6·x + 9", "x² + 3·x + 2", "3·x²",
            "(x · y) / (x · z)", "y / z", "-2·x < 4",
            "sin(x)² + cos(x)²", "(x + 1)·(x + 2)", "A·(B + C)", "A·B + A·C",
            "d/dx x³"
        }) {
            assertFalse(outsideCode.contains(forbidden),
                "Main math expression must not appear as Unicode/inline-code placeholder: " + forbidden);
        }
    }

    @Test
    void binomialDemoContainsAlignedLatexDerivation() {
        String section = sectionFor("Binomische Formel");
        assertTrue(section.contains("\\begin{aligned}"),
            "Binomial derivation must use \\begin{aligned}");
        assertTrue(section.contains("\\end{aligned}"),
            "Binomial derivation must close \\end{aligned}");
        assertTrue(section.contains("(x+3)^2"),
            "Binomial derivation must keep the LaTeX form (x+3)^2");
        assertTrue(section.contains("x^2 + 6x + 9"),
            "Binomial derivation must end at x^2 + 6x + 9 in LaTeX");
    }

    @Test
    void rationalDemoUsesFracAndNeq() {
        String section = sectionFor("Bruchkürzung");
        assertTrue(section.contains("\\frac"),
            "Rational demo must use \\frac for fractions");
        assertTrue(section.contains("\\neq"),
            "Rational demo must use \\neq for the x != 0 assumption");
    }

    @Test
    void inequalityDemoUsesLatexComparator() {
        String section = sectionFor("Ungleichung");
        assertTrue(section.contains("-2x < 4"),
            "Inequality demo must show -2x < 4 in LaTeX form");
        assertTrue(section.contains("x > -2"),
            "Inequality demo must show x > -2 in LaTeX form");
        // Must appear inside a $$ block.
        assertTrue(section.matches("(?s).*\\$\\$[^$]*-2x\\s*<\\s*4[^$]*\\$\\$.*"),
            "Inequality must be rendered inside a $$ ... $$ block");
    }

    @Test
    void derivativeDemoUsesDerivativeNotation() {
        String section = sectionFor("Ableitung");
        assertTrue(section.contains("\\frac{d}{dx}"),
            "Derivative demo must use \\frac{d}{dx} notation");
        assertTrue(section.contains("3x^2"),
            "Derivative demo must yield 3x^2 in LaTeX");
    }

    @Test
    void matrixDemoUsesBmatrix() {
        String section = sectionFor("Matrix-Distributivität");
        assertTrue(section.contains("\\begin{bmatrix}"),
            "Matrix demo must use \\begin{bmatrix}");
        assertTrue(section.contains("\\end{bmatrix}"),
            "Matrix demo must close \\end{bmatrix}");
        assertTrue(section.contains("A(B + C)") || section.contains("A(B+C)"),
            "Matrix demo must show A(B + C) in LaTeX form");
        assertTrue(section.contains("AB + AC"),
            "Matrix demo result must be AB + AC in LaTeX form");
    }

    private String sectionFor(String headingFragment) {
        Pattern p = Pattern.compile("(?m)^## .*" + Pattern.quote(headingFragment) + ".*$");
        Matcher m = p.matcher(gallery);
        assertTrue(m.find(), "Could not find section heading containing: " + headingFragment);
        int start = m.start();
        int end = gallery.indexOf("\n## ", m.end());
        if (end < 0) {
            end = gallery.length();
        }
        return gallery.substring(start, end);
    }

    private static String stripFencedCodeBlocks(String markdown) {
        return markdown.replaceAll("(?s)```.*?```", "");
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        int idx = 0;
        while ((idx = haystack.indexOf(needle, idx)) >= 0) {
            count++;
            idx += needle.length();
        }
        return count;
    }

    private static Path locateRepoRoot() {
        Path candidate = Paths.get(".").toAbsolutePath().normalize();
        for (int i = 0; i < 6; i++) {
            if (Files.exists(candidate.resolve("README.md"))
                && Files.exists(candidate.resolve("settings.gradle"))) {
                return candidate;
            }
            Path parent = candidate.getParent();
            if (parent == null) {
                break;
            }
            candidate = parent;
        }
        Path fallback = Paths.get(".").toAbsolutePath().normalize();
        assertNotNull(fallback);
        return fallback;
    }
}
