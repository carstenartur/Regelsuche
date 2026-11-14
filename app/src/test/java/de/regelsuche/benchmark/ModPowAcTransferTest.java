package de.regelsuche.benchmark;

import static org.junit.jupiter.api.Assertions.*;

import de.regelsuche.parse.ExpressionParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.GZIPInputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ModPowAcTransferTest {
    private static final Path RESEARCH = Path.of("..", "docs", "research");
    private static final Path V1 = RESEARCH.resolve("modpow-transfer-v1");
    private static final Path V2 = RESEARCH.resolve("modpow-transfer-ac-v2");

    @TempDir Path directory;

    @Test
    void reproducesTheFrozenModelAndAllEightCanonicalOutcomes() throws Exception {
        Path trained = directory.resolve("trained.json");
        ModPowFrozenTransfer.main(new String[]{"train", V1.resolve("train.json").toString(), trained.toString()});
        try (var frozen = new GZIPInputStream(Files.newInputStream(V1.resolve("model.json.gz")))) {
            assertArrayEquals(frozen.readAllBytes(), Files.readAllBytes(trained));
        }
        Path output = Path.of(Boolean.getBoolean("regelsuche.maven.reactor") ? "target" : "build",
            "discovery-artifacts", "modpow-transfer-ac-v2", "result.json");
        var result = ModPowAcTransferStudy.evaluate(V2.resolve("protocol.json"), trained, V2.resolve("test.json"));
        byte[] bytes = ModPowTransferFiles.JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(result);
        assertEquals("ff9682844a965fdaeefe7cb1bff8564739d5fea834534f03e721d0195d3c41a7", ModPowTransferFiles.hash(bytes));
        assertEquals(6, result.required("arithmeticAcTransfers").intValue());
        assertEquals(3, result.required("exactTransfers").intValue());
        assertTrue(result.required("negativeControlsClean").booleanValue());
        assertEquals(0, result.required("shuffledTrainWitnessCount").intValue());
        Files.createDirectories(output.getParent());
        Files.write(output, bytes);
    }

    @Test
    void rejectsADifferentFrozenModelBeforeOpeningTestInputs() throws Exception {
        Path changed = Files.writeString(directory.resolve("model.json"), "{}");
        var error = assertThrows(IllegalArgumentException.class, () ->
            ModPowAcTransferStudy.evaluate(V2.resolve("protocol.json"), changed, directory.resolve("absent-test")));
        assertEquals("frozen model checksum mismatch", error.getMessage());
    }

    @Test
    void neitherMissingPremisesNorAForgedFactorCanQualifyForTransfer() {
        var parser = new ExpressionParser();
        var input = parser.parseTerm("program(modpow(b,v*u,m),modpow(b,v,m))");
        var legitimate = parser.parseTerm("program(modpow(modpow(b,v,m),u,m),modpow(b,v,m))");
        var forged = parser.parseTerm("program(modpow(modpow(b,u,m),u,m),modpow(b,v,m))");
        var premises = List.of("b integer", "u integer", "u >= 0", "v integer", "v >= 0", "m integer", "m > 0");
        assertTrue(ModPowCompositionReplay.verify(input, legitimate, premises).isPresent());
        assertTrue(ModPowCompositionReplay.verify(input, legitimate, List.of()).isEmpty());
        assertTrue(ModPowCompositionReplay.verify(input, forged, premises).isEmpty());
    }
}
