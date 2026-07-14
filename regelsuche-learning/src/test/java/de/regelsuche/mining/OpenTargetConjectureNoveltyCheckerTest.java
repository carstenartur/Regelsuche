package de.regelsuche.mining;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.mining.OpenTargetConjectureMiner.OpenTargetConjecture;
import de.regelsuche.mining.OpenTargetConjectureNoveltyChecker.MatchRelation;
import de.regelsuche.mining.OpenTargetConjectureNoveltyChecker.NoveltyStatus;
import de.regelsuche.mining.OpenTargetConjectureNoveltyChecker.PriorCandidate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class OpenTargetConjectureNoveltyCheckerTest {
    private final OpenTargetConjectureNoveltyChecker checker =
        new OpenTargetConjectureNoveltyChecker();

    @Test
    void detectsExactDuplicateInActiveInventory() {
        KnownRuleRepository inventory = inventoryWith(new KnownRule(
            "known-factor-common",
            "A * B + A * C",
            "A * (B + C)"));

        var report = checker.check(factoringConjecture(), inventory, List.of());

        assertEquals(NoveltyStatus.EXACT_DUPLICATE, report.status());
        assertEquals(1, report.checkedActiveRules());
        assertEquals(0, report.checkedPriorCandidates());
        assertEquals(1, report.matches().size());
        assertEquals("ACTIVE_INVENTORY", report.matches().getFirst().source());
        assertEquals("known-factor-common", report.matches().getFirst().candidateId());
        assertEquals(MatchRelation.EXACT, report.matches().getFirst().relation());
        assertEquals("NOT_EVALUATED", report.externalNoveltyStatus());
    }

    @Test
    void detectsAlphaEquivalentCandidateFromEarlierCampaign() {
        PriorCandidate prior = new PriorCandidate(
            "campaign-2026-07",
            "candidate-17",
            "X * Y + X * Z",
            "X * (Y + Z)");

        var report = checker.check(
            factoringConjecture(), new KnownRuleRepository(), List.of(prior));

        assertEquals(NoveltyStatus.ALPHA_EQUIVALENT_DUPLICATE, report.status());
        assertEquals(1, report.matches().size());
        assertEquals(MatchRelation.ALPHA_EQUIVALENT, report.matches().getFirst().relation());
        assertEquals("campaign-2026-07", report.matches().getFirst().source());
    }

    @Test
    void preservesPlaceholderRelationsAndRewriteDirection() {
        List<PriorCandidate> structurallyDifferent = List.of(
            new PriorCandidate(
                "earlier",
                "different-bindings",
                "W * X + Y * Z",
                "W * (X + Z)"),
            new PriorCandidate(
                "earlier",
                "reverse-direction",
                "A * (B + C)",
                "A * B + A * C"));

        var report = checker.check(
            factoringConjecture(), new KnownRuleRepository(), structurallyDifferent);

        assertEquals(NoveltyStatus.NOVEL_WITHIN_PROJECT, report.status());
        assertTrue(report.matches().isEmpty());
        assertTrue(report.exactSignatureHash().startsWith("sha256:"));
        assertTrue(report.alphaSignatureHash().startsWith("sha256:"));
        assertNotEquals(report.exactSignatureHash(), report.alphaSignatureHash());
    }

    @Test
    void reportIsDeterministicAcrossReferenceOrder() {
        PriorCandidate first = new PriorCandidate(
            "campaign-b", "candidate-b", "X * Y + X * Z", "X * (Y + Z)");
        PriorCandidate second = new PriorCandidate(
            "campaign-a", "candidate-a", "P * Q + P * R", "P * (Q + R)");

        var ordered = checker.check(
            factoringConjecture(), new KnownRuleRepository(), List.of(first, second));
        var reversed = checker.check(
            factoringConjecture(), new KnownRuleRepository(), List.of(second, first));

        assertEquals(ordered, reversed);
        assertEquals(List.of("campaign-a", "campaign-b"),
            ordered.matches().stream().map(match -> match.source()).toList());
    }

    @Test
    void unparseableCandidateIsInconclusiveRatherThanNovel() {
        OpenTargetConjecture invalid = conjecture("invalid", "A +", "A");

        var report = checker.check(invalid, new KnownRuleRepository(), List.of());

        assertEquals(NoveltyStatus.INCONCLUSIVE_UNPARSEABLE, report.status());
        assertEquals("", report.exactSignatureHash());
        assertEquals("", report.alphaSignatureHash());
        assertEquals("NOT_EVALUATED", report.externalNoveltyStatus());
        assertTrue(report.explanation().startsWith("candidate pattern could not be parsed:"));
    }

    private static KnownRuleRepository inventoryWith(KnownRule... rules) {
        List<KnownRule> inventory = List.of(rules);
        return new KnownRuleRepository() {
            @Override
            public List<KnownRule> all() {
                return inventory;
            }
        };
    }

    private static OpenTargetConjecture factoringConjecture() {
        return conjecture(
            "open-target-factor-common",
            "A * B + A * C",
            "A * (B + C)");
    }

    private static OpenTargetConjecture conjecture(
        String id,
        String leftPattern,
        String rightPattern
    ) {
        return new OpenTargetConjecture(
            id,
            leftPattern,
            rightPattern,
            2,
            2,
            List.of(),
            List.of("obs-1", "obs-2"),
            List.of(),
            List.of(),
            Map.of(),
            "OBSERVED_CONJECTURE",
            "EQUIVALENCE_PRESERVING_CONVERGENT_PATHS");
    }
}
