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

class MavenParallelAppBenchmarkOwnershipContractTest {

  @Test
  void appBenchmarkAuthoritiesKeepForeignClasspathOutOfRootTasks()
      throws IOException {
    List<AppContract> contracts = List.of(
        new AppContract(
            "gradle/candidate-independent-reusable-macro-batch.gradle",
            "macroBatchAppProject",
            "macroBatchRuntimeClasspath",
            "executeCandidateIndependentReusableMacroBatchFirstRaw",
            "executeCandidateIndependentReusableMacroBatchSecondRaw",
            "runCandidateIndependentReusableMacroBatchFirst",
            "macroBatchVerifier.asFile.absolutePath"),
        new AppContract(
            "gradle/paired-task-utility-verification.gradle",
            "pairedUtilityAppProject",
            "pairedUtilityRuntimeClasspath",
            "executePairedTaskUtilityFirstRaw",
            "executePairedTaskUtilitySecondRaw",
            "runPairedTaskUtilityFirst",
            "pairedUtilityVerifier.asFile.absolutePath"),
        new AppContract(
            "gradle/macro-candidate-panel-verification.gradle",
            "candidatePanelAppProject",
            "candidatePanelRuntimeClasspath",
            "executeMacroCandidatePanelFirstRaw",
            "executeMacroCandidatePanelSecondRaw",
            "runMacroCandidatePanelFirst",
            "candidatePanelVerifier.asFile.absolutePath")
    );

    for (AppContract contract : contracts) {
      String script = Files.readString(repositoryRoot().resolve(contract.path()));
      assertTrue(script.contains(
          "def " + contract.classpathName() + " = "
              + contract.projectName() + ".providers.provider"),
          contract.path() + " must create the provider in :app");
      assertEquals(2, occurrences(
          script,
          contract.projectName() + ".tasks.register("),
          contract.path() + " must own both JavaExec authorities in :app");
      assertTrue(script.contains(
          "task.classpath = " + contract.projectName() + ".files("));
      assertTrue(script.contains("task.dependsOn 'classes'"));
      assertFalse(script.contains("task.dependsOn ':app:classes'"));
      assertFalse(script.contains(
          "def " + contract.classpathName() + " = providers.provider"));

      String firstAlias = section(
          script,
          "def " + contract.firstTask() + " = tasks.register(",
          "def " + contract.secondTask() + " = tasks.register(");
      String secondAlias = section(
          script,
          "def " + contract.secondTask() + " = tasks.register(",
          "def " + contract.nextTask() + " = tasks.register(");
      assertFalse(firstAlias.contains("JavaExec"));
      assertFalse(secondAlias.contains("JavaExec"));
      assertTrue(firstAlias.contains(
          "dependsOn " + contract.firstTask() + "Authority"));
      assertTrue(secondAlias.contains(
          "dependsOn " + contract.secondTask() + "Authority"));
      assertTrue(script.contains(contract.verifierCall()),
          contract.path() + " must retain independent verification");
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

  private record AppContract(
      String path,
      String projectName,
      String classpathName,
      String firstTask,
      String secondTask,
      String nextTask,
      String verifierCall
  ) { }
}
