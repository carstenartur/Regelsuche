package de.regelsuche.build;

import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MavenContainerImagePathContractTest {
    private static final Set<String> IGNORED_DIRECTORY_NAMES = Set.of(
        ".git",
        ".gradle",
        "build",
        "node_modules",
        "out",
        "target"
    );

    @Test
    void repositoryDockerfilePathsAreRegularAndNonSymbolic()
            throws IOException {
        verifyDockerfilePaths(repositoryRoot());
    }

    @Test
    void symbolicDockerfileFailsClosed(@TempDir Path temporary)
            throws IOException {
        Path target = temporary.resolve("real-image-definition");
        Files.writeString(target, "FROM example/image:1.2.3\n");
        Path symbolic = temporary.resolve("Dockerfile.symbolic");
        try {
            Files.createSymbolicLink(symbolic, target.getFileName());
        } catch (UnsupportedOperationException | IOException exception) {
            if (System.getProperty("os.name", "")
                    .toLowerCase()
                    .contains("windows")) {
                return;
            }
            throw exception;
        }

        assertThrows(
            IllegalArgumentException.class,
            () -> verifyDockerfilePaths(temporary)
        );
    }

    private static void verifyDockerfilePaths(Path root) throws IOException {
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(
                Path directory,
                BasicFileAttributes attributes
            ) {
                if (!directory.equals(root)
                        && IGNORED_DIRECTORY_NAMES.contains(
                            directory.getFileName().toString())) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(
                Path file,
                BasicFileAttributes attributes
            ) {
                if (file.getFileName().toString().startsWith("Dockerfile")
                        && (attributes.isSymbolicLink()
                            || Files.isSymbolicLink(file))) {
                    throw new IllegalArgumentException(
                        "container image policy invalid: symbolic Dockerfile path: "
                            + root.relativize(file)
                    );
                }
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static Path repositoryRoot() {
        String configured = System.getProperty("regelsuche.repositoryRoot");
        if (configured == null || configured.isBlank()) {
            throw new AssertionError(
                "Maven must expose maven.multiModuleProjectDirectory to tests"
            );
        }
        return Path.of(configured).toAbsolutePath().normalize();
    }
}
