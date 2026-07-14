package de.regelsuche.mining;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.mining.CrossFamilyBridgeHypothesisBuilder.BridgeHypothesis;
import de.regelsuche.mining.CrossFamilyBridgeTransferEvaluator.FamilyResult;
import de.regelsuche.mining.CrossFamilyBridgeTransferEvaluator.FamilyRole;
import de.regelsuche.mining.CrossFamilyBridgeTransferEvaluator.FamilyStatus;
import de.regelsuche.mining.CrossFamilyBridgeTransferEvaluator.FamilySuite;
import de.regelsuche.mining.CrossFamilyBridgeTransferEvaluator.TransferReport;
import de.regelsuche.mining.CrossFamilyBridgeTransferEvaluator.TransferStatus;
import de.regelsuche.mining.CrossFamilyStructuralClusterer.BridgeCluster;
import de.regelsuche.mining.CrossFamilyStructuralClusterer.ClusterStatus;
import de.regelsuche.mining.OpenTargetConjectureEvaluator.CounterexampleEvidence;
import de.regelsuche.mining.OpenTargetConjectureEvaluator.EvaluationPlan;
import de.regelsuche.mining.OpenTargetConjectureEvaluator.EvaluationReport;
import de.regelsuche.mining.OpenTargetConjectureEvaluator.EvaluationStatus;
import de.regelsuche.mining.OpenTargetConjectureEvaluator.NegativeHoldout;
import de.regelsuche.mining.OpenTargetConjectureEvaluator.NegativeHoldoutResult;
import de.regelsuche.mining.OpenTargetConjectureEvaluator.PositiveHoldout;
import de.regelsuche.mining.OpenTargetConjectureEvaluator.PositiveHoldoutResult;
import de.regelsuche.mining.OpenTargetConjectureMiner.ConvergenceEvidence;
import de.regelsuche.mining.OpenTargetConjectureMiner.OpenTargetConjecture;
import de.regelsuche.mining.OpenTargetConjectureMiner.PathEvidence;
import de.regelsuche.search.strategy.BestFirstSearchStrategy.GoalStatus;
import de.regelsuche.validation.CounterexampleSearchService.CounterexampleBudget;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CrossFamilyBridgeTransferEvaluatorTest {
    private final CrossFamilyStructuralClusterer clusterer =
        new CrossFamilyStructuralClusterer();
    private final CrossFamilyBridgeHypothesisBuilder builder =
        new CrossFamilyBridgeHypothesisBuilder();

    @Test
    void formsTargetFreeHypothesisFromFormationFamiliesOnly() {
        Formation fixture = formation(false);

        BridgeHypothesis hypothesis = fixture.hypothesis();

        assertEquals(CrossFamilyBridgeHypothesisBuilder.SCHEMA, hypothesis.schema());
        assertFalse(hypothesis.targetProvided());
        assertEquals(List.of("algebra", "rational"), hypothesis.trainingFamilies());
        assertFalse(hypothesis.toCanonicalJson().contains("functional"));
        assertEquals("NOT_EVALUATED", hypothesis.validationStatus());
        assertEquals("NOT_EVALUATED", hypothesis.proofStatus());
        assertEquals("NOT_EVALUATED", hypothesis.noveltyStatus());
        assertEquals("NOT_EVALUATED", hypothesis.ablationStatus());
        assertEquals("NOT_EVALUATED", hypothesis.interestingnessStatus());
        assertTrue(hypothesis.formationHash().matches("sha256:[0-9a-f]{64}"));
    }

    @Test
    void formationIsDeterministicAcrossConjectureAndEvidenceOrder() {
        Formation ordered = formation(false);
        Formation reversed = formation(true);

        assertEquals(
            ordered.hypothesis().formationHash(),
            reversed.hypothesis().formationHash());
        assertEquals(
            ordered.hypothesis().toCanonicalJson(),
            reversed.hypothesis().toCanonicalJson());
        assertEquals(
            ordered.hypothesis().conjecture(),
            reversed.hypothesis().conjecture());
    }

    @Test
    void acceptsFreshSuitesInFormationAndHeldOutFamiliesAndWritesEvidence()
            throws Exception {
        Formation fixture = formation(false);
        CrossFamilyBridgeTransferEvaluator evaluator =
            new CrossFamilyBridgeTransferEvaluator();
        List<FamilySuite> suites = passingSuites();

        TransferReport report = evaluator.evaluate(fixture.hypothesis(), suites);
        TransferReport reversed = evaluator.evaluate(
            fixture.hypothesis(), List.of(suites.get(2), suites.get(1), suites.get(0)));
        Path output = Path.of(
            "build", "reports", "cross-family-bridge-transfer", "report.json");
        Path hypothesisOutput = Path.of(
            "build", "reports", "cross-family-bridge-transfer", "hypothesis.json");
        report.write(output);
        Files.createDirectories(hypothesisOutput.getParent());
        Files.writeString(
            hypothesisOutput,
            fixture.hypothesis().toCanonicalJson(),
            StandardCharsets.UTF_8);

        assertTrue(report.accepted(), report.blockers().toString());
        assertEquals(
            TransferStatus.ACCEPTED_CROSS_FAMILY_TRANSFER,
            report.status());
        assertEquals(List.of("functional"), report.heldOutFamilies());
        assertEquals(3, report.familyResults().size());
        assertTrue(report.familyResults().stream().allMatch(FamilyResult::accepted));
        assertTrue(report.familyResults().stream().allMatch(result ->
            result.configuredPositiveHoldouts() == 1
                && result.executedPositiveHoldouts() == 1
                && result.skippedPositiveHoldouts() == 0
                && result.configuredNegativeHoldouts() == 1
                && result.executedNegativeHoldouts() == 1
                && result.skippedNegativeHoldouts() == 0));
        assertEquals(report.contentHash(), reversed.contentHash());
        assertEquals(report.toCanonicalJson(), reversed.toCanonicalJson());
        assertEquals(report.toCanonicalJson(), Files.readString(output));
        assertTrue(Files.isRegularFile(hypothesisOutput));
        assertTrue(report.toCanonicalJson().contains(
            "\"status\":\"ACCEPTED_CROSS_FAMILY_TRANSFER\""));
        assertTrue(report.toCanonicalJson().contains(
            "\"noveltyStatus\":\"NOT_EVALUATED\""));
    }

    @Test
    void rejectsTransferWhenOnlyFormationFamiliesPass() {
        Formation fixture = formation(false);
        CrossFamilyBridgeTransferEvaluator evaluator =
            new CrossFamilyBridgeTransferEvaluator((conjecture, plan) ->
                plan.revision().contains("functional")
                    ? rejectedByCounterexample(conjecture, plan)
                    : accepted(conjecture, plan));

        TransferReport report = evaluator.evaluate(
            fixture.hypothesis(), passingSuites());

        assertFalse(report.accepted());
        assertEquals(
            TransferStatus.REJECTED_HELD_OUT_TRANSFER,
            report.status());
        FamilyResult heldOut = result(report, "functional");
        assertEquals(FamilyRole.HELD_OUT, heldOut.role());
        assertEquals(FamilyStatus.REJECTED, heldOut.status());
        assertEquals("COUNTEREXAMPLE_FOUND", heldOut.counterexampleStatus());
        assertEquals(List.of("z=2"), heldOut.counterexampleAssignments());
        assertEquals("16", heldOut.counterexampleLeftValue());
        assertEquals("8", heldOut.counterexampleRightValue());
        assertTrue(report.blockers().stream().anyMatch(blocker ->
            blocker.startsWith("functional:")));
    }

    @Test
    void incompleteHeldOutSuiteCannotPassVacuously() {
        Formation fixture = formation(false);
        FamilySuite incomplete = new FamilySuite(
            "functional",
            FamilyRole.HELD_OUT,
            "bridge-functional-incomplete-v1",
            List.of(new PositiveHoldout(
                "functional-positive",
                "sin(t) * 6 + sin(t) * 8",
                "sin(t) * (6 + 8)")),
            List.of(),
            CounterexampleBudget.defaultBudget());

        TransferReport report = new CrossFamilyBridgeTransferEvaluator(
            CrossFamilyBridgeTransferEvaluatorTest::accepted)
            .evaluate(fixture.hypothesis(), List.of(
                algebraSuite(), rationalSuite(), incomplete));

        assertEquals(TransferStatus.INCOMPLETE_EVIDENCE, report.status());
        FamilyResult heldOut = result(report, "functional");
        assertEquals(FamilyStatus.INCOMPLETE_SUITE, heldOut.status());
        assertEquals(1, heldOut.configuredPositiveHoldouts());
        assertEquals(0, heldOut.executedPositiveHoldouts());
        assertEquals(1, heldOut.skippedPositiveHoldouts());
        assertTrue(heldOut.blockers().contains("negative holdouts missing"));
    }

    @Test
    void refusesFormationConjecturesThatDoNotReproduceTheCluster() {
        Formation fixture = formation(false);
        OpenTargetConjecture unrelated = conjecture(
            "unrelated",
            "A + 0",
            "A",
            "algebra",
            List.of(
                evidence("unrelated-1", "algebra", "x + 0", "x",
                    "alpha-unrelated-1", "value-unrelated-1", "drop-zero"),
                evidence("unrelated-2", "algebra", "y + 0", "y",
                    "alpha-unrelated-2", "value-unrelated-2", "drop-zero")));

        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> builder.form(
                fixture.cluster(),
                List.of(fixture.formationConjectures().getFirst(), unrelated)));

        assertTrue(exception.getMessage().contains("match the source cluster"));
    }

    private Formation formation(boolean reverse) {
        OpenTargetConjecture algebra = conjecture(
            "algebra-factor",
            "A * B + A * C",
            "A * (B + C)",
            "algebra",
            List.of(
                evidence("algebra-1", "algebra", "x * 2 + x * 3", "x * (2 + 3)",
                    "alpha-algebra-1", "value-algebra-1", "factor-algebra"),
                evidence("algebra-2", "algebra", "y * 4 + y * 5", "y * (4 + 5)",
                    "alpha-algebra-2", "value-algebra-2", "factor-algebra")));
        OpenTargetConjecture rational = conjecture(
            "rational-factor",
            "X * Y + X * Z",
            "X * (Y + Z)",
            "rational",
            List.of(
                evidence("rational-1", "rational",
                    "(p / q) * 2 + (p / q) * 3", "(p / q) * (2 + 3)",
                    "alpha-rational-1", "value-rational-1", "factor-rational"),
                evidence("rational-2", "rational",
                    "(r / s) * 4 + (r / s) * 5", "(r / s) * (4 + 5)",
                    "alpha-rational-2", "value-rational-2", "factor-rational")));
        if (reverse) {
            algebra = reverseEvidence(algebra);
            rational = reverseEvidence(rational);
        }
        List<OpenTargetConjecture> conjectures = reverse
            ? List.of(rational, algebra)
            : List.of(algebra, rational);
        BridgeCluster cluster = clusterer.cluster(conjectures).clusters().getFirst();
        assertEquals(ClusterStatus.ELIGIBLE_BRIDGE, cluster.status());
        BridgeHypothesis hypothesis = builder.form(cluster, conjectures);
        return new Formation(cluster, conjectures, hypothesis);
    }

    private static List<FamilySuite> passingSuites() {
        return List.of(algebraSuite(), rationalSuite(), functionalSuite());
    }

    private static FamilySuite algebraSuite() {
        return suite(
            "algebra",
            FamilyRole.FORMATION,
            "bridge-algebra-v1",
            "u * 7 + u * 11",
            "u * (7 + 11)",
            "u * 7 + v * 11");
    }

    private static FamilySuite rationalSuite() {
        return suite(
            "rational",
            FamilyRole.FORMATION,
            "bridge-rational-v1",
            "(a / b) * 7 + (a / b) * 11",
            "(a / b) * (7 + 11)",
            "(a / b) * 7 + (c / d) * 11");
    }

    private static FamilySuite functionalSuite() {
        return suite(
            "functional",
            FamilyRole.HELD_OUT,
            "bridge-functional-v1",
            "sin(t) * 6 + sin(t) * 8",
            "sin(t) * (6 + 8)",
            "sin(t) * 6 + cos(t) * 8");
    }

    private static FamilySuite suite(
        String family,
        FamilyRole role,
        String revision,
        String positiveInput,
        String positiveTarget,
        String negativeInput
    ) {
        return new FamilySuite(
            family,
            role,
            revision,
            List.of(new PositiveHoldout(
                family + "-positive", positiveInput, positiveTarget)),
            List.of(new NegativeHoldout(
                family + "-negative", negativeInput)),
            CounterexampleBudget.defaultBudget());
    }

    private static OpenTargetConjecture conjecture(
        String id,
        String leftPattern,
        String rightPattern,
        String family,
        List<ConvergenceEvidence> evidence
    ) {
        List<ConvergenceEvidence> ordered = evidence.stream()
            .sorted(java.util.Comparator.comparing(ConvergenceEvidence::observationId))
            .toList();
        return new OpenTargetConjecture(
            id,
            leftPattern,
            rightPattern,
            ordered.size(),
            (int) ordered.stream()
                .map(ConvergenceEvidence::alphaPairFingerprint)
                .distinct()
                .count(),
            List.of(family),
            ordered.stream().map(ConvergenceEvidence::observationId).toList(),
            ordered,
            List.of(),
            Map.of(),
            "OBSERVED_CONJECTURE",
            "EQUIVALENCE_PRESERVING_CONVERGENT_PATHS");
    }

    private static ConvergenceEvidence evidence(
        String observationId,
        String family,
        String input,
        String output,
        String alphaFingerprint,
        String valueFingerprint,
        String rulePrefix
    ) {
        PathEvidence direct = new PathEvidence(
            observationId + "-direct",
            List.of(input, output),
            List.of(rulePrefix + "-direct"),
            List.of(),
            1,
            1);
        PathEvidence alternate = new PathEvidence(
            observationId + "-alternate",
            List.of(input, "(" + output + ") + 0", output),
            List.of(rulePrefix + "-prepare", rulePrefix + "-finish"),
            List.of(),
            2,
            1);
        return new ConvergenceEvidence(
            observationId,
            family,
            GoalStatus.UNTARGETED,
            input,
            output,
            "canonical-" + observationId,
            5,
            alphaFingerprint,
            valueFingerprint,
            "competition-" + observationId,
            List.of(direct, alternate));
    }

    private static OpenTargetConjecture reverseEvidence(OpenTargetConjecture source) {
        List<ConvergenceEvidence> reversed = new ArrayList<>(source.evidence());
        java.util.Collections.reverse(reversed);
        reversed = reversed.stream()
            .map(CrossFamilyBridgeTransferEvaluatorTest::reversePaths)
            .toList();
        List<String> observationIds = new ArrayList<>(source.supportingObservationIds());
        java.util.Collections.reverse(observationIds);
        return new OpenTargetConjecture(
            source.conjectureId(),
            source.leftPattern(),
            source.rightPattern(),
            source.supportCount(),
            source.distinctAlphaSupport(),
            source.postHocFamilies(),
            observationIds,
            reversed,
            source.parameterRelations(),
            source.expressionPlaceholderValues(),
            source.candidateStatus(),
            source.evidenceStatus());
    }

    private static ConvergenceEvidence reversePaths(ConvergenceEvidence source) {
        List<PathEvidence> paths = new ArrayList<>(source.paths());
        java.util.Collections.reverse(paths);
        return new ConvergenceEvidence(
            source.observationId(),
            source.family(),
            source.searchStatus(),
            source.inputExpression(),
            source.outputExpression(),
            source.canonicalOutputHash(),
            source.scoreImprovement(),
            source.alphaPairFingerprint(),
            source.valuePairFingerprint(),
            source.pathCompetitionSignature(),
            paths);
    }

    private static EvaluationReport accepted(
        OpenTargetConjecture conjecture,
        EvaluationPlan plan
    ) {
        List<PositiveHoldoutResult> positives = plan.positiveHoldouts().stream()
            .map(holdout -> new PositiveHoldoutResult(
                holdout.id(), 1, true, List.of(holdout.targetExpression())))
            .toList();
        List<NegativeHoldoutResult> negatives = plan.negativeHoldouts().stream()
            .map(holdout -> new NegativeHoldoutResult(
                holdout.id(), 0, true, List.of()))
            .toList();
        return report(
            conjecture,
            plan,
            EvaluationStatus.ACCEPTED_FOR_PROOF,
            positives,
            negatives,
            new CounterexampleEvidence(
                "NO_COUNTEREXAMPLE_FOUND",
                List.of("boundary", "numeric"),
                List.of(),
                List.of(),
                "",
                "",
                "no counterexample within family budget"),
            List.of());
    }

    private static EvaluationReport rejectedByCounterexample(
        OpenTargetConjecture conjecture,
        EvaluationPlan plan
    ) {
        List<PositiveHoldoutResult> positives = plan.positiveHoldouts().stream()
            .map(holdout -> new PositiveHoldoutResult(
                holdout.id(), 1, true, List.of(holdout.targetExpression())))
            .toList();
        List<NegativeHoldoutResult> negatives = plan.negativeHoldouts().stream()
            .map(holdout -> new NegativeHoldoutResult(
                holdout.id(), 0, true, List.of()))
            .toList();
        return report(
            conjecture,
            plan,
            EvaluationStatus.REJECTED,
            positives,
            negatives,
            new CounterexampleEvidence(
                "COUNTEREXAMPLE_FOUND",
                List.of("numeric"),
                List.of(),
                List.of("z=2"),
                "16",
                "8",
                "held-out family counterexample"),
            List.of("counterexample found"));
    }

    private static EvaluationReport report(
        OpenTargetConjecture conjecture,
        EvaluationPlan plan,
        EvaluationStatus status,
        List<PositiveHoldoutResult> positives,
        List<NegativeHoldoutResult> negatives,
        CounterexampleEvidence counterexample,
        List<String> blockers
    ) {
        return new EvaluationReport(
            OpenTargetConjectureEvaluator.SCHEMA,
            conjecture.conjectureId(),
            status,
            "COMPILED",
            "dynamic-" + plan.revision(),
            "sha256:" + "1".repeat(64),
            plan.positiveHoldouts().size(),
            positives.size(),
            0,
            plan.negativeHoldouts().size(),
            negatives.size(),
            0,
            positives,
            negatives,
            counterexample,
            blockers,
            "NOT_EVALUATED",
            "NOT_EVALUATED");
    }

    private static FamilyResult result(TransferReport report, String family) {
        return report.familyResults().stream()
            .filter(result -> result.familyId().equals(family))
            .findFirst()
            .orElseThrow();
    }

    private record Formation(
        BridgeCluster cluster,
        List<OpenTargetConjecture> formationConjectures,
        BridgeHypothesis hypothesis
    ) {
    }
}