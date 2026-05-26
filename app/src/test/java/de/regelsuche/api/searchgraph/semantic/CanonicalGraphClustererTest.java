package de.regelsuche.api.searchgraph.semantic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.assumption.AssumptionContext;
import java.util.List;
import org.junit.jupiter.api.Test;

class CanonicalGraphClustererTest {

    private final CanonicalGraphClusterer clusterer = new CanonicalGraphClusterer();

    @Test
    void collapsesCommutativeVariants() {
        List<CanonicalExpressionCluster> clusters = clusterer.cluster(List.of("a+b", "b+a"), new AssumptionContext());
        assertEquals(1, clusters.size());
        assertEquals(2, clusters.getFirst().variants().size());
    }

    @Test
    void collapsesAssociativeVariants() {
        List<CanonicalExpressionCluster> clusters = clusterer.cluster(List.of("(x+y)+z", "x+(y+z)"), new AssumptionContext());
        assertEquals(1, clusters.size());
    }

    @Test
    void collapsesPowerAndMultiplyEquivalentVariants() {
        List<CanonicalExpressionCluster> clusters = clusterer.cluster(List.of("x*x", "x^2"), new AssumptionContext());
        assertEquals(1, clusters.size());
    }

    @Test
    void doesNotAssumptionFreeCollapseDivisionToOne() {
        List<CanonicalExpressionCluster> clusters = clusterer.cluster(List.of("x/x", "1"), new AssumptionContext());
        assertEquals(2, clusters.size());
    }

    @Test
    void representativeSelectionIsDeterministic() {
        var evidence = List.of(
            new CanonicalGraphClusterer.ExpressionEvidence("b+a", 1, 7),
            new CanonicalGraphClusterer.ExpressionEvidence("a+b", 2, 7)
        );
        List<CanonicalExpressionCluster> first = clusterer.clusterWithEvidence(evidence, new AssumptionContext());
        List<CanonicalExpressionCluster> second = clusterer.clusterWithEvidence(evidence, new AssumptionContext());
        assertEquals(first.getFirst().representativeExpression(), second.getFirst().representativeExpression());
        assertTrue(List.of("a+b", "b+a").contains(first.getFirst().representativeExpression()));
    }

    @Test
    void assumptionFingerprintChangesClusterHash() {
        AssumptionContext withAssumption = new AssumptionContext();
        withAssumption.add(de.regelsuche.assumption.Assumption.nonZero("x"));
        String without = clusterer.cluster(List.of("x/x"), new AssumptionContext()).getFirst().canonicalHash();
        String with = clusterer.cluster(List.of("x/x"), withAssumption).getFirst().canonicalHash();
        assertNotEquals(without, with);
    }
}
