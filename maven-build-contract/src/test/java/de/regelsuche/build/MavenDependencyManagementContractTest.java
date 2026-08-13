package de.regelsuche.build;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.SAXException;

class MavenDependencyManagementContractTest {
  private static final Set<String> MANAGED_EXTERNAL_DEPENDENCIES =
      Set.of(
          "com.fasterxml.jackson.core:jackson-databind",
          "com.fasterxml.jackson.dataformat:jackson-dataformat-yaml",
          "org.postgresql:postgresql",
          "org.hibernate.orm:hibernate-core",
          "org.hibernate.search:hibernate-search-mapper-orm",
          "org.hibernate.search:hibernate-search-backend-lucene",
          "org.hibernate.validator:hibernate-validator",
          "org.eclipse:yasson",
          "org.glassfish:jakarta.el",
          "jakarta.persistence:jakarta.persistence-api",
          "org.neo4j.driver:neo4j-java-driver",
          "org.graalvm.polyglot:polyglot");

  private static final Set<String> MANAGED_REACTOR_DEPENDENCIES =
      Set.of(
          "de.regelsuche:regelsuche-core",
          "de.regelsuche:regelsuche-egraph",
          "de.regelsuche:regelsuche-search",
          "de.regelsuche:regelsuche-validation",
          "de.regelsuche:regelsuche-math-algorithms",
          "de.regelsuche:regelsuche-math-jas",
          "de.regelsuche:regelsuche-persistence",
          "de.regelsuche:regelsuche-persistence-hibernate",
          "de.regelsuche:regelsuche-solver-ir",
          "de.regelsuche:regelsuche-solver-portfolio",
          "de.regelsuche:regelsuche-learning",
          "de.regelsuche:regelsuche-discovery",
          "de.regelsuche:regelsuche-experiments",
          "de.regelsuche:regelsuche-cli",
          "de.regelsuche:regelsuche-quality",
          "de.regelsuche:regelsuche-autopilot",
          "de.regelsuche:regelsuche-release",
          "de.regelsuche:regelsuche-benchmarks",
          "de.regelsuche:regelsuche-app",
          "de.regelsuche:regelsuche-integration-tests");

  private static final List<String> CONSUMER_POMS =
      List.of(
          "regelsuche-core/pom.xml",
          "regelsuche-persistence-hibernate/pom.xml",
          "app/pom.xml",
          "regelsuche-integration-tests/pom.xml");

  @Test
  void parentOwnsExternalAndReactorDependencyVersions() throws Exception {
    Document parent = parse(repositoryRoot().resolve("pom.xml"));
    Map<String, Element> managed = managedDependencies(parent);

    for (String coordinate : MANAGED_EXTERNAL_DEPENDENCIES) {
      Element dependency = managed.get(coordinate);
      assertNotNull(
          dependency,
          () -> "parent dependencyManagement is missing " + coordinate);
      String version = directChildText(dependency, "version");
      assertNotNull(
          version,
          () -> "managed dependency has no version: " + coordinate);
      assertTrue(
          version.matches("\\$\\{[^}]+}"),
          () -> coordinate + " must resolve through one parent property");
    }

    for (String coordinate : MANAGED_REACTOR_DEPENDENCIES) {
      Element dependency = managed.get(coordinate);
      assertNotNull(
          dependency,
          () -> "parent dependencyManagement is missing " + coordinate);
      assertEquals(
          "${project.version}",
          directChildText(dependency, "version"),
          () -> coordinate + " must follow the reactor version");
    }

    assertImportedBom(managed, "org.junit:junit-bom", "${junit.version}");
    assertImportedBom(
        managed,
        "org.testcontainers:testcontainers-bom",
        "${testcontainers.version}");
  }

  @Test
  void consumingModulesDoNotRepeatParentOwnedDependencyVersions()
      throws Exception {
    Path root = repositoryRoot();
    Set<String> centrallyManaged =
        new java.util.HashSet<>(MANAGED_EXTERNAL_DEPENDENCIES);
    centrallyManaged.addAll(MANAGED_REACTOR_DEPENDENCIES);
    centrallyManaged.add(
        "org.testcontainers:testcontainers-junit-jupiter");

    for (String relative : CONSUMER_POMS) {
      Document document = parse(root.resolve(relative));
      Element dependencies =
          directChild(document.getDocumentElement(), "dependencies");
      assertNotNull(dependencies, () -> "missing dependencies in " + relative);
      for (Element dependency : directChildren(
          dependencies,
          "dependency")) {
        String coordinate = coordinate(dependency);
        if (centrallyManaged.contains(coordinate)) {
          assertNull(
              directChild(dependency, "version"),
              () -> relative
                  + " must inherit the centrally managed version for "
                  + coordinate);
        }
      }
    }
  }

  private static void assertImportedBom(
      Map<String, Element> managed,
      String coordinate,
      String version
  ) {
    Element dependency = managed.get(coordinate);
    assertNotNull(
        dependency,
        () -> "parent dependencyManagement is missing " + coordinate);
    assertEquals(version, directChildText(dependency, "version"));
    assertEquals("pom", directChildText(dependency, "type"));
    assertEquals("import", directChildText(dependency, "scope"));
  }

  private static Map<String, Element> managedDependencies(Document parent) {
    Element management =
        directChild(parent.getDocumentElement(), "dependencyManagement");
    assertNotNull(management, "missing parent dependencyManagement");
    Element dependencies = directChild(management, "dependencies");
    assertNotNull(dependencies, "missing managed dependencies");
    Map<String, Element> result = new LinkedHashMap<>();
    for (Element dependency : directChildren(
        dependencies,
        "dependency")) {
      String coordinate = coordinate(dependency);
      assertNull(
          result.put(coordinate, dependency),
          () -> "duplicate managed dependency: " + coordinate);
    }
    return result;
  }

  private static String coordinate(Element dependency) {
    return directChildText(dependency, "groupId")
        + ":"
        + directChildText(dependency, "artifactId");
  }

  private static Path repositoryRoot() {
    String configured = System.getProperty("regelsuche.repositoryRoot");
    assertNotNull(
        configured,
        "Maven must expose maven.multiModuleProjectDirectory to tests");
    return Path.of(configured).toAbsolutePath().normalize();
  }

  private static Document parse(Path path)
      throws ParserConfigurationException, IOException, SAXException {
    DocumentBuilderFactory factory =
        DocumentBuilderFactory.newInstance();
    factory.setNamespaceAware(true);
    factory.setXIncludeAware(false);
    factory.setExpandEntityReferences(false);
    factory.setFeature(
        "http://apache.org/xml/features/disallow-doctype-decl",
        true);
    factory.setFeature(
        "http://xml.org/sax/features/external-general-entities",
        false);
    factory.setFeature(
        "http://xml.org/sax/features/external-parameter-entities",
        false);
    factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
    factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
    try (InputStream input = Files.newInputStream(path)) {
      return factory.newDocumentBuilder().parse(input);
    }
  }

  private static Element directChild(
      Element parent,
      String localName
  ) {
    if (parent == null) {
      return null;
    }
    for (Node child = parent.getFirstChild();
        child != null;
        child = child.getNextSibling()) {
      if (child instanceof Element element
          && localName.equals(element.getLocalName())) {
        return element;
      }
    }
    return null;
  }

  private static List<Element> directChildren(
      Element parent,
      String localName
  ) {
    List<Element> elements = new java.util.ArrayList<>();
    for (Node child = parent.getFirstChild();
        child != null;
        child = child.getNextSibling()) {
      if (child instanceof Element element
          && localName.equals(element.getLocalName())) {
        elements.add(element);
      }
    }
    return List.copyOf(elements);
  }

  private static String directChildText(
      Element parent,
      String localName
  ) {
    Element child = directChild(parent, localName);
    return child == null ? null : child.getTextContent().trim();
  }
}
