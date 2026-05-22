package de.regelsuche.didactic.analytics;

import de.regelsuche.didactic.DifficultyLevel;
import de.regelsuche.didactic.HintGenerator;
import de.regelsuche.didactic.PedagogyProfile;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Aggregates a {@link DidacticEventStore} into dashboard-friendly
 * counters (spec item: Didaktik-Analytics-Dashboard).
 *
 * <p>The aggregations are intentionally simple — counts, ratios, and
 * a few sub-histograms — so that they render directly as JSON for the
 * web UI and as plain Markdown for the teacher-mode export.</p>
 */
public final class DidacticAnalyticsService {

    /** Immutable snapshot of a single aggregation pass. */
    public record Snapshot(
        int totalEvents,
        int stepChecks,
        int hints,
        int correctSteps,
        int didacticallyAppropriateSteps,
        Map<String, Integer> misconceptionFrequency,
        Map<DifficultyLevel, Integer> stepChecksByDifficulty,
        Map<HintGenerator.Strength, Integer> hintsByStrength,
        Map<PedagogyProfile, Integer> hintsByProfile
    ) {
        public Snapshot {
            Objects.requireNonNull(misconceptionFrequency, "misconceptionFrequency");
            Objects.requireNonNull(stepChecksByDifficulty, "stepChecksByDifficulty");
            Objects.requireNonNull(hintsByStrength, "hintsByStrength");
            Objects.requireNonNull(hintsByProfile, "hintsByProfile");
            misconceptionFrequency = Map.copyOf(misconceptionFrequency);
            stepChecksByDifficulty = Map.copyOf(stepChecksByDifficulty);
            hintsByStrength        = Map.copyOf(hintsByStrength);
            hintsByProfile         = Map.copyOf(hintsByProfile);
        }

        /** Ratio of correct step checks, in [0, 1]. 0 when no step checks. */
        public double accuracy() {
            return stepChecks == 0 ? 0.0 : (double) correctSteps / stepChecks;
        }

        /** Ratio of didactically appropriate step checks, in [0, 1]. */
        public double appropriateness() {
            return stepChecks == 0 ? 0.0 : (double) didacticallyAppropriateSteps / stepChecks;
        }
    }

    private final DidacticEventStore store;

    public DidacticAnalyticsService(DidacticEventStore store) {
        this.store = Objects.requireNonNull(store, "store");
    }

    /** Compute a fresh {@link Snapshot} from the underlying event store. */
    public Snapshot snapshot() {
        List<DidacticEvent> events = store.events();
        int stepChecks = 0;
        int hints = 0;
        int correct = 0;
        int appropriate = 0;
        Map<String, Integer> misconceptions = new HashMap<>();
        Map<DifficultyLevel, Integer> stepsByDifficulty = new EnumMap<>(DifficultyLevel.class);
        Map<HintGenerator.Strength, Integer> hintsByStrength =
            new EnumMap<>(HintGenerator.Strength.class);
        Map<PedagogyProfile, Integer> hintsByProfile = new EnumMap<>(PedagogyProfile.class);

        for (DidacticEvent event : events) {
            switch (event.kind()) {
                case STEP_CHECK -> {
                    stepChecks++;
                    if (event.correct().orElse(false)) {
                        correct++;
                    }
                    if (event.didacticallyAppropriate().orElse(false)) {
                        appropriate++;
                    }
                    event.misconceptionId().ifPresent(id ->
                        misconceptions.merge(id, 1, Integer::sum));
                    event.difficulty().ifPresent(level ->
                        stepsByDifficulty.merge(level, 1, Integer::sum));
                }
                case HINT -> {
                    hints++;
                    event.hintStrength().ifPresent(strength ->
                        hintsByStrength.merge(strength, 1, Integer::sum));
                    event.pedagogyProfile().ifPresent(profile ->
                        hintsByProfile.merge(profile, 1, Integer::sum));
                }
            }
        }
        return new Snapshot(
            events.size(), stepChecks, hints, correct, appropriate,
            misconceptions, stepsByDifficulty, hintsByStrength, hintsByProfile);
    }
}
