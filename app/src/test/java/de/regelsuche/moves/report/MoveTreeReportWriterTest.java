package de.regelsuche.moves.report;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.moves.RewriteMoveKind;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MoveTreeReportWriterTest {

    @Test
    void writesJsonAndMarkdownArtefacts(@TempDir Path tempDir) throws Exception {
        MoveTreeReportAssembler assembler = new MoveTreeReportAssembler();
        MoveTreeReport report = assembler.assemble(
                "demo-scenario",
                List.of(
                        new MoveTreeReportAssembler.PathStep(
                                "sin(x)^2 + 2*sin(x) + 1",
                                "A^2 + 2*A + 1",
                                "substitution_introduction",
                                "",
                                List.of(
                                        "substitution.placeholder.A=sin(x)",
                                        "substitution.occurrences.A=2",
                                        "substitution.substituted=A^2 + 2*A + 1"),
                                ""),
                        new MoveTreeReportAssembler.PathStep(
                                "A^2 + 2*A + 1", "(A + 1)^2", "complete_square_bridge")),
                List.of(),
                expression -> 0.0);

        new MoveTreeReportWriter().write(tempDir, report);
        Path json = tempDir.resolve(MoveTreeReportWriter.JSON_FILE_NAME);
        Path markdown = tempDir.resolve(MoveTreeReportWriter.MARKDOWN_FILE_NAME);
        assertTrue(Files.exists(json));
        assertTrue(Files.exists(markdown));

        String jsonContent = Files.readString(json, StandardCharsets.UTF_8);
        assertTrue(jsonContent.contains("rewriteMove"), jsonContent);
        assertTrue(jsonContent.contains("SUBSTITUTE_INTRODUCE"));

        String markdownContent = Files.readString(markdown, StandardCharsets.UTF_8);
        assertTrue(markdownContent.contains("Applied moves on the successful path"));
        assertTrue(markdownContent.contains("First depth-1 candidate moves"));
        assertTrue(markdownContent.contains("Macro moves"));
        assertTrue(markdownContent.contains("Unresolved parameters"));
    }

    @Test
    void substitutionMoveCarriesResolvedParametersAndCompleteSquareIsCounted() {
        MoveTreeReport substitutionReport = new MoveTreeReportAssembler().assemble(
                "substitution-scenario",
                List.of(new MoveTreeReportAssembler.PathStep(
                        "sin(x)^2 + 2*sin(x) + 1",
                        "A^2 + 2*A + 1",
                        "substitution_introduction",
                        "",
                        List.of(
                                "substitution.placeholder.A=sin(x)",
                                "substitution.occurrences.A=2"),
                        "")),
                List.of(),
                null);
        assertFalse(substitutionReport.successfulPathMoves().isEmpty());
        assertTrue(substitutionReport.successfulPathMoves().getFirst().parameters().stream()
                .anyMatch(parameter -> parameter.value().equals("sin(x)")));

        // Depth-1 enumeration of a quadratic start expression yields a complete-square candidate.
        MoveTreeReport quadraticReport = new MoveTreeReportAssembler().assemble(
                "quadratic-scenario",
                List.of(new MoveTreeReportAssembler.PathStep(
                        "x^2 + 6*x + 5", "(x + 3)^2 - 4", "complete_square_bridge")),
                List.of(),
                null);
        assertTrue(quadraticReport.depth1Candidates().stream()
                .anyMatch(candidate -> candidate.kind() == RewriteMoveKind.COMPLETE_SQUARE));
    }
}
