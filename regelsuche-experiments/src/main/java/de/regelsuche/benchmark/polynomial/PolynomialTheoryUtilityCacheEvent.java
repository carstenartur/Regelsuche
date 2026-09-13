package de.regelsuche.benchmark.polynomial;

import de.regelsuche.benchmark.polynomial.PolynomialTheoryUtilityCandidateResult.TerminalStatus;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.regex.Pattern;

/** One ordered cache operation retained for a polynomial utility result. */
public record PolynomialTheoryUtilityCacheEvent(
    String eventId,
    int eventIndex,
    String executionInputId,
    String transitionId,
    Kind kind,
    String cacheRevision,
    String entryId,
    String evidenceHash,
    TerminalStatus replayOutcome
) {
    public PolynomialTheoryUtilityCacheEvent(String eventId, int eventIndex, String executionInputId,
            String transitionId, Kind kind, String cacheRevision, String entryId, String evidenceHash) {
        this(eventId, eventIndex, executionInputId, transitionId, kind, cacheRevision, entryId, evidenceHash, null);
    }

    public static PolynomialTheoryUtilityCacheEvent createReplayAttempt(int eventIndex, String executionInputId,
            String transitionId, String cacheRevision, String entryId, String evidenceHash,
            TerminalStatus outcome) {
        Objects.requireNonNull(outcome, "outcome");
        return new PolynomialTheoryUtilityCacheEvent(identity(eventIndex, executionInputId, transitionId,
            Kind.REPLAY, cacheRevision, entryId, evidenceHash, outcome), eventIndex, executionInputId,
            transitionId, Kind.REPLAY, cacheRevision, entryId, evidenceHash, outcome);
    }

    public static final String SCHEMA =
        "regelsuche.polynomial-theory-utility-cache-event/v1";
    public static final String ATTEMPT_SCHEMA =
        "regelsuche.polynomial-theory-utility-cache-event/v2";
    public static final String NO_TRANSITION = "NONE";
    private static final Pattern SHA_256 =
        Pattern.compile("sha256:[0-9a-f]{64}");

    public PolynomialTheoryUtilityCacheEvent {
        eventId = requireHash(eventId, "eventId");
        if (eventIndex < 0) {
            throw new IllegalArgumentException(
                "eventIndex must be non-negative"
            );
        }
        executionInputId = requireHash(
            executionInputId,
            "executionInputId"
        );
        transitionId = requireOptionalHash(
            transitionId,
            "transitionId"
        );
        kind = Objects.requireNonNull(kind, "kind");
        cacheRevision = requireText(
            cacheRevision,
            "cacheRevision"
        );
        entryId = requireHash(entryId, "entryId");
        evidenceHash = requireHash(
            evidenceHash,
            "evidenceHash"
        );
        if (replayOutcome != null && (kind != Kind.REPLAY || replayOutcome == TerminalStatus.MIXED_OUTCOMES
                || ((replayOutcome == TerminalStatus.VALIDATED_TRANSITION) == NO_TRANSITION.equals(transitionId)))) {
            throw new IllegalArgumentException("replay attempt outcome and accepted transition disagree");
        }
        if (!eventId.equals(identity(
                eventIndex,
                executionInputId,
                transitionId,
                kind,
                cacheRevision,
                entryId,
                evidenceHash, replayOutcome))) {
            throw new IllegalArgumentException(
                "cache event identity differs from its fields"
            );
        }
    }

    public static PolynomialTheoryUtilityCacheEvent create(
        int eventIndex,
        String executionInputId,
        String transitionId,
        Kind kind,
        String cacheRevision,
        String entryId,
        String evidenceHash
    ) {
        return new PolynomialTheoryUtilityCacheEvent(
            identity(
                eventIndex,
                executionInputId,
                transitionId,
                kind,
                cacheRevision,
                entryId,
                evidenceHash
            ),
            eventIndex,
            executionInputId,
            transitionId,
            kind,
            cacheRevision,
            entryId,
            evidenceHash
        );
    }

    public String schema() {
        return replayOutcome == null ? SCHEMA : ATTEMPT_SCHEMA;
    }

    public boolean lookup() {
        return kind == Kind.LOOKUP_HIT
            || kind == Kind.LOOKUP_MISS;
    }

    public boolean transitionBound() {
        return !NO_TRANSITION.equals(transitionId);
    }

    public void validateAgainst(
        int expectedIndex,
        PolynomialTheoryUtilityCandidateResult result,
        PolynomialTheoryUtilityExecutionProfile profile
    ) {
        Objects.requireNonNull(result, "result");
        Objects.requireNonNull(profile, "profile");
        var frozenProfile = PolynomialTheoryUtilityExecutionInputs.profile(
            result.input().profileId()
        );
        if (eventIndex != expectedIndex
                || !profile.equals(frozenProfile)
                || !executionInputId.equals(
                    result.input().inputId()
                )
                || !"READ_WRITE".equals(profile.cacheMode())
                || !PolynomialTheoryUtilityExecutionPlan.CACHE_REVISION
                    .equals(cacheRevision)) {
            throw new IllegalArgumentException(
                "cache event differs from its result profile"
            );
        }
        if (replayOutcome != null && result.observations() == null) {
            throw new IllegalArgumentException("replay attempt requires the observed result revision");
        }
        if (!transitionBound()) {
            if (!lookup() && replayOutcome == null) {
                throw new IllegalArgumentException(
                    "cache mutation or replay lacks transition lineage"
                );
            }
            return;
        }
        var transition = result.transitions().stream()
            .filter(value -> transitionId.equals(value.transitionId()))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException(
                "cache event refers to another result transition"
            ));
        requireTransitionLineage(transition);
    }

    private void requireTransitionLineage(
        PolynomialTheoryUtilityTransitionOutcome transition
    ) {
        switch (kind) {
            case LOOKUP_HIT, REPLAY -> {
                if (transition.cacheDisposition()
                        != PolynomialTheoryUtilityTransitionOutcome
                            .CacheDisposition.CACHE_HIT_REPLAYED
                        || !entryId.equals(transition.cacheEntryId())) {
                    throw new IllegalArgumentException(
                        "cache hit/replay event differs from its transition"
                    );
                }
            }
            case LOOKUP_MISS, INSERTION -> {
                if (transition.cacheDisposition()
                        != PolynomialTheoryUtilityTransitionOutcome
                            .CacheDisposition.CACHE_MISS_INSERTED
                        || !entryId.equals(transition.cacheEntryId())) {
                    throw new IllegalArgumentException(
                        "cache miss/insertion event differs from its transition"
                    );
                }
            }
            case EVICTION -> {
                if (transition.cacheDisposition()
                        != PolynomialTheoryUtilityTransitionOutcome
                            .CacheDisposition.CACHE_MISS_INSERTED
                        || "NONE".equals(
                            transition.evictedCacheEntryId()
                        )
                        || !entryId.equals(
                            transition.evictedCacheEntryId()
                        )) {
                    throw new IllegalArgumentException(
                        "cache eviction event differs from its transition"
                    );
                }
            }
        }
    }

    private static String identity(
        int eventIndex,
        String executionInputId,
        String transitionId,
        Kind kind,
        String cacheRevision,
        String entryId,
        String evidenceHash
    ) {
        return identity(eventIndex, executionInputId, transitionId, kind, cacheRevision, entryId, evidenceHash, null);
    }

    private static String identity(int eventIndex, String executionInputId, String transitionId, Kind kind,
            String cacheRevision, String entryId, String evidenceHash, TerminalStatus replayOutcome) {
        StringBuilder material = new StringBuilder();
        append(material, replayOutcome == null ? SCHEMA : ATTEMPT_SCHEMA);
        append(material, Integer.toString(eventIndex));
        append(
            material,
            requireHash(executionInputId, "executionInputId")
        );
        append(
            material,
            requireOptionalHash(transitionId, "transitionId")
        );
        append(
            material,
            Objects.requireNonNull(kind, "kind").name()
        );
        append(
            material,
            requireText(cacheRevision, "cacheRevision")
        );
        append(material, requireHash(entryId, "entryId"));
        append(
            material,
            requireHash(evidenceHash, "evidenceHash")
        );
        if (replayOutcome != null) append(material, replayOutcome.name());
        return hash(material.toString());
    }

    private static String requireOptionalHash(String value, String name) {
        String text = requireText(value, name);
        return NO_TRANSITION.equals(text)
            ? text
            : requireHash(text, name);
    }

    private static String hash(String material) {
        return PolynomialTheoryUtilityExecutionIdentity.sha256(
            material.getBytes(StandardCharsets.UTF_8)
        );
    }

    private static String requireHash(String value, String name) {
        String text = requireText(value, name);
        if (!SHA_256.matcher(text).matches()) {
            throw new IllegalArgumentException(name + " is not SHA-256");
        }
        return text;
    }

    private static String requireText(String value, String name) {
        String text = Objects.requireNonNull(value, name);
        if (text.isBlank()) {
            throw new IllegalArgumentException(
                name + " must not be blank"
            );
        }
        return text;
    }

    private static void append(StringBuilder target, String value) {
        target.append(value.length()).append(':').append(value);
    }

    public enum Kind {
        LOOKUP_HIT,
        LOOKUP_MISS,
        INSERTION,
        EVICTION,
        REPLAY
    }
}
