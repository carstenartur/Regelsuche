package de.regelsuche.evolution;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class EvolutionRewriteProgramBaselineAblationSchemaContractTest {
    @Test
    void schemaContainsEveryRuntimeTrackAndFailClosedPolicy()
            throws Exception {
        String schema = Files.readString(repositoryRoot()
            .resolve("docs/schemas/")
            .resolve("regelsuche-evolution-rewrite-program-baseline-ablation-plan-v1.schema.json"));

        assertTrue(schema.contains(
            "regelsuche.evolution-rewrite-program-baseline-ablation-plan/v1"));
        assertTrue(schema.contains("\"minItems\": 8"));
        assertTrue(schema.contains("\"maxItems\": 8"));
        assertTrue(schema.contains(
            "\"const\": \"MATCHED_PRIMITIVE_AND_TOTAL_WORK\""));
        assertTrue(schema.contains("\"const\": \"NOT_STARTED\""));
        assertTrue(schema.contains("\"additionalProperties\": false"));
        for (EvolutionRewriteProgramBaselineAblationPlan.TrackKind value :
                EvolutionRewriteProgramBaselineAblationPlan.TrackKind.values()) {
            assertTrue(schema.contains("\"" + value.name() + "\""),
                value.name());
        }
    }

    private static Path repositoryRoot() {
        Path root = Path.of("").toAbsolutePath().normalize();
        while (root != null && !Files.exists(root.resolve("settings.gradle"))) {
            root = root.getParent();
        }
        if (root == null) {
            throw new IllegalStateException("repository root not found");
        }
        return root;
    }
}
