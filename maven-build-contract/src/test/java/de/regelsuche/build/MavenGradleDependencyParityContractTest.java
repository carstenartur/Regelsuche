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
    String content = Files.readString(root.resolve(buildFile));

    assertTrue(
        content.contains("id 'org.graalvm.python' version '" + version + "'"),
        () -> buildFile + " must pin the GraalPy plugin to " + version);
    assertTrue(
        content.contains("polyglotVersion = '" + version + "'"),
        () -> buildFile + " must pin the injected runtime to " + version);
    assertTrue(
        content.contains("community = false"),
        () -> buildFile
            + " must select the canonical OSS GraalPy artifact used by Maven");
    assertTrue(
        content.contains("'sympy==1.14.0'"),
        () -> buildFile + " must pin SymPy 1.14.0");
    assertTrue(
        content.contains("'mpmath==1.3.0'"),
        () -> buildFile + " must pin mpmath 1.3.0");
  }

  @Test
  void embeddedGraalpyNativeAccessIsExplicitInBothReactors()
      throws IOException {
    Path root = repositoryRoot();
    String gradle = Files.readString(
        root.resolve("regelsuche-math-sympy/build.gradle"));
    String maven = Files.readString(
        root.resolve("regelsuche-math-sympy/pom.xml"));
    String argument = "--enable-native-access=ALL-UNNAMED";

    assertTrue(
        gradle.contains("def nativeAccessArgument = '" + argument + "'"),
        "Gradle must name the required classpath native-access authority");
    assertTrue(
        gradle.contains("jvmArgs nativeAccessArgument"),
        "Gradle tests must authorize the pinned native Python modules");
    assertTrue(
        gradle.contains("jvmArgs = [nativeAccessArgument]"),
        "Gradle JMH forks must authorize the same native modules");
    assertTrue(
        maven.contains(
            "<argLine>@{argLine} " + argument + "</argLine>"),
        "Maven tests must preserve JaCoCo and authorize native access");
  }

  @Test
  void isolatedGraalpyNativeModulesBindTheirHostToolchain()
      throws IOException {
    Path root = repositoryRoot();
    String runtime = Files.readString(root.resolve(
        "regelsuche-math-sympy/src/main/java/de/regelsuche/math/sympy/"
            + "GraalPySymPyRuntime.java"));

    assertTrue(
        runtime.contains(
            ".option(\"python.IsolateNativeModules\", \"true\")"),
        "the embedded runtime must isolate native modules before replacement");
    assertTrue(
        runtime.contains(".allowCreateProcess(true)"),
        "GraalPy must be allowed to invoke patchelf for ELF relocation");
    assertTrue(
        runtime.contains(
            ".environment(\"PATH\", hostExecutablePath())"),
        "the context must expose only its explicit executable search path");
    assertTrue(
        !runtime.contains(".allowEnvironmentAccess("),
        "the embedded adapter must not inherit the complete host environment");

    // GraalPy's VFS rejects writes and deletes even when host IO is enabled.
    // IsolateNativeModules therefore needs the supported writable extraction path.
    assertTrue(
        runtime.contains(
            "GraalPyResources.extractVirtualFileSystemResources("),
        "the lock-bound VFS resources must be extracted before native isolation");
    assertTrue(
        runtime.contains("GraalPyResources.forExternalDirectory("),
        "replacement contexts must use GraalPy's writable external-directory configuration");
    assertTrue(
        runtime.contains("Files.createTempDirectory("),
        "each embedded runtime must own a private extracted resource tree");
    assertTrue(
        runtime.contains("deleteResources(externalResourcesDirectory)"),
        "closing the runtime must remove its private extracted resources");
    assertTrue(
        !runtime.contains(
            ".apply(GraalPyResources.forVirtualFileSystem(fileSystem))"),
        "native isolation must not run directly inside the read-only VFS");

    for (String workflow : List.of(
        ".github/workflows/gradle.yml",
        ".github/workflows/release.yml")) {
      String content = Files.readString(root.resolve(workflow));
      assertTrue(
          content.contains("patchelf=0.14.3-1"),
          () -> workflow
              + " must provision the pinned Ubuntu 22.04 patchelf package");
    }

    String adapterDocumentation = Files.readString(
        root.resolve("docs/sympy-factorization-adapter.md"));
    String testingDocumentation = Files.readString(
        root.resolve("docs/testing.md"));
    assertTrue(
        adapterDocumentation.contains("patchelf=0.14.3-1"),
        "the adapter documentation must expose the native-isolation prerequisite");
    assertTrue(
        adapterDocumentation.contains(
            "extractVirtualFileSystemResources"),
        "the adapter documentation must explain the writable extraction boundary");
    assertTrue(
        adapterDocumentation.contains("forExternalDirectory"),
        "the adapter documentation must name the supported runtime configuration");
    assertTrue(
        testingDocumentation.contains("patchelf=0.14.3-1"),
        "the checkout testing documentation must expose the same prerequisite");
  }

  @Test
  void embeddedGraalpyLockIsSharedAndComplete()
      throws IOException {
    Path root = repositoryRoot();
    Path module = root.resolve("regelsuche-math-sympy");
    String version = mavenProperty(root, "graalpy.version");
    String gradle = Files.readString(module.resolve("build.gradle"));
    String maven = Files.readString(module.resolve("pom.xml"));
    Path lockPath = module.resolve("graalpy.lock");

    assertTrue(
        gradle.contains(
            "graalPyLockFile = file(\"$projectDir/graalpy.lock\")"),
        "Gradle must consume the committed module-local GraalPy lock");
    assertTrue(
        maven.contains(
            "<graalPyLockFile>${project.basedir}/graalpy.lock</graalPyLockFile>"),
        "Maven must consume the same committed GraalPy lock");
    assertTrue(Files.isRegularFile(lockPath),
        "the shared GraalPy lock must be committed");

    String lock = Files.readString(lockPath);
    assertTrue(lock.contains("# graalpy-version: " + version),
        "the lock must bind the managed GraalPy version");
    assertTrue(lock.contains(
        "# input-packages: mpmath==1.3.0,sympy==1.14.0"),
        "the lock must bind the configured direct Python packages");
    assertEquals(
        List.of("mpmath==1.3.0", "sympy==1.14.0"),
        Files.readAllLines(lockPath).stream()
            .map(String::trim)
            .filter(line -> !line.isBlank() && !line.startsWith("#"))
            .toList(),
        "the lock must retain the exact resolved Python package closure");
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
