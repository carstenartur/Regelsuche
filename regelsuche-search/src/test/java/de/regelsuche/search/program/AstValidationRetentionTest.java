package de.regelsuche.search.program;

import static org.junit.jupiter.api.Assertions.*;
import de.regelsuche.ast.*;
import de.regelsuche.retention.RetainedGraph;
import de.regelsuche.retention.RetainedOperation;
import de.regelsuche.scalar.ExactRational;
import de.regelsuche.symbol.SymbolId;
import de.regelsuche.transform.*;
import java.math.BigInteger;
import java.util.*;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

class AstValidationRetentionTest {
    @Test void numericRenderingIsObservedAndReleasedWithoutJsonTransport() {
        var number = largeNumber();
        observe(number, sink -> {
            var inspection = AstExpressionValidation.inspect(number);
            assertEquals(inspection.work(), sink.validation);
            assertTrue(sink.peak.characters() >= sink.initial.characters() + number.value().canonicalText().length());
            sink.assertReleased();
        });
    }

    @Test void scopedSymbolRenderingIsObservedAndReleased() {
        var symbol = new SymbolId(new UUID(12, 34), 56);
        var expression = VariableExpr.scoped(symbol);
        observe(expression, sink -> {
            AstExpressionValidation.inspect(expression);
            assertTrue(sink.peak.characters() >= sink.initial.characters() + symbol.canonicalText().length());
            sink.assertReleased();
        });
    }

    @Test void scalarRenderingPaysForItsCompletedCharactersSeparatelyFromValidation() {
        long[] execution = new long[2];
        var small = NumberExpr.exact("1/7");
        var large = largeNumber();
        observe(small, sink -> {
            AstExpressionValidation.inspect(small);
            execution[0] = sink.execution;
        });
        observe(large, sink -> {
            var inspection = AstExpressionValidation.inspect(large);
            assertEquals(inspection.work(), sink.validation);
            execution[1] = sink.execution;
        });
        assertTrue(execution[1] - execution[0] >= large.value().canonicalText().length() - small.value().canonicalText().length(),
            "completed formatting must not be hidden in the later text-validation debit");
    }

    @Test void completedScalarObservationIsNotRepeatedWithoutOwnershipGrowth() {
        var number = largeNumber();
        observe(number, sink -> {
            AstExpressionValidation.inspect(number);
            assertEquals(2, sink.checkpoints, "acquisition and completed scalar suffice for this unchanged graph");
            sink.assertReleased();
        });
    }

    @Test void completedRenderingIsReleasedBeforeVisitingTheNextNode() {
        var source = new FunctionExpr("pair", List.of(largeNumber(), new VariableExpr("end")));
        observe(source, sink -> {
            sink.checkThirdNodeRenderingReleased = true;
            AstExpressionValidation.inspect(source);
            assertEquals(3, sink.nodeDebits);
            sink.assertReleased();
        });
    }

    @Test void intermediateHistoryRenderingIsObserved() {
        var source = new VariableExpr("x");
        var intermediate = largeNumber();
        var history = new CompiledAstRewriteProgram.Candidate("program", List.of("first", "last"),
            List.of(step(source, intermediate), step(intermediate, source)));
        observe(history, sink -> {
            var inspection = AstExpressionValidation.inspectHistory(history);
            assertEquals(inspection.work(), sink.validation);
            assertTrue(sink.peak.characters() >= sink.initial.characters() + intermediate.value().canonicalText().length());
            sink.assertReleased();
        });
    }

    @Test void failedValidationDebitStillObservesRenderingAndPreservesPrimaryFailure() {
        var number = largeNumber();
        observe(number, sink -> {
            sink.rejectValidationAbove = 512;
            assertSame(sink.failure, assertThrows(ValidationAbort.class, () -> AstExpressionValidation.inspect(number)));
            assertTrue(sink.validation > 512, "already attempted validation is not refunded");
            assertTrue(sink.peak.characters() >= sink.initial.characters() + number.value().canonicalText().length(),
                "a failing debit must not hide its completed allocation");
            sink.assertReleased();
        });
    }

    @Test void traversalScratchIsObservedWithoutExpandingSharedInputOwnership() {
        int width = 500;
        var expression = new FunctionExpr("f", Collections.nCopies(width, new VariableExpr("x")));
        observe(expression, sink -> {
            var inspection = AstExpressionValidation.inspect(expression);
            assertEquals(width + 1, inspection.nodes(), "validation counts occurrences, not unique nodes");
            assertEquals(2, sink.peak.nodes(), "temporary traversal must share the actual expressions");
            assertTrue(sink.peak.references() >= sink.initial.references() + width * 2L,
                "queued expression references and queue slots must be visible");
            assertTrue(sink.checkpoints < width / 2, "do not rescan the full ownership graph per child");
            sink.assertReleased();
        });
    }

    @Test void failedFrameCloseReleasesScratchAndPreservesItsFailure() {
        var number = largeNumber();
        observe(number, sink -> {
            sink.rejectClose = true;
            assertSame(sink.failure, assertThrows(ValidationAbort.class, () -> AstExpressionValidation.inspect(number)));
            assertTrue(sink.peak.characters() > sink.initial.characters());
            sink.assertReleased();
        });
    }

    @Test void failedScopeAcquisitionStillPaysForCounterRelease() {
        var source = new VariableExpr("x");
        observe(source, sink -> {
            sink.rejectAcquisition = true;
            assertSame(sink.failure, assertThrows(ValidationAbort.class, () -> AstExpressionValidation.inspect(source)));
            assertEquals(0, sink.validation);
            assertTrue(sink.execution >= 11, "opening, completed allocation, frame cleanup and counter release stay paid");
            sink.assertReleased();
        });
    }

    @Test void queuedOccurrencesCannotMultiplyTheFiniteNodeLimit() {
        Expr expression = new VariableExpr("x");
        for (int i = 0; i < 4; i++) {
            var arguments = new ArrayList<Expr>(Collections.nCopies(4000, new VariableExpr("x")));
            arguments.set(0, expression);
            expression = new FunctionExpr("f", arguments);
        }
        final Expr source = expression;
        observe(source, sink -> {
            assertThrows(AstExpressionValidation.InvalidExpression.class, () -> AstExpressionValidation.inspect(source));
            assertTrue(sink.peak.references() <= sink.initial.references() + 2L * AstRewriteTransport.MAXIMUM_NODES + 32,
                "nested wide functions must not multiply the node limit into a much larger scratch arena");
            sink.assertReleased();
        });
    }

    @Test void structuralAndUnicodeRejectionsRetainTheOriginalLimitsAndPaidWork() {
        Expr tooDeep = new VariableExpr("x");
        for (int i = 0; i <= AstRewriteTransport.MAXIMUM_DEPTH; i++)
            tooDeep = new FunctionExpr("f", List.of(tooDeep));
        var tooWide = new FunctionExpr("f", Collections.nCopies(AstRewriteTransport.MAXIMUM_NODES, new VariableExpr("x")));
        for (var expression : List.of(tooDeep, tooWide, new VariableExpr("bad\ud800"))) {
            observe(expression, sink -> {
                assertThrows(AstExpressionValidation.InvalidExpression.class, () -> AstExpressionValidation.inspect(expression));
                assertTrue(sink.validation > 0);
                sink.assertReleased();
            });
        }
    }

    private static NumberExpr largeNumber() {
        return new NumberExpr(new ExactRational(BigInteger.TEN.pow(1000).add(BigInteger.ONE), BigInteger.valueOf(7)));
    }

    private static AstRewriteTransport.Step step(Expr source, Expr target) {
        return new AstRewriteTransport.Step(source, target, "rule", RewriteKind.SIMPLIFY,
            false, 0, true, List.of(), "pack", "license");
    }

    private static void observe(Object input, Consumer<ValidationSink> assertions) {
        var sink = new ValidationSink(input);
        try (var transport = AstTransportObservation.open(); var operation = RetainedOperation.open(sink)) {
            sink.operation = operation;
            sink.initial = RetainedGraph.measure(sink).retained();
            try {
                assertions.accept(sink);
                assertEquals(0, transport.total(), "validation must not serialize or decode AST JSON");
            } finally { sink.failed = false; }
        } finally { sink.operation = null; }
    }

    private static final class ValidationAbort extends RuntimeException {}

    private static final class ValidationSink implements RetainedOperation.Sink {
        final Object input;
        final ValidationAbort failure = new ValidationAbort();
        RetainedOperation operation;
        RetainedGraph.Usage initial, peak = new RetainedGraph.Usage(0, 0, 0);
        long validation, execution, rejectValidationAbove = Long.MAX_VALUE;
        int checkpoints, nodeDebits;
        boolean failed, rejectClose, rejectAcquisition, checkThirdNodeRenderingReleased;

        ValidationSink(Object input) { this.input = input; }
        @Override public void validationWork(long units) {
            validation = Math.addExact(validation, units);
            if (checkThirdNodeRenderingReleased && units == 1 && ++nodeDebits == 3)
                assertEquals(initial.characters(), RetainedGraph.measure(this).retained().characters(),
                    "completed scalar text cannot overlap the next scalar allocation");
            if (units > rejectValidationAbove) { failed = true; throw failure; }
        }
        @Override public void executionWork(long units) {
            execution = Math.addExact(execution, units);
            if (rejectAcquisition && units == 2 && checkpoints == 0) { failed = true; throw failure; }
            if (rejectClose && units == 4 && checkpoints > 0) { failed = true; throw failure; }
            if (failed) throw failure;
        }
        @Override public void checkpoint() {
            peak = peak.maximum(RetainedGraph.measure(this).retained());
            checkpoints++;
            if (failed) throw failure;
        }
        @Override public void retainedReferences(RetainedGraph.Visitor visitor) {
            visitor.reference(input); visitor.reference(operation);
        }
        void assertReleased() {
            assertEquals(initial, RetainedGraph.measure(this).retained(), "validation scratch cannot outlive the call");
        }
    }
}
