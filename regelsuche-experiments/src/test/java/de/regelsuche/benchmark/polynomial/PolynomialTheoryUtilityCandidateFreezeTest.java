package de.regelsuche.benchmark.polynomial;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.benchmark.polynomial
    .PolynomialTheoryUtilityCacheEvent.Kind;
import de.regelsuche.benchmark.polynomial
    .PolynomialTheoryUtilityCandidateResult.TerminalStatus;
import de.regelsuche.benchmark.polynomial
    .PolynomialTheoryUtilityTransitionOutcome.CacheDisposition;
import de.regelsuche.benchmark.polynomial
    .PolynomialTheoryUtilityTransitionTrace.PrimitiveStep;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PolynomialTheoryUtilityCandidateFreezeTest {
    private static final String CASE_ID = "z02-difference-of-squares";
    private static final String CHECKPOINT_ID = "CP06_FULL";
    private static final String ON_DEMAND =
        "ON_DEMAND_VERIFIED_FACTORIZATION";
    private static final String CACHE = "VERIFIED_DERIVED_MACRO_CACHE";
    private static final String FACTORED = "(x-1)*(x+1)";
    private static final Fixture FIXTURE = fixture();

    @Test
    void freezesAllMeasuredRowsIntoStableTargetBlindBytes() {
        var first = PolynomialTheoryUtilityCandidateFreeze.create(
            FIXTURE.measuredBatch()
        );
        var second = PolynomialTheoryUtilityCandidateFreeze.create(
            FIXTURE.measuredBatch()
        );

        assertEquals(
            "regelsuche.polynomial-theory-utility-candidate-freeze/v1",
            first.schema()
        );
        assertEquals(
            "CANDIDATES_FROZEN_QUALIFICATION_NOT_OPENED",
            first.evidenceStatus()
        );
        assertEquals("HASH_ONLY_NOT_OPENED", first.qualificationExposure());
        assertEquals(
            PolynomialTheoryUtilityPreregistration.STUDY_ID,
            first.studyId()
        );
        assertEquals(
            PolynomialTheoryUtilityExecutionInputs.EXPECTED_INPUT_COUNT,
            first.rowCount()
        );
        assertEquals(first.contentHash(), second.contentHash());
        assertEquals(first.byteLength(), second.byteLength());
        assertEquals(first.canonicalJson(), second.canonicalJson());
        assertArrayEquals(first.bytes(), second.bytes());
        assertEquals(
            first.contentHash(),
            PolynomialTheoryUtilityExecutionIdentity.sha256(first.bytes())
        );
        assertEquals(first.byteLength(), first.bytes().length);
        first.validateAgainst(FIXTURE.measuredBatch());

        String json = first.canonicalJson();
        assertTrue(json.endsWith("\n"));
        assertFalse(json.contains("\r"));
        assertEquals(600, occurrences(json, "\"rowIndex\":"));
        assertTrue(json.contains("\"rowIndex\":0"));
        assertTrue(json.contains("\"rowIndex\":599"));
        assertTrue(json.contains(
            FIXTURE.measuredBatch().measurements()
                .getLast()
                .measurementId()
        ));
        assertTrue(json.contains("\"transitionTraces\":[]"));
        assertTrue(json.contains("\"factorizationAttempts\":[]"));
        assertTrue(json.contains("\"cacheEvents\":[]"));
    }

    @Test
    void retainsNonEmptyTransitionFactorizationAndCacheLineage() {
        String json = PolynomialTheoryUtilityCandidateFreeze.create(
            FIXTURE.measuredBatch()
        ).canonicalJson();

        assertTrue(json.contains("\"transitionTraces\":[{"));
        assertTrue(json.contains("\"factorizationAttempts\":[{"));
        assertTrue(json.contains("\"cacheEvents\":[{"));
        assertTrue(json.contains(
            "\"transformedRootExpression\":\"" + FACTORED + "\""
        ));
        assertTrue(json.contains(
            "\"ruleId\":\"prepare-polynomial\""
        ));
        assertTrue(json.contains(
            "\"ruleId\":\"factor-polynomial\""
        ));
        assertTrue(json.contains("\"kind\":\"LOOKUP_MISS\""));
        assertTrue(json.contains("\"kind\":\"INSERTION\""));
        assertTrue(json.contains("\"cacheMissCount\":1"));
        assertTrue(json.contains("\"cacheInsertionCount\":1"));
    }

    @Test
    void bindsEveryFrozenInputArtifactAndOnlyHashesQualification() {
        String json = PolynomialTheoryUtilityCandidateFreeze.create(
            FIXTURE.measuredBatch()
        ).canonicalJson();

        assertBinding(
            json,
            "preregistrationBinding",
            PolynomialTheoryUtilityPreregistration.FILE_NAME,
            PolynomialTheoryUtilityPreregistration.BYTE_LENGTH,
            PolynomialTheoryUtilityPreregistration.CONTENT_HASH
        );
        assertBinding(
            json,
            "formationBinding",
            PolynomialTheoryUtilityCaseCorpus.FORMATION_FILE_NAME,
            PolynomialTheoryUtilityCaseCorpus.FORMATION_BYTE_LENGTH,
            PolynomialTheoryUtilityCaseCorpus.FORMATION_CONTENT_HASH
        );
        assertBinding(
            json,
            "qualificationBinding",
            PolynomialTheoryUtilityCaseCorpus.QUALIFICATION_FILE_NAME,
            PolynomialTheoryUtilityCaseCorpus.QUALIFICATION_BYTE_LENGTH,
            PolynomialTheoryUtilityCaseCorpus.QUALIFICATION_CONTENT_HASH
        );
        assertBinding(
            json,
            "planBinding",
            PolynomialTheoryUtilityExecutionPlan.FILE_NAME,
            PolynomialTheoryUtilityExecutionPlan.EXPECTED_BYTE_LENGTH,
            PolynomialTheoryUtilityExecutionPlan.EXPECTED_CONTENT_HASH
        );
        assertBinding(
            json,
            "executionInputBinding",
            PolynomialTheoryUtilityExecutionInputs.FILE_NAME,
            PolynomialTheoryUtilityExecutionInputs.EXPECTED_BYTE_LENGTH,
            PolynomialTheoryUtilityExecutionInputs.EXPECTED_CONTENT_HASH
        );

        for (String forbidden : List.of(
                "requiredOutcome",
                "reducibilityStatus",
                "multiplicityStatus",
                "referenceExpression",
                "expectedClassifierOutcome",
                "selectedDecision",
                "qualificationResult")) {
            assertFalse(json.contains("\"" + forbidden + "\":"));
        }
    }

    @Test
    void rejectsCounterfeitIdentityLengthBytesAndTampering() {
        var valid = PolynomialTheoryUtilityCandidateFreeze.create(
            FIXTURE.measuredBatch()
        );

        assertThrows(
            IllegalArgumentException.class,
            () -> new PolynomialTheoryUtilityCandidateFreeze(
                hash("counterfeit-freeze"),
                valid.byteLength(),
                valid.measuredBatch(),
                valid.canonicalJson()
            )
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> new PolynomialTheoryUtilityCandidateFreeze(
                valid.contentHash(),
                valid.byteLength() + 1L,
                valid.measuredBatch(),
                valid.canonicalJson()
            )
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> new PolynomialTheoryUtilityCandidateFreeze(
                valid.contentHash(),
                valid.byteLength(),
                valid.measuredBatch(),
                valid.canonicalJson() + " "
            )
        );

        byte[] tampered = valid.bytes();
        tampered[tampered.length / 2] ^= 1;
        assertFalse(valid.verify(tampered));
        assertFalse(valid.verify(null));
        assertThrows(
            IllegalArgumentException.class,
            () -> valid.requireVerified(tampered)
        );

        byte[] exposed = valid.bytes();
        byte original = exposed[0];
        exposed[0] ^= 1;
        assertNotEquals(exposed[0], valid.bytes()[0]);
        assertEquals(original, valid.bytes()[0]);
    }

    @Test
    void writesAtomicallyAndRejectsAQualificationInTheOutputDirectory(
        @TempDir Path temporary
    ) throws IOException {
        var freeze = PolynomialTheoryUtilityCandidateFreeze.create(
            FIXTURE.measuredBatch()
        );
        Path target = freeze.write(temporary.resolve("candidate"));
        assertEquals(
            PolynomialTheoryUtilityCandidateFreeze.FILE_NAME,
            target.getFileName().toString()
        );
        assertArrayEquals(freeze.bytes(), Files.readAllBytes(target));

        Path blocked = temporary.resolve("blocked");
        Files.createDirectories(blocked);
        Files.writeString(
            blocked.resolve(
                PolynomialTheoryUtilityCaseCorpus.QUALIFICATION_FILE_NAME
            ),
            "{}",
            StandardCharsets.UTF_8
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> freeze.write(blocked)
        );
        assertFalse(Files.exists(
            blocked.resolve(
                PolynomialTheoryUtilityCandidateFreeze.FILE_NAME
            )
        ));
    }

    private static void assertBinding(
        String json,
        String field,
        String path,
        long byteLength,
        String contentHash
    ) {
        String binding = "\"" + field + "\":{"
            + "\"path\":\"" + path + "\","
            + "\"byteLength\":" + byteLength + ","
            + "\"contentHash\":\"" + contentHash + "\"}";
        assertTrue(json.contains(binding));
    }

    private static int occurrences(String value, String needle) {
        int count = 0;
        int offset = 0;
        while ((offset = value.indexOf(needle, offset)) >= 0) {
            count++;
            offset += needle.length();
        }
        return count;
    }

    private static Fixture fixture() {
        var inputs = PolynomialTheoryUtilityExecutionInputs.freeze();
        var cases = PolynomialTheoryUtilityCaseCorpus.load().cases();
        List<PolynomialTheoryUtilityCandidateResult> results =
            new ArrayList<>(inputs.inputs().size());
        List<PolynomialTheoryUtilityCandidateMeasurements> measurements =
            new ArrayList<>(inputs.inputs().size());

        for (int index = 0; index < inputs.inputs().size(); index++) {
            var input = inputs.inputs().get(index);
            var studyCase = cases.get(index % cases.size());
            var candidate = candidate(input, studyCase, index);
            results.add(candidate.result());
            measurements.add(candidate.measurements());
        }

        var candidateBatch =
            PolynomialTheoryUtilityProfileAdapter.CandidateBatch.create(
                inputs,
                results
            );
        var measuredBatch =
            PolynomialTheoryUtilityCandidateMeasurementBatch.create(
                candidateBatch,
                measurements
            );
        return new Fixture(measuredBatch);
    }

    private static Candidate candidate(
        PolynomialTheoryUtilityExecutionInput input,
        PolynomialTheoryUtilityCaseCorpus.FormationCase studyCase,
        int index
    ) {
        if (evidenceInput(input, ON_DEMAND)) {
            return onDemandCandidate(input, studyCase);
        }
        if (evidenceInput(input, CACHE)) {
            return cacheMissCandidate(input, studyCase);
        }
        var result = PolynomialTheoryUtilityCandidateResult.noTransition(
            input,
            studyCase,
            "CANDIDATE_FREEZE_TEST_" + index
        );
        return new Candidate(
            result,
            PolynomialTheoryUtilityCandidateMeasurements.create(
                result,
                List.of(),
                List.of(),
                List.of()
            )
        );
    }

    private static boolean evidenceInput(
        PolynomialTheoryUtilityExecutionInput input,
        String profileId
    ) {
        return profileId.equals(input.profileId())
            && CASE_ID.equals(input.caseId())
            && CHECKPOINT_ID.equals(input.checkpointId());
    }

    private static Candidate onDemandCandidate(
        PolynomialTheoryUtilityExecutionInput input,
        PolynomialTheoryUtilityCaseCorpus.FormationCase studyCase
    ) {
        var profile = PolynomialTheoryUtilityExecutionInputs.profile(ON_DEMAND);
        var work = onDemandWork();
        var transition = transition(
            input,
            studyCase,
            profile,
            work,
            CacheDisposition.CACHE_DISABLED,
            "NONE",
            "NONE",
            "freeze-on-demand"
        );
        var result = PolynomialTheoryUtilityCandidateResult.create(
            input,
            studyCase,
            TerminalStatus.VALIDATED_TRANSITION,
            "CANDIDATE_FREEZE_ON_DEMAND_TRANSITION",
            work,
            List.of(transition),
            "VERIFIED"
        );
        var trace = PolynomialTheoryUtilityTransitionTrace.create(
            transition,
            2,
            List.of(
                PrimitiveStep.create(
                    transition,
                    0,
                    0,
                    "prepare-polynomial",
                    hash("freeze-on-demand-primitive-0")
                ),
                PrimitiveStep.create(
                    transition,
                    1,
                    1,
                    "factor-polynomial",
                    hash("freeze-on-demand-primitive-1")
                )
            ),
            List.of()
        );
        var attempt = factorizationAttempt(
            result,
            profile,
            "freeze-on-demand",
            2
        );
        return new Candidate(
            result,
            PolynomialTheoryUtilityCandidateMeasurements.create(
                result,
                List.of(trace),
                List.of(attempt),
                List.of()
            )
        );
    }

    private static Candidate cacheMissCandidate(
        PolynomialTheoryUtilityExecutionInput input,
        PolynomialTheoryUtilityCaseCorpus.FormationCase studyCase
    ) {
        var profile = PolynomialTheoryUtilityExecutionInputs.profile(CACHE);
        var work = cacheMissWork();
        String entryId = hash("freeze-cache-entry");
        var transition = transition(
            input,
            studyCase,
            profile,
            work,
            CacheDisposition.CACHE_MISS_INSERTED,
            entryId,
            "NONE",
            "freeze-cache-miss"
        );
        var result = PolynomialTheoryUtilityCandidateResult.create(
            input,
            studyCase,
            TerminalStatus.VALIDATED_TRANSITION,
            "CANDIDATE_FREEZE_CACHE_MISS_TRANSITION",
            work,
            List.of(transition),
            "VERIFIED"
        );
        var trace = PolynomialTheoryUtilityTransitionTrace.create(
            transition,
            1,
            List.of(
                PrimitiveStep.create(
                    transition,
                    0,
                    0,
                    "cache-miss-factorization",
                    hash("freeze-cache-primitive")
                )
            ),
            List.of()
        );
        var attempt = factorizationAttempt(
            result,
            profile,
            "freeze-cache-miss",
            1
        );
        var events = List.of(
            cacheEvent(0, result, transition, Kind.LOOKUP_MISS, entryId),
            cacheEvent(1, result, transition, Kind.INSERTION, entryId)
        );
        return new Candidate(
            result,
            PolynomialTheoryUtilityCandidateMeasurements.create(
                result,
                List.of(trace),
                List.of(attempt),
                events
            )
        );
    }

    private static PolynomialTheoryUtilityTransitionOutcome transition(
        PolynomialTheoryUtilityExecutionInput input,
        PolynomialTheoryUtilityCaseCorpus.FormationCase studyCase,
        PolynomialTheoryUtilityExecutionProfile profile,
        PolynomialTheoryUtilityWorkBreakdown work,
        CacheDisposition disposition,
        String cacheEntryId,
        String evictedEntryId,
        String evidencePrefix
    ) {
        String cacheRevision = disposition == CacheDisposition.CACHE_DISABLED
            ? "NONE"
            : PolynomialTheoryUtilityExecutionPlan.CACHE_REVISION;
        return PolynomialTheoryUtilityTransitionOutcome.create(
            0,
            input.inputId(),
            List.of(),
            studyCase.sourceExpression(),
            FACTORED,
            studyCase.sourceExpression(),
            FACTORED,
            profile.transformationId(),
            profile.engineId(),
            hash(evidencePrefix + ":source"),
            hash(evidencePrefix + ":transition"),
            disposition,
            cacheRevision,
            cacheEntryId,
            evictedEntryId,
            work
        );
    }

    private static PolynomialTheoryUtilityFactorizationAttempt
            factorizationAttempt(
                PolynomialTheoryUtilityCandidateResult result,
                PolynomialTheoryUtilityExecutionProfile profile,
                String evidencePrefix,
                int candidateCount
            ) {
        List<String> candidates = new ArrayList<>(candidateCount);
        for (int index = 0; index < candidateCount; index++) {
            candidates.add(hash(evidencePrefix + ":candidate:" + index));
        }
        String selected = candidates.getLast();
        return PolynomialTheoryUtilityFactorizationAttempt.create(
            0,
            result.input().inputId(),
            profile.engineId(),
            hash(evidencePrefix + ":request"),
            hash(evidencePrefix + ":request-evidence"),
            candidates,
            selected,
            result.transitions().getFirst().transitionId(),
            "VERIFIED",
            hash(evidencePrefix + ":report-evidence")
        );
    }

    private static PolynomialTheoryUtilityCacheEvent cacheEvent(
        int index,
        PolynomialTheoryUtilityCandidateResult result,
        PolynomialTheoryUtilityTransitionOutcome transition,
        Kind kind,
        String entryId
    ) {
        return PolynomialTheoryUtilityCacheEvent.create(
            index,
            result.input().inputId(),
            transition.transitionId(),
            kind,
            PolynomialTheoryUtilityExecutionPlan.CACHE_REVISION,
            entryId,
            hash("freeze-cache-event:" + index + ":" + kind)
        );
    }

    private static PolynomialTheoryUtilityWorkBreakdown onDemandWork() {
        return new PolynomialTheoryUtilityWorkBreakdown(
            2L,
            1L,
            1L,
            2L,
            1L,
            1L,
            1L,
            1L,
            1L,
            0L,
            0L,
            0L,
            0L,
            1L
        );
    }

    private static PolynomialTheoryUtilityWorkBreakdown cacheMissWork() {
        return new PolynomialTheoryUtilityWorkBreakdown(
            1L,
            1L,
            1L,
            1L,
            1L,
            1L,
            1L,
            1L,
            1L,
            1L,
            1L,
            0L,
            0L,
            1L
        );
    }

    private static String hash(String value) {
        return PolynomialTheoryUtilityExecutionIdentity.sha256(
            value.getBytes(StandardCharsets.UTF_8)
        );
    }

    private record Candidate(
        PolynomialTheoryUtilityCandidateResult result,
        PolynomialTheoryUtilityCandidateMeasurements measurements
    ) {
    }

    private record Fixture(
        PolynomialTheoryUtilityCandidateMeasurementBatch measuredBatch
    ) {
    }
}
