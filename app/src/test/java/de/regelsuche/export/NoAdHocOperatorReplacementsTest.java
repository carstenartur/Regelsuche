package de.regelsuche.export;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * Architectural guard: only {@link AstLatexRenderer}, {@link MatrixLatexRenderer}
 * and {@link MathPresentation} are allowed to perform raw string-level
 * substitutions that turn ASCII operators into LaTeX (or Unicode) math
 * glyphs. Any other production source file that contains a
 * {@code replace("*", " \cdot ")} (or similarly hand-rolled
 * <code>"·"</code> / <code>"\\cdot"</code> replacement) leaks math
 * presentation outside the central pipeline and must instead delegate to
 * {@link MathPresentation}.
 */
class NoAdHocOperatorReplacementsTest {

    private static final Set<String> ALLOWLIST = Set.of(
        "AstLatexRenderer.java",
        "MatrixLatexRenderer.java",
        "MathPresentation.java"
    );

    // Hand-rolled "*" -> "·" / "\cdot" replacements. The Lean / SMT bridges
    // intentionally rewrite "*" to " * " for prover syntax and are not
    // affected by this guard.
    private static final Pattern AD_HOC = Pattern.compile(
        "\\.replace\\s*\\(\\s*\"\\*\"\\s*,\\s*\"[^\"]*(?:·|\\\\\\\\cdot)[^\"]*\""
    );

    @Test
    void noProductionSourceLeaksMathPresentation() throws IOException {
        Path root = Path.of("src", "main", "java", "de", "regelsuche");
        if (!Files.isDirectory(root)) {
            root = Path.of("app", "src", "main", "java", "de", "regelsuche");
        }
        if (!Files.isDirectory(root)) {
            // Run from neither repo root nor app/ — skip rather than fail.
            return;
        }
        List<String> offenders = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(root)) {
            stream.filter(p -> p.toString().endsWith(".java"))
                .filter(p -> !ALLOWLIST.contains(p.getFileName().toString()))
                .forEach(p -> {
                    try {
                        String content = Files.readString(p);
                        if (AD_HOC.matcher(content).find()) {
                            offenders.add(p.toString());
                        }
                    } catch (IOException e) {
                        fail("Could not read " + p + ": " + e.getMessage());
                    }
                });
        }
        assertEquals(List.of(), offenders,
            "These files perform ad-hoc operator-to-LaTeX replacements. "
                + "Route them through MathPresentation.DEFAULT.latex(...) instead.");
    }
}
