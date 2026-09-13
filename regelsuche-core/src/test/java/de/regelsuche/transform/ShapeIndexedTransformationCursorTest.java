package de.regelsuche.transform;

import static de.regelsuche.ast.BinaryOperator.*;
import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import de.regelsuche.ast.Expr;
import de.regelsuche.parse.ExpressionParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ShapeIndexedTransformationCursorTest {
    private static final List<RewriteRule> NATIVE = AstRewriteTransformationEngine.allBuiltInRules().stream()
        .filter(rule -> rule.getClass() == PatternRewriteRule.class).toList();
    private static final PatternExpr A = PatternExpr.var("A");
    private static final ExpressionParser PARSER = new ExpressionParser();

    @Test void declaredNativeInventoryHasExactOrderedProductionParityWithFewerMatcherCalls() {
        var engine = new PreparedAstRewriteTransformationEngine(NATIVE, 12, 1000);
        long referenceMatches = 0, indexedMatches = 0, exclusions = 0;
        for (String source : corpus()) {
            try (var reference = engine.openCursor(source); var indexed = engine.openShapeIndexedCursor(source)) {
                var expected = drain(reference);
                assertEquals(engine.transform(source), expected, source);
                assertEquals(expected, drain(indexed), source);
                assertEquals(reference.snapshot().complete(), indexed.snapshot().complete(), source);
                referenceMatches += reference.work().units(TransformationCursor.Operation.RULE_MATCH);
                indexedMatches += indexed.work().units(TransformationCursor.Operation.RULE_MATCH);
                exclusions += indexed.indexReceipt().selections().stream().mapToLong(selection -> selection.rootExcludedRules()).sum();
            }
        }
        assertTrue(exclusions > 0);
        assertTrue(indexedMatches < referenceMatches);
    }

    @Test void exactChildPredicatesRejectWithoutMatchingAndReuseOnlyReachedFeatures() {
        var first = new PatternRewriteRule("first", PatternExpr.fn("f", PatternExpr.num(1)), A);
        var second = new PatternRewriteRule("second", PatternExpr.fn("f", PatternExpr.num(2)), A);
        try (var cursor = new PreparedAstRewriteTransformationEngine(List.of(first, second)).openShapeIndexedCursor("f(x)")) {
            assertTrue(drain(cursor).isEmpty());
            assertEquals(0, cursor.work().units(TransformationCursor.Operation.RULE_MATCH));
            assertEquals(2, cursor.snapshot().attempts().stream()
                .filter(attempt -> attempt.outcome() == TransformationCursor.AttemptOutcome.SHAPE_REJECTED).count());
            assertEquals(1, cursor.work().units(TransformationCursor.Operation.SHAPE_FEATURE_REUSE));
            assertEquals(1, cursor.work().units(TransformationCursor.Operation.FORMAT), "only historical source setup formats");
            assertTrue(cursor.snapshot().complete());
        }
    }

    @Test void everyProductionMatchAndInconclusiveOutcomeSurvivesConservativeProfileSelection() {
        List<PatternExpr> patterns = List.of(A, PatternExpr.num(6), PatternExpr.variable("x"),
            PatternExpr.op(POW, A, PatternExpr.num(2)), PatternExpr.op(MUL, PatternExpr.num(2), PatternExpr.num(3)),
            PatternExpr.op(ADD, A, PatternExpr.num(0)), PatternExpr.fn("f", PatternExpr.num(0), A),
            PatternExpr.fn("F", A), PatternExpr.op(ADD, A, A));
        List<RecognitionProfile> profiles = List.of(RecognitionProfile.exact(), RecognitionProfile.arithmeticAc(),
            RecognitionProfile.algebraicAc(), new RecognitionProfile(Set.of(ADD), Set.of()),
            RecognitionProfile.exact().withRecognitionRules(Set.of("ast_add_zero_right"), 1));
        List<String> sources = List.of("x", "X", "6", "2*3", "4*x^2", "x*x", "x^2", "0+x", "x+0",
            "f(x,0)", "f(0,x)", "f(x)", "F(x)", "x+x", "x+(x+x)");
        for (var profile : profiles) for (var pattern : patterns) for (String source : sources) {
            Expr input = PARSER.parseTerm(source);
            var full = EquivalenceAwarePatternMatcher.matchDetailed(pattern, input, new HashMap<>(), profile, 1);
            var rule = new PatternRewriteRule("profile-control", pattern, PatternExpr.num(7), profile);
            var engine = new PreparedAstRewriteTransformationEngine(List.of(rule), 12, 1000);
            try (var reference = engine.openCursor(source, 1); var indexed = engine.openShapeIndexedCursor(source, 1)) {
                assertEquals(drain(reference), drain(indexed), pattern + " / " + profile + " / " + source);
                assertEquals(reference.snapshot().complete(), indexed.snapshot().complete());
                if (full.matched() || full.inconclusive()) {
                    var rootAttempt = indexed.snapshot().attempts().stream().filter(attempt -> attempt.path().isEmpty()).findFirst().orElseThrow();
                    assertNotEquals(TransformationCursor.AttemptOutcome.SHAPE_REJECTED, rootAttempt.outcome());
                    assertEquals(full.inconclusive(), rootAttempt.outcome() == TransformationCursor.AttemptOutcome.MATCH_INCONCLUSIVE);
                }
            }
        }
    }

    @Test void firstCandidateAndCloseDoNotSelectLaterRulesOrEnterChildren() {
        var unrelated = new PatternRewriteRule("function", PatternExpr.fn("f", A), PatternExpr.num(0));
        var direct = NATIVE.stream().filter(rule -> rule.id().equals("ast_add_zero_right")).findFirst().orElseThrow();
        var laterFailure = new PatternRewriteRule("later-failure", A, PatternExpr.var("UNBOUND"));
        var cursor = new PreparedAstRewriteTransformationEngine(List.of(unrelated, direct, laterFailure))
            .openShapeIndexedCursor("(x+0)+0");
        assertEquals(0, cursor.work().units(TransformationCursor.Operation.SHAPE_INDEX_RULE_COMPILE));
        assertTrue(cursor.next(Long.MAX_VALUE).isPresent());
        cursor.close();
        var receipt = cursor.indexReceipt();
        assertEquals(1, cursor.work().units(TransformationCursor.Operation.RULE_MATCH));
        assertEquals(1, cursor.work().units(TransformationCursor.Operation.SHAPE_CANDIDATE_SELECT));
        assertEquals(1, receipt.selections().size());
        assertEquals(1, receipt.selections().getFirst().rootExcludedRules());
        assertEquals(1, cursor.snapshot().attempts().getFirst().ruleIndex());
        assertFalse(cursor.snapshot().complete());
        cursor.close();
        assertEquals(receipt, cursor.indexReceipt());
    }

    @Test void exhaustionInSelectionDoesNotStartTheRecursiveMatcher() {
        var rule = new PatternRewriteRule("selected", A, PatternExpr.num(7));
        boolean sawBoundary = false;
        for (int allowance = 1; allowance < 24; allowance++) {
            try (var cursor = new PreparedAstRewriteTransformationEngine(List.of(rule)).openShapeIndexedCursor("x")) {
                cursor.next(allowance);
                if (cursor.snapshot().attempts().stream().anyMatch(attempt ->
                        attempt.outcome() == TransformationCursor.AttemptOutcome.MATCH_NOT_STARTED)) {
                    sawBoundary = true;
                    assertEquals(0, cursor.work().units(TransformationCursor.Operation.RULE_MATCH));
                    assertEquals(0, cursor.work().units(TransformationCursor.Operation.INSTANTIATE));
                    assertEquals(TransformationCursor.Status.WORK_EXHAUSTED, cursor.snapshot().status());
                    assertFalse(cursor.snapshot().complete());
                }
            }
        }
        assertTrue(sawBoundary, "a spent selection allowance must stop before the matcher");
    }

    @Test void specializedDispatchIsRejectedAndIndexIdentityDoesNotChangeTheLegacyDefinition() {
        var engine = new PreparedAstRewriteTransformationEngine(NATIVE);
        var oldDefinition = engine.cursorDefinition(1000);
        assertNotEquals(oldDefinition, engine.shapeIndexedCursorDefinition(1000));
        assertEquals(oldDefinition, engine.cursorDefinition(1000));
        var custom = new PatternRewriteRule("override", A, A) {};
        assertThrows(IllegalArgumentException.class, () -> new PreparedAstRewriteTransformationEngine(List.of(custom)).openShapeIndexedCursor("x"));
        var specialized = AstRewriteTransformationEngine.allBuiltInRules().stream()
            .filter(rule -> rule.getClass() != PatternRewriteRule.class).findFirst().orElseThrow();
        assertThrows(IllegalArgumentException.class, () -> new PreparedAstRewriteTransformationEngine(List.of(specialized)).openShapeIndexedCursor("x"));
    }

    @Test void wildcardMergeDuplicatesGrowthAndCandidateCapsPreserveProductionOrder() {
        var direct = NATIVE.stream().filter(rule -> rule.id().equals("ast_add_zero_right")).findFirst().orElseThrow();
        var first = new PatternRewriteRule("wildcard-first", A, PatternExpr.num(1));
        var second = new PatternRewriteRule("wildcard-second", A, PatternExpr.num(2));
        var grow = new PatternRewriteRule("grow", A, PatternExpr.op(ADD, A, PatternExpr.num(1)));
        List<RewriteRule> rules = List.of(first, direct, second, direct, grow);
        for (int cap : List.of(1, 2, 1000)) for (int growth : List.of(0, 12)) {
            var engine = new PreparedAstRewriteTransformationEngine(rules, growth, cap);
            try (var reference = engine.openCursor("x+0"); var indexed = engine.openShapeIndexedCursor("x+0")) {
                assertEquals(drain(reference), drain(indexed));
                assertEquals(reference.snapshot().status(), indexed.snapshot().status());
                assertEquals(reference.work().primitiveRewrites(), indexed.work().primitiveRewrites());
                assertEquals(reference.work().units(TransformationCursor.Operation.INSTANTIATE),
                    indexed.work().units(TransformationCursor.Operation.INSTANTIATE));
            }
        }
    }

    @Test void indexDoesNotTurnMatcherLimitsOrInvalidInputIntoCompleteNegatives() {
        var source = PatternExpr.op(ADD, A, PatternExpr.var("B"));
        for (int index = 0; index < 7; index++) source = PatternExpr.op(ADD, source, PatternExpr.var("V"+index));
        var ac = new PatternRewriteRule("nine-ac-operands", source, PatternExpr.num(0), RecognitionProfile.arithmeticAc());
        var engine = new PreparedAstRewriteTransformationEngine(List.of(ac));
        try (var reference = engine.openCursor("a+b+c+d+e+f+g+h+i"); var indexed = engine.openShapeIndexedCursor("a+b+c+d+e+f+g+h+i")) {
            assertEquals(drain(reference), drain(indexed));
            assertEquals("COMMUTATIVE_OPERAND_LIMIT", indexed.snapshot().attempts().getFirst().detailCode());
            assertFalse(indexed.snapshot().complete());
        }
        try (var invalid = engine.openShapeIndexedCursor("(")) {
            assertTrue(drain(invalid).isEmpty());
            assertEquals(TransformationCursor.Status.INVALID_INPUT, invalid.snapshot().status());
            assertEquals(0, invalid.work().units(TransformationCursor.Operation.SHAPE_INDEX_RULE_COMPILE));
            assertTrue(invalid.indexReceipt().selections().isEmpty());
        }
    }

    @Test void retainedComponentReceiptBindsOnlyTheNewSelectionAndWorkContract() throws Exception {
        var engine = new PreparedAstRewriteTransformationEngine(NATIVE, 12, 1000);
        var cursor = engine.openShapeIndexedCursor("((x+0)*1)+(y*0)");
        drain(cursor); cursor.close();
        var receipt = cursor.indexReceipt();
        assertEquals(ShapeIndexedTransformationCursor.INDEX_REVISION, receipt.indexRevision());
        assertEquals(ShapeIndexedTransformationCursor.WORK_REVISION, receipt.cursor().workRevision());
        assertEquals(ShapeIndexedTransformationCursor.SELECTION_REVISION, receipt.cursor().definition().orderRevision());
        assertFalse(receipt.selections().isEmpty());
        var path = Path.of("target", "shape-index-component-control.json");
        Files.createDirectories(path.getParent());
        Files.write(path, mapper().writerWithDefaultPrettyPrinter().writeValueAsBytes(receipt));
        var reference = engine.openCursor("((x+0)*1)+(y*0)");
        drain(reference); reference.close();
        assertEquals(135, reference.work().units(TransformationCursor.Operation.RULE_MATCH));
        assertEquals(8, cursor.work().units(TransformationCursor.Operation.RULE_MATCH));
        Files.write(Path.of("target", "shape-index-reference-control.json"),
            mapper().writerWithDefaultPrettyPrinter().writeValueAsBytes(reference.snapshot()));
    }

    @Test void historicalV1CursorJsonBytesRemainIdentical() throws Exception {
        var engine = new PreparedAstRewriteTransformationEngine(NATIVE, 12, 1000);
        for (String[] row : new String[][] {
            {"((x+0)*1)+(y*0)", "8cd401bda03706f8e0bdf403891076f4fc3f32ce9539ad4bf503021df309ed88"},
            {"sin(x+0)", "20e22fd3890aed8eb21aaa43901ac74d6b05ae72e30a3619dd5a4c51d0da56f2"},
            {"x", "803d58f9410b813a4cd308774056ed6deb86ab6f10f11f7c89490bd8b73adaf8"}}) {
            var cursor = engine.openCursor(row[0]); drain(cursor); cursor.close();
            assertEquals(row[1], HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(mapper().writeValueAsBytes(cursor.snapshot()))));
        }
    }

    private static List<String> corpus() {
        var values = new ArrayList<>(List.of("((x+0)*1)+(y*0)", "sin(x+0)", "f(x*1,y+0)", "a*(b+c)",
            "(a+b)*c", "a*(b-c)", "x^2-y^2", "x*x", "(x+0)+(x+0)", "f(x,y,z)", "F(x)", "x"));
        for (String left : List.of("x", "0", "1", "x+0")) for (String right : List.of("x", "0", "1", "y+1"))
            for (String operator : List.of("+", "-", "*", "/", "^")) values.add("("+left+")"+operator+"("+right+")");
        return values;
    }
    static List<Transformation> drain(TransformationCursor cursor) {
        var result = new ArrayList<Transformation>();
        for (var next = cursor.next(Long.MAX_VALUE); next.isPresent(); next = cursor.next(Long.MAX_VALUE)) result.add(next.orElseThrow());
        return result;
    }
    private static JsonMapper mapper() {
        return JsonMapper.builder().enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).build();
    }
}
