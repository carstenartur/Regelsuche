package de.regelsuche.build;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class MavenEmbeddedGraalPyMemoryContractTest {
  @Test
  void nativeRuntimeQualificationUsesBoundedHeapAndExplicitForkPolicies()
      throws IOException {
    Path root = repositoryRoot();
    String gradle = Files.readString(
        root.resolve("regelsuche-math-sympy/build.gradle"));
    String maven = Files.readString(
        root.resolve("regelsuche-math-sympy/pom.xml"));

    assertTrue(
        gradle.contains("def embeddedRuntimeInitialHeap = '512m'"),
        "Gradle must declare the embedded-runtime initial heap once");
    assertTrue(
        gradle.contains("def embeddedRuntimeMaximumHeap = '2g'"),
        "Gradle must declare the bounded embedded-runtime maximum heap once");
    assertTrue(
        gradle.contains("minHeapSize = embeddedRuntimeInitialHeap"),
        "Gradle tests must use the declared initial heap");
    assertTrue(
        gradle.contains("maxHeapSize = embeddedRuntimeMaximumHeap"),
        "Gradle tests must use the declared maximum heap");
    assertTrue(
        gradle.contains("forkEvery = 1"),
        "Gradle must retire native runtime state between lifecycle test classes");
    assertTrue(
        gradle.contains("\"-Xms${embeddedRuntimeInitialHeap}\""),
        "JMH must use the same initial heap contract");
    assertTrue(
        gradle.contains("\"-Xmx${embeddedRuntimeMaximumHeap}\""),
        "JMH must use the same maximum heap contract");

    assertTrue(
        maven.contains(
            "<argLine>@{argLine} --enable-native-access=ALL-UNNAMED -Xms512m -Xmx2g</argLine>"),
        "Maven tests must preserve JaCoCo, native access and the same heap bound");
    assertTrue(
        maven.contains("<forkCount>1</forkCount>"),
        "Maven must use one bounded test fork at a time");
    assertTrue(
        maven.contains("<reuseForks>true</reuseForks>"),
        "Maven must preserve one JaCoCo-complete worker with the enlarged heap");
  }

  @Test
  void jmhReceivesThePinnedControlInterpreterWithoutExecTaskApis()
      throws IOException {
    Path root = repositoryRoot();
    String gradle = Files.readString(
        root.resolve("regelsuche-math-sympy/build.gradle"));
    String processEngine = Files.readString(root.resolve(
        "regelsuche-math-sympy/src/main/java/de/regelsuche/math/sympy/"
            + "ProcessSymPyFactorizationEngine.java"));

    assertTrue(
        gradle.contains("def symPyPythonProperty = 'regelsuche.sympy.python'"),
        "Gradle must name the JVM property shared with the process engine");
    assertTrue(
        gradle.contains(
            "\"-D${symPyPythonProperty}=${verificationPython.get().asFile.absolutePath}\""),
        "the JMH fork must receive the prepared CPython executable as a JVM property");
    assertTrue(
        gradle.contains("tasks.named('jmh') { task ->")
            && gradle.contains(
                "task.dependsOn rootProject.tasks.named('prepareVerificationEnvironment')"),
        "JMH must prepare the pinned verification environment before it starts");
    assertTrue(
        !gradle.contains(
            "tasks.named('jmh') { task ->\n    configureTestProcessBaseline(task)"),
        "JMHTask must not be configured through the unsupported environment method");
    assertTrue(
        processEngine.contains(
            "public static final String PYTHON_EXECUTABLE_PROPERTY ="),
        "the process control backend must expose the shared property contract");
    assertTrue(
        processEngine.contains(
            "System.getProperty(PYTHON_EXECUTABLE_PROPERTY)"),
        "the benchmark fork property must take part in executable resolution");
  }

  private static Path repositoryRoot() {
    String configured = System.getProperty("regelsuche.repositoryRoot");
    assertNotNull(
        configured,
        "Maven must expose maven.multiModuleProjectDirectory to tests");
    return Path.of(configured).toAbsolutePath().normalize();
  }
}
