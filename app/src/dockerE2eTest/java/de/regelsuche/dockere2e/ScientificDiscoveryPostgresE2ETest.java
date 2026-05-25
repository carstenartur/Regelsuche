package de.regelsuche.dockere2e;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.benchmark.DeterministicDiscoveryExperimentRunner;
import de.regelsuche.benchmark.DiscoveryReplayArtifactWriter;
import de.regelsuche.demo.DemoService;
import de.regelsuche.example.ScientificSeedCorpora;
import de.regelsuche.example.SeedExpression;
import de.regelsuche.graph.InMemoryExpressionGraphStore;
import de.regelsuche.mining.HypothesisCandidate;
import de.regelsuche.persistence.PersistenceConfig;
import de.regelsuche.persistence.relational.DiscoveryExperimentEntity;
import de.regelsuche.persistence.relational.ExportReportEntity;
import de.regelsuche.persistence.relational.PersistenceAdapterFactory;
import de.regelsuche.persistence.relational.ProofJobMetadataEntity;
import de.regelsuche.persistence.relational.RelationalPersistenceAdapters;
import de.regelsuche.persistence.relational.SearchRunEntity;
import de.regelsuche.persistence.relational.SeedExpressionEntity;
import de.regelsuche.validation.CandidateProofStatus;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class ScientificDiscoveryPostgresE2ETest {
    @Container
    static final GenericContainer<?> POSTGRES = new GenericContainer<>("postgres:16-alpine")
        .withEnv("POSTGRES_DB", "regelsuche")
        .withEnv("POSTGRES_USER", "regelsuche")
        .withEnv("POSTGRES_PASSWORD", "regelsuche-demo")
        .withExposedPorts(5432)
        .waitingFor(Wait.forListeningPort());

    @TempDir
    Path tempDir;

    @Test
    void scientificDiscoveryRunPersistsPostgresMetadataAndReplayArtifacts() throws Exception {
        Map<String, String> demosBySeed = Map.of(
            "identity-binomial-1", "binomial",
            "matrix-identity-1", "math-matrix",
            "rational-simplification-1", "rational"
        );
        List<SeedExpression> seeds = ScientificSeedCorpora.curated().stream()
            .filter(seed -> demosBySeed.containsKey(seed.id()))
            .toList();
        InMemoryExpressionGraphStore graphStore = new InMemoryExpressionGraphStore();
        DeterministicDiscoveryExperimentRunner runner = new DeterministicDiscoveryExperimentRunner(
            seeds.size(),
            2,
            seed -> runDemoSeed(seed, demosBySeed.get(seed.id()), graphStore)
        );

        DeterministicDiscoveryExperimentRunner.DiscoveryReport report = runner.runDetailed(seeds);
        DiscoveryReplayArtifactWriter.ArtifactBundle artifacts = new DiscoveryReplayArtifactWriter()
            .write(report, tempDir.resolve("artifacts"));

        PersistenceConfig config = PersistenceConfig.postgresqlWithJsonFallback(
            tempDir.resolve("persistence"),
            jdbcUrl(),
            "regelsuche",
            "regelsuche-demo"
        );
        try (RelationalPersistenceAdapters adapters = PersistenceAdapterFactory.create(config, null).orElseThrow()) {
            for (SeedExpression seed : seeds) {
                adapters.seeds().save(new SeedExpressionEntity(
                    seed.id(), seed.expression(), seed.category(), "scientific", seed.tags(), Instant.now()));
            }
            List<String> runIds = report.rows().stream().map(row -> "run-" + row.seed().id()).toList();
            for (int i = 0; i < report.rows().size(); i++) {
                var row = report.rows().get(i);
                adapters.searchRuns().save(new SearchRunEntity(
                    runIds.get(i),
                    row.seed().expression(),
                    row.replayPath().isEmpty() ? "" : row.replayPath().getLast(),
                    "deterministic-scientific-discovery",
                    row.success() ? "SUCCEEDED" : "FAILED",
                    row.replayPath().size(),
                    0,
                    row.hypotheses(),
                    Instant.now(),
                    Instant.now()
                ));
            }
            adapters.experiments().save(new DiscoveryExperimentEntity(
                "exp-scientific-reproduction",
                "Scientific reproduction smoke run",
                "Testcontainers PostgreSQL E2E run for reproducible discovery",
                "SUCCEEDED",
                runIds,
                Instant.now(),
                Instant.now()
            ));
            adapters.reports().save(new ExportReportEntity(
                "report-scientific-reproduction",
                "exp-scientific-reproduction",
                "Scientific Discovery HTML Report",
                Files.readString(artifacts.htmlReport()),
                "scientific-discovery",
                List.of(),
                "html",
                artifacts.htmlReport().toUri().toString(),
                runIds,
                Instant.now()
            ));
            adapters.hypotheses().orElseThrow().save("hyp-scientific-reproduction", new HypothesisCandidate(
                "hyp-scientific-reproduction",
                "scientific seed corpus",
                "reproduced structures",
                runIds,
                List.of(),
                List.of(),
                1.0,
                CandidateProofStatus.VALIDATED_BY_EXAMPLES,
                false,
                List.of("Testcontainers PostgreSQL reproduction run"),
                Map.of(),
                Instant.now()
            ));
            adapters.proofJobs().save(new ProofJobMetadataEntity(
                "proof-worker-optional-stub",
                "hyp-scientific-reproduction",
                "stub-proof-worker",
                "COMPLETED",
                artifacts.jsonReport().toUri().toString(),
                Instant.now(),
                Instant.now()
            ));

            assertEquals(seeds.size(), adapters.seeds().findAll().size());
            assertEquals("SUCCEEDED", adapters.experiments().findById("exp-scientific-reproduction").orElseThrow().status());
            assertTrue(adapters.reports().findById("report-scientific-reproduction").orElseThrow().body()
                .contains("Regelsuche Discovery Report"));
            assertEquals("COMPLETED", adapters.proofJobs().findById("proof-worker-optional-stub").orElseThrow().status());
        }
    }

    private DeterministicDiscoveryExperimentRunner.SeedRunOutcome runDemoSeed(
        SeedExpression seed,
        String demoId,
        InMemoryExpressionGraphStore graphStore
    ) {
        DemoService.DemoRunResult result = new DemoService(graphStore).run(demoId);
        List<String> replay = result.selectedPath() == null
            ? List.of(result.rootExpression())
            : result.selectedPath().steps().stream()
                .map(step -> step.beforeExpression() + " -> " + step.afterExpression())
                .toList();
        return new DeterministicDiscoveryExperimentRunner.SeedRunOutcome(
            result.targetReached(),
            seed.id() + " reproduced via " + demoId,
            result.selectedPath() == null ? List.of() : List.of(result.selectedPath().id()),
            List.of(),
            replay,
            result.elapsedMillis(),
            0L
        );
    }

    private static String jdbcUrl() {
        return "jdbc:postgresql://" + POSTGRES.getHost() + ":" + POSTGRES.getMappedPort(5432) + "/regelsuche";
    }
}
