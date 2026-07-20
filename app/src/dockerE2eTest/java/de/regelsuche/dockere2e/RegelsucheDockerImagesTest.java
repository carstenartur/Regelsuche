package de.regelsuche.dockere2e;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** Characterizes the immutable Git-tracked Docker build context. */
class RegelsucheDockerImagesTest {

    @Test
    void buildContextContainsTrackedInputsButNoMutableRepositoryState() {
        Path context = RegelsucheDockerImages.buildContext();

        assertTrue(Files.isRegularFile(context.resolve("gradlew")));
        assertTrue(Files.isRegularFile(context.resolve("Dockerfile")));
        assertTrue(Files.isRegularFile(context.resolve("Dockerfile.proof")));
        assertTrue(Files.isRegularFile(context.resolve("settings.gradle")));

        assertFalse(Files.exists(context.resolve(".git")));
        assertFalse(Files.exists(context.resolve(".gradle")));
        assertFalse(Files.exists(context.resolve("build")));
        assertFalse(Files.exists(context.resolve("app/build")));
    }
}
