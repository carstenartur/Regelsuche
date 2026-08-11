package de.regelsuche.architecture;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class ProofDockerfileBuildContextTest {

    private static final Path REPOSITORY_ROOT = locateRepositoryRoot();

    @Test
    void proofDockerfileCopiesEveryGradleModule() throws IOException {
        String settings = Files.readString(
            REPOSITORY_ROOT.resolve("settings.gradle")
        );
        String dockerfile = Files.readString(
            REPOSITORY_ROOT.resolve("Dockerfile.proof")
        );

        for (String module : settingsGradleModules(settings)) {
            assertTrue(
                dockerfile.contains(
                    "COPY " + module + "/build.gradle ./"
                        + module + "/build.gradle"
                ),
                () -> "Dockerfile.proof must copy build.gradle for :" + module
            );
            assertTrue(
                dockerfile.contains("COPY " + module + " ./" + module),
                () -> "Dockerfile.proof must copy sources for :" + module
            );
        }
    }

    private static List<String> settingsGradleModules(String settings) {
        int includeStart = settings.indexOf("include(");
        int includeEnd = settings.indexOf(')', includeStart);
        assertTrue(
            includeStart >= 0 && includeEnd > includeStart,
            "settings.gradle must contain include(...)"
        );
        return settings.substring(includeStart, includeEnd).lines()
            .map(String::trim)
            .filter(line -> line.startsWith("'"))
            .map(line -> line.substring(1, line.indexOf("'", 1)))
            .toList();
    }

    private static Path locateRepositoryRoot() {
        Path current = Path.of("").toAbsolutePath().normalize();
        while (current != null) {
            if (Files.isRegularFile(current.resolve("settings.gradle"))
                    && Files.isRegularFile(
                        current.resolve("Dockerfile.proof")
                    )) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException(
            "Could not locate repository root from working directory"
        );
    }
}
