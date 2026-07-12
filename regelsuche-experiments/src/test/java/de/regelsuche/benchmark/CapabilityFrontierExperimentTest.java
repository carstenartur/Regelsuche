package de.regelsuche.benchmark;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.search.SearchHeuristic;
import de.regelsuche.transform.AstRewriteTransformationEngine;
import de.regelsuche.transform.DifferenceOfSquaresPreparationOperator;
import de.regelsuche.transform.HypothesisTransformationEngine;
import de.regelsuche.transform.TransformationEngine;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CapabilityFrontierExperimentTest {
    private final CapabilityFrontierExperiment experiment = new CapabilityFrontierExperiment();

    @Test
    void targetGuidanceReducesStatesWithRealAtomicRewriteRules() {
        CapabilityFrontierExperiment.GuidanceComparison comparison =
            experiment.compareGuidance(neutralElementScenario());

        assertTrue(comparison.unguided().reached());
        assertTrue(comparison.guided().reached());
        assertEquals(CapabilityFrontierExperiment.GuidanceVerdict.FEWER_STATES,
            comparison.verdict());
        assertTrue(comparison.guided().exploredStates() < comparison.unguided().exploredStates(),
            comparison.toString());
        assertEquals("x", comparison.guided().path().getLast());
        assertTrue(comparison.guided().ruleIds().stream()
            .anyMatch(rule -> rule.equals("ast_canonical_normalize")
                || rule.equals("ast_multiply_one_right")
                || rule.equals("ast_add_zero_right")));
    }

    @Test
    void frontierSeparatesMissingBridgeCapabilityFromSearchFailure() {
        CapabilityFrontierExperiment.CapabilityComparison comparison =
            experiment.compareCapability(sophieGermainScenario());

        assertEquals(CapabilityFrontierExperiment.CapabilityVerdict.CAPABILITY_REQUIRED,
            comparison.verdict(), comparison.toString());
        assertFalse(comparison.withoutCapability().reached());
        assertTrue(comparison.withCapability().reached());
        assertTrue(comparison.withCapability().ruleIds().contains(
            DifferenceOfSquaresPreparationOperator.RULE_ID));
        assertTrue(comparison.withCapability().ruleIds().contains(
            "ast_square_difference_factor"));
    }

    @Test
    void reportIsDeterministicAndMachineReadable(@TempDir Path tempDirectory) throws Exception {
        CapabilityFrontierExperiment.FrontierReport first = experiment.run(
            List.of(neutralElementScenario()),
            List.of(sophieGermainScenario()));
        CapabilityFrontierExperiment.FrontierReport second = experiment.run(
            List.of(neutralElementScenario()),
            List.of(sophieGermainScenario()));

        assertEquals(first.toJson(), second.toJson());
        assertTrue(first.toJson().contains("regelsuche.capability-frontier/v1"));
        assertTrue(first.toJson().contains("FEWER_STATES"));
        assertTrue(first.toJson().contains("CAPABILITY_REQUIRED"));
        assertTrue(first.toJson().contains("\"exploredStates\""));
        assertTrue(first.toJson().contains("\"generatedTransformations\""));

        Path output = experiment.write(tempDirectory.resolve("capability-frontier.json"), first);
        assertEquals(first.toJson(), Files.readString(output, StandardCharsets.UTF_8));
    }

    private static CapabilityFrontierExperiment.GuidanceScenario neutralElementScenario() {
        return new CapabilityFrontierExperiment.GuidanceScenario(
            "neutral-elements",
            "(x + 0) * 1",
            "x",
            new AstRewriteTransformationEngine(),
            new SearchHeuristic(3, 16, 1, 2, 1, 8)
        );
    }

    private static CapabilityFrontierExperiment.CapabilityScenario sophieGermainScenario() {
        AstRewriteTransformationEngine primitive = new AstRewriteTransformationEngine(
            AstRewriteTransformationEngine.defaultRules(), 128, 160);
        TransformationEngine withBridge = new HypothesisTransformationEngine(
            primitive,
            List.of(new DifferenceOfSquaresPreparationOperator()),
            16);
        return new CapabilityFrontierExperiment.CapabilityScenario(
            "sophie-germain-frontier",
            DifferenceOfSquaresPreparationOperator.RULE_ID,
            "x^4 + 4*y^4",
            "(x^2 + 2*y^2 - 2*x*y) * (x^2 + 2*y^2 + 2*x*y)",
            primitive,
            withBridge,
            new SearchHeuristic(4, 200, 1, 10, 200, 200)
        );
    }
}
