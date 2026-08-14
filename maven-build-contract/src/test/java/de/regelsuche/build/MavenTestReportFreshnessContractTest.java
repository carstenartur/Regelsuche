package de.regelsuche.build;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.SAXException;

class MavenTestReportFreshnessContractTest {
  @Test
  void initializeRemovesOnlyPriorSurefireAndFailsafeReports() throws Exception {
    Document parent = parse(repositoryRoot().resolve("pom.xml"));
    Element plugins =
        directChild(directChild(parent.getDocumentElement(), "build"), "plugins");
    Element cleanPlugin = plugin(plugins, "org.apache.maven.plugins", "maven-clean-plugin");
    assertNotNull(cleanPlugin, "Maven must own test-report freshness");

    Element execution = execution(
        directChild(cleanPlugin, "executions"),
        "delete-stale-test-reports");
    assertNotNull(execution, "missing stale-test-report cleanup execution");
    assertEquals("initialize", directChildText(execution, "phase"));
    assertEquals(
        List.of("clean"),
        directChildTexts(directChild(execution, "goals"), "goal"));

    Element configuration = directChild(execution, "configuration");
    assertNotNull(configuration);
    assertEquals(
        "true",
        directChildText(configuration, "excludeDefaultDirectories"),
        "initialize must not erase compiled Maven outputs");

    List<Element> filesets = directChildren(
        directChild(configuration, "filesets"),
        "fileset");
    assertEquals(2, filesets.size());
    assertEquals(
        List.of(
            "${project.build.directory}/surefire-reports",
            "${project.build.directory}/failsafe-reports"),
        filesets.stream()
            .map(fileset -> directChildText(fileset, "directory"))
            .toList());
    for (Element fileset : filesets) {
      assertEquals(
          "false",
          directChildText(fileset, "followSymlinks"),
          "test-report cleanup must not traverse symbolic directories");
    }
  }

  private static Element plugin(
      Element plugins,
      String groupId,
      String artifactId) {
    if (plugins == null) {
      return null;
    }
    for (Element candidate : directChildren(plugins, "plugin")) {
      if (groupId.equals(directChildText(candidate, "groupId"))
          && artifactId.equals(directChildText(candidate, "artifactId"))) {
        return candidate;
      }
    }
    return null;
  }

  private static Element execution(Element executions, String id) {
    if (executions == null) {
      return null;
    }
    for (Element candidate : directChildren(executions, "execution")) {
      if (id.equals(directChildText(candidate, "id"))) {
        return candidate;
      }
    }
    return null;
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
    DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
    factory.setNamespaceAware(true);
    factory.setXIncludeAware(false);
    factory.setExpandEntityReferences(false);
    factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
    factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
    factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
    factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
    factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
    try (InputStream input = Files.newInputStream(path)) {
      return factory.newDocumentBuilder().parse(input);
    }
  }

  private static Element directChild(Element parent, String localName) {
    if (parent == null) {
      return null;
    }
    for (Node child = parent.getFirstChild(); child != null; child = child.getNextSibling()) {
      if (child instanceof Element element && localName.equals(element.getLocalName())) {
        return element;
      }
    }
    return null;
  }

  private static List<Element> directChildren(Element parent, String localName) {
    List<Element> values = new ArrayList<>();
    if (parent == null) {
      return List.of();
    }
    for (Node child = parent.getFirstChild(); child != null; child = child.getNextSibling()) {
      if (child instanceof Element element && localName.equals(element.getLocalName())) {
        values.add(element);
      }
    }
    return List.copyOf(values);
  }

  private static String directChildText(Element parent, String localName) {
    Element child = directChild(parent, localName);
    return child == null ? null : child.getTextContent().trim();
  }

  private static List<String> directChildTexts(Element parent, String localName) {
    return directChildren(parent, localName).stream()
        .map(Element::getTextContent)
        .map(String::trim)
        .toList();
  }
}
