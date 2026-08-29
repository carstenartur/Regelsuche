package de.regelsuche.build;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class MavenParallelReleaseAdapterOwnershipContractTest {

  @Test
  void frozenReleaseAdaptersResolveTheirOwnRuntimeClasspath()
      throws IOException {
    List<AdapterContract> adapters = List.of(
        new AdapterContract(
            "gradle/candidate-independent-finite-sequence-adapter.gradle",
            "finiteAdapterReleaseProject",
            "finiteAdapterRuntimeClasspath",
            "runCandidateIndependentFiniteSequenceAdapter",
            "verifyCandidateIndependentFiniteSequenceAdapter"),
        new AdapterContract(
            "gradle/candidate-independent-linear-recurrence-adapter.gradle",
            "recurrenceAdapterReleaseProject",
            "recurrenceAdapterRuntimeClasspath",
            "runCandidateIndependentLinearRecurrenceAdapter",
            "verifyCandidateIndependentLinearRecurrenceAdapter"),
        new AdapterContract(
            "gradle/candidate-independent-rational-assumption-adapter.gradle",
            "rationalAdapterReleaseProject",
            "rationalAdapterRuntimeClasspath",
            "runCandidateIndependentRationalAssumptionAdapter",
            "verifyCandidateIndependentRationalAssumptionAdapter")
    );

    for (AdapterContract adapter : adapters) {
      String script = Files.readString(repositoryRoot().resolve(adapter.path()));
      assertTrue(script.contains(
          "def " + adapter.classpathName() + " = "
              + adapter.projectName() + ".providers.provider"),
          adapter.path() + " must create its provider in the owning project");
      assertEquals(2, occurrences(
          script,
          adapter.projectName() + ".tasks.register("),
          adapter.path() + " must own both JavaExec authorities");
      assertTrue(script.contains(
          "task.classpath = " + adapter.projectName() + ".files("),
          adapter.path() + " must use the owning project's file collection");
      assertTrue(script.contains("task.dependsOn 'classes'"),
          adapter.path() + " must retain local production compilation");
      assertFalse(script.contains("task.dependsOn ':regelsuche-release:classes'"));
      assertFalse(script.contains(
          "def " + adapter.classpathName() + " = providers.provider"),
          adapter.path() + " must not use a root-owned foreign provider");

      String firstAlias = section(
          script,
          "def " + adapter.taskPrefix() + "First = tasks.register(",
          "def " + adapter.taskPrefix() + "Second = tasks.register(");
      String secondAlias = section(
          script,
          "def " + adapter.taskPrefix() + "Second = tasks.register(",
          "def " + adapter.verifyTask() + " = tasks.register(");
      assertFalse(firstAlias.contains("JavaExec"),
          adapter.path() + " first root alias must have no Java action");
      assertFalse(secondAlias.contains("JavaExec"),
          adapter.path() + " second root alias must have no Java action");
      assertTrue(firstAlias.contains(
          "dependsOn " + adapter.taskPrefix() + "FirstAuthority"));
      assertTrue(secondAlias.contains(
          "dependsOn " + adapter.taskPrefix() + "SecondAuthority"));
      assertTrue(script.contains("Verifier"),
          adapter.path() + " must retain independent verifier wiring");
    }
  }

  private static String section(String text, String start, String end) {
    int from = text.indexOf(start);
    assertTrue(from >= 0, () -> "missing section " + start);
    int to = text.indexOf(end, from + start.length());
    assertTrue(to > from, () -> "missing section " + end);
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

  private record AdapterContract(
      String path,
      String projectName,
      String classpathName,
      String taskPrefix,
      String verifyTask
  ) { }
}
