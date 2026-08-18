package de.regelsuche.build;

import static de.regelsuche.build.MavenPomTestSupport.directChild;
import static de.regelsuche.build.MavenPomTestSupport.directChildText;
import static de.regelsuche.build.MavenPomTestSupport.parse;
import static de.regelsuche.build.MavenPomTestSupport.repositoryRoot;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.nio.file.Path;
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
}
