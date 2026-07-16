package de.regelsuche.mining;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.mining.CrossFamilyBridgeAblationEvaluator.AblationReport;
import de.regelsuche.mining.CrossFamilyBridgeAblationEvaluator.AblationStatus;
import de.regelsuche.mining.CrossFamilyBridgeAblationEvaluator.FamilyAblation;
import de.regelsuche.mining.CrossFamilyBridgeAblationEvaluator.RunEvidence;
import de.regelsuche.mining.CrossFamilyBridgeHypothesisBuilder.BridgeHypothesis;
import de.regelsuche.mining.CrossFamilyBridgeQualificationGate.AssumptionStatus;
import de.regelsuche.mining.CrossFamilyBridgeQualificationGate.Input;
import de.regelsuche.mining.CrossFamilyBridgeQualificationGate.QualificationStatus;
import de.regelsuche.mining.CrossFamilyBridgeTransferEvaluator.FamilyResult;
import de.regelsuche.mining.CrossFamilyBridgeTransferEvaluator.FamilyRole;
import de.regelsuche.mining.CrossFamilyBridgeTransferEvaluator.FamilyStatus;
import de.regelsuche.mining.CrossFamilyBridgeTransferEvaluator.TransferReport;
import de.regelsuche.mining.CrossFamilyBridgeTransferEvaluator.TransferStatus;
import de.regelsuche.mining.InterestingnessAssessment.Eligibility;
import de.regelsuche.mining.InterestingnessEvidence.ControlClassification;
import de.regelsuche.mining.InterestingnessEvidence.ProjectNoveltyStatus;
import de.regelsuche.mining.OpenTargetConjectureMiner.ConvergenceEvidence;
import de.regelsuche.mining.OpenTargetConjectureMiner.OpenTargetConjecture;
import de.regelsuche.mining.OpenTargetConjectureMiner.PathEvidence;
import de.regelsuche.mining.OpenTargetConjectureNoveltyChecker.MatchRelation;
import de.regelsuche.mining.OpenTargetConjectureNoveltyChecker.NoveltyMatch;
import de.regelsuche.mining.OpenTargetConjectureNoveltyChecker.NoveltyReport;
import de.regelsuche.mining.OpenTargetConjectureNoveltyChecker.NoveltyStatus;
import de.regelsuche.mining.OpenTargetConjectureProofGate.EligibilityStatus;
import de.regelsuche.mining.OpenTargetConjectureProofGate.ProofReport;
import de.regelsuche.mining.OpenTargetConjectureProofGate.ProofStatus;
import de.regelsuche.search.strategy.BestFirstSearchStrategy.GoalStatus;
import de.regelsuche.solver.ir.SolverIr;
import de.regelsuche.solver.ir.SolverIr.BackendDescriptor;
import de.regelsuche.solver.ir.SolverIr.RequestedEvidence;
import de.regelsuche.solver.ir.SolverIr.ResultStatus;
import de.regelsuche.solver.ir.SolverIr.SolverResult;
import de.regelsuche.solver.ir.SolverIr.SourceProvenance;
import de.regelsuche.solver.ir.SolverIr.Theory;
import de.regelsuche.solver.ir.SolverIr.TranslationStatus;
import de.regelsuche.solver.ir.SolverObligationFactory;
import de.regelsuche.validation.CandidateProofStatus;
import de.regelsuche.validation.CounterexampleSearchService;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class CrossFamilyBridgeQualificationGateTest {
    private static final String ID = "bridge-hypothesis-factor-common";
    private static final String LEFT = "A * B + A * C";
    private static final String RIGHT = "A * (B + C)";
    private static final List<String> ASSUMPTIONS = List.of("q != 0");
    private static final List<String> PARAMETER_RELATIONS = List.of("shared(A)");
    private static final Map<String, List<String>> PLACEHOLDERS = Map.of(
        "A", List.of("m", "p / q"));

    private final CrossFamilyBridgeQualificationGate gate =
        new CrossFamilyBridgeQualificationGate();
    private final CrossFamilyBridgeAblationEvaluator ablationEvaluator =
        new CrossFamilyBridgeAblationEvaluator();

    @Test
    void qualifiesCompleteIndependentEvidenceAndWritesCanonicalArtifacts()
            throws Exception {
        Fixture fixture = fixture();
        AblationReport ablation = ablation(fixture, false);
        InterestingnessAssessment assessment = assessment(fixture, ablation);

        var report = gate.evaluate(input(fixture, fixture.novelty(), ablation, assessment));
        Path directory = Path.of(
            "build", "reports", "cross-family-bridge-qualification");
        Path ablationOutput = directory.resolve("ablation.json");
        Path reportOutput = directory.resolve("report.json");
        ablation.write(ablationOutput);
        report.write(reportOutput);

        assertTrue(report.qualified(), report.blockers().toString());
        assertEquals(
            QualificationStatus.QUALIFIED_FOR_PROMOTION_REVIEW,
            report.status());
        assertEquals(AssumptionStatus.CONSISTENT, report.assumptionStatus());
        assertEquals(AblationStatus.BENEFICIAL_HELD_OUT, report.ablationStatus());
        assertEquals(Eligibility.RANKABLE_COMPLETE, report.interestingnessEligibility());
        assertEquals("NOVEL_WITHIN_PROJECT", report.projectNoveltyStatus());
        assertEquals("SYMBOLICALLY_VERIFIED", report.symbolicProofStatus());
        assertEquals("NOT_EVALUATED", report.externalNoveltyStatus());
        assertEquals("NOT_EVALUATED", report.promotionStatus());
        assertEquals("NOT_EVALUATED", report.publicEvidenceStatus());
        assertEquals(ResultStatus.CONFIRMED, fixture.proof().result().status());
        assertEquals(TranslationStatus.LOSSLESS,
            fixture.proof().result().translationStatus());
        assertEquals(ablation.toCanonicalJson(), Files.readString(ablationOutput));
        assertEquals(report.toCanonicalJson(), Files.readString(reportOutput));
    }

    @Test
    void heldOutMaterialGainIsRequiredEvenWhenFormationFamiliesImprove() {
        Fixture fixture = fixture();
        AblationReport ablation = ablation(fixture, true);
        InterestingnessAssessment assessment = assessment(fixture, ablation);

        var report = gate.evaluate(input(fixture, fixture.novelty(), ablation, assessment));

        assertEquals(AblationStatus.NO_HELD_OUT_GAIN, ablation.status());
        assertFalse(report.qualified());
        assertTrue(report.blockers().contains(
            "held-out paired ablation is not beneficial"));
    }

    @Test
    void duplicateNoveltyBlocksWithoutErasingProofEvidence() {
        Fixture fixture = fixture();
        AblationReport ablation = ablation(fixture, false);
        InterestingnessAssessment assessment = assessment(fixture, ablation);
        NoveltyReport duplicate = new NoveltyReport(
            OpenTargetConjectureNoveltyChecker.SCHEMA,
            ID,
            NoveltyStatus.EXACT_DUPLICATE,
            sha("exact"),
            sha("alpha"),
            10,
            2,
            List.of(new NoveltyMatch(
                "ACTIVE_INVENTORY", "known-factor-common", MatchRelation.EXACT)),
            "NOT_EVALUATED",
            "active inventory duplicate");

        var report = gate.evaluate(input(fixture, duplicate, ablation, assessment));

        assertFalse(report.qualified());
        assertEquals("SYMBOLICALLY_VERIFIED", report.symbolicProofStatus());
        assertEquals("EXACT_DUPLICATE", report.projectNoveltyStatus());
        assertTrue(report.blockers().stream().anyMatch(blocker ->
            blocker.startsWith("project novelty is not established")));
    }

    @Test
    void assumptionDriftBlocksQualification() {
        Fixture fixture = fixture();
        AblationReport ablation = ablation(fixture, false);
        InterestingnessAssessment assessment = assessment(fixture, ablation);
        HypothesisCandidate drifted = fixture.lifecycle().withAssumptions(List.of());

        var report = gate.evaluate(new Input(
            fixture.hypothesis(),
            fixture.transfer(),
            drifted,
            fixture.novelty(),
            fixture.proof(),
            ablation,
            assessment));

        assertFalse(report.qualified());
        assertEquals(AssumptionStatus.MISMATCH, report.assumptionStatus());
        assertTrue(report.blockers().contains("assumption evidence mismatch"));
    }

    @Test
    void evidenceIsDeterministicAcrossFamilyOrder() {
        Fixture fixture = fixture();
        List<FamilyAblation> runs = pairedRuns(false);
        AblationReport first = ablationEvaluator.evaluate(
            fixture.hypothesis(), fixture.transfer(), runs);
        AblationReport second = ablationEvaluator.evaluate(
            fixture.hypothesis(),
            fixture.transfer(),
            List.of(runs.get(2), runs.get(0), runs.get(1)));
        InterestingnessAssessment assessment = assessment(fixture, first);

        var firstReport = gate.evaluate(input(
            fixture, fixture.novelty(), first, assessment));
        var secondReport = gate.evaluate(input(
            fixture, fixture.novelty(), second, assessment));

        assertEquals(first.contentHash(), second.contentHash());
        assertEquals(first.toCanonicalJson(), second.toCanonicalJson());
        assertEquals(firstReport.contentHash(), secondReport.contentHash());
        assertEquals(firstReport.toCanonicalJson(), secondReport.toCanonicalJson());
    }

    private AblationReport ablation(Fixture fixture, boolean heldOutUnchanged) {
        return ablationEvaluator.evaluate(
            fixture.hypothesis(), fixture.transfer(), pairedRuns(heldOutUnchanged));
    }

    private static List<FamilyAblation> pairedRuns(boolean heldOutUnchanged) {
        RunEvidence heldOutWithout = heldOutUnchanged
            ? new RunEvidence(true, 2, 8)
            : new RunEvidence(false, -1, -1L);
        return List.of(
            run("algebra", FamilyRole.FORMATION, true, 2, 9, true, 4, 25),
            run("rational", FamilyRole.FORMATION, true, 3, 12, true, 3, 12),
            new FamilyAblation(
                "functional",
                FamilyRole.HELD_OUT,
                new RunEvidence(true, 2, 8),
                heldOutWithout));
    }

    private static FamilyAblation run(
        String family,
        FamilyRole role,
        boolean withSuccess,
        int withLength,
        long withStates,
        boolean withoutSuccess,
        int withoutLength,
        long withoutStates
    ) {
        return new FamilyAblation(
            family,
            role,
            new RunEvidence(withSuccess, withLength, withStates),
            new RunEvidence(withoutSuccess, withoutLength, withoutStates));
    }

    private static InterestingnessAssessment assessment(
        Fixture fixture,
        AblationReport ablation
    ) {
        int positives = fixture.transfer().familyResults().stream()
            .mapToInt(FamilyResult::configuredPositiveHoldouts)
            .sum();
        int negatives = fixture.transfer().familyResults().stream()
            .mapToInt(FamilyResult::configuredNegativeHoldouts)
            .sum();
        InterestingnessEvidence evidence = new InterestingnessEvidence(
            fixture.transfer().contentHash(),
            positives,
            positives,
            0,
            0,
            negatives,
            negatives,
            0,
            0,
            CounterexampleSearchService.Status.NO_COUNTEREXAMPLE_FOUND,
            3,
            false,
            ProjectNoveltyStatus.NOVEL_WITHIN_PROJECT,
            3,
            true,
            1,
            1,
            true,
            ablation.beneficial() ? 800 : 0,
            ControlClassification.NONE);
        return new EvidenceAwareInterestingnessAssessor().assess(
            fixture.lifecycle(),
            0.10,
            Set.of("algebra", "rational", "functional"),
            evidence,
            InterestingnessProfile.THEORY_DISCOVERY);
    }

    private static Input input(
        Fixture fixture,
        NoveltyReport novelty,
        AblationReport ablation,
        InterestingnessAssessment assessment
    ) {
        return new Input(
            fixture.hypothesis(),
            fixture.transfer(),
            fixture.lifecycle(),
            novelty,
            fixture.proof(),
            ablation,
            assessment);
    }

    private static Fixture fixture() {
        OpenTargetConjecture conjecture = conjecture();
        BridgeHypothesis hypothesis = new BridgeHypothesis(
            CrossFamilyBridgeHypothesisBuilder.SCHEMA,
            ID,
            "structural-cluster-factor-common",
            sha("cluster"),
            false,
            LEFT,
            RIGHT,
            List.of("algebra", "rational"),
            List.of("candidate-algebra", "candidate-rational"),
            List.of("obs-algebra", "obs-rational"),
            ASSUMPTIONS,
            "NOT_EVALUATED",
            "NOT_EVALUATED",
            "NOT_EVALUATED",
            "NOT_EVALUATED",
            "NOT_EVALUATED",
            sha("formation"),
            conjecture);
        TransferReport transfer = transfer(hypothesis);
        return new Fixture(
            hypothesis,
            transfer,
            lifecycle(),
            novelty(),
            proof());
    }

    private static OpenTargetConjecture conjecture() {
        return new OpenTargetConjecture(
            ID,
            LEFT,
            RIGHT,
            2,
            2,
            List.of("algebra", "rational"),
            List.of("obs-algebra", "obs-rational"),
            List.of(
                evidence("obs-algebra", "algebra", "m * 2 + m * 3", "m * (2 + 3)"),
                evidence(
                    "obs-rational",
                    "rational",
                    "(p / q) * 4 + (p / q) * 5",
                    "(p / q) * (4 + 5)")),
            PARAMETER_RELATIONS,
            PLACEHOLDERS,
            "OBSERVED_CONJECTURE",
            "EQUIVALENCE_PRESERVING_CONVERGENT_PATHS");
    }

    private static ConvergenceEvidence evidence(
        String observationId,
        String family,
        String input,
        String output
    ) {
        PathEvidence direct = new PathEvidence(
            "path-" + observationId + "-direct",
            List.of(input, output),
            List.of("factor-common"),
            ASSUMPTIONS,
            1,
            8);
        PathEvidence alternate = new PathEvidence(
            "path-" + observationId + "-alternate",
            List.of(input, "bridge(" + input + ")", output),
            List.of("prepare", "factor-common"),
            ASSUMPTIONS,
            2,
            8);
        return new ConvergenceEvidence(
            observationId,
            family,
            GoalStatus.UNTARGETED,
            input,
            output,
            sha("canonical-" + family),
            10,
            "alpha-" + family,
            "value-" + family,
            "direct||prepare>factor-common",
            List.of(direct, alternate));
    }

    private static TransferReport transfer(BridgeHypothesis hypothesis) {
        return new TransferReport(
            CrossFamilyBridgeTransferEvaluator.SCHEMA,
            ID,
            hypothesis.sourceClusterId(),
            hypothesis.formationHash(),
            false,
            List.of("algebra", "rational"),
            List.of("functional"),
            TransferStatus.ACCEPTED_CROSS_FAMILY_TRANSFER,
            List.of(
                familyResult("algebra", FamilyRole.FORMATION),
                familyResult("rational", FamilyRole.FORMATION),
                familyResult("functional", FamilyRole.HELD_OUT)),
            List.of(),
            "NOT_EVALUATED",
            "NOT_EVALUATED",
            "NOT_EVALUATED",
            "NOT_EVALUATED",
            sha("transfer"));
    }

    private static FamilyResult familyResult(String family, FamilyRole role) {
        return new FamilyResult(
            family,
            role,
            FamilyStatus.ACCEPTED,
            1,
            1,
            0,
            1,
            1,
            0,
            List.of(),
            List.of(),
            "dynamic-" + ID,
            sha("provenance-" + family),
            CounterexampleSearchService.Status.NO_COUNTEREXAMPLE_FOUND.name(),
            List.of("numeric-boundary-values"),
            List.of(),
            List.of(),
            "",
            "",
            "no counterexample within the configured budget",
            List.of());
    }

    private static HypothesisCandidate lifecycle() {
        return new HypothesisCandidate(
            ID,
            LEFT,
            RIGHT,
            List.of("path-obs-algebra-direct", "path-obs-rational-direct"),
            List.of(
                new HypothesisCandidate.ExpressionPair(
                    "m * 2 + m * 3", "m * (2 + 3)"),
                new HypothesisCandidate.ExpressionPair(
                    "(p / q) * 4 + (p / q) * 5", "(p / q) * (4 + 5)")),
            ASSUMPTIONS,
            0.6,
            CandidateProofStatus.SYMBOLICALLY_VERIFIED,
            Boolean.FALSE,
            CounterexampleSearchService.Status.NO_COUNTEREXAMPLE_FOUND,
            List.of("numeric-boundary-values"),
            "no counterexample within the configured budget",
            PARAMETER_RELATIONS,
            PLACEHOLDERS,
            Instant.parse("2026-07-14T12:00:00Z"));
    }

    private static NoveltyReport novelty() {
        return new NoveltyReport(
            OpenTargetConjectureNoveltyChecker.SCHEMA,
            ID,
            NoveltyStatus.NOVEL_WITHIN_PROJECT,
            sha("exact"),
            sha("alpha"),
            10,
            2,
            List.of(),
            "NOT_EVALUATED",
            "no project duplicate found");
    }

    private static ProofReport proof() {
        SolverIr.Obligation obligation = new SolverObligationFactory().equality(
            ID + "-proof",
            LEFT,
            RIGHT,
            ASSUMPTIONS,
            RequestedEvidence.SYMBOLIC_CERTIFICATE,
            new SourceProvenance(
                "cross-family-bridge",
                ID,
                sha("bridge-proof-revision")));
        BackendDescriptor descriptor = new BackendDescriptor(
            "assumptions-aware-symbolic-backend",
            "1",
            List.of(Theory.REAL_ARITHMETIC),
            List.of(SolverIr.Relation.EQUALS),
            List.of(RequestedEvidence.SYMBOLIC_CERTIFICATE),
            true);
        SolverResult result = SolverResult.create(
            obligation,
            descriptor,
            ResultStatus.CONFIRMED,
            TranslationStatus.LOSSLESS,
            List.of("STRUCTURED_ASSUMPTIONS", "SYMBOLIC_EQUIVALENCE"),
            List.of(),
            "symbolic equivalence confirmed under structured assumptions",
            Map.of(),
            sha("certificate"));
        return new ProofReport(
            OpenTargetConjectureProofGate.REPORT_SCHEMA,
            ID,
            EligibilityStatus.ELIGIBLE,
            ProofStatus.SYMBOLICALLY_VERIFIED,
            obligation,
            result,
            "NOT_EVALUATED",
            List.of(),
            sha("proof"));
    }

    private static String sha(String material) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(material.getBytes(StandardCharsets.UTF_8));
            return "sha256:" + java.util.HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private record Fixture(
        BridgeHypothesis hypothesis,
        TransferReport transfer,
        HypothesisCandidate lifecycle,
        NoveltyReport novelty,
        ProofReport proof
    ) {
    }
}
