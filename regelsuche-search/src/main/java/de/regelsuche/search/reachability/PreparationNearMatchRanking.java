package de.regelsuche.search.reachability;

import de.regelsuche.transform.PatternMatchAnalyzer;
import java.util.Objects;

/**
 * Versioned characterization of the structural near-match ordering used by
 * bounded schema-directed preparation.
 *
 * <p>The rank is deliberately target-free. It depends only on the retained
 * applicability analysis: a complete match wins, then more matched pattern
 * nodes, then more retained bindings, then fewer residual obligations, then a
 * deterministic lower-bound cost over the residual kinds. Search-specific
 * tie-breakers such as AST growth, primitive path work and structural identity
 * remain outside this applicability rank.</p>
 */
public final class PreparationNearMatchRanking {
    public static final String REVISION =
        "regelsuche.preparation-near-match-ranking/v1";

    private PreparationNearMatchRanking() {
    }

    public static Rank rank(PatternMatchAnalyzer.Analysis analysis) {
        PatternMatchAnalyzer.Analysis value = Objects.requireNonNull(
            analysis, "analysis");
        return new Rank(
            value.matched(),
            value.matchedPatternNodes(),
            value.bindings().size(),
            value.residualObligations().size(),
            residualLowerBound(value));
    }

    public static int residualLowerBound(
        PatternMatchAnalyzer.Analysis analysis
    ) {
        Objects.requireNonNull(analysis, "analysis");
        return analysis.residualObligations().stream()
            .mapToInt(obligation -> residualCost(obligation.kind()))
            .sum();
    }

    public static int residualCost(PatternMatchAnalyzer.ResidualKind kind) {
        return switch (Objects.requireNonNull(kind, "kind")) {
            case LITERAL_MISMATCH -> 1;
            case BINDING_CONFLICT -> 2;
            case SHAPE_MISMATCH -> 3;
            case FUNCTION_SHAPE_MISMATCH -> 4;
        };
    }

    /** Smaller values according to {@link #compareTo(Rank)} rank first. */
    public record Rank(
        boolean matched,
        int matchedPatternNodes,
        int bindingCount,
        int residualCount,
        int residualLowerBound
    ) implements Comparable<Rank> {
        public Rank {
            if (matchedPatternNodes < 0 || bindingCount < 0
                    || residualCount < 0 || residualLowerBound < 0) {
                throw new IllegalArgumentException(
                    "near-match rank counters must be non-negative");
            }
        }

        @Override
        public int compareTo(Rank other) {
            Objects.requireNonNull(other, "other");
            int comparison = Integer.compare(
                matched ? 0 : 1,
                other.matched ? 0 : 1);
            if (comparison != 0) {
                return comparison;
            }
            comparison = Integer.compare(
                other.matchedPatternNodes,
                matchedPatternNodes);
            if (comparison != 0) {
                return comparison;
            }
            comparison = Integer.compare(other.bindingCount, bindingCount);
            if (comparison != 0) {
                return comparison;
            }
            comparison = Integer.compare(residualCount, other.residualCount);
            if (comparison != 0) {
                return comparison;
            }
            return Integer.compare(
                residualLowerBound,
                other.residualLowerBound);
        }
    }
}
