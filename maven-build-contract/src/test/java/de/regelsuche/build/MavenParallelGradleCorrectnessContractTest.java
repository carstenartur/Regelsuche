package de.regelsuche.build;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class MavenParallelGradleCorrectnessContractTest {

  @Test
  void onlyTheNonBenchmarkGradleAuthorityUsesProjectParallelism()
      throws IOException {
    String workflow = Files.readString(repositoryRoot().resolve(
        ".github/workflows/gradle.yml"));
    String gradle = section(
        workflow,
        "  gradle-verification:\n",
        "  jmh-verification:\n");
    String jmh = section(
        workflow,
        "  jmh-verification:\n",
        "  maven-product-verification:\n");
    String maven = section(
        workflow,
        "  maven-product-verification:\n",
        "  verification:\n");

    assertTrue(gradle.contains(
        "-PseparateJmhAuthority=true \"$REGELSUCHE_CI_TASK\" "
            + "--parallel --console=plain"),
        "parallel project execution is allowed only after JMH is separated");
    assertEquals(1, occurrences(workflow, "--parallel"),
        "only the correctness Gradle authority may use project parallelism");
    assertFalse(jmh.contains("--parallel"),
        "JMH tasks must remain serial on their isolated runner");
    assertFalse(maven.contains("--parallel"));
    assertFalse(workflow.contains("--max-workers"),
        "CI must use the runner CPU limit rather than an unsafe hard-coded fan-out");
    assertFalse(workflow.contains("maxParallelForks"),
        "project parallelism must not be confused with parallel JUnit forks");
    assertFalse(workflow.contains(
        "junit.jupiter.execution.parallel.enabled"),
        "JUnit method/class parallelism remains unchanged");
  }

  private static String section(String text, String start, String end) {
    int from = text.indexOf(start);
    assertTrue(from >= 0, () -> "missing workflow section " + start.strip());
    int to = text.indexOf(end, from + start.length());
    assertTrue(to > from, () -> "missing workflow section " + end.strip());
    return text.substring(from, to);
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
