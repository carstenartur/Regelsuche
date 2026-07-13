package de.regelsuche.search.learning;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.canonical.ExpressionCanonicalizer;
import de.regelsuche.search.strategy.SearchProblem.SearchTarget;
import de.regelsuche.search.telemetry.SearchEvent;
import de.regelsuche.search.telemetry.SearchEventType;
import de.regelsuche.transform.RewriteKind;
import java.util.List;
import org.junit.jupiter.api.Test;

class TransformationDescriptorTest {
    private final ExpressionCanonicalizer canonicalizer = new ExpressionCanonicalizer();

    @Test
    void capturesOnlyPreselectionTransferableFeatures() {
        SearchEvent event = decision(
            "hidden-rule-id",
            "x + x",
            "2 * x",
            List.of("x != 0"));

        TransformationDescriptor descriptor;
        try (TransformationDescriptor.Factory factory = new TransformationDescriptor.Factory(
                SearchTarget.syntaxExact("2 * x"), canonicalizer)) {
            descriptor = factory.from(event);
        }

        assertTrue(descriptor.available());
        assertEquals(RewriteKind.NORMALIZE, descriptor.rewriteKind());
        assertTrue(descriptor.equivalencePreserving());
        assertFalse(descriptor.mayIncreaseComplexity());
        assertEquals(-1, descriptor.estimatedCostDelta());
        assertEquals(1, descriptor.assumptionCount());
        assertEquals(
            1,
            descriptor.assumptionClassCounts().get(
                TransformationDescriptor.AssumptionClass.NON_ZERO));
        assertEquals(TransformationDescriptor.RootKind.ADD, descriptor.parentRoot().kind());
        assertEquals(TransformationDescriptor.RootKind.MUL, descriptor.childRoot().kind());
        assertTrue(descriptor.targeted());
        assertEquals(0, descriptor.targetDistanceAfter());
        assertTrue(descriptor.featureVector().containsKey("ast.nodeCountDelta"));
        assertTrue(descriptor.featureVector().containsKey("root.transition.ADD_TO_MUL"));
        assertFalse(descriptor.predictiveMaterial().contains("hidden-rule-id"));
        assertFalse(descriptor.predictiveMaterial().contains("x != 0"));
    }

    @Test
    void ruleIdsVariableNamesAndAssumptionValuesDoNotChangePredictiveIdentity() {
        TransformationDescriptor first;
        TransformationDescriptor second;
        try (TransformationDescriptor.Factory factory =
                new TransformationDescriptor.Factory(null, canonicalizer)) {
            first = factory.from(decision(
                "train-neutral-a", "a + a", "2 * a", List.of("a != 0")));
            second = factory.from(decision(
                "held-out-unseen-b", "b + b", "2 * b", List.of("b != 0")));
        }

        assertEquals(first.featureVector(), second.featureVector());
        assertEquals(first.predictiveFingerprint(), second.predictiveFingerprint());
    }

    @Test
    void unparseableCandidateProducesAnExplicitUnavailableDescriptor() {
        TransformationDescriptor descriptor;
        try (TransformationDescriptor.Factory factory = new TransformationDescriptor.Factory(
                SearchTarget.syntaxExact("y"), canonicalizer)) {
            descriptor = factory.from(decision("broken", "x", "@", List.of()));
        }

        assertFalse(descriptor.available());
        assertFalse(descriptor.astDelta().parseable());
        assertEquals(
            TransformationDescriptor.RootKind.UNPARSEABLE,
            descriptor.childRoot().kind());
    }

    private static SearchEvent decision(
        String ruleId,
        String parent,
        String child,
        List<String> assumptions
    ) {
        return new SearchEvent(
            0L,
            SearchEventType.TRANSFORMATION_GENERATED,
            child,
            "child-hash",
            1,
            10,
            "parent-hash",
            parent,
            ruleId,
            RewriteKind.NORMALIZE,
            false,
            -1,
            true,
            assumptions,
            0,
            0,
            0,
            "");
    }
}
