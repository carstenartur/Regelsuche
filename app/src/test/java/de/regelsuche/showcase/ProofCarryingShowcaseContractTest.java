package de.regelsuche.showcase;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class ProofCarryingShowcaseContractTest {
    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    @Test
    void checkoutOwnedVerifierRejectsDriftAndPreservesThePreExecutionBoundary()
            throws Exception {
        Path repositoryRoot = repositoryRoot();
        Process process = startVerifier(repositoryRoot);
        assertTrue(
            process.waitFor(TIMEOUT.toSeconds(), TimeUnit.SECONDS),
            () -> {
                process.destroyForcibly();
                return "showcase contract verifier exceeded " + TIMEOUT;
            });
        String output = new String(
            process.getInputStream().readAllBytes(),
            StandardCharsets.UTF_8);

        assertEquals(0, process.exitValue(), output);
        assertTrue(output.contains("seedDerivationSelfTest=PASS"), output);
        assertTrue(output.contains("orderingTamperRejection=PASS"), output);
        assertTrue(
            output.contains("randomnessSubstitutionSensitivity=PASS"),
            output);
        assertTrue(
            output.contains(
                "showcaseContractStatus=CONTRACT_FROZEN_NOT_RUN"),
            output);
        assertTrue(
            output.contains(
                "publicationGradeFlagship="
                    + "DEFERRED_PENDING_INDEPENDENT_REVIEW"),
            output);
    }

    private static Process startVerifier(Path repositoryRoot)
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
                    "scripts/verify-proof-carrying-showcase-contract.py",
                    "research/showcase/proof-carrying-self-improvement/"
                        + "showcase-plan.json");
                builder.directory(repositoryRoot.toFile());
                builder.redirectErrorStream(true);
                builder.environment().put("PYTHONDONTWRITEBYTECODE", "1");
                return builder.start();
            } catch (IOException exception) {
                failure = exception;
            }
        }
        throw new IOException(
            "no Python interpreter could execute the showcase verifier",
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
