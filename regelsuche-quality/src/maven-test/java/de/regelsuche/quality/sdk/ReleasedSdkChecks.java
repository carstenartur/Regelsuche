package de.regelsuche.quality.sdk;

import static de.regelsuche.quality.sdk.ReleasedSdkArchive.require;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Element;

final class ReleasedSdkChecks {
    private ReleasedSdkChecks() {}

    static JsonNode contracts() throws IOException {
        try (var input = ReleasedSdkChecks.class.getResourceAsStream("released-consumers-0.5.2.json")) {
            if (input == null) throw new IOException("missing versioned consumer contracts");
            return new ObjectMapper().readTree(input);
        }
    }

    static JsonNode centralRelease() throws IOException {
        try (var input = ReleasedSdkChecks.class.getResourceAsStream("released-central-sdk-0.5.3.json")) {
            if (input == null) throw new IOException("missing current published SDK receipt");
            return new ObjectMapper().readTree(input);
        }
    }

    static Set<String> strings(JsonNode input) {
        var values = new TreeSet<String>();
        input.forEach(node -> values.add(node.textValue()));
        return values;
    }

    static Element xml(Path path) throws Exception {
        ReleasedSdkArchive.noSymlinks(path);
        var factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        return factory.newDocumentBuilder().parse(path.toFile()).getDocumentElement();
    }

    static void requireStandalonePom(Path path) throws Exception {
        var pom = xml(path);
        String namespace = "http://maven.apache.org/POM/4.0.0";
        require("project".equals(pom.getLocalName()) && namespace.equals(pom.getNamespaceURI()), "unexpected Maven project root");
        for (String name : List.of("parent", "module")) {
            var elements = pom.getElementsByTagNameNS(namespace, name);
            for (int i = 0; i < elements.getLength(); i++) {
                var enclosing = elements.item(i).getParentNode();
                require(!("parent".equals(name) && enclosing == pom)
                    && !("module".equals(name) && "modules".equals(enclosing.getLocalName()) && namespace.equals(enclosing.getNamespaceURI())),
                    "external consumer must be independent of parent/reactor");
            }
        }
    }

    static Map<String, Object> surefire(Path directory, Set<String> requiredCases) throws Exception {
        Map<String, Integer> totals = new TreeMap<>(Map.of("tests", 0, "errors", 0, "failures", 0, "skipped", 0));
        Set<String> names = new TreeSet<>();
        try (var files = Files.newDirectoryStream(directory, "TEST-*.xml")) {
            for (var file : files) {
                var report = xml(file);
                require("testsuite".equals(report.getTagName()), "unexpected Surefire root");
                int actual = 0;
                for (var child = report.getFirstChild(); child != null; child = child.getNextSibling()) {
                    if (!(child instanceof Element test) || !"testcase".equals(test.getTagName())) continue;
                    actual++;
                    require(names.add(test.getAttribute("classname") + "#" + test.getAttribute("name")), "duplicate test identity");
                    for (String bad : List.of("failure", "error", "skipped"))
                        require(test.getElementsByTagName(bad).getLength() == 0, "unsuccessful Surefire test");
                }
                for (String field : totals.keySet()) {
                    int count = Integer.parseInt(report.getAttribute(field));
                    require(count >= 0 && ("tests".equals(field) ? count == actual : count == 0), "invalid Surefire count " + field);
                    totals.merge(field, count, Integer::sum);
                }
            }
        }
        require(totals.get("tests") > 0 && names.containsAll(requiredCases), "missing expected Surefire cases: " + requiredCases);
        Map<String, Object> result = new TreeMap<>(totals);
        result.put("cases", names);
        return result;
    }

    static Map<String, Object> closure(Path graph, Path dependencies, Path repository, Set<String> expected) throws IOException {
        var hashes = new TreeMap<String, String>();
        for (String module : expected) {
            Path original = repository.resolve("de/regelsuche").resolve(module).resolve(ReleasedSdkArchive.VERSION)
                .resolve(module + "-" + ReleasedSdkArchive.VERSION + ".jar");
            hashes.put(module, ReleasedSdkArchive.digest(original));
        }
        return closure(graph, dependencies, "de.regelsuche", ReleasedSdkArchive.VERSION, hashes);
    }

    static Map<String, Object> closure(Path graph, Path dependencies, String group, String version,
            Map<String, String> expectedHashes) throws IOException {
        ReleasedSdkArchive.noSymlinks(graph);
        String tgf = Files.readString(graph);
        int edges = tgf.indexOf("\n#");
        require(edges >= 0, "missing Maven runtime dependency graph");
        var nodes = new HashSet<String>();
        var expectedJars = new HashSet<String>();
        var modules = new TreeSet<String>();
        var coordinates = new ArrayList<String>();
        Map<String, String> jars = new TreeMap<>();
        try (var files = Files.newDirectoryStream(dependencies, "*.jar")) {
            for (var file : files) jars.put(file.getFileName().toString(), ReleasedSdkArchive.digest(file));
        }
        for (String line : tgf.substring(0, edges).lines().toList()) {
            var node = Pattern.compile("([0-9]+) ([^\\s]+)").matcher(line);
            require(node.matches() && nodes.add(node.group(1)), "invalid or duplicate Maven node");
            String[] parts = node.group(2).split(":", -1);
            if (nodes.size() == 1) {
                require(parts.length == 4 && parts[0].equals("example") && parts[2].equals("jar"), "wrong consumer root");
                continue;
            }
            require(parts.length == 5 && parts[2].equals("jar") && Set.of("compile", "runtime").contains(parts[4]),
                "unknown runtime coordinate: " + node.group(2));
            require(!List.of("org.springframework", "org.hibernate", "jakarta.persistence").stream().anyMatch(parts[0]::startsWith)
                && !List.of("spring-", "hibernate", "persistence").stream().anyMatch(parts[1].toLowerCase(Locale.ROOT)::contains),
                "forbidden runtime dependency: " + node.group(2));
            coordinates.add(node.group(2));
            String jar = parts[1] + "-" + parts[3] + ".jar";
            require(jar.equals(ReleasedSdkArchive.relative(jar).getFileName().toString()) && jars.containsKey(jar), "missing runtime JAR " + jar);
            expectedJars.add(jar);
            if (parts[1].startsWith("regelsuche-")) {
                require(parts[0].equals(group) && parts[3].equals(version) && modules.add(parts[1]),
                    "unreleased or duplicate SDK module");
                require(jars.get(jar).equals(expectedHashes.get(parts[1])), "different resolved SDK JAR bytes: " + jar);
            }
        }
        require(modules.equals(expectedHashes.keySet()) && jars.keySet().equals(expectedJars), "runtime closure does not match released SDK contract");
        return Map.of("regelsucheModules", modules, "coordinates", coordinates.stream().sorted().toList(), "jarSha256", jars);
    }

    static Map<String, Object> toolchain(String mavenOutput, String javaOutput) throws IOException {
        String text = mavenOutput.replaceAll("\u001b\\[[0-9;]*m", "");
        var mvn = Pattern.compile("Apache Maven (\\d+)\\.(\\d+)\\.(\\d+)(?=\\s|$)").matcher(text);
        require(mvn.find() && mvn.group(1).equals("3")
            && (Integer.parseInt(mvn.group(2)) > 9 || Integer.parseInt(mvn.group(2)) == 9 && Integer.parseInt(mvn.group(3)) >= 9),
            "Maven 3.9.9 or later 3.x required");
        require(Pattern.compile("Java version:\\s*25(?![0-9])").matcher(text).find()
            && Pattern.compile("(?:version\\s+\"|openjdk\\s+)25(?![0-9])").matcher(javaOutput).find(), "JDK 25 required for Maven and CLI");
        return Map.of("mavenVersion", String.join(".", mvn.group(1), mvn.group(2), mvn.group(3)), "mavenJavaMajor", 25, "cliJavaMajor", 25);
    }

    static Map<String, Object> cli(JsonNode contract, String output, String repeated) throws IOException {
        if (contract.has("stdout")) {
            require(output.equals(contract.required("stdout").textValue()), "released CLI result differs");
            return Map.of("policy", "EXACT_OUTPUT");
        }
        var repeat = contract.required("repeatHash");
        String prefix = contract.required("stdoutPrefix").textValue() + repeat.get(0).textValue() + "=";
        var match = Pattern.compile(Pattern.quote(prefix) + "(sha256:[0-9a-f]{64})\n").matcher(output);
        require(match.matches() && output.equals(repeated), "missing or unstable released CLI identity");
        return Map.of("policy", repeat.get(2).textValue(), repeat.get(1).textValue(), match.group(1), "repeatVerified", true);
    }

    static Map<String, String> copyInputs(Path source, Path target) throws IOException {
        ReleasedSdkArchive.noSymlinks(source);
        ReleasedSdkArchive.noSymlinks(target);
        require(Files.isDirectory(source) && !Files.exists(target, LinkOption.NOFOLLOW_LINKS), "consumer staging must be fresh");
        var result = new TreeMap<String, String>();
        Files.createDirectory(target);
        try (var files = Files.walk(source)) {
            for (Path file : files.sorted().toList()) {
                if (file.equals(source)) continue;
                Path relative = source.relativize(file);
                if (Set.of("target", "build", ".gradle", ".git").contains(relative.getName(0).toString())) continue;
                require(!Files.isSymbolicLink(file), "symbolic link in consumer inputs: " + relative);
                Path output = target.resolve(relative);
                if (Files.isDirectory(file, LinkOption.NOFOLLOW_LINKS)) Files.createDirectories(output);
                else {
                    require(Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS), "invalid consumer input: " + relative);
                    Files.copy(file, output);
                    result.put(relative.toString().replace('\\', '/'), ReleasedSdkArchive.digest(output));
                }
            }
        }
        return result;
    }
}
