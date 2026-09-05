package de.regelsuche.build;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.zip.ZipFile;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.InputSource;

/** Exercises the production descriptor with real Maven resolution, not a copied assembly model. */
class MavenDistributionAssemblyContractTest {
  private static final String POM =
      "<project xmlns=\"http://maven.apache.org/POM/4.0.0\"><modelVersion>4.0.0</modelVersion>";

  @Test
  void distributionUsesTheCurrentRuntimeGraphRatherThanAStagingDirectory() throws Exception {
    Path root = repositoryRoot();
    Element assembly = parse(root.resolve("app/src/assembly/distribution.xml"));
    List<Element> sets = children(child(assembly, "dependencySets"), "dependencySet");
    assertEquals(1, sets.size());
    Element dependencies = sets.getFirst();
    assertEquals("lib", text(dependencies, "outputDirectory"));
    assertEquals("runtime", text(dependencies, "scope"));
    assertEquals("false", text(dependencies, "useProjectArtifact"));
    assertEquals("true", text(dependencies, "useTransitiveDependencies"));
    assertEquals("false", text(dependencies, "unpack"));
    for (Element files : children(child(assembly, "fileSets"), "fileSet")) {
      assertFalse(text(files, "directory").contains("distribution/lib"));
    }
    Element dependencyPlugin = plugin(parse(root.resolve("app/pom.xml")), "maven-dependency-plugin");
    assertTrue(children(child(dependencyPlugin, "executions"), "execution").stream()
        .noneMatch(execution -> "copy-runtime-dependencies".equals(text(execution, "id"))));
  }

  @Test
  void fixturePreservesProductionPluginWithinTheProjectNamespace(@TempDir Path fixture)
      throws Exception {
    Path root = repositoryRoot();
    configureFixture(root, fixture, "de.regelsuche.assemblyfixture.namespace", "0.4.0", false);
    Element project = parse(fixture.resolve("app/pom.xml"));
    assertEquals("http://maven.apache.org/POM/4.0.0", project.getNamespaceURI());
    assertEquals(project.getNamespaceURI(), project.getAttribute("xmlns"));
    List<Element> plugins = children(child(child(project, "build"), "plugins"), "plugin");
    assertFalse(plugins.isEmpty());
    for (Element imported : plugins) {
      assertEquals(project.getNamespaceURI(), imported.getNamespaceURI());
      assertFalse(imported.hasAttribute("xmlns"),
          "Maven model plugins must inherit the project namespace, not redeclare it");
    }
    Element expected = configuredPlugin(parse(root.resolve("app/pom.xml")),
        child(parse(root.resolve("pom.xml")), "properties"), "maven-assembly-plugin");
    assertTrue(expected.isEqualNode(plugin(project, "maven-assembly-plugin")),
        "fixture serialization must preserve the production plugin configuration and pinned version");
  }

  @Test
  @Timeout(300)
  void incrementalVersionChangeAndRemovalExcludeStaleLibraries(@TempDir Path fixture)
      throws Exception {
    Path root = repositoryRoot();
    String group = "de.regelsuche.assemblyfixture.g" + UUID.randomUUID().toString().replace("-", "");
    for (String name : List.of("README.md", "LICENSE", "CITATION.cff", "CITATION.md", "codemeta.json")) {
      Files.writeString(fixture.resolve(name), "assembly fixture\n");
    }
    for (String name : List.of("src/assembly/distribution.xml", "src/main/scripts/regelsuche",
        "src/main/scripts/regelsuche.bat")) {
      Path target = fixture.resolve("app").resolve(name);
      Files.createDirectories(target.getParent());
      Files.copy(root.resolve("app").resolve(name), target);
    }

    Map<String, Path> oldLibraries = configureFixture(root, fixture, group, "0.4.0-SNAPSHOT", true);
    runMaven(fixture, "snapshot");
    verifyArchives(fixture, "0.4.0-SNAPSHOT", oldLibraries);

    // Preserve the first build and seed the exact old staging path. No clean, retry or deletion.
    Path staging = fixture.resolve("app/target/distribution/lib");
    Files.createDirectories(staging);
    for (Map.Entry<String, Path> library : oldLibraries.entrySet()) {
      Files.copy(library.getValue(), staging.resolve(library.getKey()),
          java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }
    Path foreign = staging.resolve("unrelated-obsolete-7.jar");
    Files.writeString(foreign, "must not enter the release archive");

    Map<String, Path> releaseLibraries = configureFixture(root, fixture, group, "0.4.0", false);
    runMaven(fixture, "release");
    verifyArchives(fixture, "0.4.0", releaseLibraries);
    assertTrue(Files.isRegularFile(fixture.resolve("app/target/regelsuche-0.4.0-SNAPSHOT.zip")),
        "the regression must exercise an incremental build, not a fresh target directory");
    assertEquals("must not enter the release archive", Files.readString(foreign));
    for (Map.Entry<String, Path> library : oldLibraries.entrySet()) {
      assertEquals(digest(Files.readAllBytes(library.getValue())),
          digest(Files.readAllBytes(staging.resolve(library.getKey()))));
    }
    System.out.println("distributionAssembly=VERIFIED snapshot-to-release/no-clean/removed-dependency"
        + " runtime-and-transitive/test-scope-excluded ZIP-and-TAR");
  }

  private static Map<String, Path> configureFixture(
      Path root, Path fixture, String group, String version, boolean includeRetired) throws Exception {
    Files.createDirectories(fixture.resolve("app"));
    Path repository = fixture.resolve("repository");
    Map<String, Path> libraries = new TreeMap<>();
    for (String id : List.of("library", "transitive", "runtime-only", "test-only", "retired")) {
      String dependencies = "library".equals(id) ? dependency(group, "transitive", version, "compile") : "";
      Path directory = repository.resolve(group.replace('.', '/')).resolve(id).resolve(version);
      Files.createDirectories(directory);
      Path pom = directory.resolve(id + "-" + version + ".pom");
      Files.writeString(pom, POM + "<groupId>" + group + "</groupId><artifactId>" + id
          + "</artifactId><version>" + version + "</version><dependencies>" + dependencies
          + "</dependencies></project>");
      Path jar = directory.resolve(id + "-" + version + ".jar");
      writeJar(jar, id + ":" + version);
      for (Path artifact : List.of(pom, jar)) {
        Files.writeString(Path.of(artifact + ".sha1"), HexFormat.of().formatHex(
            MessageDigest.getInstance("SHA-1").digest(Files.readAllBytes(artifact))));
      }
      if (!"test-only".equals(id) && (includeRetired || !"retired".equals(id))) {
        libraries.put(jar.getFileName().toString(), jar);
      }
    }
    Files.writeString(fixture.resolve("pom.xml"), POM + "<groupId>" + group
        + "</groupId><artifactId>fixture-parent</artifactId><version>" + version
        + "</version><packaging>pom</packaging></project>");
    Element sourceApp = parse(root.resolve("app/pom.xml"));
    Element sourceProperties = child(parse(root.resolve("pom.xml")), "properties");
    List<Element> plugins = new ArrayList<>();
    plugins.add(configuredPlugin(sourceApp, sourceProperties, "maven-assembly-plugin"));
    // Include any old staging-copy execution so the test also fails against the original build.
    Element copy = plugin(sourceApp, "maven-dependency-plugin");
    for (Element execution : new ArrayList<>(children(child(copy, "executions"), "execution"))) {
      if (!"copy-runtime-dependencies".equals(text(execution, "id"))) {
        execution.getParentNode().removeChild(execution);
      }
    }
    if (!children(child(copy, "executions"), "execution").isEmpty()) {
      setVersion(copy, text(sourceProperties, "maven.dependency.plugin.version"));
      plugins.add(copy);
    }
    String appPom = POM + "<parent><groupId>" + group
        + "</groupId><artifactId>fixture-parent</artifactId><version>" + version
        + "</version><relativePath>../pom.xml</relativePath></parent>"
        + "<artifactId>fixture-app</artifactId><packaging>pom</packaging>"
        + "<properties><releaseVersion>${project.version}</releaseVersion></properties>"
        + "<repositories><repository><id>assembly-fixture</id><url>" + repository.toUri()
        + "</url><snapshots><enabled>true</enabled></snapshots></repository></repositories>"
        + "<dependencies>" + dependency(group, "library", version, "compile")
        + dependency(group, "runtime-only", version, "runtime")
        + dependency(group, "test-only", version, "test")
        + (includeRetired ? dependency(group, "retired", version, "compile") : "")
        + "</dependencies><build><finalName>" + text(child(sourceApp, "build"), "finalName")
        + "</finalName><plugins/></build></project>";
    // Serialize the complete POM once. A standalone plugin fragment gains an xmlns
    // attribute which Maven's strict model reader rejects when pasted into a POM.
    Element project = parse(new InputSource(new StringReader(appPom)));
    Element targetPlugins = child(child(project, "build"), "plugins");
    assertNotNull(targetPlugins);
    for (Element configured : plugins) {
      targetPlugins.appendChild(project.getOwnerDocument().importNode(configured, true));
    }
    Files.writeString(fixture.resolve("app/pom.xml"), xml(project));
    // A tiny application artifact replaces product compilation; the assembly/resolver is real.
    writeJar(fixture.resolve("app/target/regelsuche-" + version + ".jar"), "app:" + version);
    return libraries;
  }

  private static String dependency(String group, String id, String version, String scope) {
    return "<dependency><groupId>" + group + "</groupId><artifactId>" + id
        + "</artifactId><version>" + version + "</version><scope>" + scope + "</scope></dependency>";
  }

  private static void writeJar(Path path, String marker) throws Exception {
    Files.createDirectories(path.getParent());
    try (JarOutputStream jar = new JarOutputStream(Files.newOutputStream(path))) {
      jar.putNextEntry(new JarEntry("fixture-marker.txt"));
      jar.write(marker.getBytes(StandardCharsets.UTF_8));
      jar.closeEntry();
    }
  }

  private static void runMaven(Path fixture, String phase) throws Exception {
    Path log = fixture.resolve("maven-" + phase + ".log");
    boolean windows = System.getProperty("os.name").startsWith("Windows");
    List<String> command = new ArrayList<>();
    if (windows) command.addAll(List.of("cmd.exe", "/c"));
    command.addAll(List.of(windows ? "mvn.cmd" : "mvn", "--batch-mode", "--no-transfer-progress",
        "-f", "app/pom.xml", "package"));
    Process process = new ProcessBuilder(command).directory(fixture.toFile())
        .redirectErrorStream(true).redirectOutput(log.toFile()).start();
    try {
      assertTrue(process.waitFor(120, TimeUnit.SECONDS), () -> "Maven timed out: " + log);
      String output = Files.readString(log);
      assertEquals(0, process.exitValue(), () -> output.substring(Math.max(0, output.length() - 16000)));
    } finally {
      if (process.isAlive()) {
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
      }
    }
  }

  private static void verifyArchives(Path fixture, String version, Map<String, Path> expected)
      throws Exception {
    String prefix = "regelsuche-" + version + "/";
    Path archive = fixture.resolve("app/target/regelsuche-" + version);
    Map<String, String> zipFiles = new TreeMap<>();
    try (ZipFile zip = new ZipFile(archive + ".zip")) {
      var entries = zip.entries();
      while (entries.hasMoreElements()) {
        var entry = entries.nextElement();
        if (!entry.isDirectory()) {
          try (InputStream input = zip.getInputStream(entry)) {
            assertEquals(null, zipFiles.put(entry.getName(), digest(input.readAllBytes())),
                "duplicate ZIP entry: " + entry.getName());
          }
        }
      }
    }
    assertEquals(zipFiles, tarFiles(Path.of(archive + ".tar")), "ZIP/TAR file payloads must agree");
    Map<String, String> expectedFiles = new TreeMap<>();
    for (Map.Entry<String, Path> library : expected.entrySet()) {
      expectedFiles.put(prefix + "lib/" + library.getKey(), digest(Files.readAllBytes(library.getValue())));
    }
    expectedFiles.put(prefix + "regelsuche.jar", digest(Files.readAllBytes(Path.of(archive + ".jar"))));
    for (String name : List.of("regelsuche", "regelsuche.bat")) {
      expectedFiles.put(prefix + "bin/" + name,
          digest(Files.readAllBytes(fixture.resolve("app/src/main/scripts/" + name))));
    }
    for (String name : List.of("README.md", "LICENSE", "CITATION.cff", "CITATION.md", "codemeta.json")) {
      expectedFiles.put(prefix + name, digest(Files.readAllBytes(fixture.resolve(name))));
    }
    assertEquals(expectedFiles, zipFiles, "archive membership and bytes must match only the current graph");
  }

  /** Reads only the short-name regular-file/directory TAR subset emitted by this tiny fixture. */
  private static Map<String, String> tarFiles(Path path) throws Exception {
    Map<String, String> files = new TreeMap<>();
    try (InputStream input = Files.newInputStream(path)) {
      for (byte[] header = input.readNBytes(512); header.length != 0; header = input.readNBytes(512)) {
        assertEquals(512, header.length, "truncated TAR header");
        if (Arrays.equals(header, new byte[512])) break;
        String prefix = field(header, 345, 155);
        String name = (prefix.isEmpty() ? "" : prefix + "/") + field(header, 0, 100);
        int size = Integer.parseInt(field(header, 124, 12).trim(), 8);
        assertTrue(size >= 0 && size < 1024 * 1024, "fixture entry exceeds one MiB");
        byte[] bytes = input.readNBytes(size);
        assertEquals(size, bytes.length, "truncated TAR payload");
        if (header[156] == 0 || header[156] == '0') {
          assertEquals(null, files.put(name, digest(bytes)), "duplicate TAR entry: " + name);
        } else {
          assertEquals('5', (char) header[156], "unexpected fixture TAR entry type");
        }
        input.skipNBytes((512 - size % 512) % 512);
      }
    }
    return files;
  }

  private static String field(byte[] bytes, int offset, int length) {
    int end = offset;
    while (end < offset + length && bytes[end] != 0) end++;
    return new String(bytes, offset, end - offset, StandardCharsets.US_ASCII);
  }

  private static String digest(byte[] bytes) throws Exception {
    return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
  }

  private static Path repositoryRoot() {
    String root = System.getProperty("regelsuche.repositoryRoot");
    assertNotNull(root, "Maven must expose the repository root");
    return Path.of(root);
  }

  private static Element parse(Path path) throws Exception {
    try (InputStream input = Files.newInputStream(path)) {
      return parse(new InputSource(input));
    }
  }

  private static Element parse(InputSource input) throws Exception {
    DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
    factory.setNamespaceAware(true);
    factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
    factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
    factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
    return factory.newDocumentBuilder().parse(input).getDocumentElement();
  }

  private static Element plugin(Element project, String id) {
    return children(child(child(project, "build"), "plugins"), "plugin").stream()
        .filter(candidate -> id.equals(text(candidate, "artifactId"))).findFirst().orElseThrow();
  }

  private static Element configuredPlugin(Element app, Element properties, String id) {
    Element plugin = (Element) plugin(app, id).cloneNode(true);
    setVersion(plugin, text(properties, id.replace('-', '.') + ".version"));
    return plugin;
  }

  private static void setVersion(Element plugin, String version) {
    assertNotNull(version);
    Element element = plugin.getOwnerDocument().createElementNS(plugin.getNamespaceURI(), "version");
    element.setTextContent(version);
    plugin.appendChild(element);
  }

  private static String xml(Element element) throws Exception {
    TransformerFactory factory = TransformerFactory.newInstance();
    factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
    factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "");
    var transformer = factory.newTransformer();
    transformer.setOutputProperty("omit-xml-declaration", "yes");
    StringWriter writer = new StringWriter();
    transformer.transform(new DOMSource(element), new StreamResult(writer));
    return writer.toString();
  }

  private static List<Element> children(Element parent, String name) {
    List<Element> result = new ArrayList<>();
    if (parent != null) {
      for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
        if (node instanceof Element element && name.equals(element.getLocalName())) result.add(element);
      }
    }
    return result;
  }

  private static Element child(Element parent, String name) {
    List<Element> matches = children(parent, name);
    return matches.isEmpty() ? null : matches.getFirst();
  }

  private static String text(Element parent, String name) {
    Element element = child(parent, name);
    return element == null ? null : element.getTextContent().trim();
  }
}
