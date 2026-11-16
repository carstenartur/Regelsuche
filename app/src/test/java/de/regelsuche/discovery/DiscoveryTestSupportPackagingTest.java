package de.regelsuche.discovery;

import static org.junit.jupiter.api.Assertions.assertEquals;

import de.regelsuche.App;
import de.regelsuche.learning.HypothesisPromotionPipeline;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class DiscoveryTestSupportPackagingTest {
    @Test
    void applicationOutputExcludesDiscoveryTestSupport() throws IOException {
        URL appOutput = App.class.getProtectionDomain().getCodeSource().getLocation();
        try (URLClassLoader appClasses = new URLClassLoader(new URL[] {appOutput}, null)) {
            List<String> packagedFixtures = Stream.of(
                    ScientificDiscoveryWorkflow.class,
                    DiscoveryEngineFactory.class,
                    DiscoveryWorkflowConfiguration.class,
                    MacroLearningService.class,
                    SemanticDiscoveryReportViewAssembler.class,
                    HypothesisPromotionPipeline.class)
                .map(Class::getName)
                .filter(name -> appClasses.findResource(name.replace('.', '/') + ".class") != null)
                .toList();
            assertEquals(List.of(), packagedFixtures);
        }
    }
}
