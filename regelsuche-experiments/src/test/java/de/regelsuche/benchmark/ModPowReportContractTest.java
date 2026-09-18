package de.regelsuche.benchmark;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.regelsuche.assumption.AssumptionSignature;
import de.regelsuche.search.program.CompiledAstReplayCodec;
import java.util.List;
import org.junit.jupiter.api.Test;

class ModPowReportContractTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test void resultRetainsActualProgramAndConditionalProofDomain() throws Exception {
        var study = ModPowDagRediscoveryStudy.run();
        var root = JSON.readTree(ModPowDagRediscoveryReport.json(study));
        assertEquals("regelsuche.modpow-dag-rediscovery-result/v2", root.path("schema").asText());
        var row = root.path("test").get(0);
        assertEquals(new CompiledAstReplayCodec().encodeExpression(study.test().getFirst().selectedProgram()),
            row.path("selectedProgram").asText(), "a reuse boolean is not the selected arithmetic program");
        assertEquals(AssumptionSignature.ofExpressions(ModPowDagRediscoveryStudy.DOMAIN_ASSUMPTIONS)
            .normalizedAssumptions(), JSON.convertValue(root.path("proofAssumptions"), List.class));
        assertTrue(root.path("workScope").asText().contains("EXCLUDES"),
            "mechanical search units must not be reported as complete computational cost");
    }

    @Test void arbitraryProfileIdsAreEscapedAsJsonData() {
        var row = ModPowDagRediscoveryStudy.runCase(
            new ModPowDagRediscoveryStudy.BitProfile("quoted \"profile\"\nnext", 5, 3, 3), false);
        var negative = ModPowDagRediscoveryStudy.runCase(
            new ModPowDagRediscoveryStudy.BitProfile("negative", 5, 3, 3), true);
        var study = new ModPowDagRediscoveryStudy.StudyResult(List.of(row), List.of(row), negative, false);
        var parsed = assertDoesNotThrow(() -> JSON.readTree(ModPowDagRediscoveryReport.json(study)));
        assertEquals(row.id(), parsed.path("test").get(0).path("id").asText());
    }
}
