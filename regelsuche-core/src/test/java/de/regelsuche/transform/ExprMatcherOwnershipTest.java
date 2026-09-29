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
        Expr representativeSource;
        ExprMatcher.MatchOutcome outcome;
        long work, workAfterFailure, retentionWork;
        MatchAbort failure;
        boolean abortOutcome, abortClose, sawSession, sawSessionStateList, inputMissing;
        boolean abortBindingCopy, sawBindingCopy, sawTraceCopy;
        boolean abortStateList, sawSecondBinding, sawOperationTrace;
        boolean sawRepresentativeList, sawLaterRepresentative, sawDescendantTrace;
        boolean sawPathCopy, sawPathBufferAndText, abortPathBuffer;
        boolean sawLimitedVisitWithDiagnostic, sawEmptyRepresentativesWithDiagnostic;
        String abortTraceOutput;
        boolean sawLiteralArguments, abortLiteralArguments, sawPatternAttempt;
        int unpublishedResultScans;
        final Set<String> patternDescriptions = Collections.newSetFromMap(new IdentityHashMap<>());
        final Set<List<?>> tracePrefixes = new HashSet<>();
        final Set<Object> states = Collections.newSetFromMap(new IdentityHashMap<>());
        final Set<Object> mutableStateLists = Collections.newSetFromMap(new IdentityHashMap<>());
        final Set<String> renderedPaths = Collections.newSetFromMap(new IdentityHashMap<>());
        final Set<String> textBeforeTrace = new HashSet<>(), traceEntries = new HashSet<>();
        final Set<String> comparisonDescriptions = Collections.newSetFromMap(new IdentityHashMap<>());
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
            boolean hasSessionStateList = false;
            boolean hasMutableBinding = false, hasFrozenBinding = false;
            boolean hasMutablePath = false, hasFrozenPath = false, hasPathBuffer = false, hasPathText = false;
            boolean hasVisit = false, hasZeroPath = false, hasEmptyRepresentativeOwner = false;
            boolean hasLimitDiagnostic = false, hasEmptyDiagnostic = false;
            var texts = new HashSet<String>(); var currentTraceEntries = new HashSet<String>();
            while (!pending.isEmpty()) {
                Object value = pending.remove(); if (!seen.add(value)) continue;
                if (value instanceof String text && text.startsWith("7:pattern")) patternDescriptions.add(text);
                if (value instanceof String text && dynamicTrace(text)) texts.add(text);
                if (value instanceof String text && (text.startsWith("4:bind") || text.startsWith("7:same-as"))) {
                    comparisonDescriptions.add(text);
                }
                sawPatternAttempt |= value instanceof EquivalenceAwarePatternMatcher.MatchAttempt;
                if ("operation:ADD".equals(value)) sawOperationTrace = true;
                if ("representative:1".equals(value)) sawLaterRepresentative = true;
                if ("contains@0".equals(value)) sawDescendantTrace = true;
                if (value instanceof String text && Set.of("root","0","1","1.0","12.0").contains(text)) {
                    renderedPaths.add(text); hasPathText |= text.equals("1.0");
                }
                if (value instanceof char[] buffer && Arrays.equals(buffer,new char[]{'1','.','0'})) hasPathBuffer = true;
                hasVisit |= value.getClass().getEnclosingClass() == ExprMatcherEngine.class
                    && value.getClass().getSimpleName().equals("ContainedVisit");
                hasEmptyRepresentativeOwner |= value instanceof Object[] array && array.length == 1
                    && array[0] instanceof ArrayList<?> list && list.isEmpty();
                sawSession |= value.getClass().getEnclosingClass() == ExprMatcherEngine.class
                    && value.getClass().getSimpleName().equals("Session");
                if (value.getClass().getEnclosingClass() == ExprMatcherEngine.class
                        && value.getClass().getSimpleName().equals("State")) states.add(value);
                if (value instanceof ExprMatcher.MatchOutcome result) outcome = result;
                if (value instanceof RetainedGraph.View view) {
                    if (value.getClass().getEnclosingClass() == ExprMatcherEngine.class
                            && value.getClass().getSimpleName().equals("Session")) {
                        var references = new ArrayList<Object>();
                        view.retainedReferences(new RetainedGraph.Visitor() {
                            @Override public void reference(Object item) { references.add(item); }
                            @Override public void requireExact(Object item,Class<?> type) { assertEquals(type,item.getClass()); }
                        });
                        hasSessionStateList |= references.stream().anyMatch(item -> item instanceof List<?> list
                            && list.stream().anyMatch(state -> state != null
                                && state.getClass().getEnclosingClass() == ExprMatcherEngine.class
                                && state.getClass().getSimpleName().equals("State")));
                    }
                    view.retainedReferences(visitor);
                }
                else if (value instanceof Object[] array) for (var item : array) visitor.reference(item);
                else if (value instanceof Collection<?> values) {
                    sawLiteralArguments |= values instanceof ArrayList<?> && !values.isEmpty()
                        && values.stream().allMatch(item -> item instanceof PatternExpr);
                    if (values instanceof List<?>) for (Object item : values) {
                        if (item instanceof String text && dynamicTrace(text)) currentTraceEntries.add(text);
                    }
                    hasZeroPath |= values.equals(List.of(0));
                    if (values instanceof LinkedHashSet<?>) {
                        hasLimitDiagnostic |= values.stream().anyMatch(item -> item instanceof ExprMatcher.MatchDiagnostic d
                            && d.code().equals("MATCH_RESULT_LIMIT"));
                        hasEmptyDiagnostic |= values.stream().anyMatch(item -> item instanceof ExprMatcher.MatchDiagnostic d
                            && d.code().equals("REPRESENTATIVE_PROVIDER_EMPTY"));
                    }
                    if (values.equals(List.of(1,0))) {
                        hasMutablePath |= values instanceof ArrayList<?>;
                        hasFrozenPath |= !(values instanceof ArrayList<?>);
                    }
                    sawRepresentativeList |= values instanceof List<?> && values.size() == 2
                        && values.stream().anyMatch(item -> item == (representativeSource == null ? input : representativeSource))
                        && values.stream().allMatch(item -> item instanceof Expr);
                    if (values instanceof List<?> list && !list.isEmpty() && "any".equals(list.getFirst())) {
                        tracePrefixes.add(List.copyOf(list));
                        sawTraceCopy |= list instanceof ArrayList<?>;
                    }
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
            sawSessionStateList |= hasSessionStateList;
            sawBindingCopy |= hasMutableBinding && hasFrozenBinding;
            sawPathCopy |= hasMutablePath && hasFrozenPath;
            sawPathBufferAndText |= hasPathBuffer && hasPathText;
            sawLimitedVisitWithDiagnostic |= hasVisit && hasZeroPath && hasLimitDiagnostic;
            sawEmptyRepresentativesWithDiagnostic |= hasEmptyRepresentativeOwner && hasEmptyDiagnostic;
            traceEntries.addAll(currentTraceEntries);
            texts.removeAll(currentTraceEntries); textBeforeTrace.addAll(texts);
            if (hasSessionStateList && outcome == null) unpublishedResultScans++;
            if (abortBindingCopy && sawBindingCopy && failure == null) {
                failure = new MatchAbort(); throw failure;
            }
            if (abortStateList && !mutableStateLists.isEmpty() && failure == null) {
                failure = new MatchAbort(); throw failure;
            }
            if (abortPathBuffer && sawPathBufferAndText && failure == null) {
                failure = new MatchAbort(); throw failure;
            }
            if (abortTraceOutput != null && textBeforeTrace.contains(abortTraceOutput) && failure == null) {
                failure = new MatchAbort(); throw failure;
            }
            if (abortLiteralArguments && sawLiteralArguments && failure == null) {
                failure = new MatchAbort(); throw failure;
            }
            if (abortOutcome && outcome != null && failure == null) {
                failure = new MatchAbort(); throw failure;
            }
        }
    }

    private static boolean dynamicTrace(String value) {
        return value.startsWith("number-property:") || value.startsWith("bind:") || value.startsWith("rebind:")
            || value.startsWith("operation:") || value.startsWith("function:") || value.startsWith("contains@")
            || value.startsWith("representative:");
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
        assertTrue(observation.sawSessionStateList,"the probe must observe the actual Session result owner");
        assertEquals(0,observation.unpublishedResultScans,
            "after Session owns its raw/frozen states, monotone result assembly avoids an intermediate full scan");
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

    @Test void aLimitedDescendantRetainsItsActualPathThroughDiagnosisPublication() {
        Expr input = new ExpressionParser().parseTerm("f(x)");
        var observation = new Observation(); observation.input = input;
        try (var scope = RetainedOperation.open(observation)) {
            observation.scope = scope;
            var result = ExprMatcher.contains(ExprMatcher.any()).match(input,new ExprMatcher.MatchOptions(null,1,100,100));
            assertEquals(1,result.matches().size());
            assertFalse(result.complete());
        }
        assertTrue(observation.sawLimitedVisitWithDiagnostic,
            "the new session diagnosis must overlap its real stopped [0] visit before release");
        assertFalse(observation.inputMissing);
        assertEquals(0,RetainedGraph.measure(observation.scope).retained().nodes());
    }

    private enum EmptyRepresentatives implements EquivalentExpressionProvider, RetainedGraph.View {
        INSTANCE;
        @Override public List<Expr> representatives(Expr input,RecognitionProfile profile) { return new ArrayList<>(); }
        @Override public void retainedReferences(RetainedGraph.Visitor visitor) { }
    }

    @Test void anEmptyRepresentativeResultSurvivesUntilItsDiagnosisIsObserved() {
        Expr input = new VariableExpr("x");
        var observation = new Observation(); observation.input = input;
        try (var scope = RetainedOperation.open(observation)) {
            observation.scope = scope;
            var result = ExprMatcher.equivalent(RecognitionProfile.exact(),ExprMatcher.any()).match(input,
                ExprMatcher.MatchOptions.defaults().withRepresentativeProvider(EmptyRepresentatives.INSTANCE));
            assertFalse(result.matched());
            assertEquals("REPRESENTATIVE_PROVIDER_EMPTY",result.diagnostics().getFirst().code());
        }
        assertTrue(observation.sawEmptyRepresentativesWithDiagnostic,
            "the session diagnosis must overlap the actual provider-result owner before release");
        assertFalse(observation.inputMissing);
        assertEquals(0,RetainedGraph.measure(observation.scope).retained().nodes());
    }

    @Test void dynamicTraceTextIsOwnedBeforeBeingAddedToAState() {
        record Case(ExprMatcher matcher,Expr input,String trace,ExprMatcher.MatchOptions options) { }
        var defaults = ExprMatcher.MatchOptions.defaults();
        Expr x = new VariableExpr("x");
        var cases = List.of(
            new Case(ExprMatcher.integerLiteral(),new NumberExpr(2),"number-property:INTEGER_LITERAL",defaults),
            new Case(ExprMatcher.bind("A",ExprMatcher.any()),x,"bind:A",defaults),
            new Case(ExprMatcher.allOf(ExprMatcher.bind("A",ExprMatcher.any()),ExprMatcher.bind("A",ExprMatcher.any())),
                x,"rebind:A",defaults),
            new Case(ExprMatcher.op(ADD,ExprMatcher.any(),ExprMatcher.any()),
                new ExpressionParser().parseTerm("x+y"),"operation:ADD",defaults),
            new Case(ExprMatcher.fn("f",ExprMatcher.any()),new FunctionExpr("f",x),"function:f",defaults),
            new Case(ExprMatcher.contains(ExprMatcher.literalVariable("x")),new FunctionExpr("f",x),"contains@0",defaults),
            new Case(ExprMatcher.equivalent(RecognitionProfile.exact(),ExprMatcher.any()),
                new ExpressionParser().parseTerm("x+0"),"representative:1",
                defaults.withRepresentativeProvider(SimplifiedRepresentatives.INSTANCE)));
        for (Case example : cases) {
            var observation = new Observation(); observation.input = example.input();
            try (var scope = RetainedOperation.open(observation)) {
                observation.scope = scope;
                assertTrue(example.matcher().match(example.input(),example.options()).matched());
            }
            assertTrue(observation.textBeforeTrace.contains(example.trace()),example.trace());
            assertFalse(observation.inputMissing);
            assertEquals(0,RetainedGraph.measure(observation.scope).retained().nodes());
        }
    }

    @Test void oneOperationReusesItsFinalTraceAcrossResultAlternatives() {
        var matcher = ExprMatcher.op(ADD,ExprMatcher.any(),ExprMatcher.anyOf(ExprMatcher.any(),ExprMatcher.any()));
        var results = matcher.match(new ExpressionParser().parseTerm("x+y")).matches();
        assertEquals(2,results.size());
        assertSame(results.getFirst().trace().getLast(),results.get(1).trace().getLast());
    }

    @Test void oneFunctionReusesItsFinalTraceAcrossResultAlternatives() {
        var results = ExprMatcher.fn("f",ExprMatcher.anyOf(ExprMatcher.any(),ExprMatcher.any()))
            .match(new FunctionExpr("f",new VariableExpr("x"))).matches();
        assertEquals(2,results.size());
        assertSame(results.getFirst().trace().getLast(),results.get(1).trace().getLast());
    }

    @Test void oneOccurrenceReusesItsTraceAcrossMatchesAtThatPath() {
        var results = ExprMatcher.contains(ExprMatcher.anyOf(ExprMatcher.any(),ExprMatcher.any()))
            .match(new VariableExpr("x")).matches();
        assertEquals(2,results.size());
        assertSame(results.getFirst().trace().getLast(),results.get(1).trace().getLast());
    }

    @Test void oneBindingReusesItsTraceAcrossResultAlternatives() {
        var results = ExprMatcher.bind("A",ExprMatcher.anyOf(ExprMatcher.any(),ExprMatcher.any()))
            .match(new VariableExpr("x")).matches();
        assertEquals(2,results.size());
        assertSame(results.getFirst().trace().getLast(),results.get(1).trace().getLast());
    }

    @Test void oneRebindingReusesItsTraceAcrossResultAlternatives() {
        var matcher = ExprMatcher.allOf(ExprMatcher.bind("A",ExprMatcher.any()),
            ExprMatcher.bind("A",ExprMatcher.anyOf(ExprMatcher.any(),ExprMatcher.any())));
        var results = matcher.match(new VariableExpr("x")).matches();
        assertEquals(2,results.size());
        assertSame(results.getFirst().trace().getLast(),results.get(1).trace().getLast());
    }

    @Test void oneRepresentativeReusesItsTraceAcrossMatches() {
        var matcher = ExprMatcher.equivalent(RecognitionProfile.exact(),ExprMatcher.anyOf(ExprMatcher.any(),ExprMatcher.any()));
        var results = matcher.match(new ExpressionParser().parseTerm("x+0"),
            ExprMatcher.MatchOptions.defaults().withRepresentativeProvider(SimplifiedRepresentatives.INSTANCE)).matches();
        assertEquals(4,results.size());
        assertSame(results.get(2).trace().getLast(),results.get(3).trace().getLast());
    }

    @Test void anAbortedTraceOutputCannotBePublishedInAState() {
        Expr input = new ExpressionParser().parseTerm("x+y");
        var observation = new Observation(); observation.input = input; observation.abortTraceOutput = "operation:ADD";
        try (var scope = RetainedOperation.open(observation)) {
            observation.scope = scope;
            var failure = assertThrows(MatchAbort.class,
                () -> ExprMatcher.op(ADD,ExprMatcher.any(),ExprMatcher.any()).match(input));
            assertSame(observation.failure,failure);
            assertFalse(observation.traceEntries.contains("operation:ADD"));
            assertNull(observation.outcome);
            assertTrue(observation.workAfterFailure > 0);
        }
        assertEquals(0,RetainedGraph.measure(observation.scope).retained().nodes());
    }

    private static ExprMatcher boundComparison(ExprMatcher.Constraint constraint) {
        return ExprMatcher.where(ExprMatcher.op(ADD,ExprMatcher.bind("A",ExprMatcher.any()),
            ExprMatcher.bind("B",ExprMatcher.any())),constraint);
    }

    @Test void aSuccessfulRebindingDoesNotRenderAnUnusedDescription() {
        Expr input = new VariableExpr("x");
        var matcher = ExprMatcher.allOf(ExprMatcher.bind("A",ExprMatcher.any()),
            ExprMatcher.bind("A",ExprMatcher.pattern(PatternExpr.var("B"))));
        var observation = new Observation(); observation.input = input;
        try (var scope = RetainedOperation.open(observation)) {
            observation.scope = scope;
            var result = matcher.match(input);
            assertTrue(result.matched()); assertTrue(result.complete());
            assertEquals(Map.of("A",input,"B",input),result.matches().getFirst().bindings());
        }
        assertTrue(observation.comparisonDescriptions.isEmpty());
        assertTrue(observation.patternDescriptions.isEmpty());
        assertFalse(observation.inputMissing);
    }

    @Test void conclusiveBindingComparisonsDoNotRenderUnusedDescriptions() {
        for (String source : List.of("x+x","x+y")) {
            Expr input = new ExpressionParser().parseTerm(source);
            var observation = new Observation(); observation.input = input;
            try (var scope = RetainedOperation.open(observation)) {
                observation.scope = scope;
                var result = boundComparison(ExprMatcher.sameAs("A","B")).match(input);
                assertEquals(source.equals("x+x"),result.matched()); assertTrue(result.complete());
                assertEquals(0,result.patternBranches(),"literal comparisons do not open commutative alternatives");
            }
            assertEquals(!source.equals("x+x"),observation.sawPatternAttempt,
                "only direct equality bypasses the existing literal matcher");
            assertTrue(observation.comparisonDescriptions.isEmpty(),source);
            assertFalse(observation.inputMissing);
            assertEquals(0,RetainedGraph.measure(observation.scope).retained().nodes());
        }
    }

    @Test void aBoundedComparisonStillReportsItsOriginalConstraintDescription() {
        Expr input = new ExpressionParser().parseTerm("(x+y)+(y+x)");
        var constraint = ExprMatcher.sameAs("A","B",RecognitionProfile.arithmeticAc());
        var expected = new ExprMatcher.MatchDiagnostic("COMMUTATIVE_BACKTRACKING_LIMIT",constraint.canonicalDescriptor());
        var observation = new Observation(); observation.input = input;
        try (var scope = RetainedOperation.open(observation)) {
            observation.scope = scope;
            var result = boundComparison(constraint).match(input,new ExprMatcher.MatchOptions(null,64,100,1));
            assertFalse(result.matched()); assertFalse(result.complete());
            assertEquals(1,result.patternBranches()); assertEquals(List.of(expected),result.diagnostics());
        }
        assertEquals(1,observation.comparisonDescriptions.size());
        assertFalse(observation.inputMissing);
    }

    @Test void comparisonOwnsTheActualRepresentativeListDuringLiteralMatching() {
        Expr input = new ExpressionParser().parseTerm("x+(x+0)");
        var profile = RecognitionProfile.exact().withRecognitionRules(Set.of("add-zero"),1);
        var observation = new Observation(); observation.input = input;
        observation.representativeSource = ((BinaryExpr) input).right();
        try (var scope = RetainedOperation.open(observation)) {
            observation.scope = scope;
            var result = boundComparison(ExprMatcher.sameAs("A","B",profile)).match(input,
                ExprMatcher.MatchOptions.defaults().withRepresentativeProvider(SimplifiedRepresentatives.INSTANCE));
            assertTrue(result.matched()); assertTrue(result.complete());
            assertEquals(ExprMatcher.RecognitionStrength.BOUNDED_REPRESENTATIVE,result.matches().getFirst().recognitionStrength());
        }
        assertTrue(observation.sawRepresentativeList);
        assertFalse(observation.inputMissing);
        assertEquals(0,RetainedGraph.measure(observation.scope).retained().nodes());
    }

    @Test void literalFunctionPreparationOwnsItsActualArgumentAssembly() {
        Expr input = new ExpressionParser().parseTerm("f(x,y)+f(y,x)");
        var observation = new Observation(); observation.input = input;
        try (var scope = RetainedOperation.open(observation)) {
            observation.scope = scope;
            var result = boundComparison(ExprMatcher.sameAs("A","B")).match(input);
            assertFalse(result.matched()); assertTrue(result.complete());
        }
        assertTrue(observation.sawLiteralArguments,"the mutable literal-argument list must be visible before freezing");
        assertFalse(observation.inputMissing);
        assertEquals(0,RetainedGraph.measure(observation.scope).retained().nodes());
    }

    @Test void anAbortedLiteralPreparationDoesNotStartThePatternComparison() {
        Expr input = new ExpressionParser().parseTerm("f(x,y)+f(y,x)");
        var observation = new Observation(); observation.input = input; observation.abortLiteralArguments = true;
        try (var scope = RetainedOperation.open(observation)) {
            observation.scope = scope;
            var failure = assertThrows(MatchAbort.class,() -> boundComparison(ExprMatcher.sameAs("A","B")).match(input));
            assertSame(observation.failure,failure);
            assertTrue(observation.sawLiteralArguments); assertFalse(observation.sawPatternAttempt);
            assertNull(observation.outcome); assertTrue(observation.workAfterFailure > 0);
        }
        assertEquals(0,RetainedGraph.measure(observation.scope).retained().nodes());
    }

    private enum NullRepresentatives implements EquivalentExpressionProvider, RetainedGraph.View {
        INSTANCE;
        @Override public List<Expr> representatives(Expr input,RecognitionProfile profile) {
            var result = new ArrayList<Expr>(); result.add(null); result.add(null); return result;
        }
        @Override public void retainedReferences(RetainedGraph.Visitor visitor) { }
    }

    @Test void repeatedComparisonDiagnosticsRenderTheirSourceOnlyOnce() {
        Expr input = new ExpressionParser().parseTerm("x+y");
        var constraint = ExprMatcher.sameAs("A","B",RecognitionProfile.exact().withRecognitionRules(Set.of("unused"),1));
        var expected = new ExprMatcher.MatchDiagnostic("REPRESENTATIVE_PROVIDER_NULL",constraint.canonicalDescriptor());
        var observation = new Observation(); observation.input = input;
        try (var scope = RetainedOperation.open(observation)) {
            observation.scope = scope;
            var result = boundComparison(constraint).match(input,
                ExprMatcher.MatchOptions.defaults().withRepresentativeProvider(NullRepresentatives.INSTANCE));
            assertFalse(result.matched()); assertEquals(List.of(expected),result.diagnostics());
        }
        assertEquals(1,observation.comparisonDescriptions.size());
        assertFalse(observation.inputMissing);
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
