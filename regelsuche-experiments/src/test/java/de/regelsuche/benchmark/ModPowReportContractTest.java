package de.regelsuche.benchmark;

import static org.junit.jupiter.api.Assertions.*;

import de.regelsuche.assumption.AssumptionSignature;
import de.regelsuche.json.JsonReader;
import de.regelsuche.search.program.CompiledAstReplayCodec;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ModPowReportContractTest {
    @Test void resultRetainsActualProgramAndConditionalProofDomain() {
        var study = ModPowDagRediscoveryStudy.run();
        var root = new JsonReader(ModPowDagRediscoveryReport.json(study)).readObject();
        assertEquals("regelsuche.modpow-dag-rediscovery-result/v2", root.get("schema"));
        var row = firstTest(root);
        assertEquals(new CompiledAstReplayCodec().encodeExpression(study.test().getFirst().selectedProgram()),
            row.get("selectedProgram"), "a reuse boolean is not the selected arithmetic program");
        assertEquals(AssumptionSignature.ofExpressions(ModPowDagRediscoveryStudy.DOMAIN_ASSUMPTIONS)
            .normalizedAssumptions(), root.get("proofAssumptions"));
        assertTrue(String.valueOf(root.get("workScope")).contains("EXCLUDES"),
            "mechanical search units must not be reported as complete computational cost");
    }

    @Test void arbitraryProfileIdsAreEscapedAsJsonData() {
        var row = ModPowDagRediscoveryStudy.runCase(
            new ModPowDagRediscoveryStudy.BitProfile("quoted \"profile\"\nnext", 5, 3, 3), false);
        var negative = ModPowDagRediscoveryStudy.runCase(
            new ModPowDagRediscoveryStudy.BitProfile("negative", 5, 3, 3), true);
        var study = new ModPowDagRediscoveryStudy.StudyResult(List.of(row), List.of(row), negative, false);
        var parsed = assertDoesNotThrow(() -> new JsonReader(ModPowDagRediscoveryReport.json(study)).readObject());
        assertEquals(row.id(), firstTest(parsed).get("id"));
    }

    private static Map<?, ?> firstTest(Map<String, Object> root) {
        return (Map<?, ?>) ((List<?>) root.get("test")).getFirst();
    }
}
