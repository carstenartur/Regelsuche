package de.regelsuche.build;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class MavenSeparatedJmhDependencyGraphContractTest {

  @Test
  void correctnessCiDetachesOnlyTheSeparatelyRequiredJmhGraph()
      throws IOException {
    String script = Files.readString(repositoryRoot().resolve(
        "gradle/separated-jmh-authority.gradle"));

    assertTrue(script.contains(
        "providers.gradleProperty(\n    'separateJmhAuthority')"));
    assertTrue(script.contains("gradle.projectsEvaluated"),
        "dependency edges must be detached after every project declared them");
    assertTrue(script.contains("rootJmhTaskNames.contains(task.name)"));
    assertTrue(script.contains("appJmhTaskNames.contains(task.name)"));
    assertTrue(script.contains("sympyJmhTaskNames.contains(task.name)"));
    assertEquals(3, occurrences(script, "setDependsOn([])"),
        "root, app and SymPy benchmark terminals must drop duplicate prerequisites");
    assertEquals(3, occurrences(script, "enabled = false"));

    assertTrue(script.contains("tasks.register('jmhAuthority')"));
    assertTrue(script.contains(
        "dependsOn 'verifyJmhRegression',\n"
            + "        'verifyJmhBenchmark',\n"
            + "        'verifyJmhAllocationBenchmark',\n"
            + "        ':regelsuche-math-sympy:"
            + "verifySymPyFactorizationBenchmark'"),
        "the isolated authority must retain the complete original graph");
    assertTrue(script.contains(
        "jmhAuthority must run without -PseparateJmhAuthority=true"));

    assertFalse(script.contains("warmup"));
    assertFalse(script.contains("iteration"));
    assertFalse(script.contains("maxParallelForks"));
    assertFalse(script.contains("filter {"));
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
