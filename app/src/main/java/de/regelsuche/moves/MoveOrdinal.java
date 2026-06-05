package de.regelsuche.moves;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Reproducible, countable position of a rewrite move in the search tree.
 *
 * <p>The ordinal is composed of three parts:</p>
 * <ol>
 *     <li>{@code ruleOrdinal} – the deterministic ordinal of the move kind
 *     (see {@link RewriteMoveKind#registryOrdinal()});</li>
 *     <li>{@code occurrenceOrdinal} – the deterministic position of the rewrite
 *     occurrence (e.g. which matching subterm position was rewritten);</li>
 *     <li>{@code parameterOrdinals} – the ordered indices of the move
 *     parameters after canonical sorting.</li>
 * </ol>
 *
 * <p>The same search run must produce identical {@code MoveOrdinal}s; different
 * parameters must produce different ordinals.</p>
 *
 * @param ruleOrdinal       deterministic ordinal of the move kind
 * @param occurrenceOrdinal deterministic occurrence position (>= 0)
 * @param parameterOrdinals ordered parameter ordinals (never {@code null})
 */
public record MoveOrdinal(int ruleOrdinal, int occurrenceOrdinal, List<Integer> parameterOrdinals)
        implements Comparable<MoveOrdinal> {

    /** Deterministic total order over ordinals. */
    public static final Comparator<MoveOrdinal> CANONICAL_ORDER =
            Comparator.comparingInt(MoveOrdinal::ruleOrdinal)
                    .thenComparingInt(MoveOrdinal::occurrenceOrdinal)
                    .thenComparing(MoveOrdinal::parameterOrdinalKey);

    public MoveOrdinal {
        parameterOrdinals = parameterOrdinals == null ? List.of() : List.copyOf(parameterOrdinals);
    }

    public MoveOrdinal(int ruleOrdinal, int occurrenceOrdinal) {
        this(ruleOrdinal, occurrenceOrdinal, List.of());
    }

    /**
     * Builds a reproducible ordinal from a move kind, an occurrence position and
     * the canonically sorted parameters of the move.
     *
     * @param kind              the move kind (never {@code null})
     * @param occurrenceOrdinal the deterministic occurrence position
     * @param parameters        the move parameters; sorted canonically before
     *                          their ordinals are derived
     * @return a reproducible {@link MoveOrdinal}
     */
    public static MoveOrdinal of(RewriteMoveKind kind, int occurrenceOrdinal, List<MoveParameter> parameters) {
        if (kind == null) {
            throw new IllegalArgumentException("kind must not be null");
        }
        int occurrence = Math.max(0, occurrenceOrdinal);
        List<MoveParameter> sorted = new ArrayList<>(parameters == null ? List.of() : parameters);
        sorted.sort(MoveParameter.CANONICAL_ORDER);
        List<Integer> ordinals = new ArrayList<>(sorted.size());
        for (int position = 0; position < sorted.size(); position++) {
            MoveParameter parameter = sorted.get(position);
            ordinals.add(parameter.hasIndex() ? parameter.parameterIndex() : position);
        }
        return new MoveOrdinal(kind.registryOrdinal(), occurrence, ordinals);
    }

    private String parameterOrdinalKey() {
        StringBuilder builder = new StringBuilder();
        for (int ordinal : parameterOrdinals) {
            builder.append(String.format("%08d", ordinal)).append('.');
        }
        return builder.toString();
    }

    @Override
    public int compareTo(MoveOrdinal other) {
        return CANONICAL_ORDER.compare(this, other);
    }
}
