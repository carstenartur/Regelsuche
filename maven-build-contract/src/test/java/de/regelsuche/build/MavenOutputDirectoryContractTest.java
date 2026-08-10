package de.regelsuche.build;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class MavenOutputDirectoryContractTest {

    @Test
    void allMavenTargetDirectoriesAreIgnoredAsReproducibleBuildProducts()
            throws IOException {
        Path root = repositoryRoot();
        List<String> activePatterns = Files.readAllLines(root.resolve(".gitignore"))
            .stream()
            .map(String::trim)
            .filter(line -> !line.isEmpty())
            .filter(line -> !line.startsWith("#"))
            .toList();

        assertTrue(
            activePatterns.contains("**/target/"),
            "Maven target directories must not dirty the checkout before "
                + "content-addressed evidence is frozen"
        );
    }

    private static Path repositoryRoot() {
        String configured = System.getProperty("regelsuche.repositoryRoot");
        assertNotNull(
            configured,
            "Maven must expose maven.multiModuleProjectDirectory to tests"
        );
        return Path.of(configured).toAbsolutePath().normalize();
    }
}
