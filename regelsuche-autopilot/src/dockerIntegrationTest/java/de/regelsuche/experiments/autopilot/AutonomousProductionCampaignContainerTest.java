package de.regelsuche.experiments.autopilot;

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

/** Checkout-local Docker reproduction for the complete Autopilot campaign. */
@Testcontainers(disabledWithoutDocker = true)
class AutonomousProductionCampaignContainerTest {
    private static final Path PROJECT_ROOT = Path.of(System.getProperty(
        "regelsuche.projectRoot",
        Path.of("").toAbsolutePath().toString()
    )).toAbsolutePath().normalize();

    @Test
    void runtimeImageReproducesLocalCampaignByteForByte(
        @TempDir Path temporaryDirectory
    ) throws Exception {
        Path localOutput = temporaryDirectory.resolve("local");
        Path containerOutput = temporaryDirectory.resolve("container");
        Path buildContext = createTrackedBuildContext(temporaryDirectory);
        Files.copy(
            buildContext.resolve("Dockerfile.autopilot"),
            buildContext.resolve("Dockerfile"),
            StandardCopyOption.REPLACE_EXISTING);
        Files.createDirectories(containerOutput);
        makeContainerWritable(containerOutput);

        AutonomousProductionCampaignRunner runner =
            new AutonomousProductionCampaignRunner();
        runner.write(localOutput, runner.runPinned(4));

        ImageFromDockerfile image = new ImageFromDockerfile()
            .withFileFromPath(".", buildContext);

        try {
            GenericContainer<?> container = new GenericContainer<>(image)
                .withFileSystemBind(
                    containerOutput.toString(),
                    "/output",
                    BindMode.READ_WRITE)
                .withStartupCheckStrategy(
                    new OneShotStartupCheckStrategy()
                        .withTimeout(Duration.ofMinutes(30)))
                .withLogConsumer(frame -> System.err.print(frame.getUtf8String()));
            try {
                container.start();
            } finally {
                container.stop();
            }

            assertTreesEqual(localOutput, containerOutput);
        } finally {
            makeContainerTreeWritable(image, containerOutput);
        }
    }

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

    private static void makeContainerTreeWritable(
        ImageFromDockerfile image,
        Path directory
    ) {
        GenericContainer<?> cleanup = new GenericContainer<>(image)
            .withFileSystemBind(directory.toString(), "/output", BindMode.READ_WRITE)
            .withCreateContainerCmdModifier(command -> {
                command.withEntrypoint("/bin/sh", "-c");
                command.withCmd("chmod -R a+rwX /output");
            })
            .withStartupCheckStrategy(
                new OneShotStartupCheckStrategy().withTimeout(Duration.ofMinutes(2)));
        try {
            cleanup.start();
        } finally {
            cleanup.stop();
        }
    }

    private static Path createTrackedBuildContext(Path temporaryDirectory)
            throws Exception {
        Process process = new ProcessBuilder(
            "git", "-C", PROJECT_ROOT.toString(), "ls-files", "-z")
            .redirectErrorStream(true)
            .start();
        byte[] output = process.getInputStream().readAllBytes();
        int exitCode = process.waitFor();
        assertEquals(
            0,
            exitCode,
            () -> "git ls-files failed: "
                + new String(output, StandardCharsets.UTF_8));

        Path context = temporaryDirectory.resolve("docker-context");
        Files.createDirectories(context);
        for (String trackedPath : new String(output, StandardCharsets.UTF_8)
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
        assertEquals(expected, actual, "campaign file tree differs");
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
