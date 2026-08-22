package de.regelsuche.build;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class MavenWebAssetParityContractTest {
  private static final Pattern GRADLE_CYTOSCAPE_VERSION = Pattern.compile(
      "webAssets\\s+'org\\.webjars\\.npm:cytoscape:([^']+)'");
  private static final Pattern MAVEN_CYTOSCAPE_VERSION = Pattern.compile(
      "<cytoscape\\.version>([^<]+)</cytoscape\\.version>");

  @Test
  void cytoscapeVersionAndPackagingStayAlignedAcrossBuildSystems()
      throws IOException {
    Path root = repositoryRoot();
    String gradle = Files.readString(root.resolve("app/build.gradle"));
    String maven = Files.readString(root.resolve("app/pom.xml"));

    assertEquals(
        requiredMatch(GRADLE_CYTOSCAPE_VERSION, gradle, "Gradle Cytoscape"),
        requiredMatch(MAVEN_CYTOSCAPE_VERSION, maven, "Maven Cytoscape"),
        "Gradle and Maven must package the same Cytoscape revision");
    assertTrue(
        maven.contains("<id>unpack-cytoscape-assets</id>"),
        "Maven must resolve the declared Cytoscape WebJar");
    assertTrue(
        maven.contains("<id>copy-cytoscape-assets</id>"),
        "Maven must replace the checked-in snapshot in the product output");
    assertTrue(
        maven.contains("dist/cytoscape.min.js"),
        "Maven must package the production Cytoscape bundle");
  }

  private static String requiredMatch(
      Pattern pattern,
      String source,
      String label
  ) {
    Matcher matcher = pattern.matcher(source);
    assertTrue(matcher.find(), () -> label + " version is missing");
    String value = matcher.group(1).trim();
    assertTrue(!value.isBlank(), () -> label + " version is blank");
    assertTrue(!matcher.find(), () -> label + " version is declared twice");
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
