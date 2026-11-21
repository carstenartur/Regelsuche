package de.regelsuche.build;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.Iterator;
import java.util.List;
import javax.xml.namespace.NamespaceContext;
import javax.xml.xpath.XPathFactory;
import org.junit.jupiter.api.Test;

class MavenSympyRuntimeContractTest {
    @Test
    void externalTestsMoveTogetherWithTheirMandatoryFullVerification() throws Exception {
        var root = Path.of(System.getProperty("regelsuche.repositoryRoot"));
        var pom = MavenPomTestSupport.parse(root.resolve("regelsuche-math-sympy/pom.xml"));
        var xpath = XPathFactory.newInstance().newXPath();
        xpath.setNamespaceContext(new NamespaceContext() {
            public String getNamespaceURI(String prefix) { return "http://maven.apache.org/POM/4.0.0"; }
            public String getPrefix(String uri) { return "m"; }
            public Iterator<String> getPrefixes(String uri) { return List.of("m").iterator(); }
        });
        var host = "//m:build/m:plugins/m:plugin[m:artifactId='maven-surefire-plugin']/m:configuration/m:excludedGroups";
        assertEquals("sympy-runtime", xpath.evaluate(host, pom));
        var full = "//m:profile[m:id='full']/m:build/m:plugins/m:plugin[m:artifactId='maven-failsafe-plugin']";
        assertEquals("false", xpath.evaluate(full + "/m:configuration/m:skipTests", pom));
        assertEquals("true", xpath.evaluate(full + "/m:configuration/m:failIfNoTests", pom));
        assertEquals("de.regelsuche.math.sympy.SympyRuntimeContainerIT", xpath.evaluate(full + "/m:configuration/m:test", pom));
        assertEquals("integration-test", xpath.evaluate(full + "/m:executions/m:execution/m:goals/m:goal[1]", pom));
        assertEquals("verify", xpath.evaluate(full + "/m:executions/m:execution/m:goals/m:goal[2]", pom));
    }
}
