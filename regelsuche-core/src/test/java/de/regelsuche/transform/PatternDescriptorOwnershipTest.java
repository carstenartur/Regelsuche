package de.regelsuche.transform;

import static de.regelsuche.ast.BinaryOperator.*;
import static org.junit.jupiter.api.Assertions.*;

import de.regelsuche.retention.RetainedGraph;
import de.regelsuche.retention.RetainedOperation;
import java.util.*;
import org.junit.jupiter.api.Test;

class PatternDescriptorOwnershipTest {
    private static final String LEFT = "first-🙂-field";
    private static final String RIGHT = "last-β-field";
    private static final String NUMBER = "1" + "7".repeat(180);
    private static final PatternExpr PATTERN = PatternExpr.op(ADD,PatternExpr.var(LEFT),
        PatternExpr.fn("nested",PatternExpr.num(NUMBER),PatternExpr.variable(RIGHT)));

    private static final class DescriptionAbort extends RuntimeException { }
    private static final class Observation implements RetainedOperation.Sink {
        RetainedOperation scope;
        String expected;
        boolean abortGrowth, abortOutput, partial, growth, numericOverlap, outputOverlap, failedOwned;
        DescriptionAbort failure;
        long paidAfterFailure;

        @Override public void executionWork(long units) {
            if (failure != null) paidAfterFailure += units;
            else if (scope != null) {
                var snapshot = snapshot();
                if ((abortGrowth && snapshot.growth()) || (abortOutput && snapshot.outputOverlap())) {
                    failure = new DescriptionAbort(); throw failure;
                }
            }
        }
        @Override public void validationWork(long units) { executionWork(units); }
        @Override public void retainedReferences(RetainedGraph.Visitor visitor) { visitor.reference(scope); }
        @Override public void checkpoint() {
            RetainedGraph.measure(scope);
            var snapshot = snapshot();
            partial |= snapshot.partial(); growth |= snapshot.growth();
            numericOverlap |= snapshot.numericOverlap(); outputOverlap |= snapshot.outputOverlap();
            failedOwned |= failure != null && (snapshot.growth() || snapshot.outputOverlap());
        }
        private Snapshot snapshot() {
            var pending = new ArrayDeque<Object>();
            var seen = Collections.newSetFromMap(new IdentityHashMap<Object,Boolean>());
            var buffers = new ArrayList<char[]>();
            boolean number = false, output = false;
            var visitor = new RetainedGraph.Visitor() {
                @Override public void reference(Object value) { if (value != null) pending.add(value); }
                @Override public void requireExact(Object value,Class<?> type) { assertEquals(type,value.getClass()); }
            };
            visitor.reference(scope);
            while (!pending.isEmpty()) {
                Object value = pending.remove(); if (!seen.add(value)) continue;
                if (value instanceof String text) { number |= text.equals(NUMBER); output |= text.equals(expected); }
                if (value instanceof char[] buffer) buffers.add(buffer);
                if (value instanceof RetainedGraph.View view) view.retainedReferences(visitor);
                else if (value instanceof Object[] array) { for (Object item : array) visitor.reference(item); }
                else if (value instanceof Collection<?> values) values.forEach(visitor::reference);
                else if (value instanceof Map<?,?> map) map.forEach((key,item) -> { visitor.reference(key); visitor.reference(item); });
            }
            boolean partial = false, growth = false, numericOverlap = false, outputOverlap = false;
            for (char[] buffer : buffers) {
                String text = new String(buffer);
                boolean patternBuffer = text.startsWith("Operation[operator=ADD, left=");
                partial |= patternBuffer && text.contains(LEFT) && !text.contains(RIGHT);
                numericOverlap |= patternBuffer && text.contains(LEFT) && number;
                outputOverlap |= patternBuffer && text.startsWith(expected) && output;
                if (patternBuffer) {
                    for (char[] other : buffers) {
                        growth |= other != buffer && other.length > buffer.length && other[0] == '\0';
                    }
                }
            }
            return new Snapshot(partial,growth,numericOverlap,outputOverlap);
        }
    }
    private record Snapshot(boolean partial,boolean growth,boolean numericOverlap,boolean outputOverlap) { }

    @Test void patternTextOwnsItsPartialBufferNumericFragmentGrowthAndFinalCopy() {
        var observation = observation();
        try (var scope = RetainedOperation.open(observation)) {
            observation.scope = scope;
            assertEquals(descriptor(PATTERN.toString()),ExprMatcher.pattern(PATTERN).canonicalDescriptor());
        }
        assertTrue(observation.partial,"observe the actual partially written pattern buffer");
        assertTrue(observation.growth,"old and replacement buffers overlap before copying");
        assertTrue(observation.numericOverlap,"the produced numeric fragment remains owned with the earlier pattern prefix");
        assertTrue(observation.outputOverlap,"the completed pattern text overlaps its actual backing workspace");
        assertReleased(observation);
    }
    @Test void failedPatternBufferGrowthRemainsOwnedAndDoesNotProduceTheFinalText() {
        var observation = observation(); observation.abortGrowth = true;
        verifyAbort(observation);
        assertFalse(observation.outputOverlap);
    }
    @Test void failedCompletedPatternTextDebitStillObservesItsBufferAndOutput() {
        var observation = observation(); observation.abortOutput = true;
        verifyAbort(observation);
        assertTrue(observation.outputOverlap);
    }
    private static Observation observation() {
        var observation = new Observation(); observation.expected = PATTERN.toString(); return observation;
    }
    private static void verifyAbort(Observation observation) {
        try (var scope = RetainedOperation.open(observation)) {
            observation.scope = scope;
            var failure = assertThrows(DescriptionAbort.class,() -> ExprMatcher.pattern(PATTERN).canonicalDescriptor());
            assertSame(observation.failure,failure); assertTrue(observation.failedOwned);
            assertTrue(observation.paidAfterFailure > 0,"cleanup is paid after the original debit failure");
        }
        assertReleased(observation);
    }
    private static void assertReleased(Observation observation) {
        assertEquals(0,RetainedGraph.measure(observation.scope).retained().nodes());
    }

    @Test void allPatternFormsKeepTheExistingRecordTextIncludingNestedListsAndUnicode() {
        var patterns = new ArrayList<>(List.of(PatternExpr.var("α,[🙂]"),PatternExpr.variable("x\nβ"),
            PatternExpr.num("-2/3"),PatternExpr.fn("empty"),PATTERN));
        PatternExpr deep = PatternExpr.var("leaf");
        for (int depth = 0; depth < 80; depth++) deep = PatternExpr.op(SUB,deep,PatternExpr.num(depth));
        patterns.add(deep);
        patterns.add(PatternExpr.fn("wide",patterns.toArray(PatternExpr[]::new)));
        for (PatternExpr pattern : patterns) {
            assertEquals(descriptor(pattern.toString()),ExprMatcher.pattern(pattern).canonicalDescriptor());
        }
    }
    private static String descriptor(String patternText) {
        String emptyOperators = frame("associative"), emptyCommutative = frame("commutative");
        String profile = frame("recognition-profile",emptyOperators,emptyCommutative,"false",frame("recognition-rules"),"0");
        return frame("pattern",patternText,profile);
    }
    private static String frame(String type,String... fields) {
        var output = new StringBuilder().append(type.length()).append(':').append(type);
        for (String field : fields) output.append(field.length()).append(':').append(field);
        return output.toString();
    }
}
