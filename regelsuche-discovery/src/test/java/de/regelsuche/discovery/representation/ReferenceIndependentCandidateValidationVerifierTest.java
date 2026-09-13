package de.regelsuche.discovery.representation;

import static de.regelsuche.discovery.representation.ReferenceIndependentValidationFixtures.FREEZE;
import static de.regelsuche.discovery.representation.ReferenceIndependentValidationFixtures.FREEZE_HASH;
import static de.regelsuche.discovery.representation.ReferenceIndependentValidationFixtures.PLAN;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

class ReferenceIndependentCandidateValidationVerifierTest {
    @Test
    void rejectsRehashedCandidateOmissionEvenWhenAllCountsBalance() throws Exception {
        ObjectNode artifact = artifact();
        outcomes(artifact).remove(0);
        String forged = rebalance(artifact);
        // This is syntactically valid and self-hashed; only the external freeze detects it.
        ReferenceIndependentCandidateValidation.Artifact.fromCanonicalJson(forged);
        assertThrows(IllegalArgumentException.class, () -> verify(forged));
    }

    @Test
    void rejectsRehashedDuplicateCandidateEvenWhenAllCountsBalance() throws Exception {
        ObjectNode artifact = artifact();
        outcomes(artifact).add(outcomes(artifact).get(0).deepCopy());
        ((ObjectNode) artifact.path("content").get("budget")).put("maxOracleCalls", 4317);
        String forged = rebalance(artifact);
        ReferenceIndependentCandidateValidation.Artifact.fromCanonicalJson(forged);
        assertThrows(IllegalArgumentException.class, () -> verify(forged));
    }

    @Test
    void rejectsRehashedFreeOracleWork() throws Exception {
        ObjectNode artifact = artifact();
        ObjectNode work = (ObjectNode) outcomes(artifact).get(0).get("work");
        work.put("oracleCalls", 0).put("completedOracleCalls", 0).put("inputCharacters", 0);
        String forged = rebalance(artifact);
        ReferenceIndependentCandidateValidation.Artifact.fromCanonicalJson(forged);
        assertThrows(IllegalArgumentException.class, () -> verify(forged));
    }

    @Test
    void rejectsRehashedTerminalReasonThatContradictsEvidence() throws Exception {
        ObjectNode artifact = artifact();
        ((ObjectNode) outcomes(artifact).get(0)).put("terminalReason", "REFUTED");
        String forged = rebalance(artifact);
        ReferenceIndependentCandidateValidation.Artifact.fromCanonicalJson(forged);
        assertThrows(IllegalArgumentException.class, () -> verify(forged));
    }

    @Test
    void acceptsTheExactCompleteMatrix() {
        var expected = ReferenceIndependentCandidateValidationTest.Evidence.ARTIFACT;
        assertEquals(expected, verify(expected.toCanonicalJson()));
    }

    private static ReferenceIndependentCandidateValidation.Artifact verify(String json) {
        return ReferenceIndependentCandidateValidationVerifier.verifyBindings(
            PLAN, FREEZE, FREEZE_HASH, json);
    }

    static ObjectNode artifact() throws Exception {
        return (ObjectNode) ReferenceIndependentCandidateValidation.JSON.readTree(
            ReferenceIndependentCandidateValidationTest.Evidence.ARTIFACT.toCanonicalJson());
    }

    static ArrayNode outcomes(ObjectNode artifact) {
        return (ArrayNode) artifact.path("content").path("rows").get(0).get("outcomes");
    }

    static String rebalance(ObjectNode artifact) {
        ObjectNode content = (ObjectNode) artifact.get("content");
        long[] total = new long[4];
        var reasons = new TreeMap<String, Integer>();
        for (var row : content.get("rows")) {
            long[] sum = new long[4];
            for (var outcome : row.get("outcomes")) {
                sum[0] += outcome.path("work").path("candidateVisits").asLong();
                sum[1] += outcome.path("work").path("oracleCalls").asLong();
                sum[2] += outcome.path("work").path("completedOracleCalls").asLong();
                sum[3] += outcome.path("work").path("inputCharacters").asLong();
                reasons.merge(outcome.path("terminalReason").asText(), 1, Integer::sum);
            }
            ObjectNode work = (ObjectNode) row.get("work");
            work.put("candidateVisits", sum[0]).put("oracleCalls", sum[1])
                .put("completedOracleCalls", sum[2]).put("inputCharacters", sum[3]);
            for (int index = 0; index < 4; index++) {
                total[index] += sum[index];
            }
        }
        ObjectNode summary = (ObjectNode) content.get("summary");
        summary.put("candidates", total[0]).put("oracleCalls", total[1])
            .put("completedOracleCalls", total[2]).put("inputCharacters", total[3]);
        summary.set("terminalReasons", ReferenceIndependentCandidateValidation.JSON.valueToTree(reasons));
        artifact.put("contentHash", ReferenceIndependentCandidateValidation.hash(content));
        return TargetFreeHeldOutMatrixRunner.canonical(artifact);
    }
}
