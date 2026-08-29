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
  void projectParallelCiMovesTheUnchangedRuntimeTestsAfterIdleJmh()
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
        "  maven-product-verification:\n");

    assertTrue(gradle.contains(
        "ORG_GRADLE_PROJECT_separateSympyRuntimeAuthority: 'true'"));
    assertTrue(gradle.contains(
        "REGELSUCHE_SEPARATE_SYMPY_RUNTIME_AUTHORITY: required-by-workflow"));
    assertFalse(gradle.contains("sympyRuntimeAuthority"),
        "the loaded correctness runner must not execute the isolated runtime graph");

    int benchmark = jmh.indexOf(
        "bash gradle/run-isolated-jmh-authority.sh");
    int secondCheckout = jmh.indexOf("path: sympy-runtime-checkout");
    int runtimeTests = jmh.indexOf(
        "bash gradle/run-isolated-sympy-runtime-authority.sh");
    assertTrue(benchmark >= 0, "the complete JMH authority is missing");
    assertTrue(secondCheckout > benchmark,
        "the runtime tests must use a second checkout after benchmark measurement");
    assertTrue(runtimeTests > secondCheckout,
        "the runtime tests must execute from the second fresh checkout");
    assertTrue(jmh.contains("working-directory: sympy-runtime-checkout"));
    assertTrue(jmh.contains(
        "sympy-runtime-checkout/regelsuche-math-sympy/build/test-results/**"));
    assertTrue(jmh.contains(
        "sympy-runtime-checkout/regelsuche-math-sympy/build/reports/tests/**"));
    assertTrue(jmh.contains(
        "sympy-runtime-checkout/regelsuche-math-sympy/build/reports/jacoco/**"));
    assertFalse(jmh.contains(
        "REGELSUCHE_SEPARATE_SYMPY_RUNTIME_AUTHORITY"),
        "the isolated authority must not inherit the delegation marker");
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
