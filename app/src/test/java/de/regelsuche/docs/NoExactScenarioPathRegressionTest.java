package de.regelsuche.docs;

import static org.junit.jupiter.api.Assertions.assertFalse;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class NoExactScenarioPathRegressionTest {
    @Test
    void discoveryScenarioResourcesDoNotContainFallbackOrExactPathMarkers() throws Exception {
        Path resourceRoot = Path.of("src/main/resources");
        for (Path file : Files.walk(resourceRoot).filter(Files::isRegularFile).toList()) {
            if (!file.toString().endsWith(".yaml")) {
                continue;
            }
            String content = Files.readString(file, StandardCharsets.UTF_8);
            assertFalse(content.contains("scenario-exact-path"), file.toString());
            assertFalse(content.contains("expectedIncomplete"), file.toString());
            assertFalse(content.toLowerCase().contains("fallback"), file.toString());
        }
    }
}
