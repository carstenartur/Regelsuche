package de.regelsuche.build;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class SchemaValidatorVersionParityContractTest {
    @Test void gradleMavenAndRuntimeValidatorIdentityAgree() throws Exception {
        var root = MavenPomTestSupport.repositoryRoot();
        var properties = MavenPomTestSupport.directChild(
                MavenPomTestSupport.parse(root.resolve("pom.xml")).getDocumentElement(), "properties");
        String maven = MavenPomTestSupport.directChildTexts(properties, "json.schema.validator.version").getFirst();
        String gradle = version("com\\.networknt:json-schema-validator:([^'\"]+)",
                Files.readString(root.resolve("regelsuche-release/build.gradle")));
        String runtime = version("VALIDATOR_VERSION\\s*=\\s*\"([^\"]+)\"",
                Files.readString(root.resolve("regelsuche-release/src/main/java/de/regelsuche/release/ReleaseEvidenceJson.java")));
        assertEquals(gradle, maven, "Both build systems must compile the same validator API");
        assertEquals(maven, runtime, "Runtime validator identity must bind the actual dependency");
    }

    private static String version(String regex, String text) {
        var matcher = Pattern.compile(regex).matcher(text);
        assertTrue(matcher.find(), "Validator version must be explicit");
        String result = matcher.group(1);
        assertFalse(matcher.find(), "Validator version must be unambiguous");
        return result;
    }
}
