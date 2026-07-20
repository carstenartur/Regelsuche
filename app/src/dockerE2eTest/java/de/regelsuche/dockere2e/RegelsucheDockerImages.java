package de.regelsuche.dockere2e;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import org.testcontainers.images.builder.ImageFromDockerfile;

/**
 * Shared lazy Docker-image builds for the repository integration-test JVM.
 *
 * <p>All images use one immutable snapshot of the Git-tracked checkout files.
 * Passing the live repository root to Testcontainers would also archive mutable
 * Gradle caches and build output; those files can change while the Docker
 * context tar is being written and make otherwise valid image builds fail
 * nondeterministically.</p>
 *
 * <p>Both web-workbench test classes consume the same {@link ImageFromDockerfile}
 * future, so Gradle's complete {@code test} lifecycle builds the standard image
 * once instead of compiling the full distribution independently per class.</p>
 */
final class RegelsucheDockerImages {
    private static final Path PROJECT_ROOT = Path.of(System.getProperty(
        "regelsuche.projectRoot",
        Path.of("").toAbsolutePath().toString()
    )).toAbsolutePath().normalize();

    private static final Path BUILD_CONTEXT = createTrackedBuildContext();

    static final ImageFromDockerfile APPLICATION = new ImageFromDockerfile()
        .withFileFromPath(".", BUILD_CONTEXT);

    static final ImageFromDockerfile PROOF = new ImageFromDockerfile()
        .withFileFromPath(".", BUILD_CONTEXT)
        .withDockerfilePath("./Dockerfile.proof");

    private RegelsucheDockerImages() {
    }

    static Path buildContext() {
        return BUILD_CONTEXT;
    }

    private static Path createTrackedBuildContext() {
        Path context = null;
        try {
            context = Files.createTempDirectory("regelsuche-docker-context-")
                .toAbsolutePath().normalize();
            Process process = new ProcessBuilder(
                "git", "-C", PROJECT_ROOT.toString(), "ls-files", "-z")
                .redirectErrorStream(true)
                .start();
            byte[] trackedOutput = process.getInputStream().readAllBytes();
            int exitCode = process.waitFor();
            if (exitCode != 0) {
                throw new IllegalStateException(
                    "git ls-files failed with exit code " + exitCode + ": "
                        + new String(trackedOutput, StandardCharsets.UTF_8));
            }

            for (String trackedPath : new String(
                    trackedOutput, StandardCharsets.UTF_8).split("\\u0000", -1)) {
                if (trackedPath.isEmpty()) {
                    continue;
                }
                Path relative = Path.of(trackedPath).normalize();
                if (relative.isAbsolute() || relative.startsWith("..")) {
                    throw new IllegalStateException(
                        "Git returned an unsafe tracked path: " + trackedPath);
                }
                Path source = PROJECT_ROOT.resolve(relative).normalize();
                Path target = context.resolve(relative).normalize();
                if (!source.startsWith(PROJECT_ROOT) || !target.startsWith(context)) {
                    throw new IllegalStateException(
                        "Tracked path escapes the Docker build context: " + trackedPath);
                }
                if (Files.isSymbolicLink(source)) {
                    throw new IllegalStateException(
                        "Symbolic links are not permitted in the Docker build context: "
                            + trackedPath);
                }
                if (!Files.isRegularFile(source)) {
                    throw new IllegalStateException(
                        "Tracked Docker input is not a regular file: " + trackedPath);
                }
                Files.createDirectories(target.getParent());
                Files.copy(
                    source,
                    target,
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.COPY_ATTRIBUTES);
            }

            Path completedContext = context;
            Runtime.getRuntime().addShutdownHook(new Thread(
                () -> deleteRecursively(completedContext),
                "regelsuche-docker-context-cleanup"));
            return completedContext;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            deleteRecursively(context);
            throw new IllegalStateException(
                "Interrupted while creating the Docker build context", exception);
        } catch (IOException exception) {
            deleteRecursively(context);
            throw new UncheckedIOException(
                "Could not create the Docker build context", exception);
        } catch (RuntimeException exception) {
            deleteRecursively(context);
            throw exception;
        }
    }

    private static void deleteRecursively(Path root) {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try (var paths = Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // The temporary context is best-effort cleanup only.
                }
            });
        } catch (IOException ignored) {
            // The temporary context is best-effort cleanup only.
        }
    }
}
