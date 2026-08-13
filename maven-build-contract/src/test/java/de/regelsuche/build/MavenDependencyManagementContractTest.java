package de.regelsuche.build;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
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

  private static final Set<String> MANAGED_PLUGINS =
      Set.of(
          "org.apache.maven.plugins:maven-clean-plugin",
          "org.apache.maven.plugins:maven-resources-plugin",
          "org.apache.maven.plugins:maven-compiler-plugin",
          "org.apache.maven.plugins:maven-surefire-plugin",
          "org.apache.maven.plugins:maven-failsafe-plugin",
          "org.apache.maven.plugins:maven-jar-plugin",
          "org.apache.maven.plugins:maven-dependency-plugin",
          "org.apache.maven.plugins:maven-assembly-plugin",
          "org.apache.maven.plugins:maven-install-plugin",
          "org.apache.maven.plugins:maven-deploy-plugin",
          "org.apache.maven.plugins:maven-site-plugin",
          "org.apache.maven.plugins:maven-enforcer-plugin",
          "org.jacoco:jacoco-maven-plugin");

  @Test
  void parentOwnsExternalReactorAndPluginVersions() throws Exception {
    Document parent = parse(repositoryRoot().resolve("pom.xml"));
    Map<String, Element> managedDependencies = managedDependencies(parent);

    for (String coordinate : MANAGED_EXTERNAL_DEPENDENCIES) {
      Element dependency = managedDependencies.get(coordinate);
      assertNotNull(
          dependency,
          () -> "parent dependencyManagement is missing " + coordinate);
      assertPropertyVersion(
          directChildText(dependency, "version"),
          "managed dependency " + coordinate);
    }

    for (String coordinate : MANAGED_REACTOR_DEPENDENCIES) {
      Element dependency = managedDependencies.get(coordinate);
      assertNotNull(
          dependency,
          () -> "parent dependencyManagement is missing " + coordinate);
      assertEquals(
          "${project.version}",
          directChildText(dependency, "version"),
          () -> coordinate + " must follow the reactor version");
    }

    assertImportedBom(
        managedDependencies,
        "org.junit:junit-bom",
        "${junit.version}");
    assertImportedBom(
        managedDependencies,
        "org.testcontainers:testcontainers-bom",
        "${testcontainers.version}");

    Map<String, Element> managedPlugins = managedPlugins(parent);
    for (String coordinate : MANAGED_PLUGINS) {
      Element plugin = managedPlugins.get(coordinate);
      assertNotNull(
          plugin,
          () -> "parent pluginManagement is missing " + coordinate);
      assertPropertyVersion(
          directChildText(plugin, "version"),
          "managed plugin " + coordinate);
    }
  }

  @Test
  void allDeclaredModulesInheritDependencyAndPluginVersions()
      throws Exception {
    Path root = repositoryRoot();
    Document parent = parse(root.resolve("pom.xml"));
    Map<String, Element> managedDependencies = managedDependencies(parent);
    Map<String, Element> managedPlugins = managedPlugins(parent);

    for (Path pom : modulePoms(parent, root)) {
      assertTrue(
          Files.isRegularFile(pom),
          () -> "declared Maven module has no pom.xml: " + pom);
      String relative = root.relativize(pom).toString().replace('\\', '/');
      Document module = parse(pom);
      Element project = module.getDocumentElement();

      Element dependencies = directChild(project, "dependencies");
      if (dependencies != null) {
        for (Element dependency : directChildren(
            dependencies,
            "dependency")) {
          String coordinate = dependencyCoordinate(dependency);
          assertNull(
              directChild(dependency, "version"),
              () -> relative
                  + " must inherit the dependency version for "
                  + coordinate);
          if (!isBomManaged(coordinate)) {
            assertNotNull(
                managedDependencies.get(coordinate),
                () -> relative
                    + " uses an unmanaged dependency: "
                    + coordinate);
          }
        }
      }

      Element build = directChild(project, "build");
      Element plugins = directChild(build, "plugins");
      if (plugins != null) {
        for (Element plugin : directChildren(plugins, "plugin")) {
          String coordinate = pluginCoordinate(plugin);
          assertNull(
              directChild(plugin, "version"),
              () -> relative
                  + " must inherit the plugin version for "
                  + coordinate);
          assertNotNull(
              managedPlugins.get(coordinate),
              () -> relative
                  + " uses an unmanaged build plugin: "
                  + coordinate);
        }
      }
    }
  }

  private static boolean isBomManaged(String coordinate) {
    return coordinate.startsWith("org.junit:")
        || coordinate.startsWith("org.junit.jupiter:")
        || coordinate.startsWith("org.testcontainers:");
  }

  private static void assertPropertyVersion(
      String version,
      String label
  ) {
    assertNotNull(version, () -> label + " has no version");
    assertTrue(
        version.matches("\\$\\{[^}]+\\}"),
        () -> label + " must resolve through one parent property");
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
    return uniqueByCoordinate(
        directChildren(dependencies, "dependency"),
        MavenDependencyManagementContractTest::dependencyCoordinate,
        "managed dependency");
  }

  private static Map<String, Element> managedPlugins(Document parent) {
    Element build = directChild(parent.getDocumentElement(), "build");
    Element management = directChild(build, "pluginManagement");
    assertNotNull(management, "missing parent pluginManagement");
    Element plugins = directChild(management, "plugins");
    assertNotNull(plugins, "missing managed plugins");
    return uniqueByCoordinate(
        directChildren(plugins, "plugin"),
        MavenDependencyManagementContractTest::pluginCoordinate,
        "managed plugin");
  }

  private static Map<String, Element> uniqueByCoordinate(
      List<Element> elements,
      java.util.function.Function<Element, String> coordinate,
      String label
  ) {
    Map<String, Element> result = new LinkedHashMap<>();
    for (Element element : elements) {
      String value = coordinate.apply(element);
      assertNull(
          result.put(value, element),
          () -> "duplicate " + label + ": " + value);
    }
    return result;
  }

  private static List<Path> modulePoms(
      Document parent,
      Path root
  ) {
    LinkedHashSet<String> modules = new LinkedHashSet<>();
    Element project = parent.getDocumentElement();
    addModules(directChild(project, "modules"), modules);
    Element profiles = directChild(project, "profiles");
    if (profiles != null) {
      for (Element profile : directChildren(profiles, "profile")) {
        addModules(directChild(profile, "modules"), modules);
      }
    }
    assertTrue(!modules.isEmpty(), "parent must declare Maven modules");
    return modules.stream()
        .map(module -> root.resolve(module).resolve("pom.xml"))
        .toList();
  }

  private static void addModules(
      Element modules,
      Set<String> result
  ) {
    if (modules == null) {
      return;
    }
    for (Element module : directChildren(modules, "module")) {
      String path = module.getTextContent().trim();
      assertTrue(!path.isBlank(), "module path must not be blank");
      result.add(path);
    }
  }

  private static String dependencyCoordinate(Element dependency) {
    return requiredChildText(dependency, "groupId")
        + ":"
        + requiredChildText(dependency, "artifactId");
  }

  private static String pluginCoordinate(Element plugin) {
    String groupId = directChildText(plugin, "groupId");
    if (groupId == null) {
      groupId = "org.apache.maven.plugins";
    }
    return groupId + ":" + requiredChildText(plugin, "artifactId");
  }

  private static String requiredChildText(
      Element parent,
      String localName
  ) {
    String value = directChildText(parent, localName);
    assertNotNull(value, () -> "missing " + localName);
    assertTrue(!value.isBlank(), () -> localName + " must not be blank");
    return value;
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
    List<Element> elements = new ArrayList<>();
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
