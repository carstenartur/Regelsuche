package de.regelsuche.mining;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.mining.CrossFamilyStructuralClusterer.BridgeCluster;
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
    void clustersOneDirectionalStructureAcrossTwoPostHocFamilies() {
        OpenTargetConjecture algebra = conjecture(
            "algebra-factor",
            "A * B + A * C",
            "A * (B + C)",
            evidence(
                "algebra-observation",
                "algebra",
                "x * 2 + x * 3",
                "x * (2 + 3)",
                "alpha-algebra",
                "value-algebra",
                List.of("ast_factor_common_left")));
        OpenTargetConjecture rational = conjecture(
            "rational-factor",
            "X * Y + X * Z",
            "X * (Y + Z)",
            evidence(
                "rational-observation",
                "rational",
                "(p / q) * 2 + (p / q) * 3",
                "(p / q) * (2 + 3)",
                "alpha-rational",
                "value-rational",
                List.of("factor-via-common-numerator", "normalize-rational")));

        var report = clusterer.cluster(List.of(rational, algebra));

        assertEquals(CrossFamilyStructuralClusterer.SCHEMA, report.schema());
        assertEquals(1, report.clusters().size());
        assertTrue(report.rejections().isEmpty());
        BridgeCluster cluster = report.clusters().getFirst();
        assertEquals(List.of("algebra", "rational"), cluster.families());
        assertEquals(List.of("algebra-factor", "rational-factor"), cluster.conjectureIds());
        assertEquals(
            List.of("algebra-observation", "rational-observation"),
            cluster.observationIds());
        assertEquals(2, cluster.distinctAlphaEvidence());
        assertEquals(2, cluster.distinctValueEvidence());
        assertEquals("EQUIVALENCE_PRESERVING_CONVERGENCE", cluster.graphRole());
        assertEquals("OBSERVED_CROSS_FAMILY_BRIDGE", cluster.bridgeStatus());
        assertEquals("NOT_EVALUATED", cluster.validationStatus());
        assertTrue(cluster.clusterId().startsWith("bridge-"));
        assertTrue(cluster.alphaRelationHash().startsWith("sha256:"));
        assertTrue(report.contentHash().startsWith("sha256:"));
        assertEquals(report.toCanonicalJson(), report.toCanonicalJson());
        assertFalse(report.toCanonicalJson().contains("ast_factor_common_left"));
        assertFalse(report.toCanonicalJson().contains("factor-via-common-numerator"));
    }

    @Test
    void rejectsSingleFamilySupportEvenWhenRuleIdsDiffer() {
        OpenTargetConjecture first = conjecture(
            "same-family-1",
            "A * B + A * C",
            "A * (B + C)",
            evidence("obs-1", "algebra", "x * 2 + x * 3", "x * 5",
                "alpha-1", "value-1", List.of("rule-a")));
        OpenTargetConjecture second = conjecture(
            "same-family-2",
            "X * Y + X * Z",
            "X * (Y + Z)",
            evidence("obs-2", "algebra", "y * 4 + y * 5", "y * 9",
                "alpha-2", "value-2", List.of("rule-b")));

        var report = clusterer.cluster(List.of(first, second));

        assertTrue(report.clusters().isEmpty());
        assertEquals(1, report.rejections().size());
        assertEquals("SINGLE_FAMILY_SUPPORT", report.rejections().getFirst().reason());
    }

    @Test
    void rejectsCrossFamilyEvidenceReducibleToAlphaRenaming() {
        OpenTargetConjecture first = conjecture(
            "renaming-1",
            "A * B + A * C",
            "A * (B + C)",
            evidence("obs-1", "algebra", "x * 2 + x * 3", "x * 5",
                "same-alpha", "value-1", List.of("rule-a")));
        OpenTargetConjecture second = conjecture(
            "renaming-2",
            "X * Y + X * Z",
            "X * (Y + Z)",
            evidence("obs-2", "rational", "y * 2 + y * 3", "y * 5",
                "same-alpha", "value-2", List.of("rule-b")));

        var report = clusterer.cluster(List.of(first, second));

        assertTrue(report.clusters().isEmpty());
        assertEquals("RENAMING_ONLY_SUPPORT", report.rejections().getFirst().reason());
    }

    @Test
    void preservesRewriteDirectionAndPlaceholderRelations() {
        OpenTargetConjecture forward = conjecture(
            "forward",
            "A * B + A * C",
            "A * (B + C)",
            evidence("obs-forward", "algebra", "x * 2 + x * 3", "x * 5",
                "alpha-forward", "value-forward", List.of("forward-rule")));
        OpenTargetConjecture reverse = conjecture(
            "reverse",
            "X * (Y + Z)",
            "X * Y + X * Z",
            evidence("obs-reverse", "rational", "q * 5", "q * 2 + q * 3",
                "alpha-reverse", "value-reverse", List.of("reverse-rule")));
        OpenTargetConjecture differentBindings = conjecture(
            "bindings",
            "W * X + Y * Z",
            "W * (X + Z)",
            evidence("obs-bindings", "matrix", "a*b+c*d", "a*(b+d)",
                "alpha-bindings", "value-bindings", List.of("binding-rule")));

        var report = clusterer.cluster(List.of(forward, reverse, differentBindings));

        assertTrue(report.clusters().isEmpty());
        assertEquals(3, report.rejections().size());
        assertTrue(report.rejections().stream()
            .allMatch(rejection -> rejection.reason().equals("INSUFFICIENT_CONJECTURES")));
    }

    @Test
    void reportIsDeterministicAcrossConjectureAndEvidenceOrder() {
        OpenTargetConjecture algebra = conjecture(
            "algebra-factor",
            "A * B + A * C",
            "A * (B + C)",
            evidence("obs-a", "algebra", "x*2+x*3", "x*5",
                "alpha-a", "value-a", List.of("rule-a")));
        OpenTargetConjecture rational = conjecture(
            "rational-factor",
            "X * Y + X * Z",
            "X * (Y + Z)",
            evidence("obs-r", "rational", "p/q*2+p/q*3", "p/q*5",
                "alpha-r", "value-r", List.of("rule-r")));

        var ordered = clusterer.cluster(List.of(algebra, rational));
        var reversed = clusterer.cluster(List.of(rational, algebra));

        assertEquals(ordered, reversed);
        assertEquals(ordered.toCanonicalJson(), reversed.toCanonicalJson());
    }

    private static OpenTargetConjecture conjecture(
        String id,
        String left,
        String right,
        ConvergenceEvidence evidence
    ) {
        return new OpenTargetConjecture(
            id,
            left,
            right,
            2,
            2,
            List.of(),
            List.of(evidence.observationId(), evidence.observationId() + "-support"),
            List.of(evidence),
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
        List<String> ruleIds
    ) {
        PathEvidence direct = new PathEvidence(
            observationId + "-direct",
            List.of(input, output),
            ruleIds,
            List.of(),
            1,
            1);
        PathEvidence alternate = new PathEvidence(
            observationId + "-alternate",
            List.of(input, input + " + 0", output),
            List.of("padding-neutral", "remove-neutral"),
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
            "independent-paths",
            List.of(direct, alternate));
    }
}
