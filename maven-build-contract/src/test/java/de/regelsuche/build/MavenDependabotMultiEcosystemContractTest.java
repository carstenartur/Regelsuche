package de.regelsuche.build;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.Test;

class MavenDependabotMultiEcosystemContractTest {
  private static final Set<String> ROUTINE_GRADLE_PATTERNS = Set.of(
      "com.fasterxml.jackson.*:*",
      "org.neo4j.driver:*",
      "org.junit.jupiter:*",
      "org.testcontainers:*",
      "org.postgresql:*",
      "org.hibernate.*:*",
      "org.eclipse:yasson",
      "org.glassfish:jakarta.el",
      "jakarta.persistence:jakarta.persistence-api");
  private static final Set<String> ROUTINE_MAVEN_PATTERNS = Set.of(
      "com.fasterxml.jackson.*:*",
      "org.neo4j.driver:*",
      "org.junit:*",
      "org.junit.jupiter:*",
      "org.testcontainers:*",
      "org.postgresql:*",
      "org.hibernate.*:*",
      "org.eclipse:yasson",
      "org.glassfish:jakarta.el",
      "jakarta.persistence:jakarta.persistence-api");
  private static final Set<String> GRAALVM_PATTERNS = Set.of(
      "org.graalvm.polyglot:*");
  private static final Set<String> WEB_ASSET_PATTERNS = Set.of(
      "org.webjars:swagger-ui",
      "org.webjars.npm:cytoscape");

  private final ObjectMapper yaml = new ObjectMapper(new YAMLFactory());

  @Test
  void sharedBuildDependenciesUseVersionedMultiEcosystemGroups()
      throws IOException {
    JsonNode root = configuration();

    assertEquals(2, root.path("version").asInt());
    assertWeeklyGroup(root, "java-runtime-maintenance");
    assertWeeklyGroup(root, "graalvm-runtime");
    assertWeeklyGroup(root, "web-assets");

    assertGroupEntry(root, "gradle", "java-runtime-maintenance",
        ROUTINE_GRADLE_PATTERNS, true);
    assertGroupEntry(root, "maven", "java-runtime-maintenance",
        ROUTINE_MAVEN_PATTERNS, true);
    assertGroupEntry(root, "gradle", "graalvm-runtime",
        GRAALVM_PATTERNS, false);
    assertGroupEntry(root, "maven", "graalvm-runtime",
        GRAALVM_PATTERNS, false);
    assertGroupEntry(root, "gradle", "web-assets",
        WEB_ASSET_PATTERNS, false);
    assertGroupEntry(root, "maven", "web-assets",
        WEB_ASSET_PATTERNS, false);
  }

  @Test
  void ordinaryGradleUpdatesCannotDuplicateGroupedPullRequests()
      throws IOException {
    JsonNode root = configuration();
    JsonNode ordinaryGradle = ordinaryEntry(root, "gradle");

    assertEquals("weekly",
        ordinaryGradle.path("schedule").path("interval").asText());
    Set<String> fullyIgnored = ignoredDependencies(
        ordinaryGradle, Set.of());
    Set<String> minorPatchIgnored = ignoredDependencies(
        ordinaryGradle,
        Set.of("version-update:semver-minor",
            "version-update:semver-patch"));

    assertTrue(minorPatchIgnored.containsAll(ROUTINE_GRADLE_PATTERNS),
        () -> "Routine cross-build dependencies must be excluded from "
            + "ordinary Gradle minor/patch PRs: " + minorPatchIgnored);
    assertTrue(fullyIgnored.containsAll(GRAALVM_PATTERNS),
        () -> "GraalVM must be isolated in its own cross-build PR");
    assertTrue(fullyIgnored.containsAll(WEB_ASSET_PATTERNS),
        () -> "Offline web assets must be isolated in one cross-build PR");
    assertFalse(hasOrdinaryEntry(root, "maven"),
        "Maven-only updates are not authorized by this focused parity tranche");
  }

  @Test
  void obsoleteGuavaMaintenanceAliasIsNotReintroduced()
      throws IOException {
    String source = java.nio.file.Files.readString(dependabotPath());

    assertFalse(source.contains("com.google.guava"));
    assertFalse(source.contains("guava"));
  }

  private JsonNode configuration() throws IOException {
    JsonNode root = yaml.readTree(dependabotPath().toFile());
    assertNotNull(root, "Dependabot configuration must parse as YAML");
    assertTrue(root.path("updates").isArray(), "updates must be an array");
    return root;
  }

  private static void assertWeeklyGroup(JsonNode root, String group) {
    JsonNode node = root.path("multi-ecosystem-groups").path(group);
    assertTrue(node.isObject(), () -> "Missing multi-ecosystem group " + group);
    assertEquals("weekly", node.path("schedule").path("interval").asText(),
        () -> group + " must own a weekly schedule");
  }

  private static void assertGroupEntry(
      JsonNode root,
      String ecosystem,
      String group,
      Set<String> expectedPatterns,
      boolean rejectsMajorUpdates
  ) {
    List<JsonNode> matches = entries(root, ecosystem, group);
    assertEquals(1, matches.size(),
        () -> "Expected one " + ecosystem + " entry for " + group);
    JsonNode entry = matches.getFirst();
    assertEquals("/", entry.path("directory").asText());
    assertEquals(expectedPatterns, textSet(entry.path("patterns")),
        () -> ecosystem + " patterns drifted for " + group);
    assertFalse(entry.has("schedule"),
        "The multi-ecosystem group, not a participant, owns the schedule");
    assertEquals(rejectsMajorUpdates,
        hasWildcardIgnore(entry, "version-update:semver-major"),
        () -> ecosystem + " major-update policy drifted for " + group);
  }

  private static JsonNode ordinaryEntry(JsonNode root, String ecosystem) {
    List<JsonNode> matches = StreamSupport.stream(
            root.path("updates").spliterator(), false)
        .filter(entry -> ecosystem.equals(
            entry.path("package-ecosystem").asText()))
        .filter(entry -> !entry.has("multi-ecosystem-group"))
        .toList();
    assertEquals(1, matches.size(),
        () -> "Expected one ordinary " + ecosystem + " entry");
    return matches.getFirst();
  }

  private static boolean hasOrdinaryEntry(JsonNode root, String ecosystem) {
    return StreamSupport.stream(root.path("updates").spliterator(), false)
        .anyMatch(entry -> ecosystem.equals(
                entry.path("package-ecosystem").asText())
            && !entry.has("multi-ecosystem-group"));
  }

  private static List<JsonNode> entries(
      JsonNode root,
      String ecosystem,
      String group
  ) {
    return StreamSupport.stream(root.path("updates").spliterator(), false)
        .filter(entry -> ecosystem.equals(
            entry.path("package-ecosystem").asText()))
        .filter(entry -> group.equals(
            entry.path("multi-ecosystem-group").asText()))
        .toList();
  }

  private static Set<String> textSet(JsonNode array) {
    assertTrue(array.isArray(), "patterns must be an array");
    Set<String> values = new HashSet<>();
    array.forEach(node -> values.add(node.asText()));
    assertEquals(array.size(), values.size(), "patterns must be unique");
    return Set.copyOf(values);
  }

  private static Set<String> ignoredDependencies(
      JsonNode entry,
      Set<String> exactUpdateTypes
  ) {
    Set<String> result = new HashSet<>();
    entry.path("ignore").forEach(ignore -> {
      Set<String> types = ignore.has("update-types")
          ? textSet(ignore.path("update-types")) : Set.of();
      if (types.equals(exactUpdateTypes)) {
        result.add(ignore.path("dependency-name").asText());
      }
    });
    return Set.copyOf(result);
  }

  private static boolean hasWildcardIgnore(
      JsonNode entry,
      String updateType
  ) {
    return StreamSupport.stream(entry.path("ignore").spliterator(), false)
        .anyMatch(ignore -> "*".equals(
                ignore.path("dependency-name").asText())
            && textSet(ignore.path("update-types")).contains(updateType));
  }

  private static Path dependabotPath() {
    return repositoryRoot().resolve(".github/dependabot.yml");
  }

  private static Path repositoryRoot() {
    String configured = System.getProperty("regelsuche.repositoryRoot");
    assertNotNull(
        configured,
        "Maven must expose maven.multiModuleProjectDirectory to tests");
    return Path.of(configured).toAbsolutePath().normalize();
  }
}
