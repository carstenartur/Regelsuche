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

    assertTrue(jmh.contains("-pl regelsuche-jmh,regelsuche-sympy-jmh -am -DskipTests verify"));
    assertFalse(jmh.contains("setup-gradle"));
    assertFalse(jmh.contains(
        "bash gradle/run-isolated-sympy-runtime-authority.sh"),
        "runtime tests must not remain serialized after benchmark measurement");
    assertFalse(jmh.contains("sympy-runtime-checkout"));
    assertTrue(jmh.contains("needs: preflight-contracts"),
        "the benchmark authority starts independently after the fast preflight");

    assertTrue(sympy.contains("actions/checkout@"),
        "the runtime authority must start from a separate fresh checkout");
    assertFalse(sympy.contains("gradle/actions/setup-gradle@"));
    assertFalse(sympy.contains("apt-get"));
    assertTrue(sympy.contains(
        "mvn --batch-mode --no-transfer-progress -Pfull -pl regelsuche-math-sympy -am verify"));
    assertFalse(sympy.contains("gradle/run-isolated-sympy-runtime-authority.sh"));
    assertFalse(sympy.contains(
        "bash gradle/run-isolated-jmh-authority.sh"));
    assertFalse(sympy.contains("working-directory: sympy-runtime-checkout"));
    assertTrue(sympy.contains("needs: preflight-contracts"),
        "the runtime authority starts concurrently with JMH after the fast preflight");
    assertTrue(sympy.contains(
        "regelsuche-math-sympy/target/failsafe-reports/**"));
    assertTrue(sympy.contains(
        "regelsuche-math-sympy/target/reports/sympy-runtime/**"));
    assertTrue(sympy.contains(
        "regelsuche-math-sympy/target/site/jacoco/**"));
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
    assertFalse(Files.exists(root.resolve("gradle/run-isolated-sympy-runtime-authority.sh")));
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
    assertFalse(build.contains("tasks.register('sympyRuntimeAuthority')"));
    assertFalse(build.contains("maxParallelForks"));
    assertFalse(build.contains("filter {"));

    assertTrue(bootstrapTest.contains("Duration.ofSeconds(20)"),
        "the bootstrap cold-start assertion must remain bounded");
    assertTrue(recoveryTest.contains(
        "private static final Duration COMPLETION_TIMEOUT = Duration.ofMinutes(1);"),
        "recovery completion must remain explicitly bounded");
    assertTrue(recoveryTest.contains("Duration.ofNanos(1)"),
        "the deliberate timeout stimulus must not be relaxed");
    assertTrue(
        recoveryTest.split("COMPLETION_TIMEOUT", -1).length - 1 == 4,
        "all three successful recovery phases must use the bounded timeout");
    assertFalse(recoveryTest.contains("Duration.ofSeconds(20)"),
        "recovery correctness must not depend on a cold-start SLO");
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
