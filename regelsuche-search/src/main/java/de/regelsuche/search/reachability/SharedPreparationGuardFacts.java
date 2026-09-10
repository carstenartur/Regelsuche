package de.regelsuche.search.reachability;

import de.regelsuche.assumption.Assumption;
import de.regelsuche.assumption.AssumptionSignature;
import de.regelsuche.ast.Expr;
import de.regelsuche.parse.ExpressionFormatter;
import de.regelsuche.parse.ExpressionParser;
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

/** Per-evaluation cache for semantically identical applicability guard facts. */
final class SharedPreparationGuardFacts {
    static final String REVISION =
        "regelsuche.shared-preparation-guard-facts/v1";

    private final Map<Key, Fact> facts = new LinkedHashMap<>();
    private long requests;
    private long cacheHits;

    synchronized Fact evaluate(
        RewriteApplicabilitySchema schema,
        PatternMatchAnalyzer.Analysis analysis,
        AssumptionSignature available
    ) {
        Objects.requireNonNull(schema, "schema");
        Objects.requireNonNull(analysis, "analysis");
        AssumptionSignature assumptions = normalized(available);
        Map<String, String> formattedBindings = formattedBindings(analysis);
        requests++;
        Key key = key(
            schema.requiredAssumptions(),
            analysis.status(),
            formattedBindings,
            assumptions);
        Fact retained = facts.get(key);
        if (retained != null) {
            cacheHits++;
            return retained;
        }
        Fact result = compute(
            schema.requiredAssumptions(),
            analysis.matched(),
            analysis.bindings(),
            assumptions);
        facts.put(key, result);
        return result;
    }

    /**
     * Reuses the same semantic guard cache from a retained match-analysis
     * snapshot. Binding expressions are parsed only on a cache miss, so a
     * shared traversal can discard heavyweight analysis internals without
     * forcing every principal to recompute its guards.
     */
    synchronized Fact evaluateSnapshot(
        RewriteApplicabilitySchema schema,
        PatternTargetedLocalBridgeSearch.AnalysisSnapshot analysis,
        AssumptionSignature available
    ) {
        Objects.requireNonNull(schema, "schema");
        Objects.requireNonNull(analysis, "analysis");
        AssumptionSignature assumptions = normalized(available);
        Map<String, String> formattedBindings = new TreeMap<>(
            analysis.bindings());
        requests++;
        Key key = key(
            schema.requiredAssumptions(),
            analysis.status(),
            formattedBindings,
            assumptions);
        Fact retained = facts.get(key);
        if (retained != null) {
            cacheHits++;
            return retained;
        }
        Fact result = compute(
            schema.requiredAssumptions(),
            matched(analysis.status()),
            parseBindings(formattedBindings),
            assumptions);
        facts.put(key, result);
        return result;
    }

    synchronized Work work() {
        return new Work(requests, facts.size(), cacheHits);
    }

    private static Fact compute(
        List<RequiredAssumptionTemplate> templates,
        boolean matched,
        Map<String, Expr> bindings,
        AssumptionSignature available
    ) {
        if (templates.isEmpty()) {
            return Fact.satisfied("NO_REQUIRED_ASSUMPTIONS");
        }
        if (!matched) {
            return Fact.unknown(
                "REQUIRED_ASSUMPTION_BINDINGS_UNAVAILABLE");
        }
        try {
            Set<String> known = Set.copyOf(available.normalizedAssumptions());
            List<String> required = new ArrayList<>();
            for (RequiredAssumptionTemplate template : templates) {
                Assumption assumption = template.instantiate(bindings);
                String normalized = AssumptionSignature.normalizeExpression(
                    assumption.expression());
                required.add(normalized);
                if (!known.contains(normalized)) {
                    return new Fact(
                        Status.UNKNOWN,
                        "REQUIRED_ASSUMPTION_UNKNOWN",
                        required);
                }
            }
            return new Fact(
                Status.SATISFIED,
                "REQUIRED_ASSUMPTIONS_SATISFIED",
                required);
        } catch (RuntimeException exception) {
            return Fact.invalid(
                "REQUIRED_ASSUMPTION_TEMPLATE_INVALID");
        }
    }

    private static Key key(
        List<RequiredAssumptionTemplate> templates,
        PatternMatchAnalyzer.Status status,
        Map<String, String> bindings,
        AssumptionSignature available
    ) {
        List<String> templateHashes = templates.stream()
            .map(RequiredAssumptionTemplate::contentHash)
            .toList();
        return new Key(
            templateHashes,
            status,
            Map.copyOf(new TreeMap<>(bindings)),
            available.fingerprint());
    }

    private static Map<String, String> formattedBindings(
        PatternMatchAnalyzer.Analysis analysis
    ) {
        Map<String, String> bindings = new TreeMap<>();
        analysis.bindings().forEach((name, expression) ->
            bindings.put(name, ExpressionFormatter.format(expression)));
        return Map.copyOf(bindings);
    }

    private static Map<String, Expr> parseBindings(
        Map<String, String> bindings
    ) {
        if (bindings.isEmpty()) {
            return Map.of();
        }
        ExpressionParser parser = new ExpressionParser();
        Map<String, Expr> result = new TreeMap<>();
        bindings.forEach((name, expression) ->
            result.put(name, parser.parseTerm(expression)));
        return Map.copyOf(result);
    }

    private static boolean matched(PatternMatchAnalyzer.Status status) {
        return status == PatternMatchAnalyzer.Status.EXACT_MATCH
            || status == PatternMatchAnalyzer.Status.MATCH_MODULO_THEORY;
    }

    private static AssumptionSignature normalized(AssumptionSignature value) {
        AssumptionSignature checked = Objects.requireNonNull(
            value, "availableAssumptions");
        return AssumptionSignature.ofExpressions(
            checked.normalizedAssumptions());
    }

    enum Status {
        SATISFIED,
        UNKNOWN,
        INVALID
    }

    record Fact(
        Status status,
        String detailCode,
        List<String> requiredAssumptions
    ) {
        Fact {
            status = Objects.requireNonNull(status, "status");
            if (detailCode == null || detailCode.isBlank()) {
                throw new IllegalArgumentException(
                    "guard fact detailCode must not be blank");
            }
            requiredAssumptions = List.copyOf(Objects.requireNonNull(
                requiredAssumptions, "requiredAssumptions"));
        }

        static Fact satisfied(String detailCode) {
            return new Fact(Status.SATISFIED, detailCode, List.of());
        }

        static Fact unknown(String detailCode) {
            return new Fact(Status.UNKNOWN, detailCode, List.of());
        }

        static Fact invalid(String detailCode) {
            return new Fact(Status.INVALID, detailCode, List.of());
        }

        boolean satisfied() {
            return status == Status.SATISFIED;
        }
    }

    record Work(long requests, long uniqueFacts, long cacheHits) {
        Work {
            if (requests < 0 || uniqueFacts < 0 || cacheHits < 0
                    || requests != uniqueFacts + cacheHits) {
                throw new IllegalArgumentException(
                    "shared guard-fact work ledger is inconsistent");
            }
        }
    }

    private record Key(
        List<String> templateHashes,
        PatternMatchAnalyzer.Status analysisStatus,
        Map<String, String> bindings,
        String assumptionFingerprint
    ) {
        private Key {
            templateHashes = List.copyOf(Objects.requireNonNull(
                templateHashes, "templateHashes"));
            analysisStatus = Objects.requireNonNull(
                analysisStatus, "analysisStatus");
            bindings = Map.copyOf(Objects.requireNonNull(bindings, "bindings"));
            assumptionFingerprint = Objects.requireNonNull(
                assumptionFingerprint, "assumptionFingerprint");
        }
    }
}
