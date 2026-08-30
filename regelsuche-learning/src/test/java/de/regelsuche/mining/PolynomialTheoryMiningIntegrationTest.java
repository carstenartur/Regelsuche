package de.regelsuche.mining;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.math.algorithms.polynomial.NativeUnivariateFactorizationEngine;
import de.regelsuche.polynomial.ExactFactorizationTransformationPipeline;
import de.regelsuche.scoring.ExpressionScore;
import de.regelsuche.transform.PolynomialDerivedMacroCache;
import de.regelsuche.transform.PolynomialTheorySubsumptionClassifier;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PolynomialTheoryMiningIntegrationTest {
    @Test
    void minedFactorizationIsClassifiedAndRetainedAcrossGenerations() {
        PolynomialDerivedMacroCache macroCache =
            new PolynomialDerivedMacroCache(4);
        PolynomialTheoryFormationOutcomeLedger ledger =
            new PolynomialTheoryFormationOutcomeLedger(8);
        PolynomialTheoryCandidateObserver observer =
            new PolynomialTheoryCandidateObserver(
                new PolynomialTheorySubsumptionClassifier(
                    NativeUnivariateFactorizationEngine
                        .boundedRationals()),
                macroCache,
                ledger);
        KnownRuleRepository knownRules = new KnownRuleRepository();

        RuleCandidate first = miner(knownRules, observer)
            .mineFromSinglePathForValidatedSchema(path(
                "generation:1:path:x",
                "x",
                "observed-factorization-generation-1"))
            .orElseThrow();

        assertTrue(first.equivalenceVerified());
        assertEquals(1, macroCache.size());
        assertEquals(1, ledger.size());
        PolynomialTheoryFormationOutcomeLedger.Entry firstOutcome =
            ledger.entries().getFirst();
        assertEquals(
            PolynomialTheorySubsumptionClassifier.Status.THEORY_SUBSUMED,
            firstOutcome.classification().status());
        assertEquals(
            PolynomialTheoryFormationOutcomeLedger.Disposition
                .DERIVED_MACRO_CACHE,
            firstOutcome.disposition());
        assertEquals(
            List.of("observed-factorization-generation-1"),
            firstOutcome.formationEvidence().appliedRuleIds());
        assertEquals(
            List.of("generation:1:path:x"),
            firstOutcome.formationEvidence().sourceProvenance());
        assertEquals(
            List.of("symbolically-verified-source-path"),
            firstOutcome.formationEvidence().validationEvidence());

        PolynomialDerivedMacroCache.Entry retained =
            macroCache.entries().getFirst();
        assertEquals(
            firstOutcome.macroEntryId().orElseThrow(),
            retained.id());
        assertEquals(1, retained.lineages().size());
        assertEquals(
            List.of(
                ExactFactorizationTransformationPipeline.TRANSFORMATION_ID),
            retained.lineages().getFirst().primitiveRuleIds(),
            "the cached macro expands through the verifier-authorized "
                + "theory method, not the mining path's source rule label");

        RuleCandidate second = miner(knownRules, observer)
            .mineFromSinglePathForValidatedSchema(path(
                "generation:2:path:y",
                "y",
                "observed-factorization-generation-2"))
            .orElseThrow();

        assertEquals(first.canonicalHash(), second.canonicalHash());
        assertEquals(1, macroCache.size());
        assertEquals(2, ledger.size());
        PolynomialDerivedMacroCache.Entry reused =
            macroCache.entries().getFirst();
        assertEquals(retained.id(), reused.id());
        assertEquals(2, reused.lineages().size());
        assertEquals(
            List.of(
                "generation:1:path:x",
                "generation:2:path:y"),
            reused.lineages().stream()
                .flatMap(lineage -> lineage.sourceProvenance().stream())
                .toList());
        assertTrue(ledger.entries().stream().allMatch(entry ->
            entry.macroEntryId().orElseThrow().equals(reused.id())));
    }

    private RuleCandidateMiner miner(
        KnownRuleRepository knownRules,
        PolynomialTheoryCandidateObserver observer
    ) {
        return new RuleCandidateMiner(
            knownRules,
            (left, right) -> true,
            observer);
    }

    private SuccessfulTransformationPath path(
        String id,
        String variable,
        String observedRuleId
    ) {
        String source = variable + "^2 - 1";
        String target = "(" + variable + " - 1) * ("
            + variable + " + 1)";
        return new SuccessfulTransformationPath(
            id,
            source,
            target,
            List.of(source, target),
            List.of(observedRuleId),
            new ExpressionScore(12, 6, 1, 3, 0),
            new ExpressionScore(8, 5, 0, 3, 0),
            true,
            "symbolically-verified-source-path",
            Map.of(),
            List.of());
    }
}
