package de.regelsuche.didactic.analytics;

import de.regelsuche.didactic.DifficultyLevel;
import de.regelsuche.didactic.HintGenerator;
import de.regelsuche.didactic.PedagogyProfile;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * One observation from the didactic layer (spec item: Didaktik-Analytics).
 *
 * <p>Events are intentionally flat and JSON-friendly. Two kinds are recorded:</p>
 * <ul>
 *   <li>{@link Kind#STEP_CHECK} — a {@code StudentStepValidator} call. The
 *       {@code correct} / {@code didacticallyAppropriate} fields capture
 *       the result; {@code misconceptionId} is set when a typical mistake
 *       was matched; {@code difficulty} is the level the student worked
 *       at.</li>
 *   <li>{@link Kind#HINT} — a {@code HintGenerator} request. {@code
 *       hintStrength} records which hint level was delivered last (or
 *       {@code null} when no step was available); {@code pedagogyProfile}
 *       carries the requested profile; {@code pathId} is the derivation
 *       the hint applies to.</li>
 * </ul>
 */
public record DidacticEvent(
    Kind kind,
    Instant timestamp,
    Optional<String> pathId,
    Optional<DifficultyLevel> difficulty,
    Optional<PedagogyProfile> pedagogyProfile,
    Optional<Boolean> correct,
    Optional<Boolean> didacticallyAppropriate,
    Optional<String> misconceptionId,
    Optional<HintGenerator.Strength> hintStrength
) {

    /** Kind of a single observation. */
    public enum Kind { STEP_CHECK, HINT }

    public DidacticEvent {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(timestamp, "timestamp");
        Objects.requireNonNull(pathId, "pathId");
        Objects.requireNonNull(difficulty, "difficulty");
        Objects.requireNonNull(pedagogyProfile, "pedagogyProfile");
        Objects.requireNonNull(correct, "correct");
        Objects.requireNonNull(didacticallyAppropriate, "didacticallyAppropriate");
        Objects.requireNonNull(misconceptionId, "misconceptionId");
        Objects.requireNonNull(hintStrength, "hintStrength");
    }

    /** Construct a STEP_CHECK event. */
    public static DidacticEvent stepCheck(
        Instant at,
        DifficultyLevel difficulty,
        boolean correct,
        boolean didacticallyAppropriate,
        String misconceptionId
    ) {
        return new DidacticEvent(
            Kind.STEP_CHECK,
            at,
            Optional.empty(),
            Optional.ofNullable(difficulty),
            Optional.empty(),
            Optional.of(correct),
            Optional.of(didacticallyAppropriate),
            Optional.ofNullable(misconceptionId),
            Optional.empty()
        );
    }

    /** Construct a HINT event. */
    public static DidacticEvent hint(
        Instant at,
        String pathId,
        PedagogyProfile profile,
        HintGenerator.Strength deliveredStrength
    ) {
        return new DidacticEvent(
            Kind.HINT,
            at,
            Optional.ofNullable(pathId),
            Optional.empty(),
            Optional.ofNullable(profile),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.ofNullable(deliveredStrength)
        );
    }
}
