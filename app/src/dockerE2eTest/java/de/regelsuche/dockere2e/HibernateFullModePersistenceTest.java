package de.regelsuche.dockere2e;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.mining.HypothesisCandidate;
import de.regelsuche.persistence.GraphPersistenceMode;
import de.regelsuche.persistence.PersistenceConfig;
import de.regelsuche.persistence.PersistenceContext;
import de.regelsuche.persistence.relational.DatabaseMigrationRunner;
import de.regelsuche.persistence.relational.CounterexampleEntity;
import de.regelsuche.persistence.relational.PersistenceAdapterFactory;
import de.regelsuche.persistence.relational.RelationalPersistenceAdapters;
import de.regelsuche.persistence.relational.SearchEntityType;
import de.regelsuche.persistence.relational.SearchFacet;
import de.regelsuche.persistence.relational.SearchIndexDocument;
import de.regelsuche.persistence.relational.SearchQuery;
import de.regelsuche.validation.CandidateProofStatus;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class HibernateFullModePersistenceTest {
    @Container
    static final GenericContainer<?> POSTGRES = PinnedPostgresContainer.create();

    @TempDir
    Path tempDir;

    @Test
    void migrationsAreIdempotentAndCreateSchemaHistory() throws Exception {
        try (Connection connection = DriverManager.getConnection(jdbcUrl(), "regelsuche", "regelsuche-demo")) {
            new DatabaseMigrationRunner().migrate(connection);
            new DatabaseMigrationRunner().migrate(connection);
            try (ResultSet resultSet = connection.createStatement()
                .executeQuery("SELECT count(*) FROM regelsuche_schema_history")) {
                assertTrue(resultSet.next());
                assertEquals(5, resultSet.getInt(1));
            }
        }
    }

    @Test
    void hibernateHypothesisRepositoryRoundTripsAssumptionsAndStatus() {
        PersistenceConfig config = postgresConfig();
        try (RelationalPersistenceAdapters adapters = PersistenceAdapterFactory.create(config, null).orElseThrow()) {
            HypothesisCandidate hypothesis = new HypothesisCandidate(
                "hyp-roundtrip",
                "A + 0",
                "A",
                List.of("path-1"),
                List.of(new HypothesisCandidate.ExpressionPair("a + 0", "a")),
                List.of("A is real"),
                0.72,
                CandidateProofStatus.VALIDATED_BY_EXAMPLES,
                false,
                List.of("neutral element"),
                java.util.Map.of("B", List.of("x + y")),
                Instant.parse("2026-05-25T00:00:00Z")
            );

            adapters.hypotheses().orElseThrow().save(hypothesis.id(), hypothesis);
            HypothesisCandidate reloaded = adapters.hypotheses().orElseThrow().findById(hypothesis.id()).orElseThrow();

            assertEquals(List.of("A is real"), reloaded.assumptions());
            assertEquals(List.of("path-1"), reloaded.supportingPaths());
            assertEquals(List.of(new HypothesisCandidate.ExpressionPair("a + 0", "a")), reloaded.supportingExpressions());
            assertEquals(List.of("neutral element"), reloaded.parameterRelations());
            assertEquals(java.util.Map.of("B", List.of("x + y")), reloaded.expressionPlaceholders());
            assertEquals(CandidateProofStatus.VALIDATED_BY_EXAMPLES, reloaded.proofStatus());
            assertEquals(false, reloaded.counterexampleStatus());
        }
    }

    @Test
    void hibernateHypothesisRepositoryRejectsMismatchedSaveId() {
        PersistenceConfig config = postgresConfig();
        try (RelationalPersistenceAdapters adapters = PersistenceAdapterFactory.create(config, null).orElseThrow()) {
            HypothesisCandidate hypothesis = new HypothesisCandidate(
                "hyp-actual-id",
                "A + 0",
                "A",
                List.of(),
                List.of(),
                List.of(),
                0.1,
                CandidateProofStatus.OBSERVED,
                null,
                List.of(),
                java.util.Map.of(),
                Instant.parse("2026-05-25T00:00:00Z")
            );
            assertThrows(IllegalArgumentException.class,
                () -> adapters.hypotheses().orElseThrow().save("different-id", hypothesis));
        }
    }

    @Test
    void hibernateCounterexampleRepositoryPersistsHypothesisForeignKeyFromScalarId() {
        PersistenceConfig config = postgresConfig();
        try (RelationalPersistenceAdapters adapters = PersistenceAdapterFactory.create(config, null).orElseThrow()) {
            HypothesisCandidate hypothesis = new HypothesisCandidate(
                "hyp-for-counterexample",
                "A + 0",
                "A",
                List.of(),
                List.of(),
                List.of(),
                0.5,
                CandidateProofStatus.OBSERVED,
                null,
                List.of(),
                java.util.Map.of(),
                Instant.parse("2026-05-25T00:00:00Z")
            );
            adapters.hypotheses().orElseThrow().save(hypothesis.id(), hypothesis);

            CounterexampleEntity counterexample = new CounterexampleEntity(
                "ce-1",
                hypothesis.id(),
                "1 + 0",
                "1",
                "1",
                List.of(),
                Instant.parse("2026-05-25T00:00:00Z")
            );
            adapters.counterexamples().save(counterexample);

            CounterexampleEntity reloaded = adapters.counterexamples().findById("ce-1").orElseThrow();
            assertEquals("hyp-for-counterexample", reloaded.hypothesisId());
        }
    }

    @Test
    void hibernateSearchFindsFullTextAndFacetMatches() {
        PersistenceConfig config = postgresConfig();
        try (RelationalPersistenceAdapters adapters = PersistenceAdapterFactory.create(config, null).orElseThrow()) {
            adapters.searchIndex().index(new SearchIndexDocument(
                SearchEntityType.HYPOTHESIS,
                "hyp-search",
                "Polynomial hypothesis",
                "Expand quadratic expansion expression and preserve assumptions",
                List.of(new SearchFacet("domain", "polynomial"), new SearchFacet("status", "validated")),
                Instant.now()
            ));
            adapters.searchIndex().index(new SearchIndexDocument(
                SearchEntityType.REPORT,
                "report-search",
                "Discovery report",
                "Quality dashboard for polynomial expansion",
                List.of(new SearchFacet("domain", "polynomial")),
                Instant.now()
            ));
            adapters.searchIndex().index(new SearchIndexDocument(
                SearchEntityType.SEED,
                "seed-search",
                "Linear seed",
                "Solve x + 1 = 2",
                List.of(new SearchFacet("domain", "equation")),
                Instant.now()
            ));

            List<String> ids = adapters.searchIndex().search(new SearchQuery(
                "quadratic expansion",
                List.of(new SearchFacet("domain", "polynomial")),
                List.of(SearchEntityType.HYPOTHESIS, SearchEntityType.REPORT, SearchEntityType.SEED),
                10
            )).stream().map(result -> result.document().entityId()).toList();

            assertEquals(List.of("hyp-search", "report-search"), ids);
        }
    }

    @Test
    void hibernateSearchCollectsEnoughHitsAfterApplyingTypeFilter() {
        PersistenceConfig config = postgresConfig();
        try (RelationalPersistenceAdapters adapters = PersistenceAdapterFactory.create(config, null).orElseThrow()) {
            for (int i = 0; i < 40; i++) {
                adapters.searchIndex().index(new SearchIndexDocument(
                    SearchEntityType.REPORT,
                    "report-filter-" + i,
                    "expansion expansion expansion",
                    "expansion coverage report " + i,
                    List.of(new SearchFacet("domain", "polynomial")),
                    Instant.now()
                ));
            }
            adapters.searchIndex().index(new SearchIndexDocument(
                SearchEntityType.HYPOTHESIS,
                "hyp-filter-target",
                "Hypothesis candidate",
                "expansion",
                List.of(new SearchFacet("domain", "polynomial")),
                Instant.now()
            ));

            List<String> ids = adapters.searchIndex().search(new SearchQuery(
                "expansion",
                List.of(new SearchFacet("domain", "polynomial")),
                List.of(SearchEntityType.HYPOTHESIS),
                1
            )).stream().map(result -> result.document().entityId()).toList();

            assertEquals(List.of("hyp-filter-target"), ids);
        }
    }

    @Test
    void fullModeBootsWithPostgresWithoutNeo4j() {
        PersistenceConfig config = postgresConfig();
        try (PersistenceContext context = PersistenceContext.from(config, null)) {
            assertEquals(GraphPersistenceMode.POSTGRESQL_WITH_JSON_FALLBACK, context.effectiveMode());
            assertTrue(context.relationalAdapters().isPresent());
        }
    }

    private PersistenceConfig postgresConfig() {
        return PersistenceConfig.postgresqlWithJsonFallback(tempDir, jdbcUrl(), "regelsuche", "regelsuche-demo");
    }

    private static String jdbcUrl() {
        return "jdbc:postgresql://" + POSTGRES.getHost() + ":" + POSTGRES.getMappedPort(5432) + "/regelsuche";
    }
}
