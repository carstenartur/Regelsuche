package de.regelsuche.search.moves;

import static de.regelsuche.ast.BinaryOperator.ADD;
import static org.junit.jupiter.api.Assertions.*;

import de.regelsuche.ast.*;
import de.regelsuche.retention.*;
import de.regelsuche.search.program.CompiledAstRewriteProgram;
import de.regelsuche.transform.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class NativeVerificationExportOwnershipTest {
    private static final class ExportAbort extends RuntimeException { }
    private static final class CleanupAbort extends RuntimeException { }
    private enum Stop { NONE, RECEIPT, LIST, VERIFICATION }

    private static final class Observation implements RetainedOperation.Sink {
        RetainedOperation scope;
        RetainedJson.Scope json;
        NativeVerification source;
        Stop stop = Stop.NONE;
        boolean repeatPrimary, failCleanup, receiptBeforeList, listBeforeVerification, overlap, rejected, failedOwner;
        ExportAbort failure;
        final CleanupAbort cleanup = new CleanupAbort();
        long work;
        @Override public void retainedReferences(RetainedGraph.Visitor visitor) { visitor.reference(scope); visitor.reference(json); }
        @Override public void validationWork(long units) { executionWork(units); }
        @Override public void executionWork(long units) {
            work += units;
            if (failure != null) {
                if (units == 4 && repeatPrimary) throw failure;
                if (units == 4 && failCleanup) throw cleanup;
                return;
            }
            if (scope == null) return;
            var current = snapshot();
            if ((stop == Stop.RECEIPT && current.receipt()) || (stop == Stop.LIST && current.list())
                    || (stop == Stop.VERIFICATION && current.verification())) {
                failure = new ExportAbort(); throw failure;
            }
        }
        @Override public void checkpoint() {
            RetainedGraph.measure(scope);
            var current = snapshot();
            receiptBeforeList |= current.receipt() && !current.list();
            listBeforeVerification |= current.list() && !current.verification();
            overlap |= current.receipt() && current.list() && current.verification() && current.source();
            rejected |= current.verification() && !current.receipt() && current.source();
            failedOwner |= failure != null && switch (stop) {
                case RECEIPT -> current.receipt(); case LIST -> current.list();
                case VERIFICATION -> current.verification(); case NONE -> false;
            };
        }
        private Snapshot snapshot() {
            var seen = graph(scope);
            boolean receipt = false, list = false, verification = false;
            for (Object value : seen) {
                receipt |= isReceipt(value);
                list |= value instanceof List<?> values && values.stream().anyMatch(NativeVerificationExportOwnershipTest::isReceipt);
                verification |= value instanceof MoveVerifier.Verification;
            }
            return new Snapshot(receipt,list,verification,seen.contains(source));
        }
    }
    private record Snapshot(boolean receipt,boolean list,boolean verification,boolean source) { }
    private static boolean isReceipt(Object value) {
        return value instanceof String text && (text.startsWith("typed-primitive-replay:") || text.startsWith("typed-program-replay:"));
    }
    private static Set<Object> graph(Object root) {
        Set<Object> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        var queue = new ArrayDeque<Object>(); if (root != null) queue.add(root);
        var visitor = new RetainedGraph.Visitor() {
            @Override public void reference(Object value) { if (value != null) queue.add(value); }
            @Override public void requireExact(Object value,Class<?> type) { assertEquals(type,value.getClass()); }
        };
        while (!queue.isEmpty()) {
            Object value = queue.remove(); if (!seen.add(value)) continue;
            if (value instanceof RetainedGraph.View view) view.retainedReferences(visitor);
            else if (value instanceof Object[] values) for (Object item : values) visitor.reference(item);
            else if (value instanceof Collection<?> values) values.forEach(visitor::reference);
            else if (value instanceof Map<?,?> values) values.forEach((key,item) -> { visitor.reference(key); visitor.reference(item); });
        }
        return seen;
    }

    @Test void primitiveReceiptExistsBeforeItsListAndCompleteVerification() { verifySuccessful(false); }
    @Test void programReceiptExistsBeforeItsListAndCompleteVerification() { verifySuccessful(true); }
    private static void verifySuccessful(boolean program) {
        var input = accepted(program); var expected = input.exportLegacy();
        var observation = new Observation(); observation.source = input;
        try (var scope = RetainedOperation.open(observation)) {
            observation.scope = scope;
            try (var json = RetainedJson.open()) {
                observation.json = json;
                var result = input.exportLegacy();
                assertEquals(expected,result); assertEquals(17,result.work());
            }
        }
        assertTrue(observation.receiptBeforeList,"the completed receipt String is owned before List.of can fail");
        assertTrue(observation.listBeforeVerification,"the real receipt list is owned before constructing the verification");
        assertTrue(observation.overlap,"source, receipt, list and final verification overlap at handoff");
        assertReleased(observation);
    }
    @Test void rejectedVerificationHasAnOwnedResultWithoutFabricatedReceipts() {
        var input = new NativeVerification(false,37,null,null,"EXPECTED_REJECTION");
        var observation = new Observation(); observation.source = input;
        try (var scope = RetainedOperation.open(observation)) {
            observation.scope = scope;
            assertEquals(new MoveVerifier.Verification(false,37,List.of(),"EXPECTED_REJECTION"),input.exportLegacy());
        }
        assertTrue(observation.rejected); assertFalse(observation.receiptBeforeList);
        assertReleased(observation);
    }
    @ParameterizedTest @CsvSource({"RECEIPT,false","RECEIPT,true","LIST,false","LIST,true","VERIFICATION,false","VERIFICATION,true"})
    void everyFailedHandoffObservesCompletedOutputAndPreservesThePrimaryError(Stop stop,boolean sameFailure) {
        var input = accepted(false); var observation = new Observation(); observation.source = input;
        observation.stop = stop; observation.repeatPrimary = sameFailure; observation.failCleanup = !sameFailure;
        try (var scope = RetainedOperation.open(observation)) {
            observation.scope = scope;
            try (var json = RetainedJson.open()) {
                observation.json = json;
                var failure = assertThrows(ExportAbort.class,input::exportLegacy);
                assertSame(observation.failure,failure); assertTrue(observation.failedOwner);
                if (!sameFailure) assertTrue(Arrays.asList(failure.getSuppressed()).contains(observation.cleanup));
                observation.repeatPrimary = false; observation.failCleanup = false;
                assertTrue(input.exportLegacy().accepted(),"all failed frames must have been released");
            }
        }
        assertReleased(observation);
    }
    private static NativeVerification accepted(boolean program) {
        var a = PatternExpr.var("A");
        var transport = new AstRewriteTransport(List.of(new PatternRewriteRule("remove-zero",PatternExpr.op(ADD,a,PatternExpr.num(0)),a)),32,32);
        var source = new BinaryExpr(new VariableExpr("x"),ADD,new NumberExpr(0));
        var step = transport.generate(source).getFirst();
        assertEquals(new VariableExpr("x"),transport.replay(source,List.of(step)));
        NativeMoveProof proof = program
            ? new NativeMoveProof.Program(new CompiledAstRewriteProgram.Candidate("program",List.of("source"),List.of(step)))
            : new NativeMoveProof.Primitive(step);
        return new NativeVerification(true,17,proof,"remove-zero","REPLAYED");
    }
    private static void assertReleased(Observation observation) {
        var retained = RetainedGraph.measure(observation.scope).retained();
        assertEquals(0,retained.nodes()); assertEquals(0,retained.characters());
    }
}
