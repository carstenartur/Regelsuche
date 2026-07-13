package de.regelsuche.search.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.search.strategy.SearchState;
import de.regelsuche.search.strategy.TranspositionGate;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class TranspositionTableIntegrationTest {

    private static SearchState state(String hash, int depth, int score, List<String> rules, String parent) {
        return state(hash, depth, score, rules, parent, List.of());
    }

    private static SearchState state(
        String hash,
        int depth,
        int score,
        List<String> rules,
        String parent,
        List<String> assumptions
    ) {
        de.regelsuche.scoring.ExpressionScore s =
            new de.regelsuche.scoring.ExpressionScore(score, 0, 0, 0, 0);
        return new SearchState(
            "expr", depth, s,
            List.of("root", "expr"),
            rules,
            java.util.Set.copyOf(rules),
            0, hash, parent, rules.isEmpty() ? null : rules.get(0),
            de.regelsuche.transform.RewriteKind.NORMALIZE,
            false, 0, true, 0
        ).withAssumptions(assumptions);
    }

    @Test
    void transpositionTablePreventsSimpleCycles() {
        SearchMemory memory = new SearchMemory();
        SearchState first = state("hash-cycle", 1, 5, List.of("commute"), "root");
        SearchState second = state("hash-cycle", 3, 5, List.of("commute"), "root");
        assertEquals(TranspositionGate.Verdict.KEEP,
            TranspositionGate.evaluate(memory, first, "p1"));
        assertEquals(TranspositionGate.Verdict.PRUNE,
            TranspositionGate.evaluate(memory, second, "p2"),
            "re-visit of the same canonical hash must be pruned");
        boolean sawEqual = memory.decisions().stream()
            .anyMatch(d -> d.reason() == PruningReason.ALREADY_KNOWN_EQUAL
                || d.reason() == PruningReason.ALREADY_KNOWN_BETTER);
        assertTrue(sawEqual,
            "expected ALREADY_KNOWN_* decision, got: " + memory.decisions());
    }

    @Test
    void betterPathUpdatesExistingState() {
        InMemoryTranspositionTable table = new InMemoryTranspositionTable();
        java.time.Instant t0 = java.time.Instant.now();
        TranspositionEntry weak = new TranspositionEntry(
            "h", "expr", 100, 5, "path-old",
            java.util.Set.of("rule_a"), 1, t0, t0);
        TranspositionEntry strong = new TranspositionEntry(
            "h", "expr", 40, 3, "path-new",
            java.util.Set.of("rule_b"), 1, t0, t0.plusSeconds(1));
        table.record(weak);
        TranspositionEntry merged = table.record(strong);
        assertEquals("path-new", merged.bestKnownPathId(),
            "better score must replace bestKnownPathId");
        assertEquals(40, merged.bestScore());
        assertEquals(3, merged.minDepthSeen());
        assertEquals(2, merged.visitCount());
        assertTrue(merged.reachedByRuleIds().contains("rule_a"));
        assertTrue(merged.reachedByRuleIds().contains("rule_b"));
    }

    @Test
    void diverseRulePathCanStillBeKept() {
        SearchMemory memory = new SearchMemory();
        java.time.Instant t0 = java.time.Instant.now();
        memory.table().record(new TranspositionEntry(
            "h", "expr", 10, 2, "path-old",
            java.util.Set.of("rule_a"), 1, t0, t0));
        SearchState reVisit = state("h", 2, 10, List.of("rule_b"), "parent");
        TranspositionGate.Verdict verdict = TranspositionGate.evaluate(memory, reVisit, "path-new");
        assertEquals(TranspositionGate.Verdict.KEEP, verdict);
        PruningDecision decision = memory.decisions().get(memory.decisions().size() - 1);
        assertEquals(PruningReason.KEPT_NEW_RULE_COMBO, decision.reason());
        assertNotNull(decision.explanation());
    }

    @Test
    void assumptionsArePartOfTranspositionIdentity() {
        SearchMemory memory = new SearchMemory();
        SearchState withoutAssumption = state("hash-x-div-x", 1, 5, List.of("r"), "root");
        SearchState withAssumption = state(
            "hash-x-div-x", 1, 5, List.of("r"), "root", List.of("x != 0"));

        assertEquals(TranspositionGate.Verdict.KEEP,
            TranspositionGate.evaluate(memory, withoutAssumption, "p1"));
        assertEquals(TranspositionGate.Verdict.KEEP,
            TranspositionGate.evaluate(memory, withAssumption, "p2"));
        assertEquals(2, memory.table().size(),
            "same canonical expression under different assumptions must not collide");
    }

    @Test
    void currentIdentityPreservesBestStateAcrossLaterLookups() {
        SearchMemory memory = new SearchMemory();
        java.time.Instant firstSeen = java.time.Instant.parse("2026-01-01T00:00:00Z");
        java.time.Instant lastSeen = java.time.Instant.parse("2026-01-02T00:00:00Z");
        String identity = "value-v1:current-hash";
        memory.table().record(new TranspositionEntry(
            identity,
            "expr",
            10,
            2,
            "best-path",
            Set.of("rule_a"),
            7,
            firstSeen,
            lastSeen
        ));

        SearchState firstRevisit = state(identity, 5, 20, List.of("rule_a"), "parent");
        assertEquals(
            TranspositionGate.Verdict.PRUNE,
            TranspositionGate.evaluate(memory, firstRevisit, "current-path-1"));

        TranspositionEntry afterFirstVisit = memory.table().lookup(identity).orElseThrow();
        assertEquals(10, afterFirstVisit.bestScore());
        assertEquals(2, afterFirstVisit.minDepthSeen());
        assertEquals("best-path", afterFirstVisit.bestKnownPathId());
        assertEquals(Set.of("rule_a"), afterFirstVisit.reachedByRuleIds());
        assertEquals(8, afterFirstVisit.visitCount());
        assertEquals(firstSeen, afterFirstVisit.firstSeen());

        SearchState laterRevisit = state(identity, 4, 15, List.of("rule_a"), "parent");
        assertEquals(
            TranspositionGate.Verdict.PRUNE,
            TranspositionGate.evaluate(memory, laterRevisit, "current-path-2"),
            "later lookups must still compare against the current best score");

        TranspositionEntry afterLaterVisit = memory.table().lookup(identity).orElseThrow();
        assertEquals(10, afterLaterVisit.bestScore());
        assertEquals(2, afterLaterVisit.minDepthSeen());
        assertEquals("best-path", afterLaterVisit.bestKnownPathId());
        assertEquals(9, afterLaterVisit.visitCount());
    }
}
