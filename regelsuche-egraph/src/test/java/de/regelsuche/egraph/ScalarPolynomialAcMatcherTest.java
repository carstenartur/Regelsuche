package de.regelsuche.egraph;

import de.regelsuche.ast.BinaryOperator;
import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.Expr;
import de.regelsuche.parse.ExpressionParser;
import de.regelsuche.transform.PatternExpr;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ScalarPolynomialAcMatcherTest {
    private static final ExpressionParser PARSER = new ExpressionParser();
    private static final PatternExpr X = PatternExpr.var("x");
    private static final PatternExpr Y = PatternExpr.var("y");

    @Test void finitePermutationReferenceAgreesForEverySmallOperandWordAndRepeatedBinding() {
        for (BinaryOperator operator : List.of(BinaryOperator.ADD, BinaryOperator.MUL)) {
            for (int size = 2; size <= 4; size++) {
                for (var word : words(size)) {
                  for (Expr grouping : groupings(word, operator)) {
                    var graph = new EGraph();
                    var root = graph.addExpression(grouping);
                    var atomIds = word.stream().map(value -> graph.addExpression(PARSER.parseTerm(value))).toList();
                    for (var pattern : List.of(PatternExpr.op(operator, X, X), PatternExpr.op(operator, X, Y),
                            PatternExpr.op(operator, PatternExpr.num(0), X))) {
                        var matcher = new ScalarPolynomialAcMatcher(graph);
                        var actual = matcher.match(ScalarPolynomialAcMatcher.compile(pattern), List.of(root), ScalarPolynomialAcMatcher.Limits.defaults());
                        assertEquals(ScalarPolynomialAcMatcher.Outcome.COMPLETE, actual.outcome());
                        Set<String> expected = reference(pattern, atomIds, graph);
                        Set<String> observed = new TreeSet<>();
                        actual.matches().forEach(match -> observed.add(key(match.bindings(), match.remainder())));
                        assertEquals(expected, observed, operator + " " + word + " " + pattern);
                    }
                  }
                }
            }
        }
    }

    @Test void orderedRolesAndNonCheapestClassAlternativesAreActuallyInspected() {
        var graph = new EGraph();
        var source = graph.addExpression(PARSER.parseTerm("a + b + a + a"));
        var pattern = ScalarPolynomialAcMatcher.compile(PatternExpr.op(BinaryOperator.ADD, X, X));
        assertTrue(new EGraphPatternMatcher(graph).matchInClass(pattern.pattern(), source).isEmpty());
        var match = new ScalarPolynomialAcMatcher(graph).match(pattern, List.of(source), ScalarPolynomialAcMatcher.Limits.defaults());
        assertEquals(1, match.matches().size());
        assertEquals(2, match.matches().getFirst().remainder().size());

        var doubled = graph.addExpression(PARSER.parseTerm("3*a+b"));
        // An explicit test fixture supplies an independently obvious polynomial equality.
        graph.union(source, doubled); graph.rebuild();
        assertEquals(PARSER.parseTerm("3*a+b"), graph.extract(source, node -> 1));
        assertFalse(new ScalarPolynomialAcMatcher(graph).match(pattern, List.of(doubled), ScalarPolynomialAcMatcher.Limits.defaults()).matches().isEmpty());
        var subtraction = graph.addExpression(PARSER.parseTerm("a-b"));
        var ordered = ScalarPolynomialAcMatcher.compile(PatternExpr.op(BinaryOperator.SUB, PatternExpr.variable("b"), PatternExpr.variable("a")));
        assertTrue(new ScalarPolynomialAcMatcher(graph).match(ordered, List.of(subtraction), ScalarPolynomialAcMatcher.Limits.defaults()).matches().isEmpty());
        assertThrows(IllegalArgumentException.class, () -> ScalarPolynomialAcMatcher.compile(PatternExpr.fn("matmul", X, Y)));
    }

    @Test void unsupportedGraphsAndPartiallyRetainedResultSetsCannotMasqueradeAsComplete() {
        var plan = ScalarPolynomialAcMatcher.compile(PatternExpr.op(BinaryOperator.ADD, X, Y));
        for (String source : List.of("f(a,b)", "a/b", "a^0", "a^-1")) {
            var graph = new EGraph(); var root = graph.addExpression(PARSER.parseTerm(source));
            var result = new ScalarPolynomialAcMatcher(graph).match(plan, List.of(root), ScalarPolynomialAcMatcher.Limits.defaults());
            assertEquals(ScalarPolynomialAcMatcher.Outcome.UNSUPPORTED_GRAPH, result.outcome());
            assertTrue(result.matches().isEmpty());
        }
        var graph = new EGraph(); var root = graph.addExpression(PARSER.parseTerm("a+b+c"));
        var partial = new ScalarPolynomialAcMatcher(graph).match(plan, List.of(root), new ScalarPolynomialAcMatcher.Limits(8, 2, 200_000));
        assertEquals(ScalarPolynomialAcMatcher.Outcome.BUDGET_INCONCLUSIVE, partial.outcome());
        assertEquals(2, partial.matches().size());
        assertTrue(partial.work().chargedUnits() <= partial.work().limit());
        assertEquals(partial.work().chargedUnits(), partial.work().counters().values().stream().mapToLong(Long::longValue).sum());
        assertTrue(partial.canonicalJson().contains("BUDGET_INCONCLUSIVE"));
    }

    @Test void malformedExponentRetainsAnExplicitFailurePrefix() {
        var graph = new EGraph();
        var bad = graph.add(ENode.leaf("num:malformed"));
        var variable = graph.add(ENode.leaf("var:a"));
        var root = graph.add(new ENode("op:POW", List.of(variable, bad)));
        var result = new ScalarPolynomialAcMatcher(graph).match(ScalarPolynomialAcMatcher.compile(X), List.of(root), ScalarPolynomialAcMatcher.Limits.defaults());
        assertEquals(ScalarPolynomialAcMatcher.Outcome.UNSUPPORTED_GRAPH, result.outcome());
        assertTrue(result.matches().isEmpty());
        assertTrue(result.work().chargedUnits() > 0);
    }

    @Test void queryAdmissionBoundsTheWholeGraphBeforeOrderingRoots() {
        var graph = new EGraph();
        for (int index = 0; index < 4097; index++) graph.add(ENode.leaf("var:a" + index));
        var result = new ScalarPolynomialAcMatcher(graph).match(ScalarPolynomialAcMatcher.compile(X), List.of(new EClassId(0)),
            new ScalarPolynomialAcMatcher.Limits(8, 2, 1));
        assertEquals(ScalarPolynomialAcMatcher.Outcome.BUDGET_INCONCLUSIVE, result.outcome());
        assertEquals("GRAPH_OR_ROOT_ADMISSION_LIMIT", result.detailCode());
        assertEquals(1, result.work().chargedUnits());
        assertTrue(result.matches().isEmpty());
    }

    @Test void standaloneMatcherCannotEraseAnExistingEClassAssumptionContext() {
        var graph = new EGraph();
        var root = graph.addExpression(PARSER.parseTerm("a+a"), List.of("a != 0"));
        var result = new ScalarPolynomialAcMatcher(graph).match(ScalarPolynomialAcMatcher.compile(PatternExpr.op(BinaryOperator.ADD, X, X)),
            List.of(root), ScalarPolynomialAcMatcher.Limits.defaults());
        assertEquals(ScalarPolynomialAcMatcher.Outcome.UNSUPPORTED_GRAPH, result.outcome());
        assertTrue(result.matches().isEmpty());
        assertEquals(List.of("a != 0"), graph.assumptionsFor(root).normalizedAssumptions());
    }

    private static List<Expr> groupings(List<String> atoms, BinaryOperator operator) {
        if (atoms.size() == 1) return List.of(PARSER.parseTerm(atoms.getFirst()));
        var result = new ArrayList<Expr>();
        for (int split = 1; split < atoms.size(); split++)
            for (var left : groupings(atoms.subList(0, split), operator))
                for (var right : groupings(atoms.subList(split, atoms.size()), operator)) result.add(new BinaryExpr(left, operator, right));
        return result;
    }

    @Test void budgetsAndCyclicAlternativesNeverClaimCompleteMatches() {
        var graph = new EGraph();
        var root = graph.addExpression(PARSER.parseTerm("a+a+b"));
        var plan = ScalarPolynomialAcMatcher.compile(PatternExpr.op(BinaryOperator.ADD, X, X));
        var stopped = new ScalarPolynomialAcMatcher(graph).match(plan, List.of(root), new ScalarPolynomialAcMatcher.Limits(8, 64, 0));
        assertEquals(ScalarPolynomialAcMatcher.Outcome.BUDGET_INCONCLUSIVE, stopped.outcome());
        assertTrue(stopped.matches().isEmpty());
        assertEquals(0, stopped.work().chargedUnits());
        var cyclic = graph.addExpression(PARSER.parseTerm("a+0"));
        graph.union(cyclic, graph.addExpression(PARSER.parseTerm("a"))); graph.rebuild();
        assertEquals(ScalarPolynomialAcMatcher.Outcome.CYCLIC_INCONCLUSIVE,
            new ScalarPolynomialAcMatcher(graph).match(plan, List.of(cyclic), ScalarPolynomialAcMatcher.Limits.defaults()).outcome());
    }

    private static List<List<String>> words(int size) {
        if (size == 0) return List.of(List.of());
        var result = new ArrayList<List<String>>();
        for (var prefix : words(size - 1)) for (String atom : List.of("a", "b", "0")) {
            var word = new ArrayList<>(prefix); word.add(atom); result.add(word);
        }
        return result;
    }

    /** Full permutations are deliberately only a finite test oracle, never a production matcher. */
    private static Set<String> reference(PatternExpr pattern, List<EClassId> atoms, EGraph graph) {
        Set<String> result = new TreeSet<>();
        for (var permutation : permutations(atoms)) {
            var bindings = new java.util.TreeMap<String, EClassId>();
            var operation = (PatternExpr.Operation) pattern;
            if (referenceAtom(operation.left(), permutation.get(0), bindings, graph)
                    && referenceAtom(operation.right(), permutation.get(1), bindings, graph))
                result.add(key(bindings, permutation.subList(2, permutation.size())));
        }
        return result;
    }

    private static boolean referenceAtom(PatternExpr pattern, EClassId atom, Map<String, EClassId> bindings, EGraph graph) {
        if (pattern instanceof PatternExpr.Placeholder variable) {
            var previous = bindings.putIfAbsent(variable.name(), atom);
            return previous == null || previous.equals(atom);
        }
        return graph.extract(atom, node -> 1).equals(pattern.instantiate(Map.of()));
    }

    private static List<List<EClassId>> permutations(List<EClassId> atoms) {
        if (atoms.isEmpty()) return List.of(List.of());
        var result = new ArrayList<List<EClassId>>();
        for (int index = 0; index < atoms.size(); index++) {
            var rest = new ArrayList<>(atoms); var first = rest.remove(index);
            for (var suffix : permutations(rest)) { var row = new ArrayList<EClassId>(); row.add(first); row.addAll(suffix); result.add(row); }
        }
        return result;
    }
    private static String key(Map<String, EClassId> bindings, List<EClassId> remainder) {
        return new java.util.TreeMap<>(bindings).toString() + "|" + remainder.stream().sorted().toList();
    }
}
