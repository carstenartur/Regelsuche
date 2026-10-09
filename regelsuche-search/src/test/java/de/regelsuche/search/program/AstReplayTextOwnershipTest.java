package de.regelsuche.search.program;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.NumberExpr;
import de.regelsuche.ast.VariableExpr;
import de.regelsuche.retention.RetainedGraph;
import de.regelsuche.retention.RetainedJson;
import de.regelsuche.retention.RetainedOperation;
import de.regelsuche.scalar.ExactRational;
import de.regelsuche.symbol.SymbolId;
import java.math.BigInteger;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AstReplayTextOwnershipTest {
    private static final class DebitFailure extends RuntimeException { }
    private static final class ObservationFailure extends RuntimeException { }
    private static final class CleanupFailure extends RuntimeException { }
    private enum Abort { NONE, RENDER, VALIDATION }

    private static final class Meter implements RetainedOperation.Sink {
        final Expr source;
        final String expected;
        final DebitFailure primary = new DebitFailure();
        RetainedOperation operation;
        RetainedJson.Scope json;
        Abort abort = Abort.NONE;
        boolean failed, repeatPrimary, distinctCleanup;
        boolean sawValidation, ownedDuringValidation = true, observedBeforeAdoption, observedAfterFailure;
        long renderingPayments;

        Meter(Expr source, String expected) { this.source = source; this.expected = expected; }
        @Override public void retainedReferences(RetainedGraph.Visitor visitor) {
            visitor.reference(source); visitor.reference(operation); visitor.reference(json);
        }
        @Override public void validationWork(long units) { executionWork(units); }
        @Override public void executionWork(long units) {
            if (operation == null) return;
            var graph = graph();
            boolean ownsText = graph.stream().anyMatch(value -> value instanceof String text && text.equals(expected));
            boolean adopted = graph.stream().anyMatch(value -> value instanceof JsonNode node
                && node.isObject() && (expected.equals(node.path("value").asText()) || expected.equals(node.path("id").asText())));
            boolean validation = StackWalker.getInstance().walk(frames -> frames.anyMatch(frame ->
                frame.getClassName().equals(AstReplayJson.class.getName()) && frame.getMethodName().equals("text")));
            if (validation) {
                sawValidation = true; ownedDuringValidation &= ownsText;
                if (!failed && abort == Abort.VALIDATION) { failed = true; throw primary; }
            }
            if (units == 1L + expected.length() && ownsText && !adopted) {
                renderingPayments++;
                if (!failed && abort == Abort.RENDER) { failed = true; throw primary; }
            }
            if (failed && units == 4) {
                if (repeatPrimary) throw primary;
                if (distinctCleanup) throw new CleanupFailure();
            }
        }
        @Override public void checkpoint() {
            RetainedGraph.measure(operation);
            var graph = graph();
            boolean ownsText = graph.stream().anyMatch(value -> value instanceof String text && text.equals(expected));
            boolean adopted = graph.stream().anyMatch(value -> value instanceof JsonNode node
                && node.isObject() && (expected.equals(node.path("value").asText()) || expected.equals(node.path("id").asText())));
            observedBeforeAdoption |= ownsText && !adopted;
            observedAfterFailure |= failed && ownsText;
            if (failed && ownsText && distinctCleanup) throw new ObservationFailure();
        }
        private Set<Object> graph() {
            var seen = Collections.newSetFromMap(new IdentityHashMap<Object, Boolean>());
            var pending = new ArrayDeque<Object>();
            var visitor = new RetainedGraph.Visitor() {
                @Override public void reference(Object value) { if (value != null) pending.add(value); }
                @Override public void requireExact(Object value, Class<?> type) { assertEquals(type, value.getClass()); }
            };
            visitor.reference(operation);
            while (!pending.isEmpty()) {
                Object value = pending.removeFirst();
                if (!seen.add(value)) continue;
                if (value instanceof RetainedGraph.View view) view.retainedReferences(visitor);
                else if (value instanceof Object[] array) for (Object child : array) visitor.reference(child);
                else if (value instanceof Map<?, ?> map) map.forEach((key, child) -> { visitor.reference(key); visitor.reference(child); });
                else if (value instanceof JsonNode node && node.isTextual()) visitor.reference(node.textValue());
                else if (value instanceof Iterable<?> sequence) for (Object child : sequence) visitor.reference(child);
            }
            return seen;
        }
        void assertReleased() {
            assertTrue(graph().stream().noneMatch(RetainedOperation.Frame.class::isInstance), "text and node frames must be released");
        }
    }

    private static NumberExpr number() {
        return new NumberExpr(new ExactRational(new BigInteger("12345678901234567"), BigInteger.valueOf(97)));
    }

    @Test void freshNumberAndSymbolRenderingsAreOwnedAndPaidBeforeValidationWithoutChangingBytes() {
        var number = number();
        var symbol = new SymbolId(UUID.fromString("f59e6646-1980-471b-8ca4-6d77b0d8eeb6"), 37);
        List<Expr> inputs = List.of(number, VariableExpr.scoped(symbol));
        List<String> texts = List.of(number.value().canonicalText(), symbol.canonicalText());
        var codec = new CompiledAstReplayCodec();
        for (int i = 0; i < inputs.size(); i++) {
            var input = inputs.get(i);
            String historical = codec.encodeExpression(input);
            var meter = new Meter(input, texts.get(i));
            try (var operation = RetainedOperation.open(meter)) {
                meter.operation = operation;
                try (var json = RetainedJson.open()) {
                    meter.json = json;
                    assertEquals(historical, codec.encodeExpression(input));
                    assertTrue(meter.sawValidation);
                    assertTrue(meter.ownedDuringValidation, "fresh text must already have an owner during its first validation debit");
                    assertTrue(meter.observedBeforeAdoption, "the completed rendering must be observed before JSON adopts it");
                    assertEquals(1, meter.renderingPayments, "charge the completed rendering once, separately from validation");
                    meter.assertReleased();
                }
            }
            assertFalse(RetainedJson.active());
        }
    }

    @Test void oversizedCompletedRenderingRemainsObservedAndPaidWhenTextValidationRejectsIt() {
        var input = new NumberExpr(new ExactRational(BigInteger.TEN.pow(CompiledAstReplayCodec.MAXIMUM_TEXT_CHARACTERS), BigInteger.ONE));
        var meter = new Meter(input, input.value().canonicalText());
        try (var operation = RetainedOperation.open(meter)) {
            meter.operation = operation;
            try (var json = RetainedJson.open()) {
                meter.json = json;
                var failure = assertThrows(IllegalArgumentException.class, () -> new AstReplayJson(new ObjectMapper()).write(input));
                assertEquals("invalid or oversized AST replay text", failure.getMessage());
                assertTrue(meter.observedBeforeAdoption, "a rendered but rejected value must still appear in an ownership observation");
                assertEquals(1, meter.renderingPayments);
                meter.assertReleased();
            }
        }
    }

    @Test void failedCompletedRenderingDebitStillObservesItsTextAndPreservesPrimary() {
        assertFailure(Abort.RENDER, false, true);
    }

    @Test void validationFailureObservesTheTextBeforeCleanupAndPreservesDistinctSecondaryFailures() {
        assertFailure(Abort.VALIDATION, false, true);
    }

    @Test void repeatedPrimaryDuringTextAndNodeReleaseDoesNotBecomeSelfSuppression() {
        assertFailure(Abort.VALIDATION, true, false);
    }

    private static void assertFailure(Abort abort, boolean repeatPrimary, boolean distinctCleanup) {
        var input = number();
        var meter = new Meter(input, input.value().canonicalText());
        meter.abort = abort; meter.repeatPrimary = repeatPrimary; meter.distinctCleanup = distinctCleanup;
        try (var operation = RetainedOperation.open(meter)) {
            meter.operation = operation;
            try (var json = RetainedJson.open()) {
                meter.json = json;
                assertSame(meter.primary, assertThrows(DebitFailure.class, () -> new AstReplayJson(new ObjectMapper()).write(input)));
                assertTrue(meter.observedAfterFailure, "failed debit must be observed while its actual text owner still exists");
                if (repeatPrimary) assertEquals(0, meter.primary.getSuppressed().length);
                if (distinctCleanup) {
                    assertTrue(List.of(meter.primary.getSuppressed()).stream().anyMatch(ObservationFailure.class::isInstance));
                    assertTrue(List.of(meter.primary.getSuppressed()).stream().anyMatch(CleanupFailure.class::isInstance));
                }
                meter.assertReleased();
                meter.failed = false; meter.abort = Abort.NONE;
                assertEquals(meter.expected, new AstReplayJson(new ObjectMapper()).write(input).get("value").textValue());
                meter.assertReleased();
            }
        }
        assertFalse(RetainedJson.active());
    }
}
