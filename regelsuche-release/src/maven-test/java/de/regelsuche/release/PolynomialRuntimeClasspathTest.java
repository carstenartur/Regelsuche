package de.regelsuche.release;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarOutputStream;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PolynomialRuntimeClasspathTest {
    @Test void identicalDependencyBytesAtANewPathRegenerateTheLiteralMavenLaunchClasspath(@TempDir Path dir) throws Exception {
        Path cache = Files.createDirectory(dir.resolve("repository"));
        // Reuse the already resolved Maven plugin and its ordinary dependencies in an offline fixture.
        try (var groups = Files.list(Path.of(System.getProperty("localRepository")))) {
            for (Path group : groups.filter(Files::isDirectory).toList())
                Files.createSymbolicLink(cache.resolve(group.getFileName()), group);
        }
        for (String artifact : List.of("first", "second")) {
            Path location = Files.createDirectories(cache.resolve("polynomialfixture").resolve(artifact).resolve("1"));
            try (var jar = new JarOutputStream(Files.newOutputStream(location.resolve(artifact + "-1.jar")))) { }
            Files.writeString(location.resolve(artifact + "-1.pom"), "<project><modelVersion>4.0.0</modelVersion>"
                + "<groupId>polynomialfixture</groupId><artifactId>" + artifact + "</artifactId><version>1</version></project>");
        }
        // Import the actual execution configuration, including the overwrite rule, from the learning worker project.
        var parser = DocumentBuilderFactory.newInstance().newDocumentBuilder();
        var xpath = javax.xml.xpath.XPathFactory.newInstance().newXPath();
        var pom = parser.parse(Path.of("../regelsuche-learning/pom.xml").toFile());
        var plugin = (org.w3c.dom.Node) xpath.evaluate("//execution[id='resolve-frozen-study-runtime']", pom,
            javax.xml.xpath.XPathConstants.NODE);
        assertNotNull(plugin, "Maven study classpath execution");
        var parent = parser.parse(Path.of("../pom.xml").toFile());
        String pluginVersion = xpath.evaluate("//properties/maven.dependency.plugin.version", parent);
        assertFalse(pluginVersion.isBlank());
        var xml = new java.io.StringWriter();
        javax.xml.transform.TransformerFactory.newInstance().newTransformer().transform(
            new javax.xml.transform.dom.DOMSource(plugin), new javax.xml.transform.stream.StreamResult(xml));
        String execution = xml.toString().substring(xml.toString().indexOf("<execution>"));
        Path result = dir.resolve("target/polynomial-runtime-classpath.txt");
        String template = """
            <project><modelVersion>4.0.0</modelVersion><groupId>polynomialfixture</groupId><artifactId>runner</artifactId><version>1</version>
              <properties><regelsuche.skipPolynomialClasspath>false</regelsuche.skipPolynomialClasspath></properties>
              <dependencies><dependency><groupId>polynomialfixture</groupId><artifactId>${worker}</artifactId><version>1</version></dependency></dependencies>
              <build><plugins><plugin><groupId>org.apache.maven.plugins</groupId><artifactId>maven-dependency-plugin</artifactId>
              <version>%s</version><executions>%s</executions></plugin></plugins></build></project>
            """;
        Files.writeString(dir.resolve("pom.xml"), template.formatted(pluginVersion, execution));
        Path first = cache.resolve("polynomialfixture/first/1/first-1.jar");
        Path second = cache.resolve("polynomialfixture/second/1/second-1.jar");
        Files.copy(first, second, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        assertEquals(-1, Files.mismatch(first, second));
        for (String artifact : List.of("first", "first", "second")) {
            Path log = dir.resolve(artifact + ".log");
            var process = new ProcessBuilder(Path.of(System.getProperty("regelsuche.mavenHome"), "bin", "mvn").toString(),
                "-o", "-B", "-ntp", "-Dmaven.repo.local=" + cache, "-Dworker=" + artifact,
                "org.apache.maven.plugins:maven-dependency-plugin:" + pluginVersion + ":build-classpath@resolve-frozen-study-runtime")
                .directory(dir.toFile()).redirectErrorStream(true).redirectOutput(log.toFile()).start();
            try {
                assertTrue(process.waitFor(60, TimeUnit.SECONDS), () -> "Maven fixture timed out in " + log);
                assertEquals(0, process.exitValue(), () -> read(log));
                assertEquals(artifact.equals("first") ? first.toString() : second.toString(), Files.readString(result).strip());
            } finally { process.destroyForcibly(); }
        }
    }

    private static String read(Path log) {
        try { return Files.readString(log); }
        catch (java.io.IOException e) { return e.toString(); }
    }
}
