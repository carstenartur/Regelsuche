package de.regelsuche.egraph;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.ast.Expr;
import de.regelsuche.ast.BinaryOperator;
import de.regelsuche.input.InputRequest;
import de.regelsuche.input.InputType;
import de.regelsuche.parse.ExpressionFormatter;
import de.regelsuche.parse.ExpressionParser;
import de.regelsuche.transform.AstRewriteTransformationEngine;
import de.regelsuche.transform.PatternExpr;
import java.util.List;
import org.junit.jupiter.api.Test;

class EqualitySaturationScalabilityTest {

    @Test
    void directEClassLookupSurvivesUnionAndRebuild() {
        EGraph eGraph = new EGraph();
        EClassId left = eGraph.addExpression(parse("a + b"));
        EClassId right = eGraph.addExpression(parse("b + a"));

        eGraph.union(left, right);
        eGraph.rebuild();

        EClass lhsClass = eGraph.classOrThrow(left);
        EClass rhsClass = eGraph.classOrThrow(right);
        assertEquals(eGraph.find(left), lhsClass.id());
        assertEquals(eGraph.find(right), rhsClass.id());
        assertEquals(lhsClass.id(), rhsClass.id());
    }

    @Test
    void symbolArityIndexFindsOnlyCompatibleClasses() {
        EGraph eGraph = new EGraph();
        EClassId add = eGraph.addExpression(parse("a + b"));
        EClassId mul = eGraph.addExpression(parse("a * b"));
        EClassId sin = eGraph.addExpression(parse("sin(x)"));
        eGraph.addExpression(parse("x"));

        var addHits = eGraph.classesWith(new ENodeSignature("op:ADD", 2));
        var mulHits = eGraph.classesWith(new ENodeSignature("op:MUL", 2));
        var sinHits = eGraph.classesWith(new ENodeSignature("fn:sin", 1));

        assertTrue(addHits.contains(eGraph.find(add)));
        assertTrue(!addHits.contains(eGraph.find(mul)));
        assertTrue(mulHits.contains(eGraph.find(mul)));
        assertTrue(!mulHits.contains(eGraph.find(add)));
        assertTrue(sinHits.contains(eGraph.find(sin)));
    }

    @Test
    void patternMatcherDoesNotScanUnrelatedSymbols() {
        EGraph eGraph = new EGraph();
        eGraph.addExpression(parse("a + b"));
        eGraph.addExpression(parse("a * b"));
        eGraph.addExpression(parse("sin(x)"));
        eGraph.addExpression(parse("cos(x)"));
        eGraph.addExpression(parse("x + 0"));
        EGraphPatternMatcher matcher = new EGraphPatternMatcher(eGraph);

        PatternExpr addPattern = PatternExpr.op(
            BinaryOperator.ADD,
            PatternExpr.var("L"),
            PatternExpr.var("R")
        );
        matcher.matchAll("add-root", addPattern, null);
        EGraphPatternMatcher.MatcherStats stats = matcher.stats();

        assertTrue(stats.candidateClassesSkipped() > 0);
        assertTrue(stats.classesScanned() < eGraph.classCount());
    }

    @Test
    void matchMemoizationInvalidatesAfterEGraphChange() {
        EGraph eGraph = new EGraph();
        eGraph.addExpression(parse("a + b"));
        EGraphPatternMatcher matcher = new EGraphPatternMatcher(eGraph);
        PatternExpr addPattern = PatternExpr.op(
            BinaryOperator.ADD,
            PatternExpr.var("L"),
            PatternExpr.var("R")
        );

        matcher.matchAll("memo-test", addPattern, null);
        long missesAfterFirst = matcher.stats().matcherCacheMisses();
        matcher.matchAll("memo-test", addPattern, null);
        long hitsAfterSecond = matcher.stats().matcherCacheHits();
        long missesAfterSecond = matcher.stats().matcherCacheMisses();

        eGraph.addExpression(parse("c + d"));
        matcher.matchAll("memo-test", addPattern, null);
        long missesAfterMutation = matcher.stats().matcherCacheMisses();

        assertTrue(missesAfterFirst > 0);
        assertTrue(hitsAfterSecond > 0);
        assertEquals(missesAfterFirst, missesAfterSecond);
        assertTrue(missesAfterMutation > missesAfterSecond);
    }

    @Test
    void worklistSaturationKeepsSameResultAsFullScan() {
        SaturationPair pair = saturateBothWays("( x + 3 ) ^ 2");

        assertEquals(
            pair.fullScanBest(),
            pair.worklistBest()
        );
        assertEquals(pair.fullScan().stats().appliedRules(), pair.worklist().stats().appliedRules());
        assertTrue(pair.worklist().stats().classesScanned() <= pair.fullScan().stats().classesScanned());
    }

    @Test
    void worklistSaturationMatchesFullScanAcrossExpressionCorpus() {
        List<String> corpus = List.of(
            "x + 0",
            "0 + x",
            "(x + 0) * 1",
            "((x + 0) + 0)",
            "a + b",
            "b + a",
            "(a + b) + c",
            "a + (b + c)",
            "(a + b) * c",
            "c * (a + b)",
            "(x + 3) ^ 2",
            "((x + 1) * (x + 2)) + (x * (x + 3))",
            "sin(0)",
            "sin(x + 0)",
            "log(x * 1)"
        );

        for (String expression : corpus) {
            SaturationPair pair = saturateBothWays(expression);
            assertEquals(pair.fullScanBest(), pair.worklistBest(),
                "dirty worklist and full scan must extract the same best expression for " + expression);
            assertEquals(pair.fullScan().stats().totalApplications(), pair.worklist().stats().totalApplications(),
                "dirty worklist and full scan must fire the same total number of rules for " + expression);
        }
    }

    private static SaturationPair saturateBothWays(String expression) {
        Expr root = parse(expression);
        EGraph worklistGraph = new EGraph();
        EClassId worklistRoot = worklistGraph.addExpression(root);
        EqualitySaturation worklist = new EqualitySaturation(
            AstRewriteTransformationEngine.defaultRules(),
            new EqualitySaturation.Config(12, 10_000, true)
        );
        EqualitySaturation.Result worklistResult = worklist.saturate(
            worklistGraph,
            worklistRoot,
            node -> node.isLeaf() ? 0 : 1
        );

        EGraph fullScanGraph = new EGraph();
        EClassId fullScanRoot = fullScanGraph.addExpression(root);
        EqualitySaturation fullScan = new EqualitySaturation(
            AstRewriteTransformationEngine.defaultRules(),
            new EqualitySaturation.Config(12, 10_000, false)
        );
        EqualitySaturation.Result fullScanResult = fullScan.saturate(
            fullScanGraph,
            fullScanRoot,
            node -> node.isLeaf() ? 0 : 1
        );

        return new SaturationPair(
            worklistResult,
            fullScanResult,
            ExpressionFormatter.format(worklistResult.expression()),
            ExpressionFormatter.format(fullScanResult.expression())
        );
    }

    private record SaturationPair(
        EqualitySaturation.Result worklist,
        EqualitySaturation.Result fullScan,
        String worklistBest,
        String fullScanBest
    ) {
    }

    private static Expr parse(String input) {
        return new ExpressionParser()
            .parse(new InputRequest(InputType.TERM, input))
            .terms()
            .getFirst();
    }
}
