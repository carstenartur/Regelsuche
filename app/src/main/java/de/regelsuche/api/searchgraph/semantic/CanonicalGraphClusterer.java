package de.regelsuche.api.searchgraph.semantic;

import de.regelsuche.assumption.AssumptionContext;
import de.regelsuche.canonical.ExpressionCanonicalizer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class CanonicalGraphClusterer {

    private final ExpressionCanonicalizer canonicalizer;

    public CanonicalGraphClusterer() {
        this(new ExpressionCanonicalizer());
    }

    public CanonicalGraphClusterer(ExpressionCanonicalizer canonicalizer) {
        this.canonicalizer = Objects.requireNonNull(canonicalizer);
    }

    public List<CanonicalExpressionCluster> cluster(Collection<String> expressions, AssumptionContext assumptions) {
        List<ExpressionEvidence> evidence = (expressions == null ? List.<String>of() : expressions.stream().toList())
            .stream()
            .map(e -> new ExpressionEvidence(e, Integer.MAX_VALUE, Integer.MIN_VALUE))
            .toList();
        return clusterWithEvidence(evidence, assumptions);
    }

    List<CanonicalExpressionCluster> clusterWithEvidence(Collection<ExpressionEvidence> evidence, AssumptionContext assumptions) {
        if (evidence == null || evidence.isEmpty()) {
            return List.of();
        }
        String assumptionFingerprint = ExpressionCanonicalizer.assumptionFingerprint(assumptions);
        Map<String, List<ExpressionEvidence>> byHash = new LinkedHashMap<>();
        Map<String, String> canonicalByHash = new LinkedHashMap<>();
        for (ExpressionEvidence expression : evidence) {
            if (expression == null || expression.expression() == null || expression.expression().isBlank()) {
                continue;
            }
            String canonical = canonicalizer.canonicalize(expression.expression());
            String hash = canonicalizer.stableHash(canonical + (assumptionFingerprint.isEmpty() ? "" : "\u0001" + assumptionFingerprint));
            canonicalByHash.putIfAbsent(hash, canonical);
            byHash.computeIfAbsent(hash, k -> new ArrayList<>()).add(expression);
        }
        List<CanonicalExpressionCluster> out = new ArrayList<>(byHash.size());
        for (Map.Entry<String, List<ExpressionEvidence>> entry : byHash.entrySet()) {
            List<ExpressionEvidence> variants = entry.getValue();
            variants.sort(Comparator.comparing(ExpressionEvidence::expression));
            ExpressionEvidence representative = variants.stream().min(
                Comparator.comparingInt(ExpressionEvidence::score).reversed()
                    .thenComparingInt(v -> canonicalizer.astNodeCount(v.expression()))
                    .thenComparingInt(v -> v.expression().length())
                    .thenComparing(ExpressionEvidence::expression)
            ).orElseThrow();
            int minDepth = variants.stream().mapToInt(ExpressionEvidence::depth).min().orElse(0);
            int bestScore = variants.stream().mapToInt(ExpressionEvidence::score).max().orElse(0);
            out.add(new CanonicalExpressionCluster(
                entry.getKey(),
                canonicalByHash.getOrDefault(entry.getKey(), representative.expression()),
                representative.expression(),
                variants.stream().map(ExpressionEvidence::expression).distinct().toList(),
                minDepth == Integer.MAX_VALUE ? 0 : minDepth,
                bestScore == Integer.MIN_VALUE ? 0 : bestScore,
                assumptionFingerprint
            ));
        }
        out.sort(Comparator.comparing(CanonicalExpressionCluster::canonicalHash));
        return out;
    }

    record ExpressionEvidence(String expression, int depth, int score) {
    }
}
