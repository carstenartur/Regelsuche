package de.regelsuche.build;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.List;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.xpath.XPathFactory;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;

/** Pins the lifecycle boundaries whose actual execution is covered by the aggregate IT. */
class MavenReleaseReadinessLifecycleContractTest {
    private static final String PLUGINS = "/project/build/plugins/plugin";

    @Test
    void actualAppProducerUsesAMavenOwnedOutput() throws Exception {
        Document app = pom("app");
        assertEquals("${project.build.directory}/reports/hidden-rule-pilot", value(app,
            PLUGINS + "[artifactId='maven-surefire-plugin']/configuration/systemPropertyVariables/"
                + "regelsuche.hiddenRulePilot.outputDirectory"));
        freshOutput(app, "delete-stale-hidden-rule-evidence", "app/target/reports/hidden-rule-pilot");
    }

    @Test
    void qualifiedReleaseVerificationIsMandatoryAfterItsActualProducers() throws Exception {
        Document aggregate = pom("regelsuche-quality-aggregate");
        for (String dependency : List.of("regelsuche-app", "regelsuche-release")) {
            assertEquals("test", value(aggregate,
                "/project/dependencies/dependency[artifactId='" + dependency + "']/scope"));
        }
        String execution = PLUGINS + "[artifactId='maven-surefire-plugin']/executions/"
            + "execution[id='verify-qualified-release-readiness']";
        assertEquals("verify", value(aggregate, execution + "/phase"));
        assertEquals("test", value(aggregate, execution + "/goals/goal"));
        assertEquals("de.regelsuche.quality.aggregate.MavenQualifiedReleaseReadinessIT",
            value(aggregate, execution + "/configuration/test"));
        for (String flag : List.of("skip", "skipTests"))
            assertEquals("false", value(aggregate, execution + "/configuration/" + flag));
        for (String flag : List.of("failIfNoTests", "failIfNoSpecifiedTests"))
            assertEquals("true", value(aggregate, execution + "/configuration/" + flag));
        assertEquals("@{argLine} --enable-native-access=ALL-UNNAMED",
            value(aggregate, execution + "/configuration/argLine"));
        freshOutput(aggregate, "delete-stale-qualified-release-evidence",
            "regelsuche-quality-aggregate/target/reports/release-readiness-qualified");
    }

    @Test
    void bothOrdinaryAndFullLifecyclesReachTheAggregateWithoutAnOptIn() throws Exception {
        Document parent = pom("");
        for (String profile : List.of("product-reactor", "full")) {
            assertEquals("regelsuche-quality-aggregate", value(parent,
                "/project/profiles/profile[id='" + profile + "']/modules/"
                    + "module[text()='regelsuche-quality-aggregate']"));
        }
        assertEquals("true", value(parent,
            "/project/profiles/profile[id='product-reactor']/activation/activeByDefault"));
    }

    private static void freshOutput(Document document, String id, String directory) throws Exception {
        String execution = PLUGINS + "[artifactId='maven-clean-plugin']/executions/execution[id='" + id + "']";
        assertEquals("initialize", value(document, execution + "/phase"));
        assertEquals("clean", value(document, execution + "/goals/goal"));
        assertEquals("true", value(document, execution + "/configuration/excludeDefaultDirectories"));
        assertEquals("1", value(document, "count(" + execution + "/configuration/filesets/fileset)"));
        assertEquals("${maven.multiModuleProjectDirectory}",
            value(document, execution + "/configuration/filesets/fileset/directory"));
        assertEquals("2", value(document, "count(" + execution + "/configuration/filesets/fileset/includes/include)"));
        assertEquals(directory, value(document, execution + "/configuration/filesets/fileset/includes/include[1]"));
        assertEquals(directory + "/**", value(document, execution + "/configuration/filesets/fileset/includes/include[2]"));
        assertEquals("false", value(document, execution + "/configuration/filesets/fileset/followSymlinks"));
    }

    private static Document pom(String module) throws Exception {
        var factory = DocumentBuilderFactory.newInstance();
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        Path root = Path.of(System.getProperty("regelsuche.repositoryRoot"));
        return factory.newDocumentBuilder().parse(root.resolve(module).resolve("pom.xml").toFile());
    }

    private static String value(Document document, String expression) throws Exception {
        return XPathFactory.newInstance().newXPath().evaluate(expression, document).trim();
    }
}
