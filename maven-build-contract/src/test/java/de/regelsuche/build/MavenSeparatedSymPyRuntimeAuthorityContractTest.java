package de.regelsuche.build;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class MavenSeparatedSymPyRuntimeAuthorityContractTest {

  @Test
  void projectParallelCiRunsUnchangedSymPyRuntimeTestsOnAnIndependentRunner()
      throws IOException {
    Path root = repositoryRoot();
    String workflow = Files.readString(root.resolve(
        ".github/workflows/gradle.yml"));
    String gradle = section(
        workflow,
        "  gradle-verification:\n",
        "  jmh-verification:\n");
    String jmh = section(
        workflow,
        "  jmh-verification:\n",
        "  sympy-runtime-verification:\n");
    String sympy = section(
        workflow,
        "  sympy-runtime-verification:\n",
        "  maven-product-verification:\n");

    assertTrue(gradle.contains(
        "ORG_GRADLE_PROJECT_separateSympyRuntimeAuthority: 'true'"));
    assertTrue(gradle.contains(
        "REGELSUCHE_SEPARATE_SYMPY_RUNTIME_AUTHORITY: required-by-workflow"));
    assertFalse(gradle.contains("sympyRuntimeAuthority"),
        "the loaded correctness runner must not execute the isolated runtime graph");

    assertTrue(jmh.contains(
        "bash gradle/run-isolated-jmh-authority.sh"));
    assertFalse(jmh.contains(
        "bash gradle/run-isolated-sympy-runtime-authority.sh"),
        "runtime tests must not remain serialized after benchmark measurement");
    assertFalse(jmh.contains("sympy-runtime-checkout"));
    assertFalse(jmh.contains("needs:"),
        "the benchmark authority must start independently");

    assertTrue(sympy.contains("actions/checkout@"),
        "the runtime authority must start from a separate fresh checkout");
    assertTrue(sympy.contains("gradle/actions/setup-gradle@"));
    assertTrue(sympy.contains(
        "bash gradle/run-isolated-sympy-runtime-authority.sh"));
    assertFalse(sympy.contains(
        "bash gradle/run-isolated-jmh-authority.sh"));
    assertFalse(sympy.contains("working-directory: sympy-runtime-checkout"));
    assertFalse(sympy.contains("needs:"),
        "the runtime authority must start concurrently with JMH");
    assertTrue(sympy.contains(
        "regelsuche-math-sympy/build/test-results/**"));
    assertTrue(sympy.contains(
        "regelsuche-math-sympy/build/reports/tests/**"));
    assertTrue(sympy.contains(
        "regelsuche-math-sympy/build/reports/jacoco/**"));
    assertFalse(sympy.contains(
        "REGELSUCHE_SEPARATE_SYMPY_RUNTIME_AUTHORITY"),
        "the isolated authority must not inherit the delegation marker");
    assertTrue(sympy.contains("if-no-files-found: error"));
  }

  @Test
  void moduleBoundaryIsFailClosedAndKeepsLocalCiCheckComplete()
      throws IOException {
    Path root = repositoryRoot();
    String build = Files.readString(root.resolve(
        "regelsuche-math-sympy/build.gradle"));
    String entrypoint = Files.readString(root.resolve(
        "gradle/run-isolated-sympy-runtime-authority.sh"));
    String bootstrapTest = Files.readString(root.resolve(
        "regelsuche-math-sympy/src/test/java/de/regelsuche/math/sympy/"
            + "GraalPySymPyRuntimeBootstrapTest.java"));
    String recoveryTest = Files.readString(root.resolve(
        "regelsuche-math-sympy/src/test/java/de/regelsuche/math/sympy/"
            + "GraalPySymPyRuntimeTest.java"));

    assertTrue(build.contains(
        "providers.gradleProperty(\n    'separateSympyRuntimeAuthority'"));
    assertTrue(build.contains(
        "REGELSUCHE_SEPARATE_SYMPY_RUNTIME_AUTHORITY"));
    assertTrue(build.contains("required-by-workflow"));
    assertTrue(build.contains(
        "separateSympyRuntimeAuthority != sympyRuntimeAuthorityAuthorized"));
    assertTrue(build.contains("tasks.named('test').configure"));
    assertTrue(build.contains("tasks.named('jacocoTestReport').configure"));
    assertTrue(build.contains("tasks.register('sympyRuntimeAuthority')"));
    assertTrue(build.contains(
        "dependsOn tasks.named('jacocoTestReport')"));
    assertTrue(build.contains("!tasks.named('test').get().enabled"));
    assertTrue(build.contains(
        "!tasks.named('jacocoTestReport').get().enabled"));
    assertFalse(build.contains("maxParallelForks"));
    assertFalse(build.contains("filter {"));

    assertTrue(entrypoint.contains("set -euo pipefail"));
    assertTrue(entrypoint.contains(
        ":regelsuche-math-sympy:sympyRuntimeAuthority"));
    assertTrue(entrypoint.contains("--no-configuration-cache"));
    assertTrue(entrypoint.contains("separateSympyRuntimeAuthority"));
    assertTrue(entrypoint.contains(
        "REGELSUCHE_SEPARATE_SYMPY_RUNTIME_AUTHORITY"));

    assertTrue(bootstrapTest.contains("Duration.ofSeconds(20)"),
        "the original cold-start assertion must not be relaxed");
    assertTrue(recoveryTest.contains("Duration.ofSeconds(20)"),
        "the timeout-recovery assertion must not be relaxed");
    assertFalse(bootstrapTest.contains("@Disabled"));
    assertFalse(recoveryTest.contains("@Disabled"));
  }

  private static String section(String text, String start, String end) {
    int from = text.indexOf(start);
    assertTrue(from >= 0, () -> "missing workflow section " + start.strip());
    int to = text.indexOf(end, from + start.length());
    assertTrue(to > from, () -> "missing workflow section " + end.strip());
    return text.substring(from, to);
  }

  private static Path repositoryRoot() {
    String configured = System.getProperty("regelsuche.repositoryRoot");
    assertNotNull(configured,
        "Maven must expose maven.multiModuleProjectDirectory to tests");
    return Path.of(configured).toAbsolutePath().normalize();
  }
}
