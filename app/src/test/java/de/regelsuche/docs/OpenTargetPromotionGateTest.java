package de.regelsuche.docs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.mining.HypothesisCandidate;
import de.regelsuche.mining.OpenTargetConjectureEvaluator.CounterexampleEvidence;
import de.regelsuche.mining.OpenTargetConjectureEvaluator.EvaluationReport;
import de.regelsuche.mining.OpenTargetConjectureEvaluator.EvaluationStatus;
import de.regelsuche.mining.OpenTargetConjectureEvaluator.NegativeHoldoutResult;
import de.regelsuche.mining.OpenTargetConjectureEvaluator.PositiveHoldoutResult;
import de.regelsuche.mining.OpenTargetConjectureMiner.ConvergenceEvidence;
import de.regelsuche.mining.OpenTargetConjectureMiner.OpenTargetConjecture;
import de.regelsuche.mining.OpenTargetConjectureMiner.PathEvidence;
import de.regelsuche.mining.OpenTargetConjectureNoveltyChecker.MatchRelation;
import de.regelsuche.mining.OpenTargetConjectureNoveltyChecker.NoveltyMatch;
import de.regelsuche.mining.OpenTargetConjectureNoveltyChecker.NoveltyReport;
import de.regelsuche.mining.OpenTargetConjectureNoveltyChecker.NoveltyStatus;
import de.regelsuche.mining.OpenTargetConjectureProofGate.EligibilityStatus;
import de.regelsuche.mining.OpenTargetConjectureProofGate.ProofObligation;
import de.regelsuche.mining.OpenTargetConjectureProofGate.ProofReport;
import de.regelsuche.mining.OpenTargetConjectureProofGate.ProofStatus;
import de.regelsuche.mining.OpenTargetHypothesisCandidateAdapter;
import de.regelsuche.proof.ProofPolicy;
import de.regelsuche.search.strategy.BestFirstSearchStrategy.GoalStatus;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class OpenTargetPromotionGateTest {
    private final OpenTargetPromotionGate gate = new OpenTargetPromotionGate();

    @Test
    void promotesAndPublishesOnlyAfterAllIndependentGatesPass() {
        Fixture fixture = fixture(false);

        OpenTargetPromotionGate.Decision decision = gate.evaluate(input(
            fixture,
            novel(fixture.conjecture().conjectureId()),
            verifiedProof(fixture.conjecture(), ProofStatus.SYMBOLICALLY_VERIFIED),
            materialAblation(),
            ProofPolicy.PROOF_OPTIONAL,
            "SCRIPT_GENERATED"));

        assertTrue(decision.promoted());
        assertTrue(decision.publicEvidenceAccepted());
        assertEquals(PromotionStage.PROMOTED, decision.promotionRecord().stage());
        assertEquals(de.regelsuche.docs.NoveltyStatus.NEW, decision.publicNoveltyStatus());
        assertEquals("NOVEL_WITHIN_PROJECT", decision.projectNoveltyStatus());
        assertEquals("NOT_EVALUATED", decision.externalNoveltyStatus());
        assertEquals("SYMBOLICALLY_VERIFIED", decision.symbolicProofStatus());
        assertEquals("NOT_EVALUATED", decision.formalProofStatus());
        assertEquals("NOT_EVALUATED", decision.interestingnessStatus());
        assertTrue(decision.promotionBlockers().isEmpty());
        assertTrue(decision.evidenceHash().startsWith("sha256:"));

        String json = decision.toCanonicalJson();
        assertEquals(json, decision.toCanonicalJson());
        assertTrue(json.contains("\"promotionEligible\":true"));
        assertTrue(json.contains("\"externalNoveltyStatus\":\"NOT_EVALUATED\""));
        assertTrue(json.contains("\"interestingnessStatus\":\"NOT_EVALUATED\""));
    }

    @Test
    void duplicateNoveltyBlocksPromotionWithoutErasingTruthEvidence() {
        Fixture fixture = fixture(false);
        NoveltyReport duplicate = new NoveltyReport(
            de.regelsuche.mining.OpenTargetConjectureNoveltyChecker.SCHEMA,
            fixture.conjecture().conjectureId(),
            NoveltyStatus.EXACT_DUPLICATE,
            "sha256:exact",
            "sha256:alpha",
            7,
            0,
            List.of(new NoveltyMatch(
                "ACTIVE_INVENTORY",
                "known-factor-common",
                MatchRelation.EXACT)),
            "NOT_EVALUATED",
            "active inventory duplicate");

        OpenTargetPromotionGate.Decision decision = gate.evaluate(input(
            fixture,
            duplicate,
            verifiedProof(fixture.conjecture(), ProofStatus.SYMBOLICALLY_VERIFIED),
            materialAblation(),
            ProofPolicy.PROOF_OPTIONAL,
            "SCRIPT_GENERATED"));

        assertFalse(decision.promoted());
        assertFalse(decision.publicEvidenceAccepted());
        assertEquals(PromotionStage.VALIDATED, decision.promotionRecord().stage());
        assertEquals("AGREE", decision.promotionRecord().oracleStatus());
        assertEquals(de.regelsuche.docs.NoveltyStatus.KNOWN_RULE,
            decision.publicNoveltyStatus());
        assertTrue(decision.promotionBlockers().contains(
            "project-novelty=EXACT_DUPLICATE"));
        assertTrue(decision.publicEvidenceDecision().rejectionReasons().contains(
            "novelty=KNOWN_RULE"));
    }

    @Test
    void mandatoryFormalProofCannotBeReplacedBySymbolicAgreement() {
        Fixture fixture = fixture(false);
        ProofReport proof = verifiedProof(
            fixture.conjecture(), ProofStatus.SYMBOLICALLY_VERIFIED);

        OpenTargetPromotionGate.Decision blocked = gate.evaluate(input(
            fixture,
            novel(fixture.conjecture().conjectureId()),
            proof,
            materialAblation(),
            ProofPolicy.PROOF_REQUIRED_FOR_PUBLIC_EVIDENCE,
            "SCRIPT_GENERATED"));

        assertFalse(blocked.promoted());
        assertFalse(blocked.publicEvidenceAccepted());
        assertEquals(PromotionStage.VALIDATED, blocked.promotionRecord().stage());
        assertTrue(blocked.promotionBlockers().contains("proof=SCRIPT_GENERATED"));
        assertTrue(blocked.publicEvidenceDecision().rejectionReasons().contains(
            "proof=SCRIPT_GENERATED"));

        OpenTargetPromotionGate.Decision confirmed = gate.evaluate(input(
            fixture,
            novel(fixture.conjecture().conjectureId()),
            proof,
            materialAblation(),
            ProofPolicy.PROOF_REQUIRED_FOR_PUBLIC_EVIDENCE,
            "PROVER_CONFIRMED"));

        assertTrue(confirmed.promoted());
        assertTrue(confirmed.publicEvidenceAccepted());
    }

    @Test
    void inconclusiveSymbolicProofLeavesCandidateValidatedButNotPromoted() {
        Fixture fixture = fixture(false);
        ProofReport inconclusive = verifiedProof(
            fixture.conjecture(), ProofStatus.INCONCLUSIVE);

        OpenTargetPromotionGate.Decision decision = gate.evaluate(input(
            fixture,
            novel(fixture.conjecture().conjectureId()),
            inconclusive,
            materialAblation(),
            ProofPolicy.PROOF_OPTIONAL,
            "SCRIPT_GENERATED"));

        assertFalse(decision.promoted());
        assertFalse(decision.publicEvidenceAccepted());
        assertEquals(PromotionStage.VALIDATED, decision.promotionRecord().stage());
        assertEquals("UNAVAILABLE", decision.promotionRecord().oracleStatus());
        assertTrue(decision.promotionBlockers().contains(
            "symbolic-proof=INCONCLUSIVE"));
    }

    @Test
    void evidenceIdentityMismatchCannotEnterPromotion() {
        Fixture fixture = fixture(false);
        NoveltyReport mismatched = novel("another-candidate");

        OpenTargetPromotionGate.Decision decision = gate.evaluate(input(
            fixture,
            mismatched,
            verifiedProof(fixture.conjecture(), ProofStatus.SYMBOLICALLY_VERIFIED),
            materialAblation(),
            ProofPolicy.PROOF_OPTIONAL,
            "SCRIPT_GENERATED"));

        assertFalse(decision.promoted());
        assertEquals(PromotionStage.OBSERVED, decision.promotionRecord().stage());
        assertTrue(decision.promotionBlockers().contains(
            "novelty-provenance-mismatch"));
    }

    @Test
    void reportIsDeterministicAcrossEvidenceAndPathOrder() {
        Fixture ordered = fixture(false);
        Fixture reversed = fixture(true);

        OpenTargetPromotionGate.Decision first = gate.evaluate(input(
            ordered,
            novel(ordered.conjecture().conjectureId()),
            verifiedProof(ordered.conjecture(), ProofStatus.SYMBOLICALLY_VERIFIED),
            materialAblation(),
            ProofPolicy.PROOF_OPTIONAL,
            "SCRIPT_GENERATED"));
        OpenTargetPromotionGate.Decision second = gate.evaluate(input(
            reversed,
            novel(reversed.conjecture().conjectureId()),
            verifiedProof(reversed.conjecture(), ProofStatus.SYMBOLICALLY_VERIFIED),
            materialAblation(),
            ProofPolicy.PROOF_OPTIONAL,
            "SCRIPT_GENERATED"));

        assertEquals(first.evidenceHash(), second.evidenceHash());
        assertEquals(first.toCanonicalJson(), second.toCanonicalJson());
        assertEquals(first.promotionRecord().rulePath(), second.promotionRecord().rulePath());
    }

    private static OpenTargetPromotionGate.Input input(
        Fixture fixture,
        NoveltyReport novelty,
        ProofReport proof,
        AblationEvidence ablation,
        ProofPolicy proofPolicy,
        String proverExecutionStatus
    ) {
        return new OpenTargetPromotionGate.Input(
            "open-target-campaign-2026-07",
            "2026-07-14",
            fixture.conjecture(),
            fixture.evaluation(),
            novelty,
            proof,
            fixture.hypothesis(),
            ablation,
            proofPolicy,
            proverExecutionStatus);
    }

    private static Fixture fixture(boolean reverse) {
        ConvergenceEvidence algebra = evidence(
            "obs-algebra",
            "algebra",
            "m * 4 + m * 5",
            "m * (4 + 5)",
            "alpha-algebra",
            "value-algebra",
            List.of(
                path("path-algebra-direct", "m * 4 + m * 5", "m * (4 + 5)",
                    List.of("factor-common")),
                path("path-algebra-alternate", "m * 4 + m * 5", "m * (4 + 5)",
                    List.of("reassociate-products", "factor-common"))));
        ConvergenceEvidence rational = evidence(
            "obs-rational",
            "rational",
            "(p / q) * 2 + (p / q) * 3",
            "(p / q) * (2 + 3)",
            "alpha-rational",
            "value-rational",
            List.of(
                path("path-rational-direct",
                    "(p / q) * 2 + (p / q) * 3",
                    "(p / q) * (2 + 3)",
                    List.of("factor-rational-common")),
                path("path-rational-alternate",
                    "(p / q) * 2 + (p / q) * 3",
                    "(p / q) * (2 + 3)",
                    List.of("normalize-rational", "factor-rational-common"))));

        if (reverse) {
            algebra = reversePaths(algebra);
            rational = reversePaths(rational);
        }
        List<ConvergenceEvidence> evidence = reverse
            ? List.of(rational, algebra)
            : List.of(algebra, rational);
        List<String> observationIds = reverse
            ? List.of("obs-rational", "obs-algebra")
            : List.of("obs-algebra", "obs-rational");
        OpenTargetConjecture conjecture = new OpenTargetConjecture(
            "open-target-factor-common",
            "A * B + A * C",
            "A * (B + C)",
            2,
            2,
            reverse ? List.of("rational", "algebra") : List.of("algebra", "rational"),
            observationIds,
            evidence,
            List.of("shared(A)"),
            Map.of("A", List.of("m", "p / q")),
            "OBSERVED_CONJECTURE",
            "EQUIVALENCE_PRESERVING_CONVERGENT_PATHS");
        EvaluationReport evaluation = acceptedEvaluation(conjecture.conjectureId());
        HypothesisCandidate hypothesis = new OpenTargetHypothesisCandidateAdapter().adapt(
            conjecture,
            evaluation,
            Instant.parse("2026-07-14T10:00:00Z"));
        return new Fixture(conjecture, evaluation, hypothesis);
    }

    private static EvaluationReport acceptedEvaluation(String candidateId) {
        PositiveHoldoutResult positive = new PositiveHoldoutResult(
            "positive-1",
            1,
            true,
            List.of("x * (2 + 3)"));
        NegativeHoldoutResult negative = new NegativeHoldoutResult(
            "negative-1",
            0,
            true,
            List.of());
        CounterexampleEvidence counterexample = new CounterexampleEvidence(
            "NO_COUNTEREXAMPLE_FOUND",
            List.of("boundary", "numeric"),
            List.of(),
            List.of(),
            "",
            "",
            "no counterexample within the configured deterministic budget");
        return new EvaluationReport(
            de.regelsuche.mining.OpenTargetConjectureEvaluator.SCHEMA,
            candidateId,
            EvaluationStatus.ACCEPTED_FOR_PROOF,
            "COMPILED",
            "dynamic-" + candidateId,
            "sha256:evaluation-provenance",
            1,
            1,
            0,
            1,
            1,
            0,
            List.of(positive),
            List.of(negative),
            counterexample,
            List.of(),
            "NOT_EVALUATED",
            "NOT_EVALUATED");
    }

    private static NoveltyReport novel(String candidateId) {
        return new NoveltyReport(
            de.regelsuche.mining.OpenTargetConjectureNoveltyChecker.SCHEMA,
            candidateId,
            NoveltyStatus.NOVEL_WITHIN_PROJECT,
            "sha256:exact-signature",
            "sha256:alpha-signature",
            7,
            3,
            List.of(),
            "NOT_EVALUATED",
            "no project duplicate found");
    }

    private static ProofReport verifiedProof(
        OpenTargetConjecture conjecture,
        ProofStatus status
    ) {
        ProofObligation obligation = new ProofObligation(
            de.regelsuche.mining.OpenTargetConjectureProofGate.OBLIGATION_SCHEMA,
            conjecture.conjectureId(),
            false,
            conjecture.leftPattern(),
            conjecture.rightPattern(),
            List.of(),
            "sha256:proof-obligation");
        List<String> blockers = status == ProofStatus.SYMBOLICALLY_VERIFIED
            ? List.of()
            : List.of("oracle produced no conclusive equivalence result");
        return new ProofReport(
            de.regelsuche.mining.OpenTargetConjectureProofGate.REPORT_SCHEMA,
            conjecture.conjectureId(),
            EligibilityStatus.ELIGIBLE,
            status,
            obligation,
            "sympy-equivalence-v1",
            status == ProofStatus.SYMBOLICALLY_VERIFIED
                ? "validated by deterministic symbolic equivalence"
                : "no equivalence evidence found",
            "NOT_EVALUATED",
            blockers,
            "sha256:proof-evidence");
    }

    private static AblationEvidence materialAblation() {
        return AblationEvidence.compare(
            true,
            1,
            5,
            true,
            3,
            30,
            "paired unseen-task evaluation with and without the candidate");
    }

    private static ConvergenceEvidence evidence(
        String observationId,
        String family,
        String input,
        String output,
        String alphaFingerprint,
        String valueFingerprint,
        List<PathEvidence> paths
    ) {
        return new ConvergenceEvidence(
            observationId,
            family,
            GoalStatus.UNTARGETED,
            input,
            output,
            "canonical-" + observationId,
            10,
            alphaFingerprint,
            valueFingerprint,
            "independent-convergent-paths",
            paths);
    }

    private static PathEvidence path(
        String pathId,
        String input,
        String output,
        List<String> ruleIds
    ) {
        List<String> expressions = ruleIds.size() == 1
            ? List.of(input, output)
            : List.of(input, "bridge(" + input + ")", output);
        return new PathEvidence(
            pathId,
            expressions,
            ruleIds,
            List.of(),
            ruleIds.size(),
            1);
    }

    private static ConvergenceEvidence reversePaths(ConvergenceEvidence evidence) {
        return new ConvergenceEvidence(
            evidence.observationId(),
            evidence.family(),
            evidence.searchStatus(),
            evidence.inputExpression(),
            evidence.outputExpression(),
            evidence.canonicalOutputHash(),
            evidence.scoreImprovement(),
            evidence.alphaPairFingerprint(),
            evidence.valuePairFingerprint(),
            evidence.pathCompetitionSignature(),
            evidence.paths().reversed());
    }

    private record Fixture(
        OpenTargetConjecture conjecture,
        EvaluationReport evaluation,
        HypothesisCandidate hypothesis
    ) {
    }
}