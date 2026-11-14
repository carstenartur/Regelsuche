package de.regelsuche.benchmark;

import com.fasterxml.jackson.databind.ObjectMapper;

import de.regelsuche.assumption.AssumptionSignature;
import de.regelsuche.parse.ExpressionFormatter;
import de.regelsuche.parse.ExpressionParser;
import de.regelsuche.scoring.ExpressionScorer;
import de.regelsuche.transform.AstRewriteTransformationEngine;
import de.regelsuche.transform.RewriteRule;
import de.regelsuche.transform.Transformation;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

final class OpenMacroPathInventory {
    record Start(String source, List<String> assumptions) {
        Start {
            assumptions = List.copyOf(assumptions);
        }
    }

    record PathChoice(
            List<String> rules,
            List<String> endpoints,
            List<String> operations,
            int endpointScore,
            String key) {}

    record Inventory(Start start, int expanded, int generated, List<PathChoice> choices) {}

    private static final ObjectMapper JSON = new ObjectMapper();
    private final AstRewriteTransformationEngine engine;
    private final Map<String, String> operation = new TreeMap<>();
    private final ExpressionScorer scorer = new ExpressionScorer();

    OpenMacroPathInventory(Map<String, List<String>> mapping) {
        new TreeMap<>(mapping)
                .forEach((op, rules) -> rules.forEach(rule -> operation.putIfAbsent(rule, op)));
        List<RewriteRule> rules =
                AstRewriteTransformationEngine.defaultRules().stream()
                        .filter(rule -> operation.containsKey(rule.id()))
                        .sorted(Comparator.comparing(RewriteRule::id))
                        .toList();
        engine = new AstRewriteTransformationEngine(rules, 16, 120);
    }

    Inventory generate(Start start) throws Exception {
        String root = ExpressionFormatter.format(new ExpressionParser().parseTerm(start.source()));
        List<Transformation> first = engine.transform(root);
        int expanded = 1, generated = first.size();
        var choices = new TreeMap<String, PathChoice>();
        for (Transformation a :
                legal(first, start.assumptions(), List.of(root)).stream().limit(12).toList()) {
            String mid = a.transformedExpression();
            var successors = engine.transform(mid);
            expanded++;
            generated += successors.size();
            for (Transformation b : legal(successors, start.assumptions(), List.of(root, mid))) {
                var ops = List.of(operation.get(a.rule()), operation.get(b.rule()));
                if (ops.getFirst().equals(ops.getLast())) continue;
                var rules = List.of(a.rule(), b.rule());
                var ends = List.of(mid, b.transformedExpression());
                String key = JSON.writeValueAsString(List.of(rules, ends));
                choices.putIfAbsent(
                        key,
                        new PathChoice(
                                rules,
                                ends,
                                ops,
                                scorer.score(ends.getLast()).weightedTotal(),
                                key));
            }
        }
        return new Inventory(start, expanded, generated, List.copyOf(choices.values()));
    }

    private static List<Transformation> legal(
            List<Transformation> all, List<String> assumptions, List<String> visited) {
        var declared = AssumptionSignature.ofExpressions(assumptions).normalizedAssumptions();
        var unique = new LinkedHashMap<List<String>, Transformation>();
        all.stream()
                .filter(Transformation::equivalencePreservingByConstruction)
                .filter(t -> !visited.contains(t.transformedExpression()))
                .filter(
                        t ->
                                declared.containsAll(
                                        AssumptionSignature.ofExpressions(t.assumptions())
                                                .normalizedAssumptions()))
                .sorted(
                        Comparator.comparing(Transformation::rule)
                                .thenComparing(Transformation::transformedExpression)
                                .thenComparing(Transformation::applicationKey))
                .forEach(t -> unique.putIfAbsent(List.of(t.rule(), t.transformedExpression()), t));
        return List.copyOf(unique.values());
    }
}
