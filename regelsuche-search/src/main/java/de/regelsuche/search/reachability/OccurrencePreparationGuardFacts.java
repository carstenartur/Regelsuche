package de.regelsuche.search.reachability;

import de.regelsuche.assumption.AssumptionSignature;
import de.regelsuche.ast.Expr;
import de.regelsuche.parse.ExpressionFormatter;
import de.regelsuche.search.reachability.SharedPreparationGuardFacts.Fact;
import de.regelsuche.search.reachability.SharedPreparationGuardFacts.Status;
import de.regelsuche.search.reachability.SharedPreparationGuardFacts.Work;
import de.regelsuche.transform.PatternMatchAnalyzer;
import de.regelsuche.transform.RequiredAssumptionTemplate;
import de.regelsuche.transform.RewriteApplicabilitySchema;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/**
 * V3 guard cache that checks every template and retains every instantiable guard.
 * The historical V2 guard evaluator and its short-circuit evidence are unchanged.
 */
final class OccurrencePreparationGuardFacts {
    private final Map<Key, Fact> facts = new LinkedHashMap<>();
    private long requests;
    private long cacheHits;

    Fact evaluate(
        RewriteApplicabilitySchema schema,
        PatternMatchAnalyzer.Analysis analysis,
        AssumptionSignature available
    ) {
        Objects.requireNonNull(schema, "schema");
        Objects.requireNonNull(analysis, "analysis");
        AssumptionSignature assumptions = AssumptionSignature.ofExpressions(
            Objects.requireNonNull(available, "availableAssumptions").normalizedAssumptions());
        Map<String, String> bindings = new TreeMap<>();
        analysis.bindings().forEach((name, expression) ->
            bindings.put(name, ExpressionFormatter.format(expression)));
        Key key = new Key(
            schema.requiredAssumptions().stream()
                .map(RequiredAssumptionTemplate::contentHash).toList(),
            analysis.status(), Map.copyOf(bindings), assumptions.fingerprint());
        requests = Math.addExact(requests, 1);
        Fact retained = facts.get(key);
        if (retained != null) {
            cacheHits = Math.addExact(cacheHits, 1);
            return retained;
        }
        Fact result = compute(schema.requiredAssumptions(), analysis, assumptions);
        facts.put(key, result);
        return result;
    }

    Work work() {
        return new Work(requests, facts.size(), cacheHits);
    }

    private static Fact compute(
        List<RequiredAssumptionTemplate> templates,
        PatternMatchAnalyzer.Analysis analysis,
        AssumptionSignature available
    ) {
        if (templates.isEmpty()) {
            return Fact.satisfied("NO_REQUIRED_ASSUMPTIONS");
        }
        if (!analysis.matched()) {
            return Fact.unknown("REQUIRED_ASSUMPTION_BINDINGS_UNAVAILABLE");
        }
        Map<String, Expr> bindings = analysis.bindings();
        List<String> required = new ArrayList<>();
        boolean invalid = false;
        for (RequiredAssumptionTemplate template : templates) {
            try {
                required.add(AssumptionSignature.normalizeExpression(
                    template.instantiate(bindings).expression()));
            } catch (RuntimeException exception) {
                // A malformed template takes precedence over unknown guards,
                // but cannot hide valid obligations that follow it.
                invalid = true;
            }
        }
        if (invalid) {
            return new Fact(Status.INVALID,
                "REQUIRED_ASSUMPTION_TEMPLATE_INVALID", required);
        }
        if (!Set.copyOf(available.normalizedAssumptions()).containsAll(required)) {
            return new Fact(Status.UNKNOWN, "REQUIRED_ASSUMPTION_UNKNOWN", required);
        }
        return new Fact(Status.SATISFIED, "REQUIRED_ASSUMPTIONS_SATISFIED", required);
    }

    private record Key(
        List<String> templateHashes,
        PatternMatchAnalyzer.Status analysisStatus,
        Map<String, String> bindings,
        String assumptionFingerprint
    ) {
    }
}
