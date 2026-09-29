package de.regelsuche.search.program;

import static org.junit.jupiter.api.Assertions.*;
import de.regelsuche.ast.*;
import de.regelsuche.symbol.*;
import de.regelsuche.retention.*;
import java.util.function.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;

class AstExpressionValidationTest {
    @Test void canonicalSizeCountsEveryTagSeparatorEscapeAndUtf8ByteWithoutCreatingJson() {
        var scope=new SymbolScope(new UUID(0,12));
        for(var expression:List.<Expr>of(new NumberExpr(1),NumberExpr.exact("-7/13"),new VariableExpr("x"),
                VariableExpr.scoped(scope.declare("x")),new FunctionExpr("f",List.of()),
                new FunctionExpr("f",List.of(new VariableExpr("é\n\"\\\u0001😀"),new NumberExpr(0))),
                new BinaryExpr(new VariableExpr("a"),BinaryOperator.ADD,new NumberExpr(0)))) {
            long bytes=new CompiledAstReplayCodec().encodeExpression(expression).getBytes(StandardCharsets.UTF_8).length;
            assertEquals(bytes,AstExpressionValidation.inspect(expression).canonicalBytes(),expression.toString());
            assertEquals(new CompiledAstReplayCodec().encodeExpression(expression).length(),AstExpressionValidation.inspect(expression).canonicalCharacters());
        }
    }
    private static final class Observation implements RetainedOperation.Sink {
        RetainedOperation scope;
        long execution, validation;
        Consumer<List<Object>> check = values -> {};
        LongConsumer charge = units -> {};
        LongConsumer validate = units -> {};
        List<Object> references() {
            var seen = new IdentityHashMap<Object, Boolean>();
            var pending = new ArrayDeque<Object>();
            pending.add(scope);
            while (!pending.isEmpty()) {
                var value = pending.remove();
                if (seen.put(value, Boolean.TRUE) != null) continue;
                if (value instanceof RetainedGraph.View view) view.retainedReferences(new RetainedGraph.Visitor() {
                    @Override public void reference(Object child) { if (child != null) pending.add(child); }
                    @Override public void requireExact(Object child, Class<?> type) { assertEquals(type, child.getClass()); }
                });
                else if (value instanceof Object[] array) for (var child : array) if (child != null) pending.add(child);
            }
            return new ArrayList<>(seen.keySet());
        }
        @Override public void executionWork(long units) { execution += units; charge.accept(units); }
        @Override public void validationWork(long units) { validation += units; validate.accept(units); }
        @Override public void checkpoint() { RetainedGraph.measure(scope); check.accept(references()); }
        @Override public void retainedReferences(RetainedGraph.Visitor visitor) {}
    }
    private static boolean owns(List<Object> values, Object wanted) {
        return values.stream().anyMatch(value -> value == wanted);
    }
    private static boolean hasText(List<Object> values, String wanted) {
        return values.stream().anyMatch(value -> value instanceof String text && text.equals(wanted));
    }
    @Test void sourceIsOwnedBeforeTheFirstPaidValidationAndReleasedAfterReturn() {
        var source = new VariableExpr("borrowed-source");
        var sink = new Observation();
        try (var scope = RetainedOperation.open(sink)) {
            sink.scope = scope;
            sink.validate = units -> assertTrue(owns(sink.references(), source), "actual source must be owned during validation");
            var result = AstExpressionValidation.inspect(source);
            assertEquals(16, result.work());
            assertEquals(result.work(), sink.validation, "diagnostic work is not charged a second time");
            assertFalse(owns(sink.references(), source));
        }
    }
    @Test void generatedRationalAndScopedSymbolTextOverlapTheirActualSourceBeforeValidation() {
        var symbol = new SymbolScope(new UUID(0, 12)).declare("x");
        var expressions = List.<Expr>of(NumberExpr.exact("1/3"), VariableExpr.scoped(symbol));
        var texts = List.of("1/3", "00000000-0000-0000-0000-00000000000c:1");
        for (int i = 0; i < expressions.size(); i++) {
            var source = expressions.get(i); var text = texts.get(i);
            var sink = new Observation();
            try (var scope = RetainedOperation.open(sink)) {
                sink.scope = scope;
                sink.validate = units -> { if (units == text.length()) {
                    assertTrue(owns(sink.references(), source));
                    assertTrue(hasText(sink.references(), text), "actual generated canonical text must remain live");
                }};
                assertEquals(1 + text.length(), AstExpressionValidation.inspect(source).work());
            }
        }
    }
    @Test void completedInspectionRemainsOwnedThroughPublication() {
        var source = NumberExpr.exact("1/3"); var sink = new Observation();
        var seen = new ArrayList<AstExpressionValidation.Inspection>();
        try (var scope = RetainedOperation.open(sink)) {
            sink.scope = scope;
            sink.check = values -> values.stream().filter(AstExpressionValidation.Inspection.class::isInstance)
                .map(AstExpressionValidation.Inspection.class::cast).forEach(seen::add);
            var result = AstExpressionValidation.inspect(source);
            assertTrue(seen.stream().anyMatch(value -> value == result));
            assertFalse(owns(sink.references(), result), "published output leaves the temporary owner");
            assertEquals(4, sink.validation);
        }
    }
    @Test void failedGeneratedOutputDebitStillObservesTextAndPreservesTheTechnicalError() {
        var sink = new Observation(); var failure = new ArithmeticException("generated output debit");
        var observed = new ArrayList<String>();
        try (var scope = RetainedOperation.open(sink)) {
            sink.scope = scope;
            sink.charge = units -> { if (hasText(sink.references(), "1/3")) throw failure; };
            sink.check = values -> { if (hasText(values, "1/3")) observed.add("1/3"); };
            assertSame(failure, assertThrows(ArithmeticException.class, () -> AstExpressionValidation.inspect(NumberExpr.exact("1/3"))));
            assertFalse(observed.isEmpty(), "completed output must be observed even when its debit aborts");
            assertFalse(hasText(sink.references(), "1/3"));
            sink.charge = units -> {};
        }
    }
    @Test void lostCompletedResultAtCloseDoesNotRefundItsValidation() {
        var sink = new Observation(); var failure = new IllegalArgumentException("result release");
        boolean[] completed = {false};
        try (var scope = RetainedOperation.open(sink)) {
            sink.scope = scope;
            sink.check = values -> completed[0] |= values.stream().anyMatch(AstExpressionValidation.Inspection.class::isInstance);
            sink.charge = units -> { if (completed[0] && units == 4) throw failure; };
            assertSame(failure, assertThrows(IllegalArgumentException.class, () -> AstExpressionValidation.inspect(NumberExpr.exact("1/3"))));
            assertEquals(4, sink.validation);
            assertEquals(0, RetainedGraph.measure(scope).retained().nodes());
            sink.charge = units -> {};
        }
    }
    @Test void malformedUnicodeCannotHideAnObservationOrReleaseFailure() {
        for (boolean atClose : List.of(false, true)) {
            var sink = new Observation(); var failure = new IllegalArgumentException("technical after rejection");
            boolean[] rejected = {false};
            try (var scope = RetainedOperation.open(sink)) {
                sink.scope = scope;
                sink.check = values -> {
                    rejected[0] |= hasText(values, "unpaired Unicode surrogate");
                    if (rejected[0] && !atClose) throw failure;
                };
                sink.charge = units -> { if (rejected[0] && atClose && units == 4) throw failure; };
                assertSame(failure, assertThrows(IllegalArgumentException.class, () -> AstExpressionValidation.inspect(new VariableExpr("x\ud800"))));
                assertEquals(3, sink.validation);
                sink.charge = units -> {}; sink.check = values -> {};
            }
        }
    }
    @Test void primaryValidationFailureSurvivesIdenticalOrDistinctCloseErrors() {
        for (boolean identical : List.of(true, false)) {
            var sink = new Observation(); var primary = new AssertionError("validation observer");
            var secondary = identical ? primary : new AssertionError("close observer");
            try (var scope = RetainedOperation.open(sink)) {
                sink.scope = scope;
                sink.validate = units -> { if (units == 3) throw primary; };
                sink.charge = units -> { if (units == 4) throw secondary; };
                assertSame(primary, assertThrows(AssertionError.class, () -> AstExpressionValidation.inspect(NumberExpr.exact("1/3"))));
                assertEquals(4, sink.validation);
                assertArrayEquals(identical ? new Throwable[0] : new Throwable[]{secondary}, primary.getSuppressed());
                assertEquals(0, RetainedGraph.measure(scope).retained().nodes());
                sink.charge = units -> {};
            }
        }
    }
    @Test void failedOwnerAcquisitionObservesTheSourceAndRestoresTheOuterScope() {
        var source = new VariableExpr("source"); var sink = new Observation();
        var failure = new ArithmeticException("owner acquisition"); boolean[] observed = {false};
        try (var scope = RetainedOperation.open(sink)) {
            sink.scope = scope;
            sink.charge = units -> { if (units == 2) throw failure; };
            sink.check = values -> observed[0] |= owns(values, source);
            assertSame(failure, assertThrows(ArithmeticException.class, () -> AstExpressionValidation.inspect(source)));
            assertTrue(observed[0]); assertEquals(0, sink.validation);
            assertFalse(owns(sink.references(), source));
            sink.charge = units -> {};
            assertDoesNotThrow(() -> AstExpressionValidation.inspect(source));
        }
    }

}
