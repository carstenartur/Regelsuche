package de.regelsuche.release;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class HiddenRuleBenchmarkReleaseEvidenceTest {
    @Test
    void acceptsBalancedLeakFreeExecutableBenchmarkEvidence() throws Exception {
        HiddenRuleBenchmarkReleaseEvidence evidence =
            HiddenRuleBenchmarkReleaseEvidence.read(
                writeReport(false, false, false));

        assertEquals(20, evidence.cases());
        assertEquals(4, evidence.families());
        assertEquals(40, evidence.configuredNegativeHoldouts());
        assertEquals(40, evidence.executedNegativeHoldouts());
        assertEquals(0, evidence.skippedNegativeHoldouts());
        assertEquals(0, evidence.falsePositiveHoldouts());
        assertEquals(20, evidence.executableRediscoveryCount());
        assertTrue(evidence.hiddenReferenceIsolated());
        assertTrue(evidence.benchmarkComplete());
        assertTrue(evidence.executableRediscoveryRetained());
        assertTrue(evidence.toCanonicalJson().contains(
            "\"hiddenReferenceIsolated\":true"));
    }

    @Test
    void falsePositiveOrLeakageRemainsVisibleAndBlocksItsAxis() throws Exception {
        HiddenRuleBenchmarkReleaseEvidence falsePositive =
            HiddenRuleBenchmarkReleaseEvidence.read(
                writeReport(true, false, false));
        assertEquals(1, falsePositive.falsePositiveHoldouts());
        assertEquals(1, falsePositive.acceptedIncompleteHoldoutCount());
        assertFalse(falsePositive.benchmarkComplete());
        assertTrue(falsePositive.hiddenReferenceIsolated());

        HiddenRuleBenchmarkReleaseEvidence leakage =
            HiddenRuleBenchmarkReleaseEvidence.read(
                writeReport(false, true, false));
        assertEquals(1, leakage.leakageViolationCount());
        assertFalse(leakage.hiddenReferenceIsolated());
        assertTrue(leakage.benchmarkComplete());
    }

    @Test
    void inconsistentDeclaredAccountingIsRejected() throws Exception {
        Path report = writeReport(false, false, true);
        assertThrows(
            IllegalArgumentException.class,
            () -> HiddenRuleBenchmarkReleaseEvidence.read(report));
    }

    private static Path writeReport(
        boolean falsePositive,
        boolean leakage,
        boolean inconsistentAccounting
    ) throws Exception {
        StringBuilder cases = new StringBuilder();
        for (int index = 0; index < 20; index++) {
            if (!cases.isEmpty()) {
                cases.append(',');
            }
            boolean affected = index == 0;
            cases.append("{")
                .append("\"opaqueCaseId\":\"fixture-").append(index).append("\",")
                .append("\"family\":\"family-").append(index % 4).append("\",")
                .append("\"candidateFrozen\":true,")
                .append("\"accepted\":true,")
                .append("\"splitPassed\":true,")
                .append("\"holdoutsComplete\":true,")
                .append("\"holdoutsPassed\":")
                .append(!falsePositive || !affected).append(',')
                .append("\"validationPassed\":true,")
                .append("\"candidateRelation\":\"EXACT\",")
                .append("\"split\":{")
                .append("\"negatives\":[{},{}],\"collisions\":[]},")
                .append("\"holdouts\":{\"negatives\":[")
                .append("{\"noApplication\":")
                .append(!falsePositive || !affected).append("},")
                .append("{\"noApplication\":true}]},")
                .append("\"leakageViolations\":")
                .append(leakage && affected
                    ? "[{\"location\":\"fixture\",\"fingerprint\":\"x\"}]"
                    : "[]")
                .append(',')
                .append("\"candidate\":{")
                .append("\"present\":true,")
                .append("\"dynamicRuleId\":\"dynamic-fixture-")
                .append(index).append("\"}")
                .append('}');
        }
        int configured = inconsistentAccounting ? 39 : 40;
        String json = "{"
            + "\"schema\":\"regelsuche.hidden-rule-benchmark/v2\","
            + "\"summary\":{" 
            + "\"cases\":20,\"families\":4,"
            + "\"frozenCandidates\":20,\"materialAblations\":20,"
            + "\"acceptedCases\":20,\"rediscoveredCases\":20,"
            + "\"negativeHoldouts\":" + configured + ','
            + "\"evaluatedNegativeHoldouts\":40,"
            + "\"skippedNegativeHoldouts\":0,"
            + "\"falsePositiveHoldouts\":" + (falsePositive ? 1 : 0) + ','
            + "\"generatedValidationExamples\":200,"
            + "\"counterexampleSearches\":20},"
            + "\"failureTaxonomy\":{},"
            + "\"cases\":[" + cases + "]}";
        Path report = Files.createTempFile("hidden-rule-release-", ".json");
        Files.writeString(report, json);
        return report;
    }
}
