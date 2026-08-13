package de.regelsuche.dockere2e;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
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

    @Test
    void wrapperBootstrapRetriesTransientDistributionFailures() throws IOException {
        Path propertiesFile = RegelsucheDockerImages.buildContext()
            .resolve("gradle/wrapper/gradle-wrapper.properties");
        Properties properties = new Properties();
        try (InputStream input = Files.newInputStream(propertiesFile)) {
            properties.load(input);
        }

        assertTrue(integerProperty(properties, "networkTimeout") >= 60_000);
        assertTrue(integerProperty(properties, "retries") >= 3);
        assertTrue(integerProperty(properties, "retryBackOffMs") >= 1_000);
        assertFalse(properties.getProperty("distributionSha256Sum", "").isBlank());
    }

    private static int integerProperty(Properties properties, String name) {
        return Integer.parseInt(properties.getProperty(name, "0"));
    }
}
