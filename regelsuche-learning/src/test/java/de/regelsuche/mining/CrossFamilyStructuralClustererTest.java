package de.regelsuche.mining;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.mining.CrossFamilyStructuralClusterer.ClusterStatus;
import de.regelsuche.mining.OpenTargetConjectureMiner.ConvergenceEvidence;
import de.regelsuche.mining.OpenTargetConjectureMiner.OpenTargetConjecture;
import de.regelsuche.mining.OpenTargetConjectureMiner.PathEvidence;
import de.regelsuche.search.strategy.BestFirstSearchStrategy.GoalStatus;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CrossFamilyStructuralClustererTest {
    private final CrossFamilyStructuralClusterer clusterer =
        new CrossFamilyStructuralClusterer();

    @Test
    void formsEligibleClusterWithoutUsingFamilyOrRuleIdsInTheSignature() {
        OpenTargetConjecture algebra = conjecture(
            "candidate-algebra",
            "A * B + A * C",
            "A * (B + C)",
            "algebra",
            List.of("alpha-a", "alpha-b"),
            "algebra-rule");
        OpenTargetConjecture rational = conjecture(
            "candidate-rational",
            "X * Y + X * Z",
            "X * (Y + Z)",
            "rational",
            List.of("alpha-c", "alpha-d"),
            "completely-different-rule");

        var report = clusterer.cluster(List.of(rational, algebra));

        assertEquals(CrossFamilyStructuralClusterer.SCHEMA, report.schema());
        assertEquals(1, report.clusters().size(), report.rejectedCandidates().toString());
        var cluster = report.clusters().getFirst();
        assertEquals(ClusterStatus.ELIGIBLE_BRIDGE, cluster.status());
        assertEquals(List.of("candidate-algebra", "candidate-rational"), cluster.candidateIds());
        assertEquals(List.of("algebra", "rational"), cluster.postHocFamilies());
        assertEquals(4, cluster.distinctAlphaSupport());
        assertTrue(cluster.structuralSignatureHash().startsWith("sha256:"));
        assertTrue(cluster.namedFeatures().stream().anyMatch(feature ->
            feature.startsWith("rootTransition=BINARY:ADD->BINARY:MUL")));
        assertFalse(cluster.structuralSignature().contains("algebra"));
        assertFalse(cluster.structuralSignature().contains("rational"));
        assertFalse(cluster.structuralSignature().contains("rule"));
    }

    @Test
    void postHocFamilyRelabelingCannotChangeTheStructuralKey() {
        var original = clusterer.cluster(List.of(
            conjecture("candidate-1", "A * B + A * C", "A * (B + C)",
                "family-a", List.of("a-1", "a-2"), "rule-a"),
            conjecture("candidate-2", "X * Y + X * Z", "X * (Y + Z)",
                "family-b", List.of("b-1", "b-2"), "rule-b")))
            .clusters().getFirst();
        var relabeled = clusterer.cluster(List.of(
            conjecture("candidate-1", "A * B + A * C", "A * (B + C)",
                "other-family", List.of("a-1", "a-2"), "other-rule"),
            conjecture("candidate-2", "X * Y + X * Z", "X * (Y + Z)",
                "another-family", List.of("b-1", "b-2"), "unknown-rule")))
            .clusters().getFirst();

        assertEquals(original.clusterId(), relabeled.clusterId());
        assertEquals(original.structuralSignature(), relabeled.structuralSignature());
        assertEquals(original.namedFeatures(), relabeled.namedFeatures());
        assertNotEquals(original.postHocFamilies(), relabeled.postHocFamilies());
    }

    @Test
    void keepsSameFamilySupportVisibleButIneligible() {
        var report = clusterer.cluster(List.of(
            conjecture("candidate-1", "A * B + A * C", "A * (B + C)",
                "algebra", List.of("a-1", "a-2"), "rule-a"),
            conjecture("candidate-2", "X * Y + X * Z", "X * (Y + Z)",
                "algebra", List.of("b-1", "b-2"), "rule-b")));

        assertEquals(ClusterStatus.SINGLE_FAMILY, report.clusters().getFirst().status());
        assertEquals(List.of("algebra"), report.clusters().getFirst().postHocFamilies());
    }

    @Test
    void rejectsSupportExplainedOnlyByAlphaRenaming() {
        var report = clusterer.cluster(List.of(
            conjecture("candidate-1", "A * B + A * C", "A * (B + C)",
                "algebra", List.of("same-alpha", "same-alpha"), "rule-a"),
            conjecture("candidate-2", "X * Y + X * Z", "X * (Y + Z)",
                "rational", List.of("same-alpha", "same-alpha"), "rule-b")));

        var cluster = report.clusters().getFirst();
        assertEquals(ClusterStatus.ALPHA_RENAME_ONLY, cluster.status());
        assertEquals(1, cluster.distinctAlphaSupport());
    }

    @Test
    void neutralElementSimplificationNeverBecomesABridge() {
        var report = clusterer.cluster(List.of(
            conjecture("candidate-1", "A + 0", "A",
                "algebra", List.of("a-1", "a-2"), "add-zero"),
            conjecture("candidate-2", "X + 0", "X",
                "rational", List.of("b-1", "b-2"), "different-add-zero")));

        assertEquals(ClusterStatus.GENERIC_NORMALIZATION,
            report.clusters().getFirst().status());
    }

    @Test
    void rewriteDirectionRemainsPartOfTheStructuralIdentity() {
        var report = clusterer.cluster(List.of(
            conjecture("forward", "A * B + A * C", "A * (B + C)",
                "algebra", List.of("a-1", "a-2"), "factor"),
            conjecture("reverse", "X * (Y + Z)", "X * Y + X * Z",
                "algebra", List.of("b-1", "b-2"), "expand")));

        assertEquals(2, report.clusters().size());
        assertNotEquals(report.clusters().get(0).structuralSignatureHash(),
            report.clusters().get(1).structuralSignatureHash());
        assertTrue(report.clusters().stream().allMatch(cluster ->
            cluster.status() == ClusterStatus.SINGLE_CANDIDATE));
    }

    private static OpenTargetConjecture conjecture(
        String id,
        String leftPattern,
        String rightPattern,
        String family,
        List<String> alphaFingerprints,
        String ruleId
    ) {
        ConvergenceEvidence first = evidence(
            id + "-obs-1", family, leftPattern, rightPattern,
            alphaFingerprints.get(0), ruleId + "-first");
        ConvergenceEvidence second = evidence(
            id + "-obs-2", family, leftPattern, rightPattern,
            alphaFingerprints.get(1), ruleId + "-second");
        int distinctAlpha = (int) alphaFingerprints.stream().distinct().count();
        return new OpenTargetConjecture(
            id,
            leftPattern,
            rightPattern,
            2,
            distinctAlpha,
            List.of(family),
            List.of(first.observationId(), second.observationId()),
            List.of(first, second),
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
        String ruleId
    ) {
        PathEvidence path = new PathEvidence(
            "path-" + observationId,
            List.of(input, output),
            List.of(ruleId),
            List.of(),
            1,
            10);
        return new ConvergenceEvidence(
            observationId,
            family,
            GoalStatus.UNTARGETED,
            input,
            output,
            "canonical-" + observationId,
            10,
            alphaFingerprint,
            "value-" + observationId,
            "competition-" + observationId,
            List.of(path));
    }
}
