package de.regelsuche.cli;

import de.regelsuche.export.DefaultTransformationExportService;
import de.regelsuche.graph.InMemoryExpressionGraphStore;
import de.regelsuche.inventory.InMemoryRuleInventoryRepository;
import de.regelsuche.math.algorithms.linalg.MatrixPreparation;
import de.regelsuche.math.algorithms.linalg.MatrixPreparationJson;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class MatrixRepresentationCliTest {
    @Test void analyzeAndReplayUseTheSameVersionedContract(@TempDir Path directory) throws Exception {
        var request = MatrixPreparation.Request.scalar("x+(2*x+y)=4; y+(x+3*y)=5", List.of("x", "y"),
            MatrixPreparation.Profile.SAFE_PREPARED_REPRESENTATION_V1, MatrixPreparation.DEFAULT_WORK);
        Path input = directory.resolve("request.json");
        Files.writeString(input, MatrixPreparationJson.requestJson(request));
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        var router = new CliRouter(new PrintStream(output), new InMemoryExpressionGraphStore(),
            new InMemoryRuleInventoryRepository(), new DefaultTransformationExportService(), false);
        assertTrue(CliRouter.isSubcommand("representations"));
        assertEquals(0, router.run(new String[]{"representations", "analyze", input.toString()}));
        String artifact = output.toString(java.nio.charset.StandardCharsets.UTF_8);
        assertEquals(MatrixPreparationJson.toJson(new MatrixPreparation().analyze(request)), artifact);
        Path retained = directory.resolve("artifact.json"); Files.writeString(retained, artifact);
        output.reset();
        assertEquals(0, router.run(new String[]{"representations", "replay", retained.toString()}));
        assertEquals(artifact, output.toString(java.nio.charset.StandardCharsets.UTF_8));
        Files.writeString(retained, artifact.replace("SOURCE_IDENTITY_PLUS_OPERATOR", "FORGED"));
        assertEquals(1, router.run(new String[]{"representations", "replay", retained.toString()}));
    }
}
