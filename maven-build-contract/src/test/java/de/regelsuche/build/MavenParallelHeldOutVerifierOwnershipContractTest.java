package de.regelsuche.build;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class MavenParallelHeldOutVerifierOwnershipContractTest {

  @Test
  void heldOutVerifierUsesTheDiscoveryProjectLock()
      throws IOException {
    Path root = repositoryRoot();
    String adapter = Files.readString(root.resolve(
        "gradle/target-free-held-out-container-reproduction.gradle"));
    String base = Files.readString(root.resolve(
        "gradle/target-free-held-out-container-reproduction-base.gradle"));

    assertTrue(adapter.contains(
        "target-free-held-out-container-reproduction-base.gradle"));
    assertFalse(adapter.contains("rootProject.tasks.named("));
    assertFalse(adapter.contains("runtimeClasspath"),
        "the registration adapter must not attach a foreign classpath to root");

    assertTrue(base.contains(
        "def verifyTargetFreeHeldOutContainerReproduction = tasks.register(\n"
            + "    'verifyTargetFreeHeldOutContainerReproduction'\n"
            + ")"),
        "root must retain the stable compatibility task path");
    String rootAlias = section(
        base,
        "def verifyTargetFreeHeldOutContainerReproduction = tasks.register(",
        "project(':regelsuche-discovery')");
    assertFalse(rootAlias.contains("JavaExec"));
    assertFalse(rootAlias.contains("runtimeClasspath"));

    String discovery = base.substring(base.indexOf(
        "project(':regelsuche-discovery')"));
    assertTrue(discovery.contains(
        "def verifierAuthority = discovery.tasks.register(\n"
            + "            'verifyTargetFreeHeldOutContainerReproduction',\n"
            + "            JavaExec"));
    assertTrue(discovery.contains(
        "dependsOn discovery.tasks.named('testClasses')"));
    assertTrue(discovery.contains(
        ".getByName('test').runtimeClasspath"));
    assertTrue(discovery.contains("hostRunATask"));
    assertTrue(discovery.contains("hostRunBTask"));
    assertTrue(discovery.contains("runTargetFreeHeldOutReproductionImage"));
    assertTrue(discovery.contains(
        "dependsOn verifierAuthority"),
        "root compatibility must delegate to the discovery-owned verifier");
    assertTrue(discovery.contains(
        "TargetFreeHeldOutContainerReproductionVerifier"));
    assertTrue(discovery.contains(
        "reproductionManifest.get().isFile()"),
        "the evidence manifest must remain mandatory");
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
