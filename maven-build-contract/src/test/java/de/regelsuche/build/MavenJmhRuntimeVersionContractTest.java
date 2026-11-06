package de.regelsuche.build;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/** A Gradle plugin update must not silently replace the frozen JMH runtime. */
class MavenJmhRuntimeVersionContractTest {
    private static final Pattern PIN = Pattern.compile(
        "(?m)^\\s*jmhVersion\\s*=\\s*['\"]([^'\"]+)['\"]"
    );

    @Test
    void bothBenchmarkModulesPinTheFrozenMeasurementRuntime() throws Exception {
        Path root = MavenPomTestSupport.repositoryRoot();
        String expected = new ObjectMapper().readTree(root.resolve(
            "config/quality/jmh-regression-policy-more-warmup-v1.json"
        ).toFile()).path("execution").path("jmhVersion").asText();
        assertFalse(expected.isBlank(), "The measurement policy must name its JMH runtime");

        for (String module : List.of("app", "regelsuche-math-sympy")) {
            String source = Files.readString(root.resolve(module).resolve("build.gradle"));
            assertEquals(expected, pinnedVersion(source),
                module + " must not inherit a different runtime from the JMH plugin");
        }
    }

    @Test
    void missingAndDuplicateRuntimePinsAreRejected() {
        assertThrows(AssertionError.class, () -> pinnedVersion("jmh {}"));
        assertThrows(AssertionError.class, () -> pinnedVersion(
            "jmhVersion = 'first'\njmhVersion = 'second'\n"));
        assertEquals("fixture", pinnedVersion("    jmhVersion = 'fixture'\n"));
    }

    private static String pinnedVersion(String source) {
        var matcher = PIN.matcher(source);
        assertTrue(matcher.find(), "An explicit JMH runtime version is required");
        String version = matcher.group(1);
        assertFalse(matcher.find(), "The JMH runtime version must be unambiguous");
        return version;
    }
}
