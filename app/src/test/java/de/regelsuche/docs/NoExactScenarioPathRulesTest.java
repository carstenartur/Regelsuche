package de.regelsuche.docs;

import static org.junit.jupiter.api.Assertions.assertFalse;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class NoExactScenarioPathRulesTest {
    @Test
    void scenarioRulePacksDoNotContainCompleteScenarioEndpoints() throws Exception {
        DiscoveryBenchmarkScenarioLoader loader = new DiscoveryBenchmarkScenarioLoader();
        for (DiscoveryBenchmarkScenario scenario : loader.loadAll("discovery-scenarios")) {
            String input = scenario.inputExpression();
            String target = scenario.targetExpression();
            try (var paths = Files.walk(Path.of("src/main/resources/discovery-scenario-rules"))) {
                for (Path path : paths.filter(Files::isRegularFile).toList()) {
                    String source = Files.readString(path);
                    assertFalse(source.contains(input), path + " contains scenario input " + scenario.id());
                    assertFalse(source.contains(target), path + " contains scenario target " + scenario.id());
                }
            }
        }
    }
}
