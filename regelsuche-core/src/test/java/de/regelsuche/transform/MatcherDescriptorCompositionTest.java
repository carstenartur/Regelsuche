package de.regelsuche.transform;

import static de.regelsuche.ast.BinaryOperator.*;
import static org.junit.jupiter.api.Assertions.*;

import de.regelsuche.retention.RetainedGraph;
import de.regelsuche.retention.RetainedOperation;
import java.util.*;
import org.junit.jupiter.api.Test;

class MatcherDescriptorCompositionTest {
    private static final class DescriptionAbort extends RuntimeException { }
    private static final class Observation implements RetainedOperation.Sink {
        RetainedOperation scope;
        String earlier, later, expected;
        Set<String> rules = Set.of();
        boolean abortSiblings, abortRules, sawLater, missingEarlier, sawFailedFields, sawOutput;
        DescriptionAbort failure;
        long paidAfterFailure;

        @Override public void executionWork(long units) {
            if (failure != null) paidAfterFailure += units;
            else if (scope != null && (abortSiblings || abortRules)) {
                var snapshot = snapshot();
                if ((abortSiblings && snapshot.partialSiblings()) || (abortRules && snapshot.partialRules())) {
                    failure = new DescriptionAbort(); throw failure;
                }
            }
        }
        @Override public void validationWork(long units) { executionWork(units); }
        @Override public void retainedReferences(RetainedGraph.Visitor visitor) { visitor.reference(scope); }
        @Override public void checkpoint() {
            RetainedGraph.measure(scope);
            var snapshot = snapshot();
            sawLater |= snapshot.laterBuffer();
            missingEarlier |= snapshot.laterBuffer() && !snapshot.earlierText();
            sawFailedFields |= failure != null && (snapshot.partialSiblings() || snapshot.partialRules());
            sawOutput |= snapshot.output();
        }
        private Snapshot snapshot() {
            var seen = Collections.newSetFromMap(new IdentityHashMap<Object,Boolean>());
            var pending = new ArrayDeque<Object>();
            var visitor = new RetainedGraph.Visitor() {
                @Override public void reference(Object value) { if (value != null) pending.add(value); }
                @Override public void requireExact(Object value,Class<?> type) { assertEquals(type,value.getClass()); }
            };
            visitor.reference(scope);
            boolean earlierText = false, laterBuffer = false, partialSiblings = false, partialRules = false, output = false;
            while (!pending.isEmpty()) {
                Object value = pending.remove(); if (!seen.add(value)) continue;
                if (value instanceof String text) {
                    earlierText |= text.equals(earlier); output |= text.equals(expected);
                }
                if (value instanceof char[] buffer && later != null) laterBuffer |= Arrays.equals(buffer,later.toCharArray());
                if (value instanceof RetainedGraph.View view) view.retainedReferences(visitor);
                else if (value instanceof Object[] array) {
                    if (value instanceof String[] fields && Arrays.stream(fields).anyMatch(Objects::isNull)) {
                        partialSiblings |= fields.length > 1 && Objects.equals(fields[0],earlier) && earlier != null;
                        partialRules |= fields.length == rules.size() && Arrays.stream(fields)
                            .anyMatch(field -> field != null && rules.contains(field));
                    }
                    for (Object item : array) visitor.reference(item);
                } else if (value instanceof Collection<?> values) values.forEach(visitor::reference);
                else if (value instanceof Map<?,?> map) map.forEach((key,item) -> { visitor.reference(key); visitor.reference(item); });
            }
            return new Snapshot(earlierText,laterBuffer,partialSiblings,partialRules,output);
        }
    }
    private record Snapshot(boolean earlierText,boolean laterBuffer,boolean partialSiblings,boolean partialRules,boolean output) { }

    private static final ExprMatcher LEFT = ExprMatcher.literalVariable("left-sentinel");
    private static final ExprMatcher RIGHT = ExprMatcher.literalVariable("right-sentinel");

    @Test void operationKeepsTheFirstChildTextWhileRenderingTheSecond() {
        verifyOverlap(ExprMatcher.op(ADD,LEFT,RIGHT),LEFT.canonicalDescriptor(),RIGHT.canonicalDescriptor());
    }
    @Test void matcherListKeepsItsCompletedPrefixWhileRenderingLaterChildren() {
        verifyOverlap(ExprMatcher.allOf(LEFT,RIGHT,ExprMatcher.any()),LEFT.canonicalDescriptor(),RIGHT.canonicalDescriptor());
    }
    @Test void equivalentKeepsItsProfileWhileRenderingTheNestedMatcher() {
        var profile = RecognitionProfile.arithmeticAc().withRecognitionRules(Set.of("rule-z","rule-a"),3);
        verifyOverlap(ExprMatcher.equivalent(profile,RIGHT),profileText(profile),RIGHT.canonicalDescriptor());
    }
    private static void verifyOverlap(MatcherDescriptor.Source source,String earlier,String later) {
        var observation = new Observation(); observation.earlier = earlier; observation.later = later;
        observation.expected = source.canonicalDescriptor();
        try (var scope = RetainedOperation.open(observation)) {
            observation.scope = scope;
            assertEquals(observation.expected,source.canonicalDescriptor());
        }
        assertTrue(observation.sawLater,"observe the actual populated child buffer");
        assertFalse(observation.missingEarlier,"a completed earlier field must remain owned throughout later rendering");
        assertTrue(observation.sawOutput);
        assertEquals(0,RetainedGraph.measure(observation.scope).retained().nodes());
    }

    @Test void failedSiblingPrefixDebitDoesNotRenderTheLaterChild() {
        var observation = new Observation(); observation.abortSiblings = true;
        observation.earlier = LEFT.canonicalDescriptor(); observation.later = RIGHT.canonicalDescriptor();
        verifyAbort(ExprMatcher.allOf(LEFT,RIGHT,ExprMatcher.any()),observation);
        assertFalse(observation.sawLater);
    }
    @Test void failedProfileCollectionDebitKeepsItsActualPartialRuleArray() {
        var observation = new Observation(); observation.abortRules = true;
        observation.rules = Set.of("rule-z","rule-a","rule-β","rule-🙂");
        var profile = RecognitionProfile.exact().withRecognitionRules(observation.rules,3);
        verifyAbort(ExprMatcher.sameAs("A","B",profile),observation);
    }
    private static void verifyAbort(MatcherDescriptor.Source source,Observation observation) {
        observation.expected = source.canonicalDescriptor();
        try (var scope = RetainedOperation.open(observation)) {
            observation.scope = scope;
            var failure = assertThrows(DescriptionAbort.class,source::canonicalDescriptor);
            assertSame(observation.failure,failure);
            assertTrue(observation.sawFailedFields); assertFalse(observation.sawOutput);
            assertTrue(observation.paidAfterFailure > 0);
        }
        assertEquals(0,RetainedGraph.measure(observation.scope).retained().nodes());
    }

    @Test void everyAlgebraMemberKeepsItsHistoricalFieldOrderAndFraming() {
        var profile = RecognitionProfile.arithmeticAc();
        String p = profileText(profile), exact = profileText(RecognitionProfile.exact());
        String a = frame("any"), x = frame("literal-variable","x"), children = frame("matcher-list",a,x);
        var pattern = PatternExpr.fn("f",PatternExpr.var("A"));
        var any = ExprMatcher.any(); var variable = ExprMatcher.literalVariable("x");
        var cases = new LinkedHashMap<MatcherDescriptor.Source,String>();
        cases.put(any,a); cases.put(variable,x);
        cases.put(ExprMatcher.literalNumber("1/3"),frame("literal-number","1/3"));
        cases.put(ExprMatcher.integerLiteral(),frame("number-property","INTEGER_LITERAL"));
        cases.put(ExprMatcher.pattern(pattern,profile),frame("pattern",pattern.toString(),p));
        cases.put(ExprMatcher.bind("A",any),frame("bind","A",a,exact));
        cases.put(ExprMatcher.allOf(any,variable),frame("all-of",children));
        cases.put(ExprMatcher.anyOf(any,variable),frame("any-of",children));
        cases.put(ExprMatcher.not(any),frame("not",a));
        cases.put(ExprMatcher.op(ADD,any,variable),frame("operation","ADD",a,x));
        cases.put(ExprMatcher.fn("f",any,variable),frame("function","f",children));
        cases.put(ExprMatcher.contains(any),frame("contains",a));
        cases.put(ExprMatcher.equivalent(profile,any),frame("equivalent",p,a));
        var same = ExprMatcher.sameAs("A","B",profile);
        cases.put(ExprMatcher.where(any,same),frame("where",a,frame("same-as","A","B",p)));
        cases.put(ExprMatcher.bindingMatches("A",any),frame("binding-matches","A",a));
        cases.put(same,frame("same-as","A","B",p));
        cases.forEach((source,expected) -> assertEquals(expected,source.canonicalDescriptor(),source.getClass().getName()));
    }

    @Test void profileOrderingMatchesNaturalUtf16OrderAcrossSizesAndSharedPrefixes() {
        for (int count : new int[]{0,1,2,17,257}) {
            var names = new LinkedHashSet<String>();
            for (int index = count - 1; index >= 0; index--) names.add("same-prefix:🙂δ:" + index);
            var profile = new RecognitionProfile(Set.of(ADD,MUL),Set.of(MUL,ADD),true,names,3);
            assertEquals(frame("same-as","A","B",profileText(profile)),ExprMatcher.sameAs("A","B",profile).canonicalDescriptor());
        }
    }

    /** Independent statement of the historical framing, outside measured production. */
    private static String frame(String type,String... fields) {
        var output = new StringBuilder().append(type.length()).append(':').append(type);
        for (String field : fields) output.append(field.length()).append(':').append(field);
        return output.toString();
    }
    private static String profileText(RecognitionProfile profile) {
        return frame("recognition-profile",
            frame("associative",profile.associativeOperators().stream().map(Enum::name).sorted().toArray(String[]::new)),
            frame("commutative",profile.commutativeOperators().stream().map(Enum::name).sorted().toArray(String[]::new)),
            Boolean.toString(profile.inferAlgebraicBindings()),
            frame("recognition-rules",profile.recognitionRuleIds().stream().sorted().toArray(String[]::new)),
            Integer.toString(profile.maxEquivalenceDepth()));
    }
}
