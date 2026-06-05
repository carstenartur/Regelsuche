package de.regelsuche.moves.hypothesis;

import de.regelsuche.moves.RewriteMoveKind;
import java.util.Comparator;
import java.util.List;

/**
 * A mathematically derived hypothesis for a rewrite-move parameter.
 *
 * <p>Unlike a blindly enumerated candidate, a hypothesis always carries the
 * {@link HypothesisSource} it was derived from, a human-readable {@code reason}
 * and the structural {@code evidence} that justifies it.</p>
 *
 * @param moveKind       the rewrite move this parameter would feed
 * @param parameterName  the name of the parameter (e.g. {@code shift}, {@code factor})
 * @param value          the raw value (e.g. {@code +1}, {@code a + b})
 * @param canonicalValue canonicalised value used for deterministic ordering
 * @param source         the mathematical source of the hypothesis
 * @param confidence     a [0,1] confidence used only for reporting, never for ordering
 * @param reason         a short explanation of why this parameter is plausible
 * @param evidence       structural evidence (subterms, skeletons, paths)
 */
public record ParameterHypothesis(
        RewriteMoveKind moveKind,
        String parameterName,
        String value,
        String canonicalValue,
        HypothesisSource source,
        double confidence,
        String reason,
        List<String> evidence) {

    /**
     * Deterministic ordering independent of confidence: by source, then move
     * kind, then canonical value, parameter name and finally raw value. This
     * guarantees reproducible hypothesis lists across runs.
     */
    public static final Comparator<ParameterHypothesis> CANONICAL_ORDER =
            Comparator.<ParameterHypothesis>comparingInt(hypothesis -> hypothesis.source().ordinal())
                    .thenComparingInt(hypothesis -> hypothesis.moveKind().ordinal())
                    .thenComparing(ParameterHypothesis::canonicalValue)
                    .thenComparing(ParameterHypothesis::parameterName)
                    .thenComparing(ParameterHypothesis::value);

    public ParameterHypothesis {
        if (moveKind == null) {
            throw new IllegalArgumentException("moveKind must not be null");
        }
        if (source == null) {
            throw new IllegalArgumentException("source must not be null");
        }
        parameterName = parameterName == null ? "" : parameterName;
        value = value == null ? "" : value;
        canonicalValue = canonicalValue == null || canonicalValue.isBlank() ? value : canonicalValue;
        reason = reason == null ? "" : reason;
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
        if (confidence < 0.0) {
            confidence = 0.0;
        } else if (confidence > 1.0) {
            confidence = 1.0;
        }
    }

    /** @return a stable identity key used to deduplicate hypotheses. */
    public String dedupeKey() {
        return source.name() + '|' + moveKind.name() + '|' + parameterName + '|' + canonicalValue + '|' + value;
    }
}
