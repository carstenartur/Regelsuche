package de.regelsuche.benchmark;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.benchmark.HistoricalRediscoveryCorpus.Relation;
import de.regelsuche.benchmark.HistoricalRediscoveryCorpus.Role;
import java.util.HashSet;
import org.junit.jupiter.api.Test;

class HistoricalRediscoveryCorpusTest {
    private static final String SHA256 = "0".repeat(64);

    @Test
    void loadsTheFrozenBalancedDiagnosticCorpus() {
        HistoricalRediscoveryCorpus.Corpus corpus =
            HistoricalRediscoveryCorpus.load();

        assertEquals(HistoricalRediscoveryCorpus.SCHEMA, corpus.schema());
        assertEquals("FROZEN_DIAGNOSTIC_CORPUS", corpus.evidenceStatus());
        assertEquals(14, corpus.cases().size());
        assertEquals(
            corpus.cases().size(),
            new HashSet<>(corpus.cases().stream()
                .map(HistoricalRediscoveryCorpus.Case::id)
                .toList()).size());
        assertTrue(corpus.cases().stream().anyMatch(value ->
            value.id().equals("sophie-germain")));
        assertTrue(corpus.cases().stream().anyMatch(value ->
            value.role() == Role.SEARCH_POLICY_CONTROL));
        assertTrue(corpus.cases().stream().anyMatch(value ->
            value.role() == Role.NEGATIVE_CONTROL
                && value.relation() == Relation.NOT_EQUIVALENT));
    }

    @Test
    void parserRejectsMissingFieldsUnknownFieldsAndInvalidControls() {
        String valid = minimalCase("NOT_EQUIVALENT", "NEGATIVE_CONTROL", "1");
        HistoricalRediscoveryCorpus.parse(valid, SHA256);

        assertThrows(
            IllegalArgumentException.class,
            () -> HistoricalRediscoveryCorpus.parse(
                valid.replace("\"claimBoundary\":\"bounded\",", ""),
                SHA256));
        assertThrows(
            IllegalArgumentException.class,
            () -> HistoricalRediscoveryCorpus.parse(
                valid.replace("\"family\":\"TEST\",",
                    "\"family\":\"TEST\",\"unknown\":true,"),
                SHA256));
        assertThrows(
            IllegalArgumentException.class,
            () -> HistoricalRediscoveryCorpus.parse(
                minimalCase("EQUIVALENT", "NEGATIVE_CONTROL", "1"),
                SHA256));
        assertThrows(
            IllegalArgumentException.class,
            () -> HistoricalRediscoveryCorpus.parse(
                minimalCase("NOT_EQUIVALENT", "NEGATIVE_CONTROL", "1.5"),
                SHA256));
    }

    private String minimalCase(
        String relation,
        String role,
        String maxDepth
    ) {
        return """
            {
              "schema":"regelsuche.historical-rediscovery-corpus/v1",
              "evidenceStatus":"FROZEN_DIAGNOSTIC_CORPUS",
              "inventoryRevision":"test/v1",
              "claimBoundary":"bounded",
              "cases":[{
                "id":"case",
                "family":"TEST",
                "source":"x",
                "target":"y",
                "relation":"%s",
                "role":"%s",
                "diagnosticPurpose":"CONTROL",
                "provenance":"TEST_FIXTURE",
                "targetRelation":"SYNTAX_EXACT",
                "oracleMaxDepth":%s,
                "oracleMaxVisitedStates":2,
                "searchMaxDepth":1,
                "searchMaxVisitedStates":2,
                "maxCandidatesPerState":1,
                "maxExpandingSteps":1,
                "beamWidth":1
              }]
            }
            """.formatted(relation, role, maxDepth);
    }
}
