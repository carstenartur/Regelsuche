package de.regelsuche.build;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class MavenEmbeddedGraalPyMemoryContractTest {
  @Test
  void nativeRuntimeQualificationUsesTheSameBoundedHeapAndForkPolicy()
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
        "native runtime lifecycle test classes must not share one worker indefinitely");
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
        maven.contains("<reuseForks>false</reuseForks>"),
        "Maven must retire native runtime state between test classes");
  }

  private static Path repositoryRoot() {
    String configured = System.getProperty("regelsuche.repositoryRoot");
    assertNotNull(
        configured,
        "Maven must expose maven.multiModuleProjectDirectory to tests");
    return Path.of(configured).toAbsolutePath().normalize();
  }
}
