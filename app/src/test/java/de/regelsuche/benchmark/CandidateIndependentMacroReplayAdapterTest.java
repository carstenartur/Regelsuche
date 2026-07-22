package de.regelsuche.benchmark;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.benchmark.CandidateIndependentMacroReplayAdapter.BatchStatus;
import de.regelsuche.benchmark.CandidateIndependentMacroReplayAdapter.ReplayTrace;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CandidateIndependentMacroReplayAdapterTest {

    @Test
    void reproducesEveryFrozenTrainTraceThroughCurrentProductionRules() {
        CandidateIndependentMacroReplayAdapter adapter =
            new CandidateIndependentMacroReplayAdapter(profile());

        var result = adapter.replayAll(traces());

        assertEquals(BatchStatus.REPRODUCED, result.status(), result.detail());
        assertEquals(4, result.results().size());
        result.results().forEach(row -> {
            assertTrue(row.reproduced(), row.evidence().detail());
            assertTrue(row.path().isPresent());
            assertFalse(row.evidence().actualRuleIds().isEmpty());
            assertEquals(
                trace(row.traceId()).primitiveSteps(),
                row.evidence().compressedOperationIds());
            assertEquals(
                row.evidence().actualRuleIds().size() + 1,
                row.evidence().expressionPath().size());
        });
    }

    @Test
    void rejectsAReplayWhoseAbstractOperationOrderWasRewritten() {
        CandidateIndependentMacroReplayAdapter adapter =
            new CandidateIndependentMacroReplayAdapter(profile());
        ReplayTrace mutated = new ReplayTrace(
            "case-13-mutated",
            "(x+1)^2",
            "x^2+2*x+1",
            List.of(
                "ast_distribute",
                "ast_power_expand",
                "ast_collect_like_terms"),
            List.of());

        var result = adapter.replay(mutated);

        assertFalse(result.reproduced());
        assertTrue(result.path().isEmpty());
        assertFalse(result.evidence().detail().isBlank());
    }

    @Test
    void rejectsProfileRulesThatAreNotInTheProductionInventory() {
        Map<String, List<String>> profile = new LinkedHashMap<>(profile());
        profile.put("invented-operation", List.of("invented_rule"));

        assertThrows(IllegalArgumentException.class,
            () -> new CandidateIndependentMacroReplayAdapter(profile));
    }

    private static ReplayTrace trace(String id) {
        return traces().stream().filter(trace -> trace.traceId().equals(id))
            .findFirst().orElseThrow();
    }

    private static List<ReplayTrace> traces() {
        return List.of(
            new ReplayTrace(
                "case-13-trace-1",
                "(x+1)^2",
                "x^2+2*x+1",
                List.of(
                    "ast_power_expand",
                    "ast_distribute",
                    "ast_collect_like_terms"),
                List.of()),
            new ReplayTrace(
                "case-13-trace-2",
                "(y+2)^2",
                "y^2+4*y+4",
                List.of(
                    "ast_power_expand",
                    "ast_distribute",
                    "ast_collect_like_terms"),
                List.of()),
            new ReplayTrace(
                "case-14-trace-1",
                "(x+x)-x",
                "x",
                List.of(
                    "ast_collect_like_terms",
                    "ast_subtract_cancel"),
                List.of()),
            new ReplayTrace(
                "case-14-trace-2",
                "(y*1)+0",
                "y",
                List.of(
                    "ast_multiply_identity",
                    "ast_add_zero"),
                List.of()));
    }

    private static Map<String, List<String>> profile() {
        LinkedHashMap<String, List<String>> result = new LinkedHashMap<>();
        result.put("ast_add_zero", List.of(
            "ast_add_zero_left", "ast_add_zero_right"));
        result.put("ast_collect_like_terms", List.of(
            "ast_double_term", "ast_canonical_normalize"));
        result.put("ast_distribute", List.of(
            "ast_distribute_left_add",
            "ast_distribute_right_add",
            "ast_distribute_left_subtract",
            "ast_distribute_right_subtract"));
        result.put("ast_multiply_identity", List.of(
            "ast_multiply_one_left", "ast_multiply_one_right"));
        result.put("ast_power_expand", List.of(
            "ast_power_two_to_product"));
        result.put("ast_subtract_cancel", List.of(
            "ast_linear_offset_simplify", "ast_canonical_normalize"));
        return result;
    }
}
