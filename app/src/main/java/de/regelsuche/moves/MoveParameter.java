package de.regelsuche.moves;

import java.util.Comparator;

/**
 * A single, named parameter of a {@link RewriteMove}.
 *
 * <p>Parameters are deterministically ordered through {@link #CANONICAL_ORDER},
 * which sorts by (1) {@code parameterIndex} when present, then (2) {@code kind},
 * (3) {@code canonicalValue} and finally (4) {@code value}. A negative
 * {@code parameterIndex} marks an unspecified index that sorts after all
 * explicitly indexed parameters.</p>
 *
 * @param name           human-readable parameter name (never {@code null})
 * @param kind           parameter classification (never {@code null})
 * @param value          raw textual value as observed
 * @param canonicalValue canonicalised value used for deterministic ordering
 * @param parameterIndex stable index, or {@code -1} when unspecified
 * @param source         provenance of the parameter (e.g. {@code "substitution-evidence"})
 */
public record MoveParameter(
        String name,
        MoveParameterKind kind,
        String value,
        String canonicalValue,
        int parameterIndex,
        String source) {

    /** Sentinel used when no stable parameter index is available. */
    public static final int UNSPECIFIED_INDEX = -1;

    /**
     * Deterministic ordering: indexed parameters first (ascending index), then
     * by kind, canonical value and finally raw value. Reproducible for a given
     * set of parameters.
     */
    public static final Comparator<MoveParameter> CANONICAL_ORDER =
            Comparator.comparingInt(MoveParameter::sortIndex)
                    .thenComparing(parameter -> parameter.kind().ordinal())
                    .thenComparing(MoveParameter::canonicalValue, Comparator.nullsFirst(Comparator.naturalOrder()))
                    .thenComparing(MoveParameter::value, Comparator.nullsFirst(Comparator.naturalOrder()))
                    .thenComparing(MoveParameter::name, Comparator.nullsFirst(Comparator.naturalOrder()));

    public MoveParameter {
        if (name == null) {
            throw new IllegalArgumentException("name must not be null");
        }
        if (kind == null) {
            throw new IllegalArgumentException("kind must not be null");
        }
        value = value == null ? "" : value;
        canonicalValue = canonicalValue == null || canonicalValue.isBlank() ? value : canonicalValue;
        source = source == null ? "" : source;
        if (parameterIndex < 0) {
            parameterIndex = UNSPECIFIED_INDEX;
        }
    }

    public MoveParameter(String name, MoveParameterKind kind, String value, String source) {
        this(name, kind, value, value, UNSPECIFIED_INDEX, source);
    }

    /** @return {@code true} when this parameter carries an explicit, stable index. */
    public boolean hasIndex() {
        return parameterIndex >= 0;
    }

    private int sortIndex() {
        return parameterIndex >= 0 ? parameterIndex : Integer.MAX_VALUE;
    }
}
