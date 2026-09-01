package de.regelsuche.benchmark.polynomial;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import de.regelsuche.benchmark.polynomial
    .PolynomialTheoryUtilityCacheEvent.Kind;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

class PolynomialTheoryUtilityCacheEventUnboundTest {
    private static final String CASE_ID = "z02-difference-of-squares";
    private static final String PROFILE_ID =
        "VERIFIED_DERIVED_MACRO_CACHE";

    @Test
    void allowsOnlyLookupEventsWithoutTransitionLineage() {
        var result = PolynomialTheoryUtilityCandidateResult.noTransition(
            input(),
            formationCase(),
            "TEST_UNBOUND_CACHE_EVENT"
        );
        var profile = PolynomialTheoryUtilityExecutionInputs.profile(
            PROFILE_ID
        );

        for (Kind kind : List.of(Kind.LOOKUP_HIT, Kind.LOOKUP_MISS)) {
            var event = event(kind);
            assertDoesNotThrow(
                () -> event.validateAgainst(0, result, profile)
            );
        }

        for (Kind kind : List.of(
                Kind.INSERTION,
                Kind.EVICTION,
                Kind.REPLAY)) {
            var event = event(kind);
            assertThrows(
                IllegalArgumentException.class,
                () -> event.validateAgainst(0, result, profile)
            );
        }
    }

    private static PolynomialTheoryUtilityCacheEvent event(Kind kind) {
        return PolynomialTheoryUtilityCacheEvent.create(
            0,
            input().inputId(),
            PolynomialTheoryUtilityCacheEvent.NO_TRANSITION,
            kind,
            PolynomialTheoryUtilityExecutionPlan.CACHE_REVISION,
            hash("entry:" + kind),
            hash("evidence:" + kind)
        );
    }

    private static PolynomialTheoryUtilityExecutionInput input() {
        return PolynomialTheoryUtilityExecutionInputs.freeze().inputs()
            .stream()
            .filter(value -> PROFILE_ID.equals(value.profileId()))
            .filter(value -> CASE_ID.equals(value.caseId()))
            .filter(value -> "CP06_FULL".equals(value.checkpointId()))
            .findFirst()
            .orElseThrow();
    }

    private static PolynomialTheoryUtilityCaseCorpus.FormationCase
            formationCase() {
        return PolynomialTheoryUtilityCaseCorpus.load().cases().stream()
            .filter(value -> CASE_ID.equals(value.caseId()))
            .findFirst()
            .orElseThrow();
    }

    private static String hash(String value) {
        return PolynomialTheoryUtilityExecutionIdentity.sha256(
            value.getBytes(StandardCharsets.UTF_8)
        );
    }
}
