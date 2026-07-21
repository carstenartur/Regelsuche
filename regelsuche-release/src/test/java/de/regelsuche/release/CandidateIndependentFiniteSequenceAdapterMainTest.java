package de.regelsuche.release;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CandidateIndependentFiniteSequenceAdapterMainTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path temporaryDirectory;

    @Test
    void twoCleanRunsAreByteIdenticalAndRetainPartialCoverage() throws Exception {
        Path first = temporaryDirectory.resolve("first/run.json");
        Path second = temporaryDirectory.resolve("second/run.json");

        run(first, profile());
        run(second, profile());

        assertArrayEquals(Files.readAllBytes(first), Files.readAllBytes(second));
        JsonNode result = JSON.readTree(first.toFile());
        assertEquals(4, result.path("executedCampaigns").asInt());
        assertEquals(24, result.path("executedEvaluations").asInt());
        assertTrue(result.path("confirmedFiniteDifferenceEvaluations").asInt() > 0);
        assertTrue(result.path("incompleteAdapterCoverageEvaluations").asInt() > 0);
        assertEquals("ADAPTER_REQUIRED",
            result.path("recurrenceAdapterStatus").asText());
        assertFalse(result.path("uniqueInfiniteContinuationClaimAuthorized")
            .asBoolean(true));
        assertFalse(result.path("publicationAuthorized").asBoolean(true));
    }

    @Test
    void heldOutCasesRemainProhibitedDuringFormation() throws Exception {
        Path output = temporaryDirectory.resolve("visibility/run.json");
        run(output, profile());

        JsonNode result = JSON.readTree(output.toFile());
        for (JsonNode campaign : result.path("campaigns")) {
            assertEquals("TRAIN_ONLY", campaign.path("formationVisibility").asText());
            assertEquals("EVALUATION_ONLY",
                campaign.path("heldOutInputAccess").asText());
            for (JsonNode formation : campaign.path("formationEvidence")) {
                assertFalse(formation.path("evaluationInputRead").asBoolean(true));
                assertFalse(formation.path("holdoutVisible").asBoolean(true));
            }
            for (JsonNode evaluation : campaign.path("evaluations")) {
                String split = evaluation.path("split").asText();
                assertEquals("TRAIN".equals(split) ? "ALLOWED" : "PROHIBITED",
                    evaluation.path("formationVisibility").asText());
                assertEquals("EVALUATION_ONLY",
                    evaluation.path("heldOutInputReadStage").asText());
            }
        }
    }

    @Test
    void manipulatedFormationProfileFailsClosed() throws Exception {
        String original = Files.readString(profile(), StandardCharsets.UTF_8);
        Path manipulated = temporaryDirectory.resolve("manipulated-profile.json");
        Files.writeString(manipulated,
            original.replace(
                "\"implementationStatus\": \"ADAPTER_REQUIRED\"",
                "\"implementationStatus\": \"AVAILABLE\""),
            StandardCharsets.UTF_8);

        IllegalArgumentException failure = assertThrows(
            IllegalArgumentException.class,
            () -> run(temporaryDirectory.resolve("rejected/run.json"), manipulated));
        assertTrue(failure.getMessage().contains("contentHash mismatch"));
    }

    private static void run(Path output, Path profile) throws Exception {
        CandidateIndependentFiniteSequenceAdapterMain.main(new String[] {
            "--corpus", corpus().toString(),
            "--profile", profile.toString(),
            "--freeze-receipt", freezeReceipt().toString(),
            "--output", output.toString(),
            "--repository-revision", "WORKTREE"
        });
    }

    private static Path corpus() {
        return repositoryRoot().resolve(
            "research/benchmarks/candidate-independent/case-corpus.json");
    }

    private static Path profile() {
        return repositoryRoot().resolve(
            "research/benchmarks/candidate-independent/finite-sequence-candidate-forms.json");
    }

    private static Path freezeReceipt() {
        return repositoryRoot().resolve(
            "research/benchmarks/candidate-independent/corpus-freeze-receipt.json");
    }

    private static Path repositoryRoot() {
        Path current = Path.of("").toAbsolutePath().normalize();
        if (Files.isRegularFile(current.resolve("settings.gradle"))) {
            return current;
        }
        Path parent = current.getParent();
        if (parent != null && Files.isRegularFile(parent.resolve("settings.gradle"))) {
            return parent;
        }
        throw new IllegalStateException(
            "Could not locate repository root from " + current);
    }
}
