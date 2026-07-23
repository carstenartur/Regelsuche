package de.regelsuche.radar;

import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.graph.InMemoryExpressionGraphStore;
import de.regelsuche.inventory.InMemoryRuleInventoryRepository;
import de.regelsuche.plugin.PluginRuntimeConfig;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class AstRuleRadarPerformanceTest {
    @Test
    void boundedLargeAstInspectionRemainsInteractive() {
        String expression = "x";
        for (int index = 0; index < 45; index++) {
            expression = "(" + expression + " + 0)";
        }
        String input = expression;
        try (AstRuleRadarService service = new AstRuleRadarService(
            new InMemoryRuleInventoryRepository(),
            new InMemoryExpressionGraphStore(),
            PluginRuntimeConfig.defaults())) {
            AstRuleRadar.Context context = new AstRuleRadar.Context(
                de.regelsuche.knowledge.RuleProfile.CORE,
                Set.of(), Set.of(),
                false, false,
                de.regelsuche.validation.CandidateProofStatus.VALIDATED_BY_EXAMPLES,
                "DISCOVERY", "",
                8, 160,
                List.of(), false, "", Map.of());

            AstRuleRadar.Snapshot snapshot = assertTimeoutPreemptively(
                Duration.ofSeconds(5),
                () -> service.inspect(input, context));
            assertTrue(snapshot.valid());
            assertTrue(snapshot.nodes().size() >= 90);
            assertTrue(snapshot.candidates().size() <= 160);
        }
    }
}
