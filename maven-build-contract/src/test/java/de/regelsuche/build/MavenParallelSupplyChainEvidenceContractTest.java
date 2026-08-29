package de.regelsuche.build;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class MavenParallelSupplyChainEvidenceContractTest {

  @Test
  void sbomResolutionRunsUnderEachOwningProjectLock()
      throws IOException {
    String script = Files.readString(repositoryRoot().resolve(
        "gradle/supply-chain-evidence.gradle"));
    String fragment = section(
        script,
        "def fragmentRegistrations = []",
        "tasks.register('cyclonedxBom')");
    String aggregate = script.substring(script.indexOf(
        "tasks.register('cyclonedxBom')"));

    assertTrue(fragment.contains("rootProject.allprojects"));
    assertTrue(fragment.contains(
        "candidateProject.tasks.register(\n"
            + "            'writeSupplyChainDependencyFragment'"));
    assertTrue(fragment.contains("candidateProject.configurations"));
    assertTrue(fragment.contains(
        "configuration.incoming.resolutionResult"));
    assertTrue(fragment.contains("outputs.upToDateWhen { false }"),
        "every authority run must resolve a fresh project fragment");
    assertTrue(fragment.contains("projectPath: candidateProject.path"));
    assertTrue(fragment.contains("rootDependencies:"));

    assertTrue(aggregate.contains(
        "dependsOn fragmentRegistrations.collect"));
    assertTrue(aggregate.contains(
        "inputs.files(fragmentRegistrations.collect"));
    assertTrue(aggregate.contains("new JsonSlurper()"));
    assertTrue(aggregate.contains(
        "dependency fragment project mismatch"));
    assertTrue(aggregate.contains(
        "conflicting dependency component"));
    assertFalse(aggregate.contains("candidateProject.configurations"),
        "the root aggregate task must never resolve a foreign project graph");
    assertFalse(aggregate.contains(".incoming.resolutionResult"),
        "the root aggregate task must consume immutable files only");

    assertTrue(aggregate.contains("bomFormat: 'CycloneDX'"));
    assertTrue(aggregate.contains("specVersion: '1.6'"));
    assertTrue(aggregate.contains(
        "name: 'checkout-gradle-dependency-inventory'"));
    assertFalse(script.contains("UUID.randomUUID"));
    assertFalse(script.contains("Instant.now"));
  }

  private static String section(String text, String start, String end) {
    int from = text.indexOf(start);
    assertTrue(from >= 0, () -> "missing section " + start);
    int to = text.indexOf(end, from + start.length());
    assertTrue(to > from, () -> "missing section " + end);
    return text.substring(from, to);
  }

  private static Path repositoryRoot() {
    String configured = System.getProperty("regelsuche.repositoryRoot");
    assertNotNull(configured,
        "Maven must expose maven.multiModuleProjectDirectory to tests");
    return Path.of(configured).toAbsolutePath().normalize();
  }
}
