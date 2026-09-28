package de.regelsuche.transform;

import static de.regelsuche.ast.BinaryOperator.ADD;
import static org.junit.jupiter.api.Assertions.*;

import de.regelsuche.ast.*;
import de.regelsuche.parse.ExpressionParser;
import de.regelsuche.retention.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ExprMatcherOwnershipTest {
    private static final class MatchAbort extends RuntimeException { }
    private static final class Observation implements RetainedOperation.Sink {
        RetainedOperation scope;
        Expr input;
        ExprMatcher.MatchOutcome outcome;
        long work, workAfterFailure, retentionWork;
        MatchAbort failure;
        boolean abortOutcome, abortClose, sawSession, inputMissing;
        boolean abortBindingCopy, sawBindingCopy, sawTraceCopy;
        boolean abortStateList, sawSecondBinding, sawOperationTrace;
        boolean sawRepresentativeList, sawLaterRepresentative, sawDescendantTrace;
        boolean sawPathCopy, sawPathBufferAndText, abortPathBuffer;
        int unpublishedResultScans;
        final Set<String> patternDescriptions = Collections.newSetFromMap(new IdentityHashMap<>());
        final Set<List<?>> tracePrefixes = new HashSet<>();
        final Set<Object> states = Collections.newSetFromMap(new IdentityHashMap<>());
        final Set<Object> mutableStateLists = Collections.newSetFromMap(new IdentityHashMap<>());
        final Set<String> renderedPaths = Collections.newSetFromMap(new IdentityHashMap<>());
        @Override public void executionWork(long units) {
            work += units;
            if (failure != null) workAfterFailure += units;
            else if (abortClose && outcome != null && units == 4) {
                failure = new MatchAbort(); throw failure;
            }
        }
        @Override public void validationWork(long units) { executionWork(units); }
        @Override public void retainedReferences(RetainedGraph.Visitor visitor) { visitor.reference(scope); }
        @Override public void checkpoint() {
            retentionWork += RetainedGraph.measure(scope).work();
            var pending = new ArrayDeque<Object>();
            var seen = Collections.newSetFromMap(new IdentityHashMap<Object,Boolean>());
            var visitor = new RetainedGraph.Visitor() {
                @Override public void reference(Object value) { if (value != null) pending.add(value); }
                @Override public void requireExact(Object value, Class<?> type) { assertEquals(type,value.getClass()); }
            };
            visitor.reference(scope);
            boolean hasStateList = false;
            boolean hasMutableBinding = false, hasFrozenBinding = false;
            boolean hasMutablePath = false, hasFrozenPath = false, hasPathBuffer = false, hasPathText = false;
            while (!pending.isEmpty()) {
                Object value = pending.remove(); if (!seen.add(value)) continue;
                if (value instanceof String text && text.startsWith("7:pattern")) patternDescriptions.add(text);
                if ("operation:ADD".equals(value)) sawOperationTrace = true;
                if ("representative:1".equals(value)) sawLaterRepresentative = true;
                if ("contains@0".equals(value)) sawDescendantTrace = true;
                if (value instanceof String text && Set.of("root","0","1","1.0","12.0").contains(text)) {
                    renderedPaths.add(text); hasPathText |= text.equals("1.0");
                }
                if (value instanceof char[] buffer && Arrays.equals(buffer,new char[]{'1','.','0'})) hasPathBuffer = true;
                sawSession |= value.getClass().getEnclosingClass() == ExprMatcherEngine.class
                    && value.getClass().getSimpleName().equals("Session");
                if (value.getClass().getEnclosingClass() == ExprMatcherEngine.class
                        && value.getClass().getSimpleName().equals("State")) states.add(value);
                if (value instanceof ExprMatcher.MatchOutcome result) outcome = result;
                if (value instanceof RetainedGraph.View view) view.retainedReferences(visitor);
                else if (value instanceof Object[] array) for (var item : array) visitor.reference(item);
                else if (value instanceof Collection<?> values) {
                    if (values.equals(List.of(1,0))) {
                        hasMutablePath |= values instanceof ArrayList<?>;
                        hasFrozenPath |= !(values instanceof ArrayList<?>);
                    }
                    sawRepresentativeList |= values instanceof List<?> && values.size() == 2
                        && values.stream().anyMatch(item -> item == input)
                        && values.stream().allMatch(item -> item instanceof Expr);
                    if (values instanceof List<?> list && !list.isEmpty() && "any".equals(list.getFirst())) {
                        tracePrefixes.add(List.copyOf(list));
                        sawTraceCopy |= list instanceof ArrayList<?>;
                    }
                    hasStateList |= values.stream().anyMatch(item -> item != null
                        && item.getClass().getEnclosingClass() == ExprMatcherEngine.class
                        && item.getClass().getSimpleName().equals("State"));
                    if (values instanceof ArrayList<?> && values.stream().anyMatch(item -> item != null
                            && item.getClass().getEnclosingClass() == ExprMatcherEngine.class
                            && item.getClass().getSimpleName().equals("State"))) mutableStateLists.add(values);
                    values.forEach(visitor::reference);
                }
                else if (value instanceof Map<?,?> map) {
                    sawSecondBinding |= map.get("B") == input;
                    if (map.get("A") == input) {
                        hasMutableBinding |= map instanceof HashMap<?,?>;
                        hasFrozenBinding |= !(map instanceof HashMap<?,?>);
                    }
                    map.forEach((key,item) -> { visitor.reference(key); visitor.reference(item); });
                }
                else if (value instanceof BinaryExpr binary) {
                    visitor.reference(binary.left()); visitor.reference(binary.right());
                } else if (value instanceof FunctionExpr function) visitor.reference(function.arguments());
            }
            inputMissing |= !seen.contains(input);
            sawBindingCopy |= hasMutableBinding && hasFrozenBinding;
            sawPathCopy |= hasMutablePath && hasFrozenPath;
            sawPathBufferAndText |= hasPathBuffer && hasPathText;
            if (hasStateList && outcome == null) unpublishedResultScans++;
            if (abortBindingCopy && sawBindingCopy && failure == null) {
                failure = new MatchAbort(); throw failure;
            }
            if (abortStateList && !mutableStateLists.isEmpty() && failure == null) {
                failure = new MatchAbort(); throw failure;
            }
            if (abortPathBuffer && sawPathBufferAndText && failure == null) {
                failure = new MatchAbort(); throw failure;
            }
            if (abortOutcome && outcome != null && failure == null) {
                failure = new MatchAbort(); throw failure;
            }
        }
    }

    private static ExprMatcher matcher() {
        return ExprMatcher.pattern(PatternExpr.fn("f",
            PatternExpr.op(ADD,PatternExpr.var("A"),PatternExpr.var("B")),PatternExpr.var("A")),
            RecognitionProfile.arithmeticAc());
    }

    @Test void matcherAlgebraOptionsAndConstraintsExposeTheirActualReferences() {
        var any = ExprMatcher.any(); var profile = RecognitionProfile.arithmeticAc();
        var definitions = List.of(any,ExprMatcher.literalNumber(2),ExprMatcher.literalVariable("x"),
            ExprMatcher.integerLiteral(),matcher(),ExprMatcher.bind("A",any),ExprMatcher.allOf(any,any),
            ExprMatcher.anyOf(any,any),ExprMatcher.not(any),ExprMatcher.op(ADD,any,any),
            ExprMatcher.fn("f",any),ExprMatcher.contains(any),ExprMatcher.equivalent(profile,any),
            ExprMatcher.where(any,ExprMatcher.sameAs("A","B")),ExprMatcher.bindingMatches("A",any),
            ExprMatcher.MatchOptions.defaults());
        assertDoesNotThrow(() -> RetainedGraph.measure(definitions));
    }

    @Test void sessionAndIndependentOutcomeRemainOwnedThroughResultAssembly() {
        Expr input = new ExpressionParser().parseTerm("f(x+y,y)");
        var expected = matcher().match(input);
        var observation = new Observation(); observation.input = input;
        ExprMatcher.MatchOutcome result;
        try (var scope = RetainedOperation.open(observation)) {
            observation.scope = scope;
            result = matcher().match(input);
        }
        assertEquals(expected,result);
        assertTrue(observation.sawSession,"the actual matcher session must be an owner during execution");
        assertSame(result,observation.outcome,"the actual returned result must be observed before release");
        assertFalse(observation.inputMissing);
        assertDoesNotThrow(() -> RetainedGraph.measure(result));
        assertEquals(0,RetainedGraph.measure(observation.scope).retained().nodes());
    }

    @Test void diagnosticResultsRemainIndependentlyDescribable() {
        var result = ExprMatcher.anyOf(ExprMatcher.any(),ExprMatcher.any()).match(new VariableExpr("x"),
            new ExprMatcher.MatchOptions(null,1,10,10));
        assertTrue(result.matched()); assertFalse(result.complete());
        assertEquals("MATCH_RESULT_LIMIT",result.diagnostics().getFirst().code());
        assertDoesNotThrow(() -> RetainedGraph.measure(result));
    }

    @Test void monotonicallyGrowingResultAssemblyUsesItsPublicationObservation() {
        Expr input = new ExpressionParser().parseTerm("f(x+y,y)");
        var observation = new Observation(); observation.input = input;
        try (var scope = RetainedOperation.open(observation)) {
            observation.scope = scope;
            assertTrue(matcher().match(input).matched());
        }
        assertNotNull(observation.outcome,"the complete result and source graph must still be observed");
        assertEquals(0,observation.unpublishedResultScans,
            "retaining the raw/frozen states through monotone result assembly avoids an intermediate full scan");
        assertFalse(observation.inputMissing);
    }

    @Test void anExplicitPatternDescriptionPublishesItsActualOutputText() {
        var observation = new Observation();
        String description;
        try (var scope = RetainedOperation.open(observation)) {
            observation.scope = scope;
            description = matcher().canonicalDescriptor();
        }
        assertTrue(observation.patternDescriptions.contains(description),
            "explicit descriptor output must be owned and charged before returning");
        assertTrue(observation.work >= description.length());
        assertEquals(0,RetainedGraph.measure(observation.scope).retained().nodes());
    }

    private static ExprMatcher twoBindings() {
        return ExprMatcher.allOf(ExprMatcher.bind("A",ExprMatcher.any()),ExprMatcher.bind("B",ExprMatcher.any()));
    }

    @Test void intermediateBindingsAndTraceCopiesRemainVisibleBeforeComposition() {
        Expr input = new VariableExpr("x");
        var observation = new Observation(); observation.input = input;
        ExprMatcher.MatchOutcome result;
        try (var scope = RetainedOperation.open(observation)) {
            observation.scope = scope;
            result = twoBindings().match(input);
        }
        assertEquals(Map.of("A",input,"B",input),result.matches().getFirst().bindings());
        assertEquals(List.of("any","bind:A","any","bind:B"),result.matches().getFirst().trace());
        assertTrue(observation.sawBindingCopy,"the actual mutable binding copy must overlap its frozen result");
        assertTrue(observation.sawTraceCopy,"the actual trace assembly list must remain observable");
        assertTrue(observation.tracePrefixes.contains(List.of("any")));
        assertTrue(observation.tracePrefixes.contains(List.of("any","bind:A")));
        assertTrue(observation.tracePrefixes.contains(List.of("any","bind:A","any")));
        assertFalse(observation.inputMissing);
        assertEquals(0,RetainedGraph.measure(observation.scope).retained().nodes());
    }

    @Test void anAbortedBindingCopyCannotReturnAnUnpaidCompletedOutcome() {
        Expr input = new VariableExpr("x");
        var observation = new Observation(); observation.input = input; observation.abortBindingCopy = true;
        try (var scope = RetainedOperation.open(observation)) {
            observation.scope = scope;
            var failure = assertThrows(MatchAbort.class,() -> twoBindings().match(input));
            assertSame(observation.failure,failure);
            assertTrue(observation.sawBindingCopy);
            assertNull(observation.outcome);
            assertTrue(observation.workAfterFailure > 0,"cleanup and unreturned match work must remain paid");
        }
        assertEquals(0,RetainedGraph.measure(observation.scope).retained().nodes());
    }

    @Test void anExactRecognitionThatChangesNoFieldsReusesItsState() {
        Expr input = new VariableExpr("x");
        var matcher = ExprMatcher.pattern(PatternExpr.var("A"));
        var expected = matcher.match(input);
        var observation = new Observation(); observation.input = input;
        ExprMatcher.MatchOutcome result;
        try (var scope = RetainedOperation.open(observation)) {
            observation.scope = scope;
            result = matcher.match(input);
        }
        assertEquals(expected,result);
        System.out.println("P04_MATCHER_STATE_UPDATE states=" + observation.states.size()
            + " execution=" + observation.work + " retention=" + observation.retentionWork
            + " total=" + (observation.work + observation.retentionWork));
        assertTrue(observation.states.size() <= 3,
            "source, changed bindings and new trace need at most three states; unchanged recognition adds none");
        assertFalse(observation.inputMissing);
        assertEquals(0,RetainedGraph.measure(observation.scope).retained().nodes());
    }

    @Test void alternativeResultsOwnTheirActualMutableAssemblyList() {
        verifyComposedLists(ExprMatcher.anyOf(ExprMatcher.any(),ExprMatcher.literalVariable("x")),
            new VariableExpr("x"),2);
    }

    @Test void conjunctionResultsOwnTheirActualIntermediateLists() {
        verifyComposedLists(ExprMatcher.allOf(ExprMatcher.any(),ExprMatcher.any()),new VariableExpr("x"),1);
    }

    @Test void functionArgumentsOwnTheirActualIntermediateLists() {
        verifyComposedLists(ExprMatcher.fn("f",ExprMatcher.any(),ExprMatcher.any()),
            new ExpressionParser().parseTerm("f(x,y)"),1);
    }

    @Test void bindingResultsOwnTheirActualMutableAssemblyList() {
        verifyComposedLists(ExprMatcher.bind("A",ExprMatcher.any()),new VariableExpr("x"),1);
    }

    @Test void constraintResultsOwnTheirActualMutableAssemblyList() {
        verifyComposedLists(ExprMatcher.where(ExprMatcher.pattern(PatternExpr.var("A")),
            ExprMatcher.bindingMatches("A",ExprMatcher.any())),new VariableExpr("x"),1);
    }

    @Test void operationSidesOwnTheirActualIntermediateLists() {
        verifyComposedLists(ExprMatcher.op(ADD,ExprMatcher.any(),ExprMatcher.any()),
            new ExpressionParser().parseTerm("x+y"),1);
    }

    @Test void anAbortedOperationAssemblyCannotProduceItsFinalTrace() {
        Expr input = new ExpressionParser().parseTerm("x+y");
        var observation = new Observation(); observation.input = input; observation.abortStateList = true;
        var matcher = ExprMatcher.op(ADD,ExprMatcher.any(),ExprMatcher.any());
        try (var scope = RetainedOperation.open(observation)) {
            observation.scope = scope;
            var failure = assertThrows(MatchAbort.class,() -> matcher.match(input));
            assertSame(observation.failure,failure);
            assertFalse(observation.mutableStateLists.isEmpty());
            assertFalse(observation.sawOperationTrace,"the final trace is built only after assembly and its limit");
            assertNull(observation.outcome);
            assertTrue(observation.workAfterFailure > 0);
        }
        assertEquals(0,RetainedGraph.measure(observation.scope).retained().nodes());
    }

    @Test void anOperationLimitPreservesAlternativeOrderAndAppendsItsFinalTrace() {
        Expr input = new ExpressionParser().parseTerm("x+y");
        var matcher = ExprMatcher.op(ADD,ExprMatcher.anyOf(ExprMatcher.any(),ExprMatcher.any()),
            ExprMatcher.anyOf(ExprMatcher.any(),ExprMatcher.literalVariable("y")));
        var diagnostic = new ExprMatcher.MatchDiagnostic("MATCH_RESULT_LIMIT",matcher.canonicalDescriptor());
        var observation = new Observation(); observation.input = input;
        try (var scope = RetainedOperation.open(observation)) {
            observation.scope = scope;
            var result = matcher.match(input,new ExprMatcher.MatchOptions(null,2,100,100));
            assertEquals(2,result.matches().size());
            assertEquals(List.of("any","any","operation:ADD"),result.matches().getFirst().trace());
            assertEquals(List.of("any","literal-variable","operation:ADD"),result.matches().get(1).trace());
            assertEquals(10,result.evaluatedSteps());
            assertFalse(result.complete());
            assertEquals(List.of(diagnostic),result.diagnostics());
        }
        assertFalse(observation.inputMissing);
        assertEquals(0,RetainedGraph.measure(observation.scope).retained().nodes());
    }

    @Test void descendantResultsOwnTheirActualMutableAssemblyList() {
        verifyComposedLists(ExprMatcher.contains(ExprMatcher.any()),
            new ExpressionParser().parseTerm("f(x+y,z)"),5);
    }

    @Test void equivalentResultsOwnTheirActualMutableAssemblyList() {
        verifyComposedLists(ExprMatcher.equivalent(RecognitionProfile.exact(),ExprMatcher.any()),
            new VariableExpr("x"),1);
    }

    private enum SimplifiedRepresentatives implements EquivalentExpressionProvider, RetainedGraph.View {
        INSTANCE;
        @Override public List<Expr> representatives(Expr input,RecognitionProfile profile) {
            return List.of(input,((BinaryExpr) input).left());
        }
        @Override public void retainedReferences(RetainedGraph.Visitor visitor) { }
    }

    @Test void equivalentMatchingOwnsItsActualReturnedRepresentativeList() {
        Expr input = new ExpressionParser().parseTerm("x+0");
        var matcher = ExprMatcher.equivalent(RecognitionProfile.exact(),ExprMatcher.any());
        var options = ExprMatcher.MatchOptions.defaults().withRepresentativeProvider(SimplifiedRepresentatives.INSTANCE);
        var expected = matcher.match(input,options);
        var observation = new Observation(); observation.input = input;
        try (var scope = RetainedOperation.open(observation)) {
            observation.scope = scope;
            var result = matcher.match(input,options);
            assertEquals(expected,result);
            assertEquals(2,result.matches().size());
            assertSame(input,result.matches().getFirst().representative());
            assertSame(((BinaryExpr) input).left(),result.matches().get(1).representative());
            assertEquals(List.of("any","representative:1"),result.matches().get(1).trace());
        }
        assertTrue(observation.sawRepresentativeList,"the provider's actual list must survive nested evaluation");
        assertFalse(observation.inputMissing);
        assertEquals(0,RetainedGraph.measure(observation.scope).retained().nodes());
    }

    @Test void anAbortedDescendantAssemblyDoesNotVisitItsChildren() {
        verifyTraversalAbort(ExprMatcher.contains(ExprMatcher.any()),new ExpressionParser().parseTerm("x+y"),
            ExprMatcher.MatchOptions.defaults());
    }

    @Test void anAbortedRepresentativeAssemblyDoesNotMatchItsNextRepresentative() {
        verifyTraversalAbort(ExprMatcher.equivalent(RecognitionProfile.exact(),ExprMatcher.any()),
            new ExpressionParser().parseTerm("x+0"),
            ExprMatcher.MatchOptions.defaults().withRepresentativeProvider(SimplifiedRepresentatives.INSTANCE));
    }

    private static void verifyTraversalAbort(ExprMatcher matcher,Expr input,ExprMatcher.MatchOptions options) {
        var observation = new Observation(); observation.input = input; observation.abortStateList = true;
        try (var scope = RetainedOperation.open(observation)) {
            observation.scope = scope;
            var failure = assertThrows(MatchAbort.class,() -> matcher.match(input,options));
            assertSame(observation.failure,failure);
            assertFalse(observation.mutableStateLists.isEmpty());
            assertFalse(observation.sawLaterRepresentative);
            assertFalse(observation.sawDescendantTrace);
            assertNull(observation.outcome);
            assertTrue(observation.workAfterFailure > 0);
        }
        assertEquals(0,RetainedGraph.measure(observation.scope).retained().nodes());
    }

    @Test void aDescendantLimitPreservesPreorderPathsAndItsOriginalDiagnostic() {
        Expr input = new ExpressionParser().parseTerm("f(x+y,z)");
        var matcher = ExprMatcher.contains(ExprMatcher.any());
        var expectedDiagnostic = new ExprMatcher.MatchDiagnostic("MATCH_RESULT_LIMIT",ExprMatcher.any().canonicalDescriptor());
        var observation = new Observation(); observation.input = input;
        try (var scope = RetainedOperation.open(observation)) {
            observation.scope = scope;
            var result = matcher.match(input,new ExprMatcher.MatchOptions(null,3,100,100));
            assertEquals(List.of(List.of("any","contains@root"),List.of("any","contains@0"),
                List.of("any","contains@0.0")),result.matches().stream().map(ExprMatcher.MatchResult::trace).toList());
            assertEquals(4,result.evaluatedSteps());
            assertEquals(List.of(expectedDiagnostic),result.diagnostics());
            assertFalse(result.complete());
        }
        assertFalse(observation.inputMissing);
        assertEquals(0,RetainedGraph.measure(observation.scope).retained().nodes());
    }

    @Test void descendantPathCopiesAndRenderedBuffersOverlapTheirActualResults() {
        Expr input = new ExpressionParser().parseTerm("f(y,g(x,z))");
        var observation = new Observation(); observation.input = input;
        try (var scope = RetainedOperation.open(observation)) {
            observation.scope = scope;
            var result = ExprMatcher.contains(ExprMatcher.literalVariable("x")).match(input);
            assertEquals(List.of("literal-variable","contains@1.0"),result.matches().getFirst().trace());
        }
        assertTrue(observation.sawPathCopy,"the actual mutable and frozen [1,0] path must overlap");
        assertTrue(observation.sawPathBufferAndText,"the populated path buffer must overlap its String result");
        assertFalse(observation.inputMissing);
        assertEquals(0,RetainedGraph.measure(observation.scope).retained().nodes());
    }

    @Test void aMissingDescendantDoesNotRenderUnusedOccurrenceText() {
        Expr input = new ExpressionParser().parseTerm("f(x,y)");
        var observation = new Observation(); observation.input = input;
        try (var scope = RetainedOperation.open(observation)) {
            observation.scope = scope;
            assertFalse(ExprMatcher.contains(ExprMatcher.literalVariable("absent")).match(input).matched());
        }
        assertTrue(observation.renderedPaths.isEmpty(),"occurrence text is needed only for actual matches");
        assertFalse(observation.inputMissing);
    }

    @Test void pathRenderingKeepsMultiDigitIndicesAndNestedOrder() {
        var arguments = new ArrayList<Expr>();
        for (int i = 0; i < 12; i++) arguments.add(new VariableExpr("y"));
        arguments.add(new FunctionExpr("g",new VariableExpr("x")));
        Expr input = new FunctionExpr("f",arguments);
        var observation = new Observation(); observation.input = input;
        try (var scope = RetainedOperation.open(observation)) {
            observation.scope = scope;
            var result = ExprMatcher.contains(ExprMatcher.literalVariable("x")).match(input);
            assertEquals(1,result.matches().size());
            assertEquals(List.of("literal-variable","contains@12.0"),result.matches().getFirst().trace());
        }
        assertFalse(observation.inputMissing);
        assertEquals(0,RetainedGraph.measure(observation.scope).retained().nodes());
    }

    @Test void anAbortedOccurrenceTextCopyCannotProduceAnOutcome() {
        Expr input = new ExpressionParser().parseTerm("f(y,g(x,z))");
        var observation = new Observation(); observation.input = input; observation.abortPathBuffer = true;
        try (var scope = RetainedOperation.open(observation)) {
            observation.scope = scope;
            var failure = assertThrows(MatchAbort.class,
                () -> ExprMatcher.contains(ExprMatcher.literalVariable("x")).match(input));
            assertSame(observation.failure,failure);
            assertTrue(observation.sawPathBufferAndText);
            assertNull(observation.outcome);
            assertTrue(observation.workAfterFailure > 0);
        }
        assertEquals(0,RetainedGraph.measure(observation.scope).retained().nodes());
    }

    private static void verifyComposedLists(ExprMatcher matcher,Expr input,int matches) {
        var expected = matcher.match(input);
        var observation = new Observation(); observation.input = input;
        try (var scope = RetainedOperation.open(observation)) {
            observation.scope = scope;
            var actual = matcher.match(input);
            assertEquals(expected,actual);
            assertEquals(matches,actual.matches().size());
        }
        assertFalse(observation.mutableStateLists.isEmpty(),
            "actual mutable composition lists must be observed before only their frozen result remains");
        assertFalse(observation.inputMissing);
        assertEquals(0,RetainedGraph.measure(observation.scope).retained().nodes());
    }

    @Test void anAbortedAlternativeAssemblyDoesNotGenerateItsLaterBranch() {
        Expr input = new VariableExpr("x");
        var observation = new Observation(); observation.input = input; observation.abortStateList = true;
        var matcher = ExprMatcher.anyOf(ExprMatcher.any(),ExprMatcher.bind("B",ExprMatcher.any()));
        try (var scope = RetainedOperation.open(observation)) {
            observation.scope = scope;
            var failure = assertThrows(MatchAbort.class,() -> matcher.match(input));
            assertSame(observation.failure,failure);
            assertFalse(observation.mutableStateLists.isEmpty());
            assertFalse(observation.sawSecondBinding,"later alternatives must not run after the paid assembly abort");
            assertNull(observation.outcome);
            assertTrue(observation.workAfterFailure > 0);
        }
        assertEquals(0,RetainedGraph.measure(observation.scope).retained().nodes());
    }

    @Test void aBoundedPatternMatchDoesNotConstructUnusedDiagnosticDescriptions() {
        Expr input = new ExpressionParser().parseTerm("f(x+y,y)");
        var observation = new Observation(); observation.input = input;
        try (var scope = RetainedOperation.open(observation)) {
            observation.scope = scope;
            assertTrue(matcher().match(input).matched());
        }
        assertTrue(observation.patternDescriptions.isEmpty(),
            "a successful bounded match needs no canonical diagnostic description");
        assertFalse(observation.inputMissing);
    }

    @Test void aRealStepLimitStillProducesTheSamePaidDescription() {
        Expr input = new ExpressionParser().parseTerm("f(x+y,y)");
        var pattern = matcher(); var expected = pattern.canonicalDescriptor();
        var limited = ExprMatcher.allOf(ExprMatcher.any(),pattern);
        var observation = new Observation(); observation.input = input;
        ExprMatcher.MatchOutcome result;
        try (var scope = RetainedOperation.open(observation)) {
            observation.scope = scope;
            result = limited.match(input,new ExprMatcher.MatchOptions(null,64,2,1000));
        }
        assertFalse(result.complete()); assertFalse(result.matched()); assertEquals(2,result.evaluatedSteps());
        assertEquals(new ExprMatcher.MatchDiagnostic("MATCH_STEP_LIMIT",expected),result.diagnostics().getFirst());
        assertTrue(observation.patternDescriptions.contains(result.diagnostics().getFirst().matcherDescriptor()));
        assertFalse(observation.inputMissing);
    }

    @Test void anExhaustedSiblingDoesNotRenderAnotherStepLimitDescription() {
        Expr input = new VariableExpr("x"); var pattern = matcher();
        var expected = new ExprMatcher.MatchDiagnostic("MATCH_STEP_LIMIT",pattern.canonicalDescriptor());
        var observation = new Observation(); observation.input = input;
        try (var scope = RetainedOperation.open(observation)) {
            observation.scope = scope;
            var result = ExprMatcher.anyOf(pattern,pattern).match(input,
                new ExprMatcher.MatchOptions(null,64,1,1000));
            assertFalse(result.matched()); assertEquals(1,result.evaluatedSteps());
            assertEquals(List.of(expected),result.diagnostics());
        }
        assertEquals(1,observation.patternDescriptions.size(),
            "after the first step-limit diagnostic, exhausted siblings need no new descriptions");
    }

    @Test void aConstraintStepLimitKeepsItsOwnDescription() {
        Expr input = new VariableExpr("x");
        var constraint = ExprMatcher.bindingMatches("missing",matcher());
        var expected = new ExprMatcher.MatchDiagnostic("MATCH_STEP_LIMIT",constraint.canonicalDescriptor());
        var observation = new Observation(); observation.input = input;
        try (var scope = RetainedOperation.open(observation)) {
            observation.scope = scope;
            var result = ExprMatcher.where(ExprMatcher.any(),constraint).match(input,
                new ExprMatcher.MatchOptions(null,64,2,1000));
            assertFalse(result.matched()); assertEquals(2,result.evaluatedSteps());
            assertEquals(List.of(expected),result.diagnostics());
        }
        assertEquals(1,observation.patternDescriptions.size());
    }

    @Test void aRealResultLimitKeepsTheFirstResultAndItsExactDescription() {
        Expr input = new ExpressionParser().parseTerm("f(x+y,y)");
        var limited = ExprMatcher.anyOf(matcher(),matcher());
        var expected = new ExprMatcher.MatchDiagnostic("MATCH_RESULT_LIMIT",limited.canonicalDescriptor());
        var first = matcher().match(input).matches().getFirst();
        var observation = new Observation(); observation.input = input;
        try (var scope = RetainedOperation.open(observation)) {
            observation.scope = scope;
            var result = limited.match(input,new ExprMatcher.MatchOptions(null,1,64,1000));
            assertEquals(List.of(first),result.matches());
            assertEquals(List.of(expected),result.diagnostics());
            assertFalse(result.complete());
        }
        assertEquals(2,observation.patternDescriptions.size(),
            "the actual result-limit description contains both children, each rendered once");
        assertFalse(observation.inputMissing);
    }

    @Test void abortedOutcomePaysItsUnreturnedStepsAndNestedBranchCounters() {
        verifyFailedHandoff(false);
    }

    @Test void failedFrameCloseAlsoPaysUnreturnedCountersExactlyOnce() {
        verifyFailedHandoff(true);
    }

    private static void verifyFailedHandoff(boolean close) {
        Expr input = new ExpressionParser().parseTerm("f(x+y,y)");
        var observation = new Observation(); observation.input = input;
        observation.abortClose = close; observation.abortOutcome = !close;
        try (var scope = RetainedOperation.open(observation)) {
            observation.scope = scope;
            var failure = assertThrows(MatchAbort.class,() -> matcher().match(input));
            assertSame(observation.failure,failure);
            assertNotNull(observation.outcome);
            assertTrue(observation.outcome.patternBranches() > 2);
            long delegated = (long) observation.outcome.evaluatedSteps() + observation.outcome.patternBranches();
            assertEquals(delegated + (close ? 0 : 4),observation.workAfterFailure,
                "settle completed unreturned counters once, in addition to actual frame cleanup");
            assertFalse(observation.inputMissing);
        }
        assertEquals(0,RetainedGraph.measure(observation.scope).retained().nodes());
    }

    @Test void anOpaqueRepresentativeCaptureIsNotReportedAsMeasuredZero() {
        Expr input = new VariableExpr("x"); var calls = new AtomicInteger();
        EquivalentExpressionProvider opaque = (expression,profile) -> { calls.incrementAndGet(); return List.of(expression); };
        var options = new ExprMatcher.MatchOptions(opaque,1,10,10);
        var matcher = ExprMatcher.equivalent(RecognitionProfile.exact(),ExprMatcher.any());
        assertTrue(matcher.match(input,options).matched()); assertEquals(1,calls.get());
        var observation = new Observation(); observation.input = input;
        try (var scope = RetainedOperation.open(observation)) {
            observation.scope = scope;
            assertThrows(RetainedGraph.Unmeasured.class,() -> matcher.match(input,options));
            assertEquals(1,calls.get(),"unsupported native ownership must fail before invoking its capture");
        }
    }
}
