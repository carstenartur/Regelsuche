package de.regelsuche.didactic;

import de.regelsuche.equivalence.EquivalenceService;
import java.util.Objects;
import java.util.Optional;

/**
 * Validates a single student-provided intermediate step (spec item 7).
 *
 * <p>Given the {@code currentExpression} (what the student saw) and the
 * {@code studentStep} (what the student wrote), the validator reports
 * whether the step is:</p>
 *
 * <ul>
 *   <li><b>mathematically correct</b> — i.e. equivalent to the current
 *       expression — using the existing {@link EquivalenceService};</li>
 *   <li><b>didactically appropriate</b> for the configured
 *       {@link DifficultyLevel} — a deep or otherwise complex expression
 *       on a beginner level is correct but not a good step;</li>
 *   <li>or matches a known {@link MisconceptionRule} via the
 *       {@link MisconceptionDetector}.</li>
 * </ul>
 *
 * <p>The result is a small immutable {@link Result} record that callers
 * (REST endpoint, future UI, tests) can pattern-match on.</p>
 */
public final class StudentStepValidator {

    /** Outcome of a single validation. */
    public record Result(
        boolean correct,
        boolean didacticallyAppropriate,
        String message,
        Optional<MisconceptionRule> misconception
    ) {
        public Result {
            Objects.requireNonNull(message, "message");
            Objects.requireNonNull(misconception, "misconception");
        }
    }

    private final EquivalenceService equivalence;
    private final MisconceptionDetector misconceptions;
    private final DidacticCostModel costModel;

    public StudentStepValidator(EquivalenceService equivalence) {
        this(equivalence,
            new MisconceptionDetector(equivalence),
            new DidacticCostModel());
    }

    public StudentStepValidator(EquivalenceService equivalence,
                                MisconceptionDetector misconceptions,
                                DidacticCostModel costModel) {
        this.equivalence    = Objects.requireNonNull(equivalence, "equivalence");
        this.misconceptions = Objects.requireNonNull(misconceptions, "misconceptions");
        this.costModel      = Objects.requireNonNull(costModel, "costModel");
    }

    /** Convenience overload using the constructor's {@link DidacticCostModel#level()}. */
    public Result validate(String currentExpression, String studentStep) {
        return validate(currentExpression, studentStep, costModel.level());
    }

    public Result validate(String currentExpression, String studentStep, DifficultyLevel level) {
        Objects.requireNonNull(currentExpression, "currentExpression");
        Objects.requireNonNull(studentStep, "studentStep");
        Objects.requireNonNull(level, "level");

        if (studentStep.isBlank()) {
            return new Result(false, false,
                "Der Schritt ist leer.", Optional.empty());
        }

        Optional<MisconceptionRule> misconception = misconceptions.detectTermStep(
            currentExpression, studentStep);
        if (misconception.isPresent()) {
            MisconceptionRule rule = misconception.orElseThrow();
            return new Result(false, false,
                "Typischer Fehler erkannt: " + rule.typicalCause()
                    + " " + rule.correctionSuggestion(),
                misconception);
        }

        boolean correct;
        try {
            correct = equivalence.areEquivalent(currentExpression, studentStep);
        } catch (RuntimeException ex) {
            return new Result(false, false,
                "Der Schritt konnte nicht überprüft werden: " + ex.getMessage(),
                Optional.empty());
        }
        if (!correct) {
            return new Result(false, false,
                "Der Schritt ist mathematisch nicht äquivalent zur Ausgangsform.",
                Optional.empty());
        }

        boolean appropriate = isWithinDifficulty(studentStep, level);
        if (!appropriate) {
            return new Result(true, false,
                "Der Schritt ist mathematisch korrekt, aber für die gewählte "
                    + "Schwierigkeitsstufe (" + level + ") zu komplex.",
                Optional.empty());
        }
        return new Result(true, true,
            "Schritt akzeptiert.", Optional.empty());
    }

    private boolean isWithinDifficulty(String expression, DifficultyLevel level) {
        // Re-use the cost model with the requested level; if the cost
        // exceeds a reasonable budget, the step is "too complex".
        DidacticCostModel scoped = new DidacticCostModel(level, costModel.profile());
        int cost = scoped.cost(expression,
            new de.regelsuche.canonical.ExpressionCanonicalizer(),
            new de.regelsuche.scoring.ExpressionScorer().score(expression));
        return cost < complexityBudget(level);
    }

    private static int complexityBudget(DifficultyLevel level) {
        return switch (level) {
            case GRUNDSCHULE  -> 30;
            case MITTELSTUFE  -> 80;
            case OBERSTUFE    -> 200;
            case UNIVERSITAET -> 600;
            case EXPERTE      -> Integer.MAX_VALUE;
        };
    }
}
