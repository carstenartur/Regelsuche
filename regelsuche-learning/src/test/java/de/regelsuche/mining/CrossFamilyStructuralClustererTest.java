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
            List.of("alpha-a1", "alpha-a2"),
            List.of("value-a1", "value-a2"),
            "algebra-rule",
            List.of());
        OpenTargetConjecture rational = conjecture(
            "candidate-rational",
            "X * Y + X * Z",
            "X * (Y + Z)",
            "rational",
            List.of("alpha-r1", "alpha-r2"),
            List.of("value-r1", "value-r2"),
            "completely-different-rule",
            List.of());

        var report = clusterer.cluster(List.of(rational, algebra));

        assertEquals(CrossFamilyStructuralClusterer.SCHEMA, report.schema());
        assertEquals(1, report.clusters().size(), report.rejectedCandidates().toString());
        assertTrue(report.rejectedCandidates().isEmpty());
        var cluster = report.clusters().getFirst();
        assertEquals(ClusterStatus.ELIGIBLE_BRIDGE, cluster.status());
        assertEquals(List.of("candidate-algebra", "candidate-rational"), cluster.candidateIds());
        assertEquals(List.of("algebra", "rational"), cluster.postHocFamilies());
        assertEquals(4, cluster.distinctAlphaSupport());
        assertEquals(4, cluster.distinctValueSupport());
        assertEquals(2, cluster.independentAlphaProfiles());
        assertEquals(2, cluster.independentValueProfiles());
        assertTrue(cluster.structuralSignatureHash().startsWith("sha256:"));
        assertTrue(report.contentHash().startsWith("sha256:"));
        assertTrue(cluster.namedFeatures().stream().anyMatch(feature ->
            feature.startsWith("rootTransition=BINARY:ADD->BINARY:MUL")));
        String json = report.toCanonicalJson();
        assertEquals(json, report.toCanonicalJson());
        assertFalse(json.contains("algebra-rule"));
        assertFalse(json.contains("completely-different-rule"));
        assertFalse(cluster.structuralSignature().contains("algebra"));
        assertFalse(cluster.structuralSignature().contains("rational"));
    }

    @Test
    void postHocRelabelingCannotChangeTheStructuralKey() {
        var original = clusterer.cluster(List.of(
            conjecture("candidate-1", "A * B + A * C", "A * (B + C)",
                "family-a", List.of("a-1", "a-2"), List.of("v-1", "v-2"),
                "rule-a", List.of("nonZero(B)")),
            conjecture("candidate-2", "X * Y + X * Z", "X * (Y + Z)",
                "family-b", List.of("b-1", "b-2"), List.of("w-1", "w-2"),
                "rule-b", List.of("nonZero(B)"))))
            .clusters().getFirst();
        var relabeled = clusterer.cluster(List.of(
            conjecture("candidate-1", "A * B + A * C", "A * (B + C)",
                "other-family", List.of("a-1", "a-2"), List.of("v-1", "v-2"),
                "other-rule", List.of("nonZero(B)")),
            conjecture("candidate-2", "X * Y + X * Z", "X * (Y + Z)",
                "another-family", List.of("b-1", "b-2"), List.of("w-1", "w-2"),
                "unknown-rule", List.of("nonZero(B)"))))
            .clusters().getFirst();

        assertEquals(original.clusterId(), relabeled.clusterId());
        assertEquals(original.structuralSignature(), relabeled.structuralSignature());
        assertEquals(original.namedFeatures(), relabeled.namedFeatures());
        assertEquals(List.of("nonZero(B)"), original.assumptions());
        assertNotEquals(original.postHocFamilies(), relabeled.postHocFamilies());
    }

    @Test
    void keepsSameFamilySupportVisibleButIneligible() {
        var report = clusterer.cluster(List.of(
            conjecture("candidate-1", "A * B + A * C", "A * (B + C)",
                "algebra", List.of("a-1", "a-2"), List.of("v-1", "v-2"),
                "rule-a", List.of()),
            conjecture("candidate-2", "X * Y + X * Z", "X * (Y + Z)",
                "algebra", List.of("b-1", "b-2"), List.of("w-1", "w-2"),
                "rule-b", List.of())));

        assertEquals(ClusterStatus.SINGLE_FAMILY, report.clusters().getFirst().status());
        assertEquals(List.of("algebra"), report.clusters().getFirst().postHocFamilies());
    }

    @Test
    void rejectsSupportProfilesExplainedOnlyByAlphaRenaming() {
        var report = clusterer.cluster(List.of(
            conjecture("candidate-1", "A * B + A * C", "A * (B + C)",
                "algebra", List.of("same-1", "same-2"), List.of("v-1", "v-2"),
                "rule-a", List.of()),
            conjecture("candidate-2", "X * Y + X * Z", "X * (Y + Z)",
                "rational", List.of("same-1", "same-2"), List.of("w-1", "w-2"),
                "rule-b", List.of())));

        var cluster = report.clusters().getFirst();
        assertEquals(ClusterStatus.ALPHA_RENAME_ONLY, cluster.status());
        assertEquals(1, cluster.independentAlphaProfiles());
    }

    @Test
    void rejectsRepeatedValueEvidenceEvenWithDistinctAlphaProfiles() {
        var report = clusterer.cluster(List.of(
            conjecture("candidate-1", "A * B + A * C", "A * (B + C)",
                "algebra", List.of("a-1", "a-2"), List.of("same-v1", "same-v2"),
                "rule-a", List.of()),
            conjecture("candidate-2", "X * Y + X * Z", "X * (Y + Z)",
                "rational", List.of("b-1", "b-2"), List.of("same-v1", "same-v2"),
                "rule-b", List.of())));

        var cluster = report.clusters().getFirst();
        assertEquals(ClusterStatus.DUPLICATE_VALUE_ONLY, cluster.status());
        assertEquals(2, cluster.independentAlphaProfiles());
        assertEquals(1, cluster.independentValueProfiles());
    }

    @Test
    void neutralElementSimplificationNeverBecomesABridge() {
        var report = clusterer.cluster(List.of(
            conjecture("candidate-1", "A + 0", "A",
                "algebra", List.of("a-1", "a-2"), List.of("v-1", "v-2"),
                "add-zero", List.of()),
            conjecture("candidate-2", "X + 0", "X",
                "rational", List.of("b-1", "b-2"), List.of("w-1", "w-2"),
                "different-add-zero", List.of())));

        assertEquals(ClusterStatus.GENERIC_NORMALIZATION,
            report.clusters().getFirst().status());
    }

    @Test
    void rewriteDirectionRemainsPartOfTheStructuralIdentity() {
        var report = clusterer.cluster(List.of(
            conjecture("forward", "A * B + A * C", "A * (B + C)",
                "algebra", List.of("a-1", "a-2"), List.of("v-1", "v-2"),
                "factor", List.of()),
            conjecture("reverse", "X * (Y + Z)", "X * Y + X * Z",
                "algebra", List.of("b-1", "b-2"), List.of("w-1", "w-2"),
                "expand", List.of())));

        assertEquals(2, report.clusters().size());
        assertNotEquals(report.clusters().get(0).structuralSignatureHash(),
            report.clusters().get(1).structuralSignatureHash());
        assertTrue(report.clusters().stream().allMatch(cluster ->
            cluster.status() == ClusterStatus.SINGLE_CANDIDATE));
    }

    @Test
    void malformedSupportIsRejectedAndInputOrderIsDeterministic() {
        OpenTargetConjecture valid = conjecture(
            "valid", "A * B + A * C", "A * (B + C)", "algebra",
            List.of("a-1", "a-2"), List.of("v-1", "v-2"), "rule", List.of());
        ConvergenceEvidence one = evidence(
            "broken-obs", "rational", "x * 2 + x * 3", "x * (2 + 3)",
            "broken-alpha", "broken-value", "broken-rule", List.of());
        OpenTargetConjecture broken = new OpenTargetConjecture(
            "broken",
            "X * Y + X * Z",
            "X * (Y + Z)",
            2,
            2,
            List.of("rational"),
            List.of("broken-obs", "missing-obs"),
            List.of(one),
            List.of(),
            Map.of(),
            "OBSERVED_CONJECTURE",
            "EQUIVALENCE_PRESERVING_CONVERGENT_PATHS");

        var ordered = clusterer.cluster(List.of(valid, broken));
        var reversed = clusterer.cluster(List.of(broken, valid));

        assertEquals(ordered, reversed);
        assertEquals(1, ordered.rejectedCandidates().size());
        assertEquals("support-count-inconsistent",
            ordered.rejectedCandidates().getFirst().reason());
    }

    private static OpenTargetConjecture conjecture(
        String id,
        String leftPattern,
        String rightPattern,
        String family,
        List<String> alphaFingerprints,
        List<String> valueFingerprints,
        String rulePrefix,
        List<String> assumptions
    ) {
        ConvergenceEvidence first = evidence(
            id + "-obs-1", family, leftPattern, rightPattern,
            alphaFingerprints.get(0), valueFingerprints.get(0),
            rulePrefix + "-first", assumptions);
        ConvergenceEvidence second = evidence(
            id + "-obs-2", family, leftPattern, rightPattern,
            alphaFingerprints.get(1), valueFingerprints.get(1),
            rulePrefix + "-second", assumptions);
        return new OpenTargetConjecture(
            id,
            leftPattern,
            rightPattern,
            2,
            (int) alphaFingerprints.stream().distinct().count(),
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
        String valueFingerprint,
        String ruleId,
        List<String> assumptions
    ) {
        PathEvidence direct = new PathEvidence(
            "path-" + observationId + "-direct",
            List.of(input, output),
            List.of(ruleId),
            assumptions,
            1,
            10);
        PathEvidence alternate = new PathEvidence(
            "path-" + observationId + "-alternate",
            List.of(input, "bridge(" + input + ")", output),
            List.of(ruleId + "-prepare", ruleId + "-finish"),
            assumptions,
            2,
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
            valueFingerprint,
            "competition-" + observationId,
            List.of(direct, alternate));
    }
}
