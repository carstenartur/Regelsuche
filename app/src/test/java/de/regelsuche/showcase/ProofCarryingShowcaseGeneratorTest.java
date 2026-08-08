package de.regelsuche.showcase;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.regelsuche.equivalence.AssumptionAwareEquivalenceService.Status;
import de.regelsuche.math.algorithms.equivalence.RationalFunctionNormalFormEquivalencePortAdapter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProofCarryingShowcaseGeneratorTest {
    private static final Duration TIMEOUT = Duration.ofSeconds(60);
    private static final ObjectMapper JSON = new ObjectMapper()
        .findAndRegisterModules();

    @TempDir
    Path tempDir;

    @Test
    void generatesOneDeterministicBalancedAndExactlyValidFixture()
            throws Exception {
        Path repositoryRoot = repositoryRoot();
        Path output = tempDir.resolve("generated-final-test.json");
        Process process = startGenerator(repositoryRoot, output);
        assertTrue(
            process.waitFor(TIMEOUT.toSeconds(), TimeUnit.SECONDS),
            () -> {
                process.destroyForcibly();
                return "showcase case generator exceeded " + TIMEOUT;
            });
        String processOutput = new String(
            process.getInputStream().readAllBytes(),
            StandardCharsets.UTF_8);

        assertEquals(0, process.exitValue(), processOutput);
        assertTrue(processOutput.contains("generatorDeterminism=PASS"),
            processOutput);
        assertTrue(
            processOutput.contains("seedSubstitutionSensitivity=PASS"),
            processOutput);
        assertTrue(
            processOutput.contains(
                "generatedSurfaceStatus="
                    + "FINAL_TEST_GENERATED_NOT_EXECUTED"),
            processOutput);

        JsonNode generated = JSON.readTree(output.toFile());
        assertEquals(
            "regelsuche.proof-carrying-showcase-generated-final-test/v1",
            generated.path("schema").asText());
        assertEquals("FINAL_TEST_GENERATED_NOT_EXECUTED",
            generated.path("status").asText());
        assertEquals(24, generated.path("caseCount").asInt());
        assertEquals(24, generated.path("cases").size());
        assertEquals(3, generated.path("familySummaries").size());
        generated.path("familySummaries").forEach(summary -> {
            assertEquals(8, summary.path("caseCount").asInt());
            assertEquals(List.of(3, 4, 5, 6),
                StreamSupport.stream(
                    summary.path("difficultyLevels").spliterator(), false)
                    .map(JsonNode::asInt)
                    .toList());
        });

        Set<String> caseIds = new HashSet<>();
        Set<String> caseHashes = new HashSet<>();
        Set<String> structuralFingerprints = new HashSet<>();
        RationalFunctionNormalFormEquivalencePortAdapter evaluator =
            new RationalFunctionNormalFormEquivalencePortAdapter();
        for (JsonNode showcaseCase : generated.path("cases")) {
            String caseId = showcaseCase.path("caseId").asText();
            assertTrue(caseIds.add(caseId), "duplicate case ID " + caseId);
            assertTrue(
                caseHashes.add(showcaseCase.path("contentHash").asText()),
                "duplicate case content hash " + caseId);
            assertTrue(
                structuralFingerprints.add(
                    showcaseCase.path("structuralFingerprint").asText()),
                "duplicate structural fingerprint " + caseId);
            List<String> assumptions = StreamSupport.stream(
                showcaseCase.path("assumptions").spliterator(), false)
                .map(JsonNode::asText)
                .toList();
            var evaluation = evaluator.evaluate(
                showcaseCase.path("inputExpression").asText(),
                showcaseCase.path("targetExpression").asText(),
                assumptions);
            assertEquals(Status.CONFIRMED, evaluation.status(),
                () -> caseId + ": " + evaluation.detail());
            assertTrue(evaluation.missingAssumptions().isEmpty(),
                () -> caseId + ": " + evaluation.missingAssumptions());
            assertTrue(evaluation.unsupportedAssumptions().isEmpty(),
                () -> caseId + ": " + evaluation.unsupportedAssumptions());
        }
    }

    private static Process startGenerator(Path repositoryRoot, Path output)
            throws IOException {
        List<String> candidates = System.getProperty("os.name")
            .toLowerCase(Locale.ROOT).contains("windows")
                ? List.of("python", "python3")
                : List.of("python3", "python");
        IOException failure = null;
        for (String executable : candidates) {
            try {
                ProcessBuilder builder = new ProcessBuilder(
                    executable,
                    "scripts/generate-proof-carrying-showcase-final-test.py",
                    "--plan",
                    "research/showcase/proof-carrying-self-improvement/"
                        + "showcase-plan.json",
                    "--self-test",
                    "--output",
                    output.toAbsolutePath().toString());
                builder.directory(repositoryRoot.toFile());
                builder.redirectErrorStream(true);
                builder.environment().put("PYTHONDONTWRITEBYTECODE", "1");
                return builder.start();
            } catch (IOException exception) {
                failure = exception;
            }
        }
        throw new IOException(
            "no Python interpreter could execute the showcase generator",
            failure);
    }

    private static Path repositoryRoot() {
        Path current = Path.of("").toAbsolutePath().normalize();
        while (current != null) {
            if (Files.isRegularFile(current.resolve("settings.gradle"))
                    && Files.isRegularFile(current.resolve("gradlew"))) {
                return current;
            }
            current = current.getParent();
        }
        fail("unable to locate repository root from "
            + Path.of("").toAbsolutePath());
        throw new IllegalStateException("unreachable");
    }
}
