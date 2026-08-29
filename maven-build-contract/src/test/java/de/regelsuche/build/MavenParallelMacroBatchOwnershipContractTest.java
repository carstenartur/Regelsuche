package de.regelsuche.build;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class MavenParallelMacroBatchOwnershipContractTest {

  @Test
  void reusableMacroJavaExecutionUsesTheOwningAppProjectLock()
      throws IOException {
    String script = Files.readString(repositoryRoot().resolve(
        "gradle/candidate-independent-reusable-macro-batch.gradle"));

    assertTrue(script.contains(
        "def macroBatchRuntimeClasspath = "
            + "macroBatchAppProject.providers.provider"),
        "the runtime classpath provider must belong to :app");
    assertEquals(2, occurrences(
        script,
        "macroBatchAppProject.tasks.register("),
        "both JavaExec producers must be owned by :app");
    assertTrue(script.contains(
        "'executeCandidateIndependentReusableMacroBatchFirstRaw',\n"
            + "        JavaExec"));
    assertTrue(script.contains(
        "'executeCandidateIndependentReusableMacroBatchSecondRaw',\n"
            + "        JavaExec"));
    assertTrue(script.contains(
        "task.dependsOn 'classes', "
            + "':verifyCandidateIndependentCaseCorpus'"),
        "the app-owned task must retain app compilation and corpus verification");
    assertTrue(script.contains(
        "task.classpath = macroBatchAppProject.files("
            + "macroBatchRuntimeClasspath)"));

    assertFalse(script.contains(
        "def macroBatchRuntimeClasspath = providers.provider"),
        "a root-owned provider must not resolve :app configurations");
    assertFalse(script.contains(
        "task.dependsOn ':app:classes'"),
        "an app-owned task must depend on its local classes task");
    assertFalse(script.contains(
        "tasks.register(\n"
            + "    'executeCandidateIndependentReusableMacroBatch"),
        "the root project must not own JavaExec tasks that resolve :app runtimeClasspath");

    assertTrue(script.contains(
        "tasks.register(\n"
            + "    'runCandidateIndependentReusableMacroBatchFirst', Exec"));
    assertTrue(script.contains(
        "tasks.register(\n"
            + "    'runCandidateIndependentReusableMacroBatchSecond', Exec"));
    assertTrue(script.contains(
        "tasks.register(\n"
            + "    'verifyCandidateIndependentReusableMacroBatch', Exec"));
    assertTrue(script.contains(
        "macroBatchVerifier.asFile.absolutePath"),
        "ownership repair must retain the independent verifier");
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
