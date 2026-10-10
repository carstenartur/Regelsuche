package de.regelsuche.transform;

import static de.regelsuche.ast.BinaryOperator.MUL;
import static de.regelsuche.ast.BinaryOperator.POW;
import static org.junit.jupiter.api.Assertions.*;

import de.regelsuche.ast.*;
import de.regelsuche.retention.RetainedGraph;
import de.regelsuche.retention.RetainedOperation;
import de.regelsuche.scalar.ExactRational;
import java.math.BigInteger;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ExactMonomialOwnershipTest {
    private static final class DebitAbort extends RuntimeException { }
    private static final class CleanupAbort extends RuntimeException { }

    private static final class Observation implements RetainedOperation.Sink {
        RetainedOperation scope;
        Expr input;
        long work, retentionWork;
        int singleUnitDebits, powersPeak, monomialsPeak;
        boolean missingInput, optionalMonomial, rootScalars, renderedExponent;
        boolean abortVisit, abortMonomial, abortRootTrial, abortRendered, repeatPrimary, failCleanup, sawFailedMonomial;
        DebitAbort failure;
        final CleanupAbort cleanup = new CleanupAbort();

        @Override public void executionWork(long units) {
            work += units;
            if (scope == null) return;
            if (units == 1) singleUnitDebits++;
            if (failure != null) {
                if (repeatPrimary && units == 4) throw failure;
                if (failCleanup && units == 4) throw cleanup;
                return;
            }
            if ((abortVisit && units == 1) || (abortMonomial && snapshot().monomials() > 0)
                    || (abortRootTrial && rootScalars) || (abortRendered && renderedExponent)) {
                failure = new DebitAbort();
                throw failure;
            }
        }
        @Override public void validationWork(long units) { executionWork(units); }
        @Override public void retainedReferences(RetainedGraph.Visitor visitor) { visitor.reference(scope); }
        @Override public void checkpoint() {
            retentionWork += RetainedGraph.measure(scope).work();
            var current = snapshot();
            powersPeak = Math.max(powersPeak,current.powers());
            monomialsPeak = Math.max(monomialsPeak,current.monomials());
            missingInput |= input != null && !current.objects().contains(input);
            optionalMonomial |= current.optionalMonomial();
            rootScalars |= current.objects().stream().anyMatch(BigInteger.valueOf(24)::equals)
                && current.objects().stream().anyMatch(BigInteger.valueOf(12)::equals)
                && current.objects().stream().anyMatch(BigInteger.valueOf(1728)::equals);
            boolean exponent = current.objects().stream().anyMatch(value -> value instanceof NumberExpr number
                && number.value().equalsInteger(2));
            boolean power = current.objects().stream().anyMatch(value -> value instanceof BinaryExpr binary
                && binary.operator() == POW && binary.right() instanceof NumberExpr number
                && number.value().equalsInteger(2));
            renderedExponent |= exponent && !power;
            sawFailedMonomial |= failure != null && current.monomials() > 0;
        }
        private Snapshot snapshot() {
            var pending = new ArrayDeque<Object>();
            Set<Object> seen = Collections.newSetFromMap(new IdentityHashMap<>());
            var visitor = new RetainedGraph.Visitor() {
                @Override public void reference(Object value) { if (value != null) pending.add(value); }
                @Override public void requireExact(Object value,Class<?> type) { assertEquals(type,value.getClass()); }
            };
            visitor.reference(scope);
            int powers = 0, monomials = 0;
            boolean optional = false;
            while (!pending.isEmpty()) {
                Object value = pending.remove();
                if (!seen.add(value)) continue;
                if (value instanceof BoundedExactMonomial) monomials++;
                if (value instanceof RetainedGraph.View view) view.retainedReferences(visitor);
                else if (value instanceof Optional<?> envelope) {
                    optional |= envelope.orElse(null) instanceof BoundedExactMonomial;
                    envelope.ifPresent(visitor::reference);
                } else if (value instanceof Map<?,?> map) {
                    if (!map.isEmpty() && map.keySet().stream().allMatch(String.class::isInstance)
                            && map.values().stream().allMatch(Integer.class::isInstance)) powers++;
                    map.forEach((key,item) -> { visitor.reference(key); visitor.reference(item); });
                } else if (value instanceof Collection<?> values) values.forEach(visitor::reference);
                else if (value instanceof Object[] array) for (Object item : array) visitor.reference(item);
                else if (value instanceof BinaryExpr binary) {
                    visitor.reference(binary.left()); visitor.reference(binary.right());
                } else if (value instanceof FunctionExpr function) visitor.reference(function.arguments());
                else if (value instanceof NumberExpr number) visitor.reference(number.value());
                else if (value instanceof ExactRational rational) {
                    visitor.reference(rational.numerator()); visitor.reference(rational.denominator());
                }
            }
            return new Snapshot(seen,powers,monomials,optional);
        }
    }
    private record Snapshot(Set<Object> objects,int powers,int monomials,boolean optionalMonomial) { }

    @Test void productKeepsItsOperandsAccumulatorAndOptionalHandoffObservable() {
        Expr input = new BinaryExpr(new VariableExpr("x"),MUL,new VariableExpr("y"));
        var observation = new Observation(); observation.input = input;
        BoundedExactMonomial result;
        try (var scope = RetainedOperation.open(observation)) {
            observation.scope = scope;
            result = BoundedExactMonomial.from(input,new BoundedExactMonomial.Budget()).orElseThrow();
        }
        assertEquals(input,result.toExpr());
        assertTrue(observation.powersPeak >= 3,"both operand maps and the accumulating powers must overlap");
        assertTrue(observation.monomialsPeak >= 3,"the combined value must overlap its still-owned operands");
        assertTrue(observation.optionalMonomial,"the actual returned Optional owns its monomial before handoff");
        assertFalse(observation.missingInput);
        assertTrue(observation.work > 4); assertTrue(observation.retentionWork > 0);
        assertReleased(observation);
    }

    @Test void matcherInferenceOwnsIntegerRootTemporariesAndUnfoldedAstLeaves() {
        var coefficient = new ExactRational(BigInteger.valueOf(-1331),BigInteger.valueOf(2197));
        Expr input = new BinaryExpr(new NumberExpr(coefficient),MUL,
            new BinaryExpr(new BinaryExpr(new VariableExpr("x"),POW,new NumberExpr(6)),MUL,
                new BinaryExpr(new VariableExpr("y"),POW,new NumberExpr(3))));
        Expr expected = new BinaryExpr(new BinaryExpr(new NumberExpr(
            new ExactRational(BigInteger.valueOf(-11),BigInteger.valueOf(13))),MUL,
            new BinaryExpr(new VariableExpr("x"),POW,new NumberExpr(2))),MUL,new VariableExpr("y"));
        var observation = new Observation(); observation.input = input;
        var pattern = power(3);
        EquivalenceAwarePatternMatcher.MatchAttempt result;
        try (var scope = RetainedOperation.open(observation)) {
            observation.scope = scope;
            result = EquivalenceAwarePatternMatcher.matchDetailed(pattern,input,Map.of(),RecognitionProfile.algebraicAc());
        }
        assertTrue(result.matched()); assertEquals(expected,result.bindings().get("A"));
        assertEquals(0,result.visitedBranches(),"algebraic visits must not be reinterpreted as AC branches");
        assertTrue(observation.rootScalars,"the actual sum 24, midpoint 12 and cube 1728 coexist in root search");
        assertTrue(observation.renderedExponent,"the produced exponent exists before its power node is constructed");
        assertTrue(observation.optionalMonomial); assertFalse(observation.missingInput);
        assertReleased(observation);
    }

    @Test void rejectedInferenceStillOwnsItsCompletedChildMonomials() {
        Expr input = new BinaryExpr(new VariableExpr("x"),de.regelsuche.ast.BinaryOperator.DIV,new VariableExpr("x"));
        var observation = new Observation(); observation.input = input;
        try (var scope = RetainedOperation.open(observation)) {
            observation.scope = scope;
            var result = EquivalenceAwarePatternMatcher.matchDetailed(PatternExpr.num(1),input,Map.of(),RecognitionProfile.algebraicAc());
            assertFalse(result.matched()); assertFalse(result.inconclusive());
        }
        assertTrue(observation.monomialsPeak >= 2,"symbolic division rejects only after both real child values exist");
        assertTrue(observation.optionalMonomial); assertFalse(observation.missingInput);
        assertReleased(observation);
    }

    @Test void coefficientAndExponentLimitsKeepAttemptedMonomialsAndCallerBindings() {
        var cases = Map.of(
            "ALGEBRAIC_COEFFICIENT_LIMIT",new BinaryExpr(new NumberExpr(2),POW,new NumberExpr(5000)),
            "ALGEBRAIC_EXPONENT_LIMIT",new BinaryExpr(
                new BinaryExpr(new VariableExpr("x"),POW,new NumberExpr(Integer.MAX_VALUE)),MUL,new VariableExpr("x")));
        for (var entry : cases.entrySet()) {
            var observation = new Observation(); observation.input = entry.getValue();
            var bindings = new HashMap<String,Expr>(Map.of("seed",new NumberExpr(7)));
            var pattern = power(2);
            EquivalenceAwarePatternMatcher.MatchAttempt result;
            try (var scope = RetainedOperation.open(observation)) {
                observation.scope = scope;
                result = EquivalenceAwarePatternMatcher.matchDetailed(pattern,entry.getValue(),bindings,RecognitionProfile.algebraicAc());
            }
            assertTrue(result.inconclusive()); assertEquals(entry.getKey(),result.limitCode());
            assertEquals(Map.of("seed",new NumberExpr(7)),result.bindings());
            assertEquals(result.bindings(),bindings); assertEquals(0,result.visitedBranches());
            assertTrue(observation.monomialsPeak > 0,"the admitted base exists before the original arithmetic limit");
            assertFalse(observation.missingInput); assertTrue(observation.work > 4);
            assertReleased(observation);
        }
    }

    @Test void lastAdmittedAlgebraicVisitIsPaidOnceAndLimitAddsNoUnperformedVisit() {
        var input = new FunctionExpr("unsupported",List.of());
        var budget = almostExhausted(input);
        var observation = new Observation(); observation.input = input;
        try (var scope = RetainedOperation.open(observation)) {
            observation.scope = scope;
            assertTrue(BoundedExactMonomial.from(input,budget).isEmpty());
            var limit = assertThrows(BoundedExactMonomial.LimitExceeded.class,() -> BoundedExactMonomial.from(input,budget));
            assertEquals("ALGEBRAIC_WORK_LIMIT",limit.code);
            assertEquals(1,observation.singleUnitDebits,"one admitted visit is paid; the limited attempt never visits a node");
        }
        assertReleased(observation);
    }

    @Test void failedVisitDebitIsNotRefundedOrSettledAgain() {
        var input = new FunctionExpr("unsupported",List.of());
        var budget = almostExhausted(input);
        var observation = new Observation(); observation.input = input; observation.abortVisit = true;
        try (var scope = RetainedOperation.open(observation)) {
            observation.scope = scope;
            var failure = assertThrows(DebitAbort.class,() -> BoundedExactMonomial.from(input,budget));
            assertSame(observation.failure,failure);
            var limit = assertThrows(BoundedExactMonomial.LimitExceeded.class,() -> BoundedExactMonomial.from(input,budget));
            assertEquals("ALGEBRAIC_WORK_LIMIT",limit.code);
            assertEquals(1,observation.singleUnitDebits,"the debit that threw was still the sole consumed visit");
            assertEquals(0,RetainedGraph.measure(scope).retained().nodes());
        }
        assertReleased(observation);
    }

    @Test void failedMonomialPublicationIsObservedAndPrimaryErrorSurvivesCleanup() {
        var input = new VariableExpr("x");
        var observation = new Observation(); observation.input = input;
        observation.abortMonomial = true; observation.failCleanup = true;
        try (var scope = RetainedOperation.open(observation)) {
            observation.scope = scope;
            var failure = assertThrows(DebitAbort.class,() -> BoundedExactMonomial.from(input,new BoundedExactMonomial.Budget()));
            assertSame(observation.failure,failure);
            assertTrue(observation.sawFailedMonomial,"failed allocation settlement still observes the completed monomial");
            assertTrue(Arrays.asList(failure.getSuppressed()).contains(observation.cleanup));
            assertEquals(0,RetainedGraph.measure(scope).retained().nodes());
            observation.failCleanup = false;
            assertTrue(BoundedExactMonomial.from(input,new BoundedExactMonomial.Budget()).isPresent());
        }
        assertReleased(observation);
    }

    @ParameterizedTest @ValueSource(ints = {0,1,2})
    void repeatedPrimaryCleanupPreservesFailuresDuringConversionRootSearchAndRendering(int phase) {
        var coefficient = new ExactRational(BigInteger.valueOf(-1331),BigInteger.valueOf(2197));
        Expr input = new BinaryExpr(new NumberExpr(coefficient),MUL,
            new BinaryExpr(new BinaryExpr(new VariableExpr("x"),POW,new NumberExpr(6)),MUL,
                new BinaryExpr(new VariableExpr("y"),POW,new NumberExpr(3))));
        var observation = new Observation(); observation.input = input; observation.repeatPrimary = true;
        observation.abortMonomial = phase == 0; observation.abortRootTrial = phase == 1; observation.abortRendered = phase == 2;
        try (var scope = RetainedOperation.open(observation)) {
            observation.scope = scope;
            var failure = assertThrows(DebitAbort.class,() -> EquivalenceAwarePatternMatcher.matchDetailed(
                power(3),input,Map.of(),RecognitionProfile.algebraicAc()));
            assertSame(observation.failure,failure,"cleanup must not replace the primary with a self-suppression error");
            observation.repeatPrimary = false;
            assertEquals(0,RetainedGraph.measure(scope).retained().nodes());
            assertTrue(EquivalenceAwarePatternMatcher.matchDetailed(power(3),input,Map.of(),RecognitionProfile.algebraicAc()).matched());
        }
        assertReleased(observation);
    }

    private static BoundedExactMonomial.Budget almostExhausted(Expr input) {
        var budget = new BoundedExactMonomial.Budget();
        for (int visit = 0; visit < 9999; visit++) assertTrue(BoundedExactMonomial.from(input,budget).isEmpty());
        return budget;
    }
    private static PatternExpr power(int exponent) {
        return PatternExpr.op(POW,PatternExpr.var("A"),PatternExpr.num(exponent));
    }
    private static void assertReleased(Observation observation) {
        var retained = RetainedGraph.measure(observation.scope).retained();
        assertEquals(0,retained.nodes()); assertEquals(0,retained.characters());
    }
}
