package de.regelsuche.dockere2e;

import java.nio.file.Path;
import org.testcontainers.images.builder.ImageFromDockerfile;

/**
 * Shared lazy Docker-image builds for the repository integration-test JVM.
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

    static final ImageFromDockerfile APPLICATION = new ImageFromDockerfile()
        .withFileFromPath(".", PROJECT_ROOT);

    static final ImageFromDockerfile PROOF = new ImageFromDockerfile()
        .withFileFromPath(".", PROJECT_ROOT)
        .withDockerfilePath("./Dockerfile.proof");

    private RegelsucheDockerImages() {
    }
}
