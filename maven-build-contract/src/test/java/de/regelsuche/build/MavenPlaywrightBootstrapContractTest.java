package de.regelsuche.build;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class MavenPlaywrightBootstrapContractTest {
  private static final String PLAYWRIGHT_VERSION = "1.60.0";
  private static final String PLAYWRIGHT_COORDINATE =
      "com.microsoft.playwright:playwright:" + PLAYWRIGHT_VERSION;

  @Test
  void ciBootstrapUsesThePinnedCliWithoutCompilingTheMainReactor()
      throws IOException {
    Path root = repositoryRoot();
    String appBuild = Files.readString(root.resolve("app/build.gradle"));
    String bootstrap = Files.readString(
        root.resolve("playwright-bootstrap/build.gradle"));
    String settings = Files.readString(
        root.resolve("playwright-bootstrap/settings.gradle"));
    String workflow = Files.readString(
        root.resolve(".github/workflows/gradle.yml"));

    assertEquals(2, occurrences(appBuild, PLAYWRIGHT_COORDINATE),
        "the browser and Docker E2E source sets must share one pinned Playwright version");
    assertTrue(
        bootstrap.contains("playwrightVersion = '" + PLAYWRIGHT_VERSION + "'"),
        "the isolated bootstrap must use the application Playwright version");
    assertTrue(
        bootstrap.contains("playwrightCli playwrightCoordinate"),
        "the bootstrap must resolve only the dedicated Playwright CLI configuration");
    assertTrue(
        bootstrap.contains("classpath = configurations.playwrightCli"),
        "the CLI task must not use an application source-set runtime classpath");
    assertTrue(
        bootstrap.contains("args 'install-deps', 'chromium'"),
        "the isolated task must retain the existing Chromium host-dependency command");
    assertFalse(bootstrap.contains("project(':"),
        "the bootstrap must not depend on a production module");
    assertFalse(bootstrap.contains("sourceSets"),
        "the bootstrap must not compile application source sets");
    assertTrue(
        settings.contains("regelsuche-playwright-bootstrap"),
        "the bootstrap must remain an independent Gradle build");

    assertTrue(
        workflow.contains(
            "./gradlew --no-daemon -p playwright-bootstrap "
                + "installPlaywrightHostDependencies --console=plain"),
        "CI must invoke the isolated Playwright bootstrap");
    assertFalse(
        workflow.contains(
            "./gradlew --no-daemon :app:installPlaywrightHostDependencies "
                + "--console=plain"),
        "CI must not compile the main reactor merely to install host libraries");
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
    assertNotNull(
        configured,
        "Maven must expose maven.multiModuleProjectDirectory to tests");
    return Path.of(configured).toAbsolutePath().normalize();
  }
}
