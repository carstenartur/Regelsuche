package de.regelsuche.build;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.SAXException;

/** Shared secure POM parsing and direct-child helpers for build contracts. */
final class MavenPomTestSupport {
    private MavenPomTestSupport() {
    }

    static Path repositoryRoot() {
        String configured = System.getProperty("regelsuche.repositoryRoot");
        assertNotNull(
            configured,
            "Maven must expose maven.multiModuleProjectDirectory to tests"
        );
        Path root = Path.of(configured).toAbsolutePath().normalize();
        assertTrue(
            Files.isRegularFile(root.resolve("pom.xml")),
            () -> "no root pom.xml below " + root
        );
        return root;
    }

    static Document parse(Path path)
            throws ParserConfigurationException, IOException, SAXException {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        factory.setFeature(
            "http://apache.org/xml/features/disallow-doctype-decl",
            true
        );
        factory.setFeature(
            "http://xml.org/sax/features/external-general-entities",
            false
        );
        factory.setFeature(
            "http://xml.org/sax/features/external-parameter-entities",
            false
        );
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        try (InputStream input = Files.newInputStream(path)) {
            return factory.newDocumentBuilder().parse(input);
        }
    }

    static Element directChild(Element parent, String localName) {
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

    static String directChildText(Element parent, String localName) {
        Element child = directChild(parent, localName);
        return child == null ? null : child.getTextContent().trim();
    }

    static List<String> directChildTexts(
            Element parent,
            String localName) {
        assertNotNull(parent);
        List<String> values = new ArrayList<>();
        for (Node child = parent.getFirstChild();
                child != null;
                child = child.getNextSibling()) {
            if (child instanceof Element element
                    && localName.equals(element.getLocalName())) {
                values.add(element.getTextContent().trim());
            }
        }
        return List.copyOf(values);
    }
}
