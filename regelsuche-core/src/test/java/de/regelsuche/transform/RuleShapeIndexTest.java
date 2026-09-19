package de.regelsuche.transform;

import static de.regelsuche.ast.BinaryOperator.*;
import static org.junit.jupiter.api.Assertions.*;

import de.regelsuche.ast.*;
import de.regelsuche.parse.ExpressionParser;
import de.regelsuche.symbol.SymbolId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RuleShapeIndexTest {
    @Test void unrelatedInventoryNeverEntersTheMatcherAndFallbackKeepsOriginalOrder() {
        var rules = new ArrayList<RewriteRule>();
        var a = PatternExpr.var("A");
        rules.add(new PatternRewriteRule("wildcard", a, a));
        for (int i = 0; i < 1024; i++) rules.add(new PatternRewriteRule("f" + i, PatternExpr.fn("f" + i, a), a));
        var add = new PatternRewriteRule("add", PatternExpr.op(ADD, a, PatternExpr.num(0)), a);
        rules.add(add);
        var custom = new PatternRewriteRule("custom", PatternExpr.fn("absent", a), a) {
            @Override public boolean matches(Expr expression) { return expression instanceof VariableExpr; }
            @Override public Expr apply(Expr expression) { return new NumberExpr(7); }
        };
        rules.add(custom);
        var index = new RuleShapeIndex(rules);
        assertEquals(List.of(rules.getFirst(), add, custom), list(index.candidates(new BinaryExpr(new VariableExpr("x"), ADD, new NumberExpr(0)))));
        assertEquals(List.of(rules.getFirst(), rules.get(13), custom),
            list(index.candidates(new FunctionExpr("f12", List.of(new VariableExpr("x"))))));
        assertEquals(List.of(rules.getFirst(), custom),
            list(index.candidates(new FunctionExpr("f12", List.of(new VariableExpr("x"), new VariableExpr("y"))))));
    }

    @Test void everyActuallyMatchingRuleSurvivesAcrossRecognitionProfilesAndExactLeaves() {
        var a = PatternExpr.var("A");
        var patterns = List.of(a, PatternExpr.num("1/3"), new PatternExpr.LiteralVariable("x"),
            PatternExpr.op(MUL, a, a), PatternExpr.op(POW, a, PatternExpr.num(2)),
            PatternExpr.fn("f", a, a));
        var rules = new ArrayList<RewriteRule>();
        for (var profile : List.of(RecognitionProfile.exact(), RecognitionProfile.arithmeticAc(), RecognitionProfile.algebraicAc(),
                RecognitionProfile.exact().withRecognitionRules(java.util.Set.of("external"), 1))) {
            for (var pattern : patterns) rules.add(new PatternRewriteRule("rule" + rules.size(), pattern, a, profile));
        }
        var index = new RuleShapeIndex(rules);
        var parser = new ExpressionParser();
        for (String source : List.of("x", "y", "1/3", "2/3", "x*x", "x^2", "(x*y)*(y*x)", "f(x,x)", "f(x,y)", "f(x)")) {
            Expr expression = parser.parseTerm(source);
            var admitted = list(index.candidates(expression));
            for (var rule : rules) if (rule.matches(expression)) assertTrue(admitted.contains(rule), rule.id() + ":" + source);
        }
        Expr scoped = VariableExpr.scoped(new SymbolId(new UUID(0, 7), 1));
        for (var rule : rules) if (rule.matches(scoped)) assertTrue(list(index.candidates(scoped)).contains(rule));
        var number = new PatternRewriteRule("rational", PatternExpr.num("1/3"), a);
        var numeric = new RuleShapeIndex(List.of(number));
        assertEquals(List.of(number), list(numeric.candidates(NumberExpr.exact("2/6"))));
        assertTrue(list(numeric.candidates(NumberExpr.exact("2/3"))).isEmpty());
        for (var profile : List.of(RecognitionProfile.exact().withRecognitionRules(java.util.Set.of("external"), 0),
                RecognitionProfile.exact().withRecognitionRules(java.util.Set.of(), 1))) {
            var external = new PatternRewriteRule("external", PatternExpr.fn("f", a), a, profile);
            assertEquals(List.of(external), list(new RuleShapeIndex(List.of(external)).candidates(new NumberExpr(7))));
        }
    }

    @Test void indexedExecutionPreservesOrderedStringAndTypedResultsIncludingBounds() {
        var rules = new ArrayList<>(AstRewriteTransformationEngine.defaultRules());
        var a = PatternExpr.var("A");
        rules.add(new PatternRewriteRule("custom", PatternExpr.fn("absent", a), a) {
            @Override public boolean matches(Expr expression) { return expression instanceof VariableExpr; }
            @Override public Expr apply(Expr expression) { return new NumberExpr(7); }
            @Override public List<de.regelsuche.assumption.Assumption> assumptions(Expr expression) {
                return List.of(de.regelsuche.assumption.Assumption.nonZero("x"));
            }
        });
        rules.add(new PatternRewriteRule("ac", PatternExpr.op(ADD, a, PatternExpr.num(0)), a, RecognitionProfile.arithmeticAc()));
        for (int cap : List.of(1, 3, 80)) {
            var scan = new PreparedAstRewriteTransformationEngine(rules, 2, cap);
            var indexed = scan.withRuleIndex();
            var parser = new ExpressionParser();
            for (String source : List.of("x+0", "0+x", "f(x*1,y+0)", "(x+1)*(y+2)", "x*x+x*x", "(1/3+x)+0", "x^2")) {
                assertEquals(scan.transform(source), indexed.transform(source), source);
                assertEquals(scan.astTransport().generate(parser.parseTerm(source)), indexed.astTransport().generate(parser.parseTerm(source)), source);
            }
            Expr scoped = new FunctionExpr("f", List.of(new BinaryExpr(VariableExpr.scoped(new SymbolId(new UUID(0, 8), 1)), ADD, NumberExpr.exact("0"))));
            var steps = indexed.astTransport().generate(scoped);
            assertEquals(scan.astTransport().generate(scoped), steps);
            for (var step : steps) {
                assertEquals(step.target(), scan.astTransport().replay(scoped, List.of(step)));
                assertEquals(step.target(), indexed.astTransport().replay(scoped, List.of(step)));
            }
            var conditional = indexed.astTransport().generate(new VariableExpr("x")).stream()
                .filter(step -> step.rule().equals("custom")).findFirst().orElseThrow();
            var forged = new AstRewriteTransport.Step(conditional.source(), conditional.target(), conditional.rule(),
                conditional.kind(), conditional.mayIncreaseComplexity(), conditional.estimatedCostDelta(),
                conditional.equivalencePreservingByConstruction(), List.of(), conditional.packId(), conditional.license());
            assertThrows(IllegalArgumentException.class,
                () -> indexed.astTransport().replay(conditional.source(), List.of(forged)));
        }
    }

    private static List<RewriteRule> list(Iterable<RewriteRule> rules) {
        var result = new ArrayList<RewriteRule>();
        rules.forEach(result::add);
        return result;
    }
}
