package de.regelsuche.persistence.relational;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class RelationalEntityContractTest {

    @Test
    void searchRunRetainsCanonicalDefaultsAndRejectsInvalidCounters() {
        Instant startedAt = Instant.parse("2026-08-10T00:00:00Z");
        List<String> bestPathIds = new ArrayList<>(
            List.of("state-1", "state-2"));
        SearchRunEntity run = new SearchRunEntity(
            "run-1",
            null,
            null,
            null,
            null,
            3,
            2,
            bestPathIds,
            startedAt,
            null);
        bestPathIds.add("state-after-construction");

        assertEquals("run-1", run.id());
        assertEquals("", run.sourceExpression());
        assertEquals("", run.targetExpression());
        assertEquals("", run.strategy());
        assertEquals("CREATED", run.status());
        assertEquals(3, run.visitedStates());
        assertEquals(2, run.frontierSize());
        assertEquals(List.of("state-1", "state-2"), run.bestPathIds());
        assertEquals(startedAt, run.startedAt());
        assertNull(run.finishedAt());

        assertThrows(IllegalArgumentException.class, () -> new SearchRunEntity(
            "run-2", "x", "y", "best-first", "DONE",
            -1, 0, List.of(), startedAt, startedAt));
        assertThrows(IllegalArgumentException.class, () -> new SearchRunEntity(
            "run-3", "x", "y", "best-first", "DONE",
            0, -1, List.of(), startedAt, startedAt));
        assertThrows(IllegalArgumentException.class, () -> new SearchRunEntity(
            " ", "x", "y", "best-first", "DONE",
            0, 0, List.of(), startedAt, startedAt));
    }

    @Test
    void discoveryAndSeedEntitiesPreserveListsAndNullSafeDefaults() {
        List<String> searchRunIds = new ArrayList<>(
            List.of("run-1", "run-2"));
        DiscoveryExperimentEntity experiment = new DiscoveryExperimentEntity(
            "experiment-1",
            null,
            null,
            null,
            searchRunIds,
            null,
            null);
        searchRunIds.add("run-after-construction");

        assertEquals("experiment-1", experiment.id());
        assertEquals("experiment-1", experiment.name());
        assertEquals("", experiment.description());
        assertEquals("DRAFT", experiment.status());
        assertEquals(List.of("run-1", "run-2"), experiment.searchRunIds());
        assertNotNull(experiment.createdAt());
        assertEquals(experiment.createdAt(), experiment.updatedAt());

        Instant createdAt = Instant.parse("2026-08-10T01:00:00Z");
        List<String> tags = new ArrayList<>(
            List.of("polynomial", "positive"));
        SeedExpressionEntity seed = new SeedExpressionEntity(
            "seed-1", "x^2 + 1", null, null, tags, createdAt);
        tags.add("tag-after-construction");

        assertEquals("seed-1", seed.id());
        assertEquals("x^2 + 1", seed.expression());
        assertEquals("general", seed.domain());
        assertEquals("unknown", seed.difficulty());
        assertEquals(List.of("polynomial", "positive"), seed.tags());
        assertEquals(createdAt, seed.createdAt());
        assertThrows(IllegalArgumentException.class, () -> new SeedExpressionEntity(
            "seed-2", "", "algebra", "easy", List.of(), createdAt));
    }

    @Test
    void reportEntityRetainsSearchReferencesAndCanonicalFacets() {
        Instant createdAt = Instant.parse("2026-08-10T02:00:00Z");
        List<SearchFacet> facets = new ArrayList<>(List.of(
            new SearchFacet(" status ", "confirmed"),
            new SearchFacet("domain", "algebra")));
        List<String> references = new ArrayList<>(List.of("run-2", "run-1"));
        ExportReportEntity report = new ExportReportEntity(
            "report-1",
            null,
            null,
            null,
            null,
            facets,
            null,
            null,
            references,
            createdAt);
        facets.add(new SearchFacet("status", "mutated"));
        references.add("run-after-construction");

        assertEquals("report-1", report.id());
        assertEquals("", report.experimentId());
        assertEquals("report-1", report.title());
        assertEquals("", report.body());
        assertEquals("general", report.domain());
        assertEquals(
            List.of(
                new SearchFacet("domain", "algebra"),
                new SearchFacet("status", "confirmed")),
            report.facets());
        assertEquals("markdown", report.format());
        assertEquals("", report.storageUri());
        assertEquals(List.of("run-2", "run-1"), report.referencedSearchRunIds());
        assertEquals(createdAt, report.createdAt());

        ExportReportEntity compact = new ExportReportEntity(
            "report-2", "experiment-2", "Title", "json", "file:/report.json",
            List.of("run-1"), createdAt);
        assertEquals("", compact.body());
        assertEquals("general", compact.domain());
        assertTrue(compact.facets().isEmpty());
        assertEquals("json", compact.format());
    }

    @Test
    void benchmarkEntityClampsQualityAndRejectsNegativeWork() {
        Instant measuredAt = Instant.parse("2026-08-10T03:00:00Z");
        BenchmarkResultEntity high = new BenchmarkResultEntity(
            "benchmark-1", null, null, 25, 4, 5, 1.7, measuredAt);
        BenchmarkResultEntity low = new BenchmarkResultEntity(
            "benchmark-2", "experiment-1", "baseline", 0, 0, 0, -0.5,
            measuredAt);

        assertEquals("benchmark-1", high.id());
        assertEquals("", high.experimentId());
        assertEquals("benchmark-1", high.benchmarkName());
        assertEquals(25, high.durationMillis());
        assertEquals(4, high.solvedCount());
        assertEquals(5, high.totalCount());
        assertEquals(1.0, high.qualityScore());
        assertEquals(0.0, low.qualityScore());
        assertEquals(measuredAt, high.measuredAt());

        assertThrows(IllegalArgumentException.class, () -> new BenchmarkResultEntity(
            "benchmark-3", "", "invalid", -1, 0, 0, 0.0, measuredAt));
        assertThrows(IllegalArgumentException.class, () -> new BenchmarkResultEntity(
            "benchmark-4", "", "invalid", 0, -1, 0, 0.0, measuredAt));
        assertThrows(IllegalArgumentException.class, () -> new BenchmarkResultEntity(
            "benchmark-5", "", "invalid", 0, 0, -1, 0.0, measuredAt));
    }

    @Test
    void proofMetadataAndCounterexampleKeepExplicitLifecycleEvidence() {
        Instant submittedAt = Instant.parse("2026-08-10T04:00:00Z");
        ProofJobMetadataEntity proof = new ProofJobMetadataEntity(
            "proof-1", null, null, null, null, submittedAt, null);

        assertEquals("proof-1", proof.id());
        assertEquals("", proof.hypothesisId());
        assertEquals("unknown", proof.prover());
        assertEquals("QUEUED", proof.status());
        assertEquals("", proof.artifactUri());
        assertEquals(submittedAt, proof.submittedAt());
        assertNull(proof.completedAt());

        Instant foundAt = Instant.parse("2026-08-10T05:00:00Z");
        List<String> assumptions = new ArrayList<>(List.of("x != 0"));
        CounterexampleEntity counterexample = new CounterexampleEntity(
            "counterexample-1",
            "hypothesis-before-attach",
            null,
            null,
            null,
            assumptions,
            foundAt);
        assumptions.add("mutated-after-construction");

        assertEquals("counterexample-1", counterexample.id());
        assertEquals("hypothesis-before-attach", counterexample.hypothesisId());
        assertEquals("", counterexample.inputExpression());
        assertEquals("", counterexample.expectedExpression());
        assertEquals("", counterexample.actualExpression());
        assertEquals(List.of("x != 0"), counterexample.assumptions());
        assertEquals(foundAt, counterexample.foundAt());

        HypothesisCandidateEntity hypothesis = new HypothesisCandidateEntity(
            "hypothesis-after-attach",
            null,
            "x + 0",
            "x",
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            java.util.Map.of(),
            "PROPOSED",
            null,
            1.0,
            foundAt);
        counterexample.attach(hypothesis);
        assertEquals("hypothesis-after-attach", counterexample.hypothesisId());
        assertEquals(List.of("x != 0"), counterexample.assumptions());
    }
}
