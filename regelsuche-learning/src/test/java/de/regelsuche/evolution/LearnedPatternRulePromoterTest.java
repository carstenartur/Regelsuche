package de.regelsuche.evolution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.assumption.AssumptionSignature;
import de.regelsuche.canonical.ExpressionCanonicalizer;
import de.regelsuche.search.reachability.PatternTargetedLocalBridgeSearch;
import de.regelsuche.search.reachability.RulePreparationCoordinator;
import de.regelsuche.search.reachability.UnifiedRulePreparationCoordinator;
import de.regelsuche.transform.AstRewriteTransformationEngine;
import de.regelsuche.transform.PatternExpr;
import de.regelsuche.transform.RewriteKind;
import de.regelsuche.transform.RewriteRule;
import java.util.List;
import org.junit.jupiter.api.Test;

class LearnedPatternRulePromoterTest {
    private static final String REVISION =
        "0123456789abcdef0123456789abcdef01234567";
    private static final String HASH_A = "sha256:" + "1".repeat(64);
    private static final String HASH_B = "sha256:" + "2".repeat(64);
    private static final String HASH_C = "sha256:" + "3".repeat(64);
    private static final String HASH_D = "sha256:" + "4".repeat(64);
    private final ExpressionCanonicalizer canonicalizer =
        new ExpressionCanonicalizer();

    @Test
    void promotesAnExactlyProvedGeneAndReusesOrdinaryPreparation() {
        EvolutionGenome genome = genome();
        LearnedPatternRulePromoter promoter =
            new LearnedPatternRulePromoter();
        LearnedPatternRulePromoter.Promotion promotion = promoter.promote(
            genome,
            "difference-squares",
            evidence());

        assertTrue(promotion.proof().proved());
        assertTrue(promotion.rule().isEquivalencePreservingByConstruction());
        assertTrue(promotion.rule().descriptor().eligibleForRegistration());
        assertEquals(
            promotion.rule().id(),
            promotion.applicabilitySchema().ruleId());
        assertTrue(promotion.receipt().contentHash()
            .matches("sha256:[0-9a-f]{64}"));
        assertTrue(promotion.receipt().toCanonicalJson()
            .contains(promotion.receipt().contentHash()));

        UnifiedRulePreparationCoordinator coordinator =
            new UnifiedRulePreparationCoordinator(
                List.of(promotion.applicabilitySchema()),
                cancellationRules(),
                REVISION,
                new PatternTargetedLocalBridgeSearch.Budget(
                    3, 128, 1_024, 8, 160, 128,
                    32, 5_000, 2_500));
        UnifiedRulePreparationCoordinator.Evaluation evaluation =
            coordinator.analyze(
                "((x^2 * a) / a) - y^2",
                AssumptionSignature.ofExpressions(List.of()));
        RulePreparationCoordinator.Outcome outcome = evaluation.outcome(
            promotion.rule().id()).orElseThrow();

        assertTrue(outcome.prepared());
        assertEquals(
            canonicalizer.stableHash("(x - y) * (x + y)"),
            canonicalizer.stableHash(outcome.candidate().orElseThrow()
                .transformedExpression()));
        assertEquals(List.of("a != 0"),
            outcome.candidate().orElseThrow().assumptions());
        assertEquals(
            List.of(
                "ast_cancel_division_factor",
                promotion.rule().id()),
            outcome.candidate().orElseThrow().primitiveRuleIds());
        assertTrue(coordinator.verify(evaluation).valid());
    }

    @Test
    void rawCompiledGenomeRulesRemainUntrusted() {
        RewriteRule raw = new EvolutionGenomeCompiler()
            .compile(genome())
            .rules()
            .getFirst();

        assertFalse(raw.isEquivalencePreservingByConstruction());
    }

    @Test
    void promoterRejectsAnUnprovedOrConditionalGene() {
        EvolutionGenome invalidIdentity = genomeWithGene(
            new EvolutionGenome.RewriteGene(
                "wrong-identity",
                "?A^2-?B^2",
                "?A+?B",
                RewriteKind.FACTOR,
                false,
                -1,
                4,
                4,
                List.of(),
                obligations()));

        assertThrows(IllegalArgumentException.class,
            () -> new LearnedPatternRulePromoter().promote(
                invalidIdentity,
                "wrong-identity",
                evidence()));
    }

    @Test
    void exactVerifierFailsClosedOutsideItsFragment() {
        ExactPolynomialPatternIdentityVerifier verifier =
            new ExactPolynomialPatternIdentityVerifier();

        ExactPolynomialPatternIdentityVerifier.Verification result =
            verifier.verify(
                PatternExpr.fn("sin", PatternExpr.var("A")),
                PatternExpr.var("A"));

        assertEquals(
            ExactPolynomialPatternIdentityVerifier.Status.UNSUPPORTED,
            result.status());
        assertFalse(result.proved());
    }

    @Test
    void exactProofHashBindsTheSubjectAndVerifierBudget() {
        PatternExpr a = PatternExpr.var("A");
        PatternExpr b = PatternExpr.var("B");
        PatternExpr source = EvolutionGenomeCompiler.parsePattern(
            "?A^2-?B^2");
        PatternExpr target = EvolutionGenomeCompiler.parsePattern(
            "(?A-?B)*(?A+?B)");
        ExactPolynomialPatternIdentityVerifier.Verification first =
            new ExactPolynomialPatternIdentityVerifier().verify(
                source, target);
        ExactPolynomialPatternIdentityVerifier.Verification second =
            new ExactPolynomialPatternIdentityVerifier(
                new ExactPolynomialPatternIdentityVerifier.Budget(
                    512, 256, 4_096, 32, 12, 256))
                .verify(source, target);
        ExactPolynomialPatternIdentityVerifier.Verification otherSubject =
            new ExactPolynomialPatternIdentityVerifier().verify(
                EvolutionGenomeCompiler.parsePattern("?A^2+2*?A*?B+?B^2"),
                EvolutionGenomeCompiler.parsePattern("(?A+?B)^2"));

        assertTrue(first.proved());
        assertTrue(second.proved());
        assertTrue(otherSubject.proved());
        assertNotEquals(first.proofHash(), second.proofHash());
        assertNotEquals(first.proofHash(), otherSubject.proofHash());
        assertEquals("?A", EvolutionGenomeCompiler.renderPattern(a));
        assertEquals("?B", EvolutionGenomeCompiler.renderPattern(b));
    }

    private static EvolutionGenome genome() {
        return genomeWithGene(new EvolutionGenome.RewriteGene(
            "difference-squares",
            "?A^2-?B^2",
            "(?A-?B)*(?A+?B)",
            RewriteKind.FACTOR,
            true,
            -1,
            4,
            8,
            List.of(),
            obligations()));
    }

    private static EvolutionGenome genomeWithGene(
        EvolutionGenome.RewriteGene gene
    ) {
        return EvolutionGenome.create(
            EvolutionGenome.Objective.OPEN_TARGET_OPERATOR,
            new EvolutionGenome.TrainingScope(
                EvolutionGenome.SourceSplit.TRAIN,
                HASH_A,
                HASH_B,
                HASH_C,
                HASH_D),
            List.of(gene),
            List.of(new EvolutionGenome.FeatureWeight(
                EvolutionGenome.FitnessSignal.UNSEEN_TRAIN_CASES_SOLVED,
                1_000)),
            EvolutionGenome.GuardPolicy.strictDefault(),
            EvolutionGenome.ResourceBudget.conservativeDefault(),
            List.of("symbolic-polynomial"),
            List.of());
    }

    private static List<EvolutionGenome.EvidenceObligation> obligations() {
        return List.of(
            EvolutionGenome.EvidenceObligation.SEMANTIC_VALIDATION,
            EvolutionGenome.EvidenceObligation.COUNTEREXAMPLE_SEARCH,
            EvolutionGenome.EvidenceObligation.PROOF_OR_CERTIFICATE,
            EvolutionGenome.EvidenceObligation.HOLDOUT_EVALUATION);
    }

    private static LearnedPatternRulePromoter.PromotionEvidence evidence() {
        return new LearnedPatternRulePromoter.PromotionEvidence(
            HASH_A,
            HASH_B,
            HASH_C,
            HASH_D,
            REVISION);
    }

    private static List<RewriteRule> cancellationRules() {
        return AstRewriteTransformationEngine.allBuiltInRules().stream()
            .filter(rule -> "ast_cancel_division_factor"
                .equals(rule.id()))
            .toList();
    }
}
