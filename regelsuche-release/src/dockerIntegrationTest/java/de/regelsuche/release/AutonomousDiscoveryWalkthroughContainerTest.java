package de.regelsuche.release;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.BindMode;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.startupcheck.OneShotStartupCheckStrategy;
import org.testcontainers.images.builder.ImageFromDockerfile;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Checkout-local Docker reproduction for the autonomous-discovery walkthrough.
 *
 * <p>This is deliberately a Testcontainers integration test rather than a
 * GitHub Actions implementation. The root {@code ./gradlew test} lifecycle can
 * therefore execute the same contract from any ordinary checkout with Docker.</p>
 */
@Testcontainers(disabledWithoutDocker = true)
class AutonomousDiscoveryWalkthroughContainerTest {
    private static final String REVISION =
        "0123456789abcdef0123456789abcdef01234567";
    private static final Path PROJECT_ROOT = Path.of(System.getProperty(
        "regelsuche.projectRoot",
        Path.of("").toAbsolutePath().toString()
    )).toAbsolutePath().normalize();

    @Test
    void containerTargetReproducesLocalWalkthroughAndCommittedFigures(
        @TempDir Path temporaryDirectory
    ) throws Exception {
        Path localOutput = temporaryDirectory.resolve("local");
        Path containerOutput = temporaryDirectory.resolve("container");
        Path buildContext = createTrackedBuildContext(temporaryDirectory);
        Files.createDirectories(containerOutput);
        makeContainerWritable(containerOutput);

        new AutonomousDiscoveryWalkthroughRunner().run(localOutput, REVISION);

        ImageFromDockerfile walkthroughImage = new ImageFromDockerfile()
            .withFileFromPath(".", buildContext)
            .withTarget("walkthrough")
            .withBuildArg("REGELSUCHE_REPOSITORY_REVISION", REVISION);

        try {
            GenericContainer<?> container = new GenericContainer<>(walkthroughImage)
                .withFileSystemBind(
                    containerOutput.toString(),
                    "/out",
                    BindMode.READ_WRITE)
                .withStartupCheckStrategy(
                    new OneShotStartupCheckStrategy().withTimeout(Duration.ofMinutes(30)))
                .withLogConsumer(frame -> System.err.print(frame.getUtf8String()));
            try {
                container.start();
            } finally {
                container.stop();
            }

            assertTreesEqual(localOutput, containerOutput);
            Path committedFigures = PROJECT_ROOT.resolve(
                "docs/generated/autonomous-discovery-walkthrough");
            assertTrue(Files.isDirectory(committedFigures), committedFigures.toString());
            assertTreesEqual(localOutput.resolve("figures"), committedFigures);
        } finally {
            makeContainerTreeWritable(walkthroughImage, containerOutput);
        }
    }

    /**
     * Makes a host bind mount writable for rootless Docker and user-namespace
     * remapping, matching the permission boundary of the former shell workflow.
     */
    private static void makeContainerWritable(Path directory) throws Exception {
        PosixFileAttributeView posix = Files.getFileAttributeView(
            directory, PosixFileAttributeView.class);
        if (posix != null) {
            posix.setPermissions(PosixFilePermissions.fromString("rwxrwxrwx"));
            return;
        }

        File file = directory.toFile();
        assertTrue(file.setReadable(true, false),
            () -> "could not make bind mount readable: " + directory);
        assertTrue(file.setWritable(true, false),
            () -> "could not make bind mount writable: " + directory);
        assertTrue(file.setExecutable(true, false),
            () -> "could not make bind mount searchable: " + directory);
    }

    /**
     * Restores recursive host access after the root-owned image has written the
     * bind mount. Otherwise JUnit cannot delete nested container-created paths
     * when it closes the {@link TempDir} extension context.
     */
    private static void makeContainerTreeWritable(
        ImageFromDockerfile walkthroughImage,
        Path directory
    ) {
        GenericContainer<?> cleanup = new GenericContainer<>(walkthroughImage)
            .withFileSystemBind(directory.toString(), "/out", BindMode.READ_WRITE)
            .withCreateContainerCmdModifier(command -> {
                command.withEntrypoint("/bin/sh", "-c");
                command.withCmd("chmod -R a+rwX /out");
            })
            .withStartupCheckStrategy(
                new OneShotStartupCheckStrategy().withTimeout(Duration.ofMinutes(2)));
        try {
            cleanup.start();
        } finally {
            cleanup.stop();
        }
    }

    /**
     * Copies only Git-tracked checkout files into a stable temporary context.
     *
     * <p>Passing the live repository root to Testcontainers also archives
     * mutable {@code .gradle} and module build caches. Those files can change
     * while the tar stream is being written and make an otherwise valid image
     * build fail nondeterministically. The tracked-file snapshot preserves
     * current worktree edits while excluding caches, build output and Git
     * metadata by construction.</p>
     */
    private static Path createTrackedBuildContext(Path temporaryDirectory)
            throws Exception {
        Process process = new ProcessBuilder(
            "git", "-C", PROJECT_ROOT.toString(), "ls-files", "-z")
            .start();
        byte[] trackedOutput = process.getInputStream().readAllBytes();
        byte[] diagnostics = process.getErrorStream().readAllBytes();
        int exitCode = process.waitFor();
        assertEquals(
            0,
            exitCode,
            () -> "git ls-files failed: "
                + new String(diagnostics, StandardCharsets.UTF_8));

        Path context = temporaryDirectory.resolve("docker-context");
        Files.createDirectories(context);
        for (String trackedPath : new String(trackedOutput, StandardCharsets.UTF_8)
                .split("\u0000")) {
            if (trackedPath.isEmpty()) {
                continue;
            }
            Path relative = Path.of(trackedPath).normalize();
            assertTrue(!relative.isAbsolute() && !relative.startsWith(".."), trackedPath);
            Path source = PROJECT_ROOT.resolve(relative).normalize();
            Path target = context.resolve(relative).normalize();
            assertTrue(source.startsWith(PROJECT_ROOT), source.toString());
            assertTrue(target.startsWith(context), target.toString());
            Files.createDirectories(target.getParent());
            Files.copy(
                source,
                target,
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.COPY_ATTRIBUTES);
        }
        return context;
    }

    private static void assertTreesEqual(Path expectedRoot, Path actualRoot)
            throws Exception {
        List<Path> expected = regularFiles(expectedRoot);
        List<Path> actual = regularFiles(actualRoot);
        assertEquals(expected, actual, "file tree differs");
        for (Path relative : expected) {
            assertArrayEquals(
                Files.readAllBytes(expectedRoot.resolve(relative)),
                Files.readAllBytes(actualRoot.resolve(relative)),
                relative.toString());
        }
    }

    private static List<Path> regularFiles(Path root) throws Exception {
        try (var paths = Files.walk(root)) {
            return paths
                .filter(Files::isRegularFile)
                .map(root::relativize)
                .sorted(Comparator.comparing(Path::toString))
                .toList();
        }
    }
}
