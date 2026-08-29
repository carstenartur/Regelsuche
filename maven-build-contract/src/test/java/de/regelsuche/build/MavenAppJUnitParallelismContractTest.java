package de.regelsuche.build;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import org.junit.jupiter.api.Test;

class MavenAppJUnitParallelismContractTest {
  @Test
  void ordinaryAppTestsUseBoundedClassParallelismOnly() throws IOException {
    Path root = repositoryRoot();
    Path configuration = root.resolve(
        "app/src/test/resources/junit-platform.properties");
    Properties properties = new Properties();
    try (Reader reader = Files.newBufferedReader(configuration)) {
      properties.load(reader);
    }

    assertEquals("true", properties.getProperty(
        "junit.jupiter.execution.parallel.enabled"));
    assertEquals("same_thread", properties.getProperty(
        "junit.jupiter.execution.parallel.mode.default"),
        "methods inside one test class must remain serial");
    assertEquals("concurrent", properties.getProperty(
        "junit.jupiter.execution.parallel.mode.classes.default"));
    assertEquals("fixed", properties.getProperty(
        "junit.jupiter.execution.parallel.config.strategy"));
    assertEquals("2", properties.getProperty(
        "junit.jupiter.execution.parallel.config.fixed.parallelism"));
    assertEquals("2", properties.getProperty(
        "junit.jupiter.execution.parallel.config.fixed.max-pool-size"));

    assertFalse(Files.exists(root.resolve(
        "app/src/e2eTest/resources/junit-platform.properties")),
        "browser E2E tests must not inherit the ordinary unit-test policy");
    assertFalse(Files.exists(root.resolve(
        "app/src/dockerE2eTest/resources/junit-platform.properties")),
        "Docker E2E tests must not inherit the ordinary unit-test policy");
  }

  private static Path repositoryRoot() {
    String configured = System.getProperty("regelsuche.repositoryRoot");
    assertNotNull(
        configured,
        "Maven must expose maven.multiModuleProjectDirectory to tests");
    return Path.of(configured).toAbsolutePath().normalize();
  }
}
