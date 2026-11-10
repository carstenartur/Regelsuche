package de.regelsuche.quality.aggregate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

/** Source contract: reporting must follow the active Maven reactor, not a copied module count. */
final class MavenSlowTestModuleContract {
    private static final String AGGREGATE = "regelsuche-quality-aggregate";
    private static final String MODULES = "regelsuche.slow-test-report.modules";

    static Set<String> expectedModules(Path root, String profileId) throws Exception {
        var project = parse(root.resolve("pom.xml"));
        var modules = new LinkedHashSet<String>();
        addModules(modules, child(project, "modules"));
        addModules(modules, child(profile(project, profileId), "modules"));
        var reportable = new LinkedHashSet<String>();
        for (String module : modules) {
            var packaging = child(parse(root.resolve(module).resolve("pom.xml")), "packaging");
            // POM-only modules produce no test reports; the aggregate must not read its own stale output.
            if (!AGGREGATE.equals(module) && (packaging == null || !"pom".equals(packaging.getTextContent().trim())))
                reportable.add(module);
        }
        return Set.copyOf(reportable);
    }

    static void verify(Path root) throws Exception {
        var aggregate = parse(root.resolve(AGGREGATE).resolve("pom.xml"));
        for (String profileId : List.of("product-reactor", "full")) {
            var properties = child("full".equals(profileId) ? profile(aggregate, profileId) : aggregate, "properties");
            var declaration = child(properties, MODULES);
            require(declaration != null, "missing report module declaration for " + profileId);
            var configured = new LinkedHashSet<String>();
            for (String name : declaration.getTextContent().trim().split("[,\\s]+"))
                require(!name.isBlank() && configured.add(name), "blank or duplicate report module: " + name);
            var expected = expectedModules(root, profileId);
            var missing = new LinkedHashSet<>(expected); missing.removeAll(configured);
            var extra = new LinkedHashSet<>(configured); extra.removeAll(expected);
            require(missing.isEmpty() && extra.isEmpty(), profileId + " slow-test module mismatch: missing=" + missing + ", extra=" + extra);
        }
    }

    private static void addModules(Set<String> destination, Element modules) {
        require(modules != null, "missing reactor modules");
        for (var module : children(modules, "module")) {
            String name = module.getTextContent().trim();
            require(!name.isBlank() && destination.add(name), "blank or duplicate reactor module: " + name);
        }
    }
    private static Element profile(Element project, String id) {
        for (var candidate : children(child(project, "profiles"), "profile")) {
            var identifier = child(candidate, "id");
            if (identifier != null && id.equals(identifier.getTextContent().trim())) return candidate;
        }
        throw new IllegalStateException("missing Maven profile " + id);
    }
    private static List<Element> children(Element parent, String name) {
        var result = new ArrayList<Element>();
        if (parent != null) for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling())
            if (node instanceof Element element && name.equals(element.getLocalName())) result.add(element);
        return result;
    }
    private static Element child(Element parent, String name) {
        var matches = children(parent, name);
        require(matches.size() <= 1, "duplicate POM element " + name);
        return matches.isEmpty() ? null : matches.getFirst();
    }
    private static Element parse(Path path) throws Exception {
        var factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true); factory.setXIncludeAware(false); factory.setExpandEntityReferences(false);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        try (var input = Files.newInputStream(path)) { return factory.newDocumentBuilder().parse(input).getDocumentElement(); }
    }
    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("repository root required");
        verify(Path.of(args[0]));
        System.out.println("PASS: product and full slow-test reports cover the exact reportable reactor modules");
    }
}
