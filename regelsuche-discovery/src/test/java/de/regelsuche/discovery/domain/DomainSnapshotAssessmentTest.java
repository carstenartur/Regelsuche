package de.regelsuche.discovery.domain;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.JsonNode;
import de.regelsuche.discovery.domain.DiscoveryDomain.DiscoveryBudget;
import de.regelsuche.discovery.domain.DiscoveryDomain.DiscoverySeed;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DomainSnapshotAssessmentTest {
    @TempDir Path directory;

    @Test void expressionProofNamesExactInputsAndKeepsExternalDecisionsIndependent() {
        var source = workspace(new ExpressionRewriteDiscoveryDomain("x"), "x + 0");
        var result = assess(source);
        assertEquals("PROVED_POLYNOMIAL_IDENTITY", result.at("/proof/status").asText());
        assertEquals("x + 0", result.at("/proof/originalSource").asText());
        assertEquals("x", result.at("/proof/requestedTarget").asText());
        assertEquals(source.evidence().contentHash(), result.at("/source/evidenceHash").asText());
        assertEquals("POST_FREEZE_REVIEW_REQUIRED", result.at("/externalNovelty/status").asText());
        assertEquals("BLOCKED", result.at("/promotion/status").asText());
        assertEquals("BLOCKED", result.at("/publicEvidence/status").asText());
        assertEquals(0, result.at("/ablation/budget/maxDepth").asInt(-1));
        assertEquals(5, result.at("/ablation/resources").size());
        assertEquals(result, assess(source));
    }

    @Test void canonicalTraceForTranscendentalInputCannotReceivePolynomialProof() {
        var result = assess(workspace(new ExpressionRewriteDiscoveryDomain("sin(x)"), "sin(x) + 0"));
        assertEquals("OUTSIDE_CHECKED_POLYNOMIAL_FRAGMENT", result.at("/proof/status").asText());
        assertEquals("EXACT_DISCOVERY_REPLAY", result.at("/validation/method").asText());
    }

    @Test void twoSequenceAdaptersRetainConditionalProofAndFiniteEvidence() {
        for (var source : new DomainExportWorkspace[]{
                workspace(new FiniteDifferenceSequenceDomain(), "observed=1,4,9,16;holdout=25,36"),
                workspace(new LinearRecurrenceSequenceDomain(), "observed=0,1,1,2,3,5;holdout=8,13;maximumOrder=3")}) {
            var result = assess(source);
            assertEquals("PROVED_MODEL", result.at("/proof/status").asText(), result::toString);
            assertEquals("CONFIRMED_FINITE_DATA", result.at("/validation/status").asText());
            assertEquals("CONFIRMED_FINITE_DATA", result.at("/proof/modelReceipt/finiteCheck/status").asText());
            assertEquals(source.evidence().certificate().contentHash(), result.at("/source/renderedCertificateHash").asText());
        }
    }

    @Test void refutationIncludesReproducedWitnessWithoutIssuingProof() {
        var result = assess(workspace(new FiniteDifferenceSequenceDomain(), "observed=1,4,9,16;holdout=26"));
        assertEquals("REFUTED", result.at("/validation/status").asText());
        assertFalse(result.at("/counterexamples/attempts").isEmpty());
        var witness = result.at("/counterexamples/independentCheck");
        assertEquals("REFUTED_FINITE_MODEL", witness.path("status").asText());
        assertEquals(4, witness.path("index").asInt(-1));
        assertEquals("26", witness.path("retained").asText());
        assertEquals("25", witness.path("predicted").asText());
        assertTrue(result.at("/counterexamples/attempts").findValuesAsText("candidateHash")
            .contains(witness.path("candidateHash").asText()));
        assertEquals("NO_SELECTED_CANDIDATE", result.at("/proof/status").asText());
    }

    private JsonNode assess(DomainExportWorkspace workspace) {
        return DomainExportWorkspace.read(DomainSnapshotAssessment.assess(workspace).toCanonicalJson());
    }

    private <S,C,K> DomainExportWorkspace workspace(DiscoveryDomain<S,C,K> domain, String payload) {
        var source = new DomainDiscoveryRunner().run("assessment", domain,
            DiscoverySeed.create("input", domain.domainId(), payload, "bounded test"),
            new DiscoveryBudget(4, 25, 80, 10, 10, 32)).evidence();
        new DomainDiscoveryExport().write(directory, source);
        return DomainExportWorkspace.fromVerified(new DomainDiscoveryExportVerifier().requireVerified(directory));
    }
}
