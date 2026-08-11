package de.regelsuche.build;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

class MavenTestTimeoutContractTest {

    @Test
    void everySurefireForkHasAFiniteVersionedTimeout() throws Exception {
        Path pom = repositoryRoot().resolve("pom.xml");
        Document document = parse(pom);
        Element project = document.getDocumentElement();
        Element properties = directChild(project, "properties");
        assertEquals(
            "600",
            directChildText(properties, "maven.test.fork.timeout.seconds")
        );

        Element surefire = configuredPlugin(
            project,
            "org.apache.maven.plugins",
            "maven-surefire-plugin"
        );
        assertNotNull(
            surefire,
            "maven-surefire-plugin must be configured under build/plugins"
        );
        Element configuration = directChild(surefire, "configuration");
        assertNotNull(
            configuration,
            "maven-surefire-plugin must declare an active configuration"
        );
        assertEquals(
            "${maven.test.fork.timeout.seconds}",
            directChildText(configuration, "forkedProcessTimeoutInSeconds")
        );
        assertEquals(
            "60",
            directChildText(configuration, "forkedProcessExitTimeoutInSeconds")
        );
    }

    private static Path repositoryRoot() {
        String configured = System.getProperty("regelsuche.repositoryRoot");
        assertNotNull(
            configured,
            "Maven must expose maven.multiModuleProjectDirectory to tests"
        );
        Path root = Path.of(configured).toAbsolutePath().normalize();
        assertTrue(Files.isRegularFile(root.resolve("pom.xml")));
        return root;
    }

    private static Document parse(Path path) throws Exception {
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

    private static Element configuredPlugin(
        Element project,
        String expectedGroup,
        String expectedArtifact
    ) {
        Element build = directChild(project, "build");
        Element plugins = directChild(build, "plugins");
        if (plugins == null) {
            return null;
        }
        for (Node child = plugins.getFirstChild();
                child != null;
                child = child.getNextSibling()) {
            if (!(child instanceof Element candidate)
                    || !"plugin".equals(candidate.getLocalName())) {
                continue;
            }
            String group = directChildText(candidate, "groupId");
            if (group == null || group.isBlank()) {
                group = "org.apache.maven.plugins";
            }
            if (expectedGroup.equals(group)
                    && expectedArtifact.equals(
                        directChildText(candidate, "artifactId")
                    )) {
                return candidate;
            }
        }
        return null;
    }

    private static Element directChild(Element parent, String localName) {
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

    private static String directChildText(Element parent, String localName) {
        Element child = directChild(parent, localName);
        return child == null ? null : child.getTextContent().trim();
    }
}
