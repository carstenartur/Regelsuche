package de.regelsuche.paths;

import de.regelsuche.discovery.DiscoveredTransformation;
import de.regelsuche.validation.CandidateProofStatus;
import de.regelsuche.search.TeachingPathScorer;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Strategies for ordering discovered transformation paths.
 *
 * <p>Used by {@code GET /api/paths?sort=score|length|teaching|proof}.</p>
 */
public final class PathSorters {

    /** Recognised sort tokens for the HTTP API. */
    public enum Mode {
        SCORE, LENGTH, TEACHING, PROOF;

        public static Mode parse(String value) {
            if (value == null || value.isBlank()) {
                return SCORE;
            }
            try {
                return Mode.valueOf(value.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ex) {
                return SCORE;
            }
        }
    }

    private final TeachingPathScorer teachingScorer;

    public PathSorters() {
        this(new TeachingPathScorer());
    }

    public PathSorters(TeachingPathScorer teachingScorer) {
        this.teachingScorer = teachingScorer;
    }

    public List<DiscoveredTransformation> sort(List<DiscoveredTransformation> input, Mode mode) {
        return input.stream().sorted(comparatorFor(mode)).toList();
    }

    public Comparator<DiscoveredTransformation> comparatorFor(Mode mode) {
        return switch (mode == null ? Mode.SCORE : mode) {
            case SCORE -> Comparator.comparingInt(DiscoveredTransformation::totalImprovement).reversed();
            case LENGTH -> Comparator.comparingInt((DiscoveredTransformation t) -> t.steps().size());
            case TEACHING -> Comparator
                .comparingDouble((DiscoveredTransformation t) -> teachingScorer.score(t))
                .reversed();
            case PROOF -> Comparator
                .comparingInt((DiscoveredTransformation t) -> proofWeight(t.validationStatus()))
                .reversed()
                .thenComparingInt(t -> -t.totalImprovement());
        };
    }

    private static int proofWeight(CandidateProofStatus status) {
        return status == null ? 0 : status.ordinal();
    }
}
