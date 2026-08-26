package de.regelsuche.build;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class MavenGradleDependencyParityContractTest {
  private static final List<String> JACKSON_DATABIND_BUILD_FILES = List.of(
      "regelsuche-core/build.gradle",
      "regelsuche-discovery/build.gradle",
      "regelsuche-learning/build.gradle",
      "regelsuche-math-sympy/build.gradle",
      "regelsuche-quality/build.gradle",
      "regelsuche-release/build.gradle",
      "regelsuche-solver-ir/build.gradle");

  @Test
  void jacksonVersionsStayAlignedAcrossMavenAndGradle()
      throws IOException {
    Path root = repositoryRoot();
    String version = mavenProperty(root, "jackson.version");

    for (String relative : JACKSON_DATABIND_BUILD_FILES) {
      assertDependency(root, relative,
          "com.fasterxml.jackson.core:jackson-databind", version);
    }
    assertDependency(root, "regelsuche-core/build.gradle",
        "com.fasterxml.jackson.dataformat:jackson-dataformat-yaml", version);
    assertDependency(root, "app/build.gradle",
        "com.fasterxml.jackson.dataformat:jackson-dataformat-yaml", version);
  }

  @Test
  void graalvmVersionStaysAlignedAcrossMavenAndGradle()
      throws IOException {
    Path root = repositoryRoot();
    assertDependency(root, "app/build.gradle",
        "org.graalvm.polyglot:polyglot",
        mavenProperty(root, "graalvm.polyglot.version"));
  }

  @Test
  void embeddedGraalpyVersionStaysAlignedAcrossMavenAndGradle()
      throws IOException {
    Path root = repositoryRoot();
    String version = mavenProperty(root, "graalpy.version");
    String buildFile = "regelsuche-math-sympy/build.gradle";

    assertDependency(root, buildFile,
        "org.graalvm.polyglot:python", version);
    assertDependency(root, buildFile,
        "org.graalvm.python:python-embedding", version);
    String content = Files.readString(root.resolve(buildFile));
    assertTrue(
        content.contains("id 'org.graalvm.python' version '" + version + "'"),
        () -> buildFile + " must pin the GraalPy plugin to " + version);
    assertTrue(
        content.contains("'sympy==1.14.0'"),
        () -> buildFile + " must pin SymPy 1.14.0");
    assertTrue(
        content.contains("'mpmath==1.3.0'"),
        () -> buildFile + " must pin mpmath 1.3.0");
  }

  private static void assertDependency(
      Path root,
      String relative,
      String coordinate,
      String version
  ) throws IOException {
    String content = Files.readString(root.resolve(relative));
    String declaration = "'" + coordinate + ":" + version + "'";
    assertTrue(
        content.contains(declaration),
        () -> relative + " must declare " + declaration);
  }

  private static String mavenProperty(Path root, String name)
      throws IOException {
    String pom = Files.readString(root.resolve("pom.xml"));
    String startTag = "<" + name + ">";
    String endTag = "</" + name + ">";
    int start = pom.indexOf(startTag);
    int end = pom.indexOf(endTag);
    assertTrue(start >= 0 && end > start,
        () -> "Missing Maven property " + name);
    String value = pom.substring(start + startTag.length(), end).trim();
    assertTrue(!value.isBlank(), () -> name + " must not be blank");
    assertTrue(pom.indexOf(startTag, start + 1) < 0,
        () -> name + " must be declared once");
    return value;
  }

  private static Path repositoryRoot() {
    String configured = System.getProperty("regelsuche.repositoryRoot");
    assertNotNull(
        configured,
        "Maven must expose maven.multiModuleProjectDirectory to tests");
    return Path.of(configured).toAbsolutePath().normalize();
  }
}
