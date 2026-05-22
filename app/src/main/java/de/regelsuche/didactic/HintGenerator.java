package de.regelsuche.didactic;

import de.regelsuche.discovery.DiscoveredTransformation;
import de.regelsuche.discovery.TransformationStep;
import de.regelsuche.explain.ExplanationService;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Graduated hint system (spec item 8).
 *
 * <p>Given a known-good {@link DiscoveredTransformation derivation} and a
 * "current expression" (the state the student is in), the generator
 * returns a sequence of hints of strictly increasing strength:</p>
 *
 * <ol>
 *   <li><b>Small hint</b> — a high-level nudge ("Bringe alle x-Terme auf
 *       eine Seite.") without revealing the operation.</li>
 *   <li><b>Strong hint</b> — names the concrete operation the next step
 *       performs ("Subtrahiere 3 auf beiden Seiten.").</li>
 *   <li><b>Full step</b> — shows the {@code before → after} of the next
 *       step.</li>
 * </ol>
 *
 * <p>The {@link PedagogyProfile} influences phrasing: a
 * {@link PedagogyProfile#VERY_DETAILED} profile produces longer
 * descriptions, {@link PedagogyProfile#CONCISE} the shortest viable one.</p>
 */
public final class HintGenerator {

    /** Strength level of a single hint, from softest to most revealing. */
    public enum Strength { SMALL, STRONG, FULL_STEP }

    /** A single hint plus the strength it represents. */
    public record Hint(Strength strength, String text) {
        public Hint {
            Objects.requireNonNull(strength, "strength");
            Objects.requireNonNull(text, "text");
        }
    }

    private final ExplanationService explanations;

    public HintGenerator() {
        this(new ExplanationService());
    }

    public HintGenerator(ExplanationService explanations) {
        this.explanations = Objects.requireNonNull(explanations, "explanations");
    }

    /**
     * Build the graduated hint sequence for the next step in
     * {@code derivation} after {@code currentExpression}. If
     * {@code currentExpression} cannot be located in the derivation, the
     * generator falls back to hints for the first step.
     */
    public List<Hint> hintsFor(DiscoveredTransformation derivation, String currentExpression) {
        return hintsFor(derivation, currentExpression, PedagogyProfile.SCHOOL);
    }

    public List<Hint> hintsFor(DiscoveredTransformation derivation,
                               String currentExpression,
                               PedagogyProfile profile) {
        Objects.requireNonNull(derivation, "derivation");
        Objects.requireNonNull(profile, "profile");
        TransformationStep next = locateNextStep(derivation, currentExpression);
        if (next == null) {
            return List.of();
        }
        return hintsForStep(next, profile);
    }

    /**
     * Same as {@link #hintsFor(DiscoveredTransformation, String, PedagogyProfile)}
     * but for an explicit step.
     */
    public List<Hint> hintsForStep(TransformationStep step, PedagogyProfile profile) {
        Objects.requireNonNull(step, "step");
        Objects.requireNonNull(profile, "profile");

        List<Hint> hints = new ArrayList<>(3);
        hints.add(new Hint(Strength.SMALL,     smallHint(step, profile)));
        hints.add(new Hint(Strength.STRONG,    strongHint(step, profile)));
        hints.add(new Hint(Strength.FULL_STEP, fullStepHint(step)));
        return hints;
    }

    // -------- hint composition --------

    private static String smallHint(TransformationStep step, PedagogyProfile profile) {
        String generic = genericGoalPhrase(step);
        if (profile == PedagogyProfile.CONCISE) {
            return generic;
        }
        return "Tipp: " + generic;
    }

    private String strongHint(TransformationStep step, PedagogyProfile profile) {
        String ruleHint = explanations.renderStep(step, ExplanationService.Form.SCHOOL);
        // The SCHOOL form is multi-line; pick the most actionable line — the
        // "Begründung:" — as the strong hint. Fall back to the whole form.
        for (String line : ruleHint.split("\\R")) {
            if (line.startsWith("Begründung: ")) {
                return profile == PedagogyProfile.VERY_DETAILED
                    ? ruleHint
                    : line.substring("Begründung: ".length());
            }
        }
        return ruleHint;
    }

    private String fullStepHint(TransformationStep step) {
        return explanations.renderStep(step, ExplanationService.Form.SHORT);
    }

    /** A short, rule-kind-driven sentence — does NOT reveal the operation. */
    private static String genericGoalPhrase(TransformationStep step) {
        return switch (step.ruleKind()) {
            case SIMPLIFY  -> "Versuche, den Ausdruck zu vereinfachen.";
            case EXPAND    -> "Multipliziere die Klammer aus.";
            case FACTOR    -> "Klammere einen gemeinsamen Faktor aus.";
            case NORMALIZE -> "Bringe den Ausdruck in eine übersichtliche Form.";
        };
    }

    private static TransformationStep locateNextStep(DiscoveredTransformation derivation,
                                                     String currentExpression) {
        List<TransformationStep> steps = derivation.steps();
        if (steps == null || steps.isEmpty()) {
            return null;
        }
        if (currentExpression == null || currentExpression.isBlank()) {
            return steps.getFirst();
        }
        String needle = currentExpression.trim();
        for (TransformationStep step : steps) {
            if (step.beforeExpression().trim().equals(needle)) {
                return step;
            }
        }
        // Fallback: if the student has progressed past a step, return the
        // step whose `before` we have not yet reached.
        for (TransformationStep step : steps) {
            if (!step.afterExpression().trim().equals(needle)) {
                return step;
            }
        }
        return steps.getFirst();
    }
}
