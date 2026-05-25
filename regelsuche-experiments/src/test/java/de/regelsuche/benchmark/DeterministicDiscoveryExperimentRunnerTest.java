package de.regelsuche.benchmark;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.example.SeedExpression;
import java.util.List;
import org.junit.jupiter.api.Test;

class DeterministicDiscoveryExperimentRunnerTest {

    @Test
    void enforcesGlobalBudgetAndProducesDeterministicReport() {
        DeterministicDiscoveryExperimentRunner runner = new DeterministicDiscoveryExperimentRunner(
            2,
            4,
            seed -> new DeterministicDiscoveryExperimentRunner.SeedRunOutcome(
                true,
                "processed " + seed.id(),
                List.of("hyp-" + seed.id()),
                List.of(),
                List.of(seed.expression(), "normalized(" + seed.expression() + ")"),
                7L,
                11L
            )
        );

        List<SeedExpression> seeds = List.of(
            new SeedExpression("seed-c", "(x + 1)^2", "test", "binomial", List.of(), List.of()),
            new SeedExpression("seed-a", "x + 0", "test", "identity", List.of(), List.of()),
            new SeedExpression("seed-b", "x * 1", "test", "identity", List.of(), List.of())
        );

        DeterministicDiscoveryExperimentRunner.DiscoveryReport report = runner.runDetailed(seeds);

        assertEquals(2, report.rows().size(), "global budget must cap the number of processed seeds");
        assertEquals("seed-a", report.rows().get(0).seed().id());
        assertEquals("seed-b", report.rows().get(1).seed().id());
        assertEquals(2, report.metrics().processedSeeds());
        assertEquals(2, report.metrics().successfulSeeds());
        assertEquals(2, report.metrics().hypotheses());
        assertTrue(report.renderDeterministicJson().contains("\"schema\":\"regelsuche.discovery-report/v1\""));
    }

    @Test
    void plainRunnerInterfaceRemainsBackwardsCompatible() {
        DeterministicDiscoveryExperimentRunner runner = new DeterministicDiscoveryExperimentRunner(
            2,
            2,
            seed -> new DeterministicDiscoveryExperimentRunner.SeedRunOutcome(
                seed.expression().contains("+ 0"),
                "summary for " + seed.expression(),
                List.of(),
                List.of(),
                List.of(),
                0L,
                0L
            )
        );

        List<DiscoveryExperimentRunner.ExperimentResult> results = runner.run(List.of("x * 1", "x + 0", "z + 0"));

        assertEquals(2, results.size());
        assertEquals("x * 1", results.get(0).seedExpression());
        assertEquals("summary for x * 1", results.get(0).summary());
        assertEquals("x + 0", results.get(1).seedExpression());
        assertTrue(results.get(1).success());
    }
}
