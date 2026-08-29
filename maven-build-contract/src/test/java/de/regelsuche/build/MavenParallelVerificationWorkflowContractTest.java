package de.regelsuche.build;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class MavenParallelVerificationWorkflowContractTest {
  private static final String PLAYWRIGHT_VERSION = "1.60.0";
  private static final String FULL_MAVEN_COMMAND =
      "mvn --batch-mode --no-transfer-progress -Pfull verify";

  @Test
  void independentAuthoritiesRunInParallelAndConvergeFailClosed()
      throws IOException {
    String workflow = Files.readString(repositoryRoot().resolve(
        ".github/workflows/gradle.yml"));
    String gradle = section(
        workflow,
        "  gradle-verification:\n",
        "  maven-product-verification:\n");
    String maven = section(
        workflow,
        "  maven-product-verification:\n",
        "  verification:\n");
    String convergence = section(
        workflow,
        "  verification:\n",
        "  publish-pages:\n");
    int publicationStart = workflow.indexOf("  publish-pages:\n");
    assertTrue(publicationStart >= 0,
        "missing workflow section publish-pages");
    String publication = workflow.substring(publicationStart);

    assertTrue(gradle.contains("actions/checkout@"),
        "the Gradle authority must start from its own checkout");
    assertTrue(gradle.contains(
        "./gradlew --no-daemon -p playwright-bootstrap "
            + "installPlaywrightHostDependencies --console=plain"));
    assertTrue(gradle.contains(
        "./gradlew --no-daemon --no-configuration-cache "
            + "\"$REGELSUCHE_CI_TASK\""));
    assertFalse(gradle.contains(FULL_MAVEN_COMMAND),
        "Maven must not remain serialized behind the Gradle authority");

    assertTrue(maven.contains("actions/checkout@"),
        "the Maven authority must start from a separate fresh checkout");
    assertTrue(maven.contains("cache: maven"),
        "only the Maven dependency repository should be restored");
    assertTrue(maven.contains(
        "-f playwright-bootstrap/pom.xml"));
    assertTrue(maven.contains(
        "org.codehaus.mojo:exec-maven-plugin:3.5.0:java"));
    assertTrue(maven.contains(FULL_MAVEN_COMMAND),
        "the complete product and Docker contract must remain unchanged");
    assertFalse(maven.contains("needs: gradle-verification"),
        "Maven must begin concurrently rather than wait for Gradle");
    assertFalse(maven.contains("actions/download-artifact@"),
        "Maven must not consume Gradle build output or test evidence");
    assertFalse(maven.contains("./gradlew"),
        "the Maven authority must not execute or depend on the Gradle reactor");
    assertEquals(1, occurrences(workflow, FULL_MAVEN_COMMAND),
        "the full Maven contract must execute exactly once per workflow run");

    String compactConvergence = compact(convergence);
    assertTrue(compactConvergence.contains(
        "needs: [gradle-verification, maven-product-verification]"));
    assertTrue(compactConvergence.contains(
        "needs.gradle-verification.result != 'success' || "
            + "(github.event_name != 'create' && "
            + "needs.maven-product-verification.result != 'success')"),
        "the stable required check must reject either incomplete authority");
    assertTrue(convergence.contains("run: exit 1"),
        "an incomplete authority set must fail rather than become skipped-success");
    assertFalse(convergence.contains("actions/checkout@"),
        "the convergence job is orchestration, not a third verification build");
    assertFalse(convergence.contains("./gradlew"));
    assertFalse(convergence.contains("mvn "));

    assertTrue(publication.contains("needs: verification"),
        "publication must wait for the converged Gradle and Maven authorities");
    assertTrue(workflow.contains(
        "github.event_name == 'create' && "
            + "'Showcase train-freeze authority v1' || "
            + "'Checkout-local ciCheck'"),
        "the existing merge-governance check context must remain stable");
  }

  @Test
  void mavenBootstrapUsesTheSamePinnedPlaywrightCliAsGradleAndTheApp()
      throws IOException {
    Path root = repositoryRoot();
    String appBuild = Files.readString(root.resolve("app/build.gradle"));
    String gradleBootstrap = Files.readString(root.resolve(
        "playwright-bootstrap/build.gradle"));
    String mavenBootstrap = Files.readString(root.resolve(
        "playwright-bootstrap/pom.xml"));

    String coordinate =
        "com.microsoft.playwright:playwright:" + PLAYWRIGHT_VERSION;
    assertEquals(2, occurrences(appBuild, coordinate));
    assertTrue(gradleBootstrap.contains(
        "playwrightVersion = '" + PLAYWRIGHT_VERSION + "'"));
    assertTrue(mavenBootstrap.contains(
        "<playwright.version>" + PLAYWRIGHT_VERSION
            + "</playwright.version>"));
    assertTrue(mavenBootstrap.contains(
        "<exec-maven-plugin.version>3.5.0"
            + "</exec-maven-plugin.version>"));
    assertTrue(mavenBootstrap.contains(
        "<mainClass>com.microsoft.playwright.CLI</mainClass>"));
    assertTrue(mavenBootstrap.contains("<argument>install-deps</argument>"));
    assertTrue(mavenBootstrap.contains("<argument>chromium</argument>"));
    assertFalse(mavenBootstrap.contains("<modules>"),
        "the host bootstrap must remain outside the production Maven reactor");
  }

  private static String section(String text, String start, String end) {
    int from = text.indexOf(start);
    assertTrue(from >= 0, () -> "missing workflow section " + start.strip());
    int to = text.indexOf(end, from + start.length());
    assertTrue(to > from, () -> "missing workflow section " + end.strip());
    return text.substring(from, to);
  }

  private static String compact(String text) {
    return text.replaceAll("\\s+", " ").trim();
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
