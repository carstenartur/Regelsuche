package de.regelsuche.transform;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import org.junit.jupiter.api.Test;

class TransformationProvenanceTest {
    @Test
    void ordinaryConstructorsAndExpandableMacrosKeepRealPrimitiveCounts() {
        var primitive = new Transformation("rule", "x");
        assertEquals("rule:x", primitive.applicationKey());
        assertEquals(List.of("rule"), primitive.primitiveRuleIds());
        assertEquals(new ExecutionWork(1, 0, 0), primitive.executionWork());
        assertInstanceOf(TransformationProvenance.PrimitiveRewriteSequence.class, primitive.provenance());
        var macro = new Transformation("macro", "y", RewriteKind.NORMALIZE, false, 0, true,
            "macro@x", List.of(), "core", "PROJECT", List.of("a", "b", "a"));
        assertEquals(3, macro.primitiveStepCount());
        assertEquals(List.of("a", "b", "a"), macro.provenance().primitiveRuleIds());
        assertEquals(0L, macro.exactTheoryStepCount());
        assertEquals(3L, macro.executionWork().canonicalWorkUnits());
    }

    @Test
    void primitiveIdentityBindsOrderedMultiplicityAndApplicationIdentity() {
        var first = new TransformationProvenance.PrimitiveRewriteSequence(List.of("a", "b"), "apply");
        assertNotEquals(first.contentHash(), new TransformationProvenance.PrimitiveRewriteSequence(
            List.of("b", "a"), "apply").contentHash());
        assertNotEquals(first.contentHash(), new TransformationProvenance.PrimitiveRewriteSequence(
            List.of("a", "b", "b"), "apply").contentHash());
        assertNotEquals(first.contentHash(), new TransformationProvenance.PrimitiveRewriteSequence(
            List.of("a", "b"), "other").contentHash());
        assertThrows(IllegalArgumentException.class,
            () -> new TransformationProvenance.PrimitiveRewriteSequence(List.of(), "empty"));
        assertThrows(IllegalArgumentException.class,
            () -> new TransformationProvenance.PrimitiveRewriteSequence(List.of("a"), "\ud800").contentHash());
    }

    @Test
    void mathematicalWorkNeverWrapsOrSaturatesIntoAnAffordablePath() {
        var largestTheory = new ExecutionWork(0, 1, Long.MAX_VALUE);
        assertThrows(ArithmeticException.class, () -> largestTheory.plus(new ExecutionWork(1, 0, 0)));
        assertThrows(ArithmeticException.class, () -> largestTheory.plus(new ExecutionWork(0, 1, 1)));
        assertThrows(IllegalArgumentException.class, () -> new ExecutionWork(0, 1, 0));
        assertThrows(IllegalArgumentException.class, () -> new ExecutionWork(0, 0, 1));
        var metrics = TransformationWorkMetrics.flatEngine(1).withCandidateWork(largestTheory);
        assertThrows(ArithmeticException.class, metrics::totalWorkUnitsV2);
        assertEquals(3L, metrics.totalWorkUnits());
    }

    @Test
    void workAggregationAndDedupCountersPreserveMathematicalDimensions() {
        var mathematical = new ExecutionWork(3, 2, 17);
        var metrics = TransformationWorkMetrics.flatEngine(2).withCandidateWork(mathematical);
        var combined = metrics.plus(metrics).withDuplicateCandidatesDropped(1);
        assertEquals(new ExecutionWork(6, 4, 34), combined.candidateWork());
        assertEquals(combined.totalWorkUnits() + 40, combined.totalWorkUnitsV2());
        assertThrows(IllegalArgumentException.class, () -> ExactTheoryEvidence.fromVerified("invented evidence"));
    }
}
