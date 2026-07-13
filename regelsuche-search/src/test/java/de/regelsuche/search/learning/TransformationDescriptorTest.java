package de.regelsuche.search.learning;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.canonical.ExpressionCanonicalizer;
import de.regelsuche.search.learning.TransformationDescriptor.LocalStatus;
import de.regelsuche.search.learning.TransformationDescriptor.OccurrenceRole;
import de.regelsuche.search.learning.TransformationDescriptor.RootKind;
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

        TransformationDescriptor descriptor = descriptor(
            event, SearchTarget.syntaxExact("2 * x"));

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
        assertEquals(RootKind.ADD, descriptor.parentRoot().kind());
        assertEquals(RootKind.MUL, descriptor.childRoot().kind());
        assertTrue(descriptor.targeted());
        assertEquals(0, descriptor.targetDistanceAfter());
        assertTrue(descriptor.featureVector().containsKey("ast.nodeCountDelta"));
        assertTrue(descriptor.featureVector().containsKey("root.transition.ADD_TO_MUL"));
        assertEquals(LocalStatus.AVAILABLE, descriptor.localChange().status());
        assertEquals(OccurrenceRole.ROOT, descriptor.localChange().role());
        assertEquals(0, descriptor.localChange().depth());
        assertFalse(descriptor.predictiveMaterial().contains("hidden-rule-id"));
        assertFalse(descriptor.predictiveMaterial().contains("x != 0"));
    }

    @Test
    void isolatesNestedAcChildWithoutInventingLeftRightOrder() {
        TransformationDescriptor descriptor = descriptor(decision(
            "product-square", "(x * x) * y", "x ^ 2 * y", List.of()), null);

        assertEquals(LocalStatus.AVAILABLE, descriptor.localChange().status());
        assertEquals(1, descriptor.localChange().depth());
        assertEquals(OccurrenceRole.AC_CHILD, descriptor.localChange().role());
        assertEquals(RootKind.MUL, descriptor.localChange().contextRoot().kind());
        assertEquals(RootKind.MUL, descriptor.localChange().beforeRoot().kind());
        assertEquals(RootKind.POW, descriptor.localChange().afterRoot().kind());
        assertEquals(1, descriptor.featureVector().get("local.role.AC_CHILD"));
        assertEquals(1, descriptor.featureVector().get("local.depth"));
    }

    @Test
    void isolatesOrderedRightChild() {
        TransformationDescriptor descriptor = descriptor(decision(
            "right-simplify", "x / (y + 0)", "x / y", List.of()), null);

        assertEquals(LocalStatus.AVAILABLE, descriptor.localChange().status());
        assertEquals(1, descriptor.localChange().depth());
        assertEquals(OccurrenceRole.RIGHT, descriptor.localChange().role());
        assertEquals(RootKind.DIV, descriptor.localChange().contextRoot().kind());
        assertEquals(RootKind.ADD, descriptor.localChange().beforeRoot().kind());
        assertEquals(RootKind.VARIABLE, descriptor.localChange().afterRoot().kind());
    }

    @Test
    void isolatesFunctionArgumentAndIndex() {
        TransformationDescriptor descriptor = descriptor(decision(
            "argument-simplify", "sin(x + 0)", "sin(x)", List.of()), null);

        assertEquals(LocalStatus.AVAILABLE, descriptor.localChange().status());
        assertEquals(1, descriptor.localChange().depth());
        assertEquals(OccurrenceRole.ARGUMENT, descriptor.localChange().role());
        assertEquals(0, descriptor.localChange().argumentIndex());
        assertEquals(RootKind.FUNCTION, descriptor.localChange().contextRoot().kind());
    }

    @Test
    void ambiguousMultiSiteChangeCarriesNoGuessedPosition() {
        TransformationDescriptor descriptor = descriptor(decision(
            "swap", "x + y", "y + x", List.of()), null);

        assertEquals(LocalStatus.AMBIGUOUS, descriptor.localChange().status());
        assertFalse(descriptor.localChange().available());
        assertEquals(-1, descriptor.localChange().depth());
        assertEquals(OccurrenceRole.UNAVAILABLE, descriptor.localChange().role());
        assertEquals(0, descriptor.featureVector().get("local.available"));
        assertEquals(1, descriptor.featureVector().get("local.status.AMBIGUOUS"));
        assertFalse(descriptor.featureVector().containsKey("local.depth"));
    }

    @Test
    void ruleIdsVariableNamesAndAssumptionValuesDoNotChangePredictiveIdentity() {
        TransformationDescriptor first;
        TransformationDescriptor second;
        try (TransformationDescriptor.Factory factory =
                new TransformationDescriptor.Factory(null, canonicalizer)) {
            first = factory.from(decision(
                "train-neutral-a", "f(a + 0)", "f(a)", List.of("a != 0")));
            second = factory.from(decision(
                "held-out-unseen-b", "f(b + 0)", "f(b)", List.of("b != 0")));
        }

        assertEquals(first.featureVector(), second.featureVector());
        assertEquals(first.predictiveFingerprint(), second.predictiveFingerprint());
        assertEquals(OccurrenceRole.ARGUMENT, first.localChange().role());
    }

    @Test
    void unparseableCandidateProducesExplicitUnavailableDescriptors() {
        TransformationDescriptor descriptor = descriptor(
            decision("broken", "x", "@", List.of()),
            SearchTarget.syntaxExact("y"));

        assertFalse(descriptor.available());
        assertFalse(descriptor.astDelta().parseable());
        assertEquals(RootKind.UNPARSEABLE, descriptor.childRoot().kind());
        assertEquals(LocalStatus.UNPARSEABLE, descriptor.localChange().status());
        assertFalse(descriptor.localChange().available());
    }

    private TransformationDescriptor descriptor(SearchEvent event, SearchTarget target) {
        try (TransformationDescriptor.Factory factory =
                new TransformationDescriptor.Factory(target, canonicalizer)) {
            return factory.from(event);
        }
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
