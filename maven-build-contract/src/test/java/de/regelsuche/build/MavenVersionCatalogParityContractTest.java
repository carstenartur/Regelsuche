package de.regelsuche.build;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Element;

/** Guards shared literal catalog versions; not a resolved dependency-graph comparison. */
class MavenVersionCatalogParityContractTest {
    private static final Map<String, String> MAVEN_PROPERTIES = Map.of(
        "junit-jupiter", "junit.version",
        "testcontainers", "testcontainers.version",
        "postgresql", "postgresql.version",
        "hibernate-orm", "hibernate.orm.version",
        "hibernate-search", "hibernate.search.version",
        "hibernate-validator", "hibernate.validator.version",
        "yasson", "yasson.version",
        "jakarta-el", "jakarta.el.version");
    private static final Pattern SECTION = Pattern.compile(
        "\\[([A-Za-z0-9_.-]+)](?:\\s*#.*)?");
    private static final Pattern VERSION = Pattern.compile(
        "([A-Za-z0-9_.-]+)\\s*=\\s*\"([^\"\\r\\n]+)\"(?:\\s*#.*)?");

    @Test
    void everySharedCatalogVersionMatchesItsMavenProperty() throws Exception {
        Path root = MavenPomTestSupport.repositoryRoot();
        assertAligned(mavenProperties(root), catalogVersions(Files.readString(
            root.resolve("gradle/libs.versions.toml"))));
    }

    @Test
    void independentVersionDriftIsRejectedRatherThanHiddenByOtherMatchingEntries()
            throws Exception {
        Path root = MavenPomTestSupport.repositoryRoot();
        Element properties = mavenProperties(root);
        Map<String, String> versions = catalogVersions(Files.readString(
            root.resolve("gradle/libs.versions.toml")));
        for (String alias : MAVEN_PROPERTIES.keySet()) {
            Map<String, String> changed = new LinkedHashMap<>(versions);
            changed.put(alias, versions.get(alias) + "-intentional-drift");
            AssertionError failure = assertThrows(AssertionError.class,
                () -> assertAligned(properties, changed), alias);
            assertTrue(failure.getMessage().contains(alias), failure.getMessage());
        }
    }

    @Test
    void readsOnlyTheLiteralVersionsSectionAndAllowsComments() {
        assertEquals(Map.of("hibernate-orm", "7.4.7.Final"), catalogVersions("""
            # fixture, not a general TOML parser
            [versions] # shared versions
            hibernate-orm = "7.4.7.Final" # one literal
            [libraries]
            hibernate-orm = { module = "not-a-version" }
            """));
    }

    @Test
    void missingDuplicateOrNonLiteralVersionsFailClosed() {
        for (String fixture : List.of(
                "hibernate-orm = \"1\"",
                "[versions]\n",
                "[versions]\nhibernate-orm = \"1\"\nhibernate-orm = \"2\"",
                "[versions]\nhibernate-orm = \"1\"\n[versions]\nyasson = \"2\"",
                "[versions]\nhibernate-orm = 1",
                "[versions]\nhibernate-orm = \" \"",
                "[versions]\nhibernate-orm = { ref = \"x\" }")) {
            assertThrows(AssertionError.class, () -> catalogVersions(fixture), fixture);
        }
    }

    private static Element mavenProperties(Path root) throws Exception {
        return MavenPomTestSupport.directChild(
            MavenPomTestSupport.parse(root.resolve("pom.xml")).getDocumentElement(),
            "properties");
    }

    private static void assertAligned(Element properties, Map<String, String> versions) {
        assertEquals(MAVEN_PROPERTIES.keySet(), versions.keySet(),
            "Every catalog version must have an explicit Maven parity binding");
        for (var binding : MAVEN_PROPERTIES.entrySet()) {
            List<String> values = MavenPomTestSupport.directChildTexts(properties, binding.getValue());
            assertEquals(1, values.size(), binding.getValue() + " must be declared exactly once");
            assertFalse(values.getFirst().isBlank(), binding.getValue() + " must not be blank");
            assertEquals(values.getFirst(), versions.get(binding.getKey()),
                binding.getKey() + " must match Maven " + binding.getValue());
        }
    }

    private static Map<String, String> catalogVersions(String catalog) {
        Map<String, String> versions = new LinkedHashMap<>();
        boolean insideVersions = false;
        boolean seenVersions = false;
        for (String raw : catalog.lines().toList()) {
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            if (line.startsWith("[")) {
                var section = SECTION.matcher(line);
                assertTrue(section.matches(), "Unsupported catalog section: " + line);
                insideVersions = "versions".equals(section.group(1));
                if (insideVersions) {
                    assertFalse(seenVersions, "Duplicate [versions] section");
                    seenVersions = true;
                }
            } else if (insideVersions) {
                var version = VERSION.matcher(line);
                assertTrue(version.matches(), "Expected a quoted literal catalog version: " + line);
                assertFalse(version.group(2).isBlank(), "Blank catalog version: " + line);
                assertNull(versions.putIfAbsent(version.group(1), version.group(2)),
                    "Duplicate catalog version: " + version.group(1));
            }
        }
        assertTrue(seenVersions && !versions.isEmpty(), "Missing or empty [versions] section");
        return Map.copyOf(versions);
    }
}
