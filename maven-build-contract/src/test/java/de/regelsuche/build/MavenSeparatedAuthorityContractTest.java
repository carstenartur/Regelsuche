package de.regelsuche.build;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class MavenSeparatedAuthorityContractTest {
  private static final String FULL_MAVEN_COMMAND =
      "mvn --batch-mode --no-transfer-progress -Pfull verify";

  @Test
  void focusedMavenSubprocessIsDelegatedOnlyWhenFullMavenIsRequired()
      throws IOException {
    Path root = repositoryRoot();
    String workflow = Files.readString(root.resolve(
        ".github/workflows/gradle.yml"));
    String coreBuild = Files.readString(root.resolve(
        "regelsuche-core/build.gradle"));
    String gradle = section(
        workflow,
        "  gradle-verification:\n",
        "  jmh-verification:\n");
    String maven = section(
        workflow,
        "  maven-product-verification:\n",
        "  verification:\n");
    String convergence = section(
        workflow,
        "  verification:\n",
        "  publish-pages:\n");

    assertTrue(gradle.contains(
        "REGELSUCHE_SEPARATE_MAVEN_AUTHORITY: "
            + "${{ github.event_name != 'create' && "
            + "'required-by-workflow' || 'disabled' }}"));
    assertTrue(gradle.contains(
        "ORG_GRADLE_PROJECT_separateMavenAuthority: "
            + "${{ github.event_name != 'create' && 'true' || 'false' }}"));
    assertFalse(gradle.contains(FULL_MAVEN_COMMAND),
        "the correctness job must not invoke the duplicate full Maven reactor");

    assertTrue(maven.contains("if: github.event_name != 'create'"),
        "delegation must be disabled for the Maven-less showcase create event");
    assertTrue(maven.contains(FULL_MAVEN_COMMAND));
    assertTrue(maven.contains("actions/checkout@"));
    assertFalse(maven.contains("needs: gradle-verification"));
    assertEquals(1, occurrences(workflow, FULL_MAVEN_COMMAND));

    String compactConvergence = compact(convergence);
    assertTrue(compactConvergence.contains(
        "github.event_name != 'create' && "
            + "needs.maven-product-verification.result != 'success'"),
        "ordinary CI must fail unless the complete delegated Maven authority succeeds");

    assertTrue(coreBuild.contains(
        "providers.gradleProperty(\n    'separateMavenAuthority')"));
    assertTrue(coreBuild.contains(
        "providers.environmentVariable(\n"
            + "    'REGELSUCHE_SEPARATE_MAVEN_AUTHORITY')"));
    assertTrue(coreBuild.contains(
        "separateMavenAuthorityMarker != 'required-by-workflow'"));
    assertTrue(coreBuild.contains(
        "throw new GradleException(\n"
            + "        'separateMavenAuthority requires the versioned "
            + "workflow authority marker')"));
    assertTrue(coreBuild.contains(
        "enabled = !separateMavenAuthority"));
    assertTrue(coreBuild.contains(
        "rootProject.tasks.named('check') {\n"
            + "    dependsOn verifyMavenCoreReactor\n"
            + "}"),
        "the local/default Gradle contract must retain the focused Maven task");
    assertTrue(coreBuild.contains("'maven-build-contract'"));
    assertTrue(coreBuild.contains("outputs.upToDateWhen { false }"),
        "default local execution must remain a fresh Maven contract run");
    assertFalse(coreBuild.contains("onlyIf { false"));
    assertFalse(workflow.contains("-x verifyMavenCoreReactor"));
    assertFalse(workflow.contains("--exclude-task verifyMavenCoreReactor"));
  }

  private static String section(String text, String start, String end) {
    int from = text.indexOf(start);
    assertTrue(from >= 0, () -> "missing workflow section " + start.strip());
    int to = text.indexOf(end, from + start.length());
    assertTrue(to > from, () -> "missing workflow section " + end.strip());
    return text.substring(from, to);
  }

  private static String compact(String text) {
    return text.replaceAll("\\s+", " ").trim();
  }

  private static int occurrences(String text, String value) {
    int count = 0;
    int offset = 0;
    while ((offset = text.indexOf(value, offset)) >= 0) {
      count++;
      offset += value.length();
    }
    return count;
  }

  private static Path repositoryRoot() {
    String configured = System.getProperty("regelsuche.repositoryRoot");
    assertNotNull(configured,
        "Maven must expose maven.multiModuleProjectDirectory to tests");
    return Path.of(configured).toAbsolutePath().normalize();
  }
}
