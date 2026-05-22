package de.regelsuche.didactic;

import java.util.Objects;

/**
 * A typical student misconception — a transformation that "looks plausible"
 * but is mathematically wrong (spec item 4).
 *
 * <p>Each misconception carries:</p>
 * <ul>
 *   <li>{@code id} — stable identifier (used by analytics / JSON exports);</li>
 *   <li>{@code wrongRulePattern} — the pattern the student likely
 *       applied, in pseudo-code (informational, used in explanations);</li>
 *   <li>{@code typicalCause} — short German phrase describing the root
 *       cause ("Distributivität fälschlich auf Quotient angewendet");</li>
 *   <li>{@code explanation} — what is actually wrong and why;</li>
 *   <li>{@code correctionSuggestion} — what the student should do instead.</li>
 * </ul>
 *
 * <p>Misconceptions are detected by {@link MisconceptionDetector}; matching
 * is done structurally on the before/after pair of an attempted step. The
 * detector is intentionally conservative — false positives would be worse
 * than missed detections in a teaching context.</p>
 */
public record MisconceptionRule(
    String id,
    String wrongRulePattern,
    String typicalCause,
    String explanation,
    String correctionSuggestion
) {
    public MisconceptionRule {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(wrongRulePattern, "wrongRulePattern");
        Objects.requireNonNull(typicalCause, "typicalCause");
        Objects.requireNonNull(explanation, "explanation");
        Objects.requireNonNull(correctionSuggestion, "correctionSuggestion");
    }
}
