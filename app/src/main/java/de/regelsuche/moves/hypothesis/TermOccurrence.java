package de.regelsuche.moves.hypothesis;

import java.util.Comparator;

/**
 * A single occurrence of a subterm inside an expression, enriched with the
 * structural metadata needed to reason about plausible parameters.
 *
 * @param canonicalValue  canonical (formatted) form used for matching/ordering
 * @param originalValue   the form as it appeared in the source expression
 * @param depth           distance from the root (root is {@code 0})
 * @param occurrenceCount how often {@code canonicalValue} occurs in the whole expression
 * @param parentOperator  operator of the parent ({@code +}, {@code *}, {@code ^},
 *                        {@code fn:sin}, {@code =}), or empty for the root
 * @param role            structural role within the parent
 * @param path            stable AST path from the root (e.g. {@code "0.1"})
 */
public record TermOccurrence(
        String canonicalValue,
        String originalValue,
        int depth,
        int occurrenceCount,
        String parentOperator,
        TermRole role,
        String path) {

    /** Deterministic ordering: by path, then role, then canonical value. */
    public static final Comparator<TermOccurrence> CANONICAL_ORDER =
            Comparator.comparing(TermOccurrence::path)
                    .thenComparingInt(occurrence -> occurrence.role().ordinal())
                    .thenComparing(TermOccurrence::canonicalValue)
                    .thenComparingInt(TermOccurrence::depth);

    public TermOccurrence {
        canonicalValue = canonicalValue == null ? "" : canonicalValue;
        originalValue = originalValue == null || originalValue.isBlank() ? canonicalValue : originalValue;
        parentOperator = parentOperator == null ? "" : parentOperator;
        role = role == null ? TermRole.ROOT : role;
        path = path == null ? "" : path;
        if (depth < 0) {
            depth = 0;
        }
        if (occurrenceCount < 1) {
            occurrenceCount = 1;
        }
    }

    /** @return a copy of this occurrence with the aggregated {@code occurrenceCount}. */
    TermOccurrence withOccurrenceCount(int count) {
        return new TermOccurrence(canonicalValue, originalValue, depth, count, parentOperator, role, path);
    }

    /** @return {@code true} when this occurrence is a composite term (binary/function). */
    public boolean isComposite() {
        // Composite forms contain an operator symbol or a function call.
        return canonicalValue.indexOf('(') >= 0
                || canonicalValue.indexOf(' ') >= 0;
    }
}
