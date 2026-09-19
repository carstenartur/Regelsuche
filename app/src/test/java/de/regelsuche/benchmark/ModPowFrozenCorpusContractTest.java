package de.regelsuche.benchmark;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Consumer;
import java.util.zip.GZIPInputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The public v1 command is an immutable experiment, not a caller-selected benchmark. */
class ModPowFrozenCorpusContractTest {
    private static final String MODEL_SHA = "e027a7d4b2b03b5b826eb6c5c7513c7f2241be32667e3813226efa77205f2068";
    @TempDir Path temporary;

    @Test
    void droppingTheKnownFailureCannotTurnTheOfficialVerdictGreen() throws IOException {
        rejectsChangedTest(root -> root.withArray("cases").remove(3));
    }

    @Test
    void omittingTheNegativeControlCannotClaimCleanControls() throws IOException {
        rejectsChangedTest(root -> root.withArray("cases").remove(4));
    }

    @Test
    void relabelingAPositiveCaseIsNotTheFrozenExperiment() throws IOException {
        rejectsChangedTest(root -> ((ObjectNode) root.withArray("cases").get(3)).put("positive", false));
    }

    @Test
    void changingTheDeclaredWorkProfileIsNotTheFrozenExperiment() throws IOException {
        rejectsChangedTest(root -> ((ObjectNode) root.withArray("cases").get(0)).put("qBits", 17));
    }

    @Test
    void changingTheSourceWhileKeepingTheCaseInventoryIsRejected() throws IOException {
        rejectsChangedTest(root -> ((ObjectNode) root.withArray("cases").get(0))
            .put("source", "program(modpow(b,t*s,m),modpow(b,t,m))"));
    }

    @Test
    void changedTrainingBytesCannotCreateAnOfficialV1Model() throws IOException {
        Path training = temporary.resolve("changed-training.json");
        var root = (ObjectNode) ModPowTransferFiles.JSON.readTree(Files.readAllBytes(corpus("train.json")));
        ((ObjectNode) root.withArray("cases").get(0)).put("qBits", 6);
        Files.write(training, ModPowTransferFiles.JSON.writeValueAsBytes(root));
        Path output = temporary.resolve("model.json");
        var error = assertThrows(IllegalArgumentException.class,
            () -> ModPowFrozenTransfer.main(new String[]{"train", training.toString(), output.toString()}));
        assertTrue(error.getMessage().contains("TRAIN corpus checksum mismatch"));
        assertFalse(Files.exists(output));
    }

    @Test
    void canonicalCopiesReproduceTheFrozenModelAndYellowResultExactly() throws IOException {
        Path training = Files.copy(corpus("train.json"), temporary.resolve("training-copy.json"));
        Path testing = Files.copy(corpus("test.json"), temporary.resolve("testing-copy.json"));
        Path model = temporary.resolve("fresh-model.json");
        Path result = temporary.resolve("fresh-result.json");
        ModPowFrozenTransfer.main(new String[]{"train", training.toString(), model.toString()});
        assertArrayEquals(archived("model.json.gz"), Files.readAllBytes(model));
        ModPowFrozenTransfer.main(new String[]{"test", model.toString(), MODEL_SHA,
            testing.toString(), result.toString()});
        assertArrayEquals(archived("result.json.gz"), Files.readAllBytes(result));
        assertEquals("YELLOW", ModPowTransferFiles.JSON.readTree(Files.readAllBytes(result)).path("verdict").asText());
    }

    @Test
    void modelChecksumStillPrecedesAnyTestCorpusAccess() throws IOException {
        Path model = writeModel();
        Path absentTest = temporary.resolve("does-not-exist.json");
        Path result = temporary.resolve("no-result.json");
        var error = assertThrows(IllegalArgumentException.class,
            () -> ModPowFrozenTransfer.main(new String[]{"test", model.toString(), "0".repeat(64),
                absentTest.toString(), result.toString()}));
        assertTrue(error.getMessage().contains("frozen model checksum mismatch"));
        assertFalse(Files.exists(result));
    }

    private void rejectsChangedTest(Consumer<ObjectNode> mutation) throws IOException {
        var root = (ObjectNode) ModPowTransferFiles.JSON.readTree(Files.readAllBytes(corpus("test.json")));
        mutation.accept(root);
        Path modified = temporary.resolve("changed-test.json");
        Files.write(modified, ModPowTransferFiles.JSON.writeValueAsBytes(root));
        Path model = writeModel();
        Path result = temporary.resolve("must-not-exist.json");
        var error = assertThrows(IllegalArgumentException.class,
            () -> ModPowFrozenTransfer.main(new String[]{"test", model.toString(), MODEL_SHA,
                modified.toString(), result.toString()}));
        assertTrue(error.getMessage().contains("TEST corpus checksum mismatch"));
        assertFalse(Files.exists(result));
    }

    private Path writeModel() throws IOException {
        return Files.write(temporary.resolve("frozen-model.json"), archived("model.json.gz"));
    }

    private static byte[] archived(String name) throws IOException {
        try (var input = new GZIPInputStream(Files.newInputStream(corpus(name)))) {
            return input.readAllBytes();
        }
    }

    private static Path corpus(String name) {
        Path current = Path.of("").toAbsolutePath();
        for (Path root : new Path[]{current, current.getParent()}) {
            if (root == null) continue;
            Path candidate = root.resolve("docs/research/modpow-transfer-v1").resolve(name);
            if (Files.isRegularFile(candidate)) return candidate;
        }
        throw new IllegalStateException("canonical v1 corpus not found: " + name);
    }
}
