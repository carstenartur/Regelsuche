package de.regelsuche.transform;

import de.regelsuche.ast.BinaryOperator;
import de.regelsuche.retention.RetainedGraph;
import de.regelsuche.retention.RetainedOperation;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AstRewriteTransportInventoryTest {
    private static PatternRewriteRule rule(RecognitionProfile profile) {
        var a = PatternExpr.var("A");
        return new PatternRewriteRule("zero", PatternExpr.op(BinaryOperator.ADD, a, PatternExpr.num(0)), a, profile);
    }
    private static AstRewriteTransport transport(RewriteRule... rules) {
        return new AstRewriteTransport(List.of(rules), 32, 32);
    }
    @Test void exactOwnedRulesAndEmptyTransportsHaveADeclaredInventory() {
        assertTrue(transport().hasBoundedExecutionInventory());
        assertTrue(transport(rule(RecognitionProfile.exact())).hasBoundedExecutionInventory());
    }
    @Test void equivalenceMatchingAndSubclassCallbacksRemainOutsideTheInventory() {
        assertFalse(transport(rule(RecognitionProfile.arithmeticAc())).hasBoundedExecutionInventory());
        assertFalse(transport(rule(RecognitionProfile.exact().withRecognitionRules(java.util.Set.of("r"), 1)))
            .hasBoundedExecutionInventory());
        var poison = new PatternRewriteRule("poison", PatternExpr.var("A"), PatternExpr.var("A")) {
            @Override public RecognitionProfile recognitionProfile() { throw new AssertionError("callback invoked during admission"); }
        };
        assertFalse(transport(poison).hasBoundedExecutionInventory());
    }
    @Test void everyInspectedRuleAndRejectedPrefixRemainPaid() {
        var exact = rule(RecognitionProfile.exact());
        long one = measured(transport(exact));
        assertTrue(one > measured(transport()));
        assertTrue(measured(transport(exact, exact)) > one);
        assertTrue(measured(transport(exact, rule(RecognitionProfile.arithmeticAc()))) > one);
    }
    @Test void atomicFailedDebitPreservesItsPaidPrefix() {
        var sink = new Work();
        var transport = transport(rule(RecognitionProfile.exact()));
        try (var scope = RetainedOperation.open(sink)) {
            sink.limit = 2;
            assertSame(sink.failure, assertThrows(IllegalStateException.class, transport::hasBoundedExecutionInventory));
            assertTrue(sink.work > sink.limit);
            sink.limit = Long.MAX_VALUE;
        }
        assertFalse(RetainedOperation.isObserved());
    }
    private static long measured(AstRewriteTransport transport) {
        var sink = new Work();
        try (var scope = RetainedOperation.open(sink)) { transport.hasBoundedExecutionInventory(); }
        return sink.work;
    }
    private static final class Work implements RetainedOperation.Sink {
        long work, limit = Long.MAX_VALUE;
        final IllegalStateException failure = new IllegalStateException("debit");
        @Override public void executionWork(long units) { work += units; if (work > limit) throw failure; }
        @Override public void validationWork(long units) { executionWork(units); }
        @Override public void checkpoint() {}
        @Override public void retainedReferences(RetainedGraph.Visitor visitor) {}
    }
}
