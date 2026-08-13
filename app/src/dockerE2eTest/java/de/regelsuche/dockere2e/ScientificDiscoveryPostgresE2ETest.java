package de.regelsuche.dockere2e;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.benchmark.DeterministicDiscoveryExperimentRunner;
import de.regelsuche.discovery.ScientificDiscoveryWorkflow;
import de.regelsuche.example.ScientificSeedCorpora;
import de.regelsuche.example.SeedExpression;
import de.regelsuche.persistence.GraphPersistenceMode;
import de.regelsuche.persistence.PersistenceConfig;
import de.regelsuche.persistence.relational.RelationalPersistenceAdapters;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class ScientificDiscoveryPostgresE2ETest {
    @Container
    static final GenericContainer<?> POSTGRES = PinnedPostgresContainer.create();

    @TempDir
    Path tempDir;

    @Test
    void fullHybridAppWorkflowPersistsAndReloadsScientificDiscoveryRun() throws Exception {
        List<SeedExpression> seeds = ScientificSeedCorpora.curated().stream()
            .filter(seed -> List.of(
                "binomial", "geometric-series", "factorization", "trigonometric", "matrix", "rational", "counterexample"
            ).contains(seed.category()))
            .toList();
        Path artifactRoot = Path.of(System.getProperty(
            "regelsuche.discoveryArtifactsDir",
            tempDir.resolve("artifacts").toString()
        )).resolve("scientific-postgres-e2e");
        PersistenceConfig config = PersistenceConfig.postgresqlWithJsonFallback(
            tempDir.resolve("persistence"),
            jdbcUrl(),
            "regelsuche",
            "regelsuche-demo"
        );

        ScientificDiscoveryWorkflow.RunResult run;
        try (ScientificDiscoveryWorkflow workflow = ScientificDiscoveryWorkflow.boot(config, null)) {
            run = workflow.run("exp-scientific-reproduction", seeds, seeds.size(), 4, artifactRoot);
            assertEquals(GraphPersistenceMode.POSTGRESQL_WITH_JSON_FALLBACK, run.context().effectiveMode());
            assertTrue(run.context().relationalAdapters().isPresent(), "PostgreSQL adapters must be active");
            assertFalse(run.context().graphStore().discoveredTransformations().isEmpty());
            assertTrue(Files.exists(run.artifacts().jsonReport()));
            assertTrue(Files.exists(run.artifacts().htmlReport()));
            assertTrue(Files.exists(artifactRoot.resolve("discovery-report.md")));
            assertTrue(Files.exists(artifactRoot.resolve("discovery-replay.json")));
            assertTrue(Files.exists(run.artifacts().screenshotPng()));
            assertTrue(Files.exists(run.artifacts().replayGif()));
            assertTrue(Files.readString(run.artifacts().htmlReport()).contains("Regelsuche Discovery Report"));
            assertEquals(run.report().renderDeterministicJson(), Files.readString(run.artifacts().jsonReport()));

            RelationalPersistenceAdapters adapters = run.context().relationalAdapters().orElseThrow();
            assertEquals(seeds.size(), adapters.seeds().findAll().size());
            assertEquals(seeds.size(), adapters.searchRuns().findAll().size());
            assertEquals(seeds.size(), adapters.proofJobs().findAll().size());
            assertEquals(6, adapters.reports().findAll().size(),
                "json/html/png/gif/markdown/replay artifact metadata must be stored");
            assertTrue(adapters.hypotheses().orElseThrow().findById("hyp-identity-binomial-1").isPresent());
            assertFalse(adapters.counterexamples().findAll().isEmpty(), "counterexample-trap seed must persist a counterexample");
            assertEquals("SUCCEEDED", adapters.experiments().findById("exp-scientific-reproduction").orElseThrow().status());
        }

        DeterministicDiscoveryExperimentRunner.DiscoveryReport firstReport = run.report();
        try (ScientificDiscoveryWorkflow workflow = ScientificDiscoveryWorkflow.boot(config, null)) {
            DeterministicDiscoveryExperimentRunner.DiscoveryReport secondReport = workflow.run(
                "exp-scientific-reproduction-repeat", seeds, seeds.size(), 1, artifactRoot.resolve("repeat")
            ).report();
            assertEquals(firstReport.renderDeterministicJson(), secondReport.renderDeterministicJson());
        }
    }

    private static String jdbcUrl() {
        return "jdbc:postgresql://" + POSTGRES.getHost() + ":" + POSTGRES.getMappedPort(5432) + "/regelsuche";
    }
}
