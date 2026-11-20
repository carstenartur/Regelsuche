package de.regelsuche.build;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.nio.file.Files;
import java.nio.file.Path;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathFactory;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

class MavenDistributionArchiveGateContractTest {
    @Test
    void fullLifecycleRunsTheInstalledClientAfterPackaging() throws Exception {
        Element execution = execution("regelsuche-quality-aggregate/pom.xml", "verify-installed-python-client");
        var xpath = XPathFactory.newInstance().newXPath();
        assertEquals("maven-failsafe-plugin", xpath.evaluate("ancestor::*[local-name()='plugin']/*[local-name()='artifactId']", execution));
        assertEquals("integration-test", xpath.evaluate("*[local-name()='goals']/*[local-name()='goal'][1]", execution));
        assertEquals("verify", xpath.evaluate("*[local-name()='goals']/*[local-name()='goal'][2]", execution));
        String driver = Files.readString(MavenPomTestSupport.repositoryRoot().resolve(
            "regelsuche-quality-aggregate/src/test/java/de/regelsuche/quality/aggregate/MavenPythonClientDistributionIT.java"));
        assertFalse(driver.contains("ProcessBuilder("), "No host interpreter in external client qualification");
        Element configuration = child(execution, "configuration");
        assertEquals("de.regelsuche.quality.aggregate.MavenPythonClientDistributionIT", text(configuration, "test"));
        assertEquals("false", text(configuration, "skipTests"));
        assertEquals("false", text(configuration, "skip"));
        assertEquals("true", text(configuration, "failIfNoSpecifiedTests"));
        assertEquals("full", xpath.evaluate("ancestor::*[local-name()='profile']/*[local-name()='id']", execution));
    }

    @Test
    void recordsAnUncachedTransitiveRuntimeClasspathBeforeAssembly() throws Exception {
        Element execution = execution("app/pom.xml", "record-current-runtime-classpath");
        assertEquals("prepare-package", text(execution, "phase"));
        assertEquals("build-classpath", value(execution, "goals", "goal"));
        Element configuration = child(execution, "configuration");
        assertEquals("runtime", text(configuration, "includeScope"));
        assertEquals("false", text(configuration, "excludeTransitive"));
        assertEquals("true", text(configuration, "regenerateFile"));
        assertEquals("UTF-8", text(configuration, "outputEncoding"));
        assertEquals("${project.build.directory}/runtime-classpath.txt", text(configuration, "outputFile"));
    }

    @Test
    void declaresAnExplicitPackageAuditIndependentOfOrdinaryTestSelection() throws Exception {
        Element execution = execution("regelsuche-quality-aggregate/pom.xml", "verify-distribution-archives");
        assertEquals("package", text(execution, "phase"));
        assertEquals("test", value(execution, "goals", "goal"));
        Element configuration = child(execution, "configuration");
        assertEquals("de.regelsuche.quality.aggregate.MavenDistributionArchiveAuditIT", text(configuration, "test"));
        assertEquals("false", text(configuration, "skip"));
        assertEquals("false", text(configuration, "skipTests"));
        assertEquals("true", text(configuration, "failIfNoTests"));
        assertEquals("true", text(configuration, "failIfNoSpecifiedTests"));
        assertEquals("${releaseVersion}", value(configuration, "systemPropertyVariables", "regelsuche.distributionVersion"));
    }

    private static Element execution(String file, String id) throws Exception {
        Path root = MavenPomTestSupport.repositoryRoot();
        var document = MavenPomTestSupport.parse(root.resolve(file));
        String query = "//*[local-name()='execution'][*[local-name()='id']='" + id + "']";
        NodeList matches = (NodeList) XPathFactory.newInstance().newXPath().evaluate(
            query, document, XPathConstants.NODESET);
        assertEquals(1, matches.getLength(), "exactly one execution: " + id);
        return (Element) matches.item(0);
    }

    private static Element child(Element parent, String name) {
        assertNotNull(parent);
        for (var node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof Element element && name.equals(element.getLocalName())) return element;
        }
        throw new AssertionError("Missing element " + name);
    }

    private static String text(Element parent, String name) {
        return child(parent, name).getTextContent().trim();
    }

    private static String value(Element parent, String outer, String inner) {
        return text(child(parent, outer), inner);
    }
}
