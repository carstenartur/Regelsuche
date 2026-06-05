package de.regelsuche.moves.report;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MoveTreeReportDeterminismTest {

    private List<MoveTreeReportAssembler.PathStep> steps() {
        return List.of(
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
                        "A^2 + 2*A + 1", "(A + 1)^2", "complete_square_bridge"));
    }

    @Test
    void repeatedGenerationProducesByteIdenticalArtefacts(@TempDir Path tempDir) throws Exception {
        Path first = Files.createDirectories(tempDir.resolve("run-1"));
        Path second = Files.createDirectories(tempDir.resolve("run-2"));

        MoveTreeReport reportOne = new MoveTreeReportAssembler()
                .assemble("determinism-scenario", steps(), List.of(), expression -> 0.0);
        MoveTreeReport reportTwo = new MoveTreeReportAssembler()
                .assemble("determinism-scenario", steps(), List.of(), expression -> 0.0);

        new MoveTreeReportWriter().write(first, reportOne);
        new MoveTreeReportWriter().write(second, reportTwo);

        assertEquals(
                Files.readString(first.resolve(MoveTreeReportWriter.JSON_FILE_NAME), StandardCharsets.UTF_8),
                Files.readString(second.resolve(MoveTreeReportWriter.JSON_FILE_NAME), StandardCharsets.UTF_8),
                "move-tree-report.json must be byte-identical across runs");
        assertEquals(
                Files.readString(first.resolve(MoveTreeReportWriter.MARKDOWN_FILE_NAME), StandardCharsets.UTF_8),
                Files.readString(second.resolve(MoveTreeReportWriter.MARKDOWN_FILE_NAME), StandardCharsets.UTF_8),
                "move-tree-report.md must be byte-identical across runs");
    }
}
