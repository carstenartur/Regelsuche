package de.regelsuche.benchmark.polynomial;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import de.regelsuche.benchmark.polynomial.PolynomialTheoryUtilityCandidateResult.TerminalStatus;
import de.regelsuche.benchmark.polynomial.PolynomialTheoryUtilityCaseCorpus.FormationCase;
import de.regelsuche.benchmark.polynomial.PolynomialTheoryUtilityMeasuredExecution.MeasuredRun;
import de.regelsuche.benchmark.polynomial.PolynomialTheoryUtilityProfileAdapter.RunDescriptor;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Runs the public formation inputs through all ready profiles at the frozen checkpoints. */
class PolynomialTheoryUtilityPublicProfileComparisonTest {
    private static final String EXTERNAL_PROFILE = "OPTIONAL_EXTERNAL_VERIFIED_FACTORIZATION";

    @Test
    void reproducesObservedPublicProfiles() throws IOException {
        var inputs = PolynomialTheoryUtilityExecutionInputs.freeze();
        var cases = PolynomialTheoryUtilityCaseCorpus.load().cases();
        var profiles = PolynomialTheoryUtilityProfileRegistry.entries();
        var report = new StringBuilder();
        report.append("# regelsuche.polynomial-theory-utility-public-profile-comparison/v1\n");
        report.append("# inputs=").append(inputs.contentHash()).append('\n');
        report.append("# formation=").append(PolynomialTheoryUtilityCaseCorpus.FORMATION_CONTENT_HASH).append('\n');
        row(report, "checkpoint", "profile", "availability", "planned_rows", "observed_rows", "validated_rows",
            "no_transition", "unsupported", "budget_inconclusive", "technical_failure", "mixed",
            "transitions", "primitive_work", "mechanical_work", "factorization_work", "cache_hits",
            "cache_misses", "cache_insertions", "cache_replays", "row_evidence_sha256");

        for (var checkpoint : PolynomialTheoryUtilityExecutionPlan.CHECKPOINTS) {
            List<PolynomialTheoryUtilityExecutionInput> reference = List.of();
            for (var profile : profiles) {
                String profileId = profile.profile().profileId();
                var planned = inputs.inputs().stream().filter(input -> profileId.equals(input.profileId())
                    && checkpoint.checkpointId().equals(input.checkpointId())).toList();
                assertEquals(cases.size(), planned.size(), profileId);
                assertEquals(cases.stream().map(FormationCase::caseId).toList(),
                    planned.stream().map(PolynomialTheoryUtilityExecutionInput::caseId).toList());
                if (reference.isEmpty()) {
                    reference = planned;
                } else {
                    for (int index = 0; index < planned.size(); index++) {
                        requireMatchedBudget(reference.get(index), planned.get(index));
                    }
                }
                if (EXTERNAL_PROFILE.equals(profileId)) {
                    assertEquals(PolynomialTheoryUtilityProfileRegistry.EXTERNAL_BLOCKER, profile.blocker());
                    row(report, checkpoint.checkpointId(), profileId, profile.blocker(), planned.size(), 0,
                        "-", "-", "-", "-", "-", "-", "-", "-", "-", "-", "-", "-", "-", "-", "-");
                } else {
                    summarize(report, planned, observe(profile.adapter().orElseThrow(), planned, cases));
                }
            }
        }

        String output = System.getProperty("polynomialPublicProfileComparisonOutput");
        if (output == null) {
            var checkout = Path.of(System.getProperty("regelsuche.repositoryRoot", ".."));
            assertEquals(Files.readString(checkout.resolve("docs/research/polynomial-theory-runtime/"
                + "public-profile-comparison.tsv")), report.toString());
        } else {
            Files.writeString(Path.of(output), report.toString());
        }
    }

    private static void requireMatchedBudget(PolynomialTheoryUtilityExecutionInput reference,
            PolynomialTheoryUtilityExecutionInput input) {
        assertEquals(reference.admittedPrimitiveWork(), input.admittedPrimitiveWork(), input.caseId());
        assertEquals(reference.totalMechanicalWork(), input.totalMechanicalWork(), input.caseId());
        assertEquals(reference.factorizationWork(), input.factorizationWork(), input.caseId());
    }

    private static List<PolynomialTheoryUtilityMeasuredCandidate> observe(PolynomialTheoryUtilityProfileAdapter adapter,
            List<PolynomialTheoryUtilityExecutionInput> planned, List<FormationCase> cases) {
        var first = planned.getFirst();
        var descriptor = new RunDescriptor(first.runId(), first.profileId(), first.checkpointId(),
            first.adapterId(), cases.size());
        var retained = new ArrayList<PolynomialTheoryUtilityMeasuredCandidate>();
        try (var run = adapter.openRun(descriptor)) {
            var measuredRun = assertInstanceOf(MeasuredRun.class, run);
            for (int index = 0; index < cases.size(); index++) {
                var input = planned.get(index);
                var formation = cases.get(index);
                var row = measuredRun.executeObserved(input, formation);
                row.result().validateAgainst(input, formation);
                retained.add(row);
            }
        }
        if (PolynomialTheoryUtilityDerivedCacheAdapter.PROFILE_ID.equals(first.profileId())) {
            var history = new PolynomialTheoryUtilityCacheRunHistory(first.runId());
            retained.forEach(history::accept);
        }
        return retained;
    }

    private static void summarize(StringBuilder report, List<PolynomialTheoryUtilityExecutionInput> planned,
            List<PolynomialTheoryUtilityMeasuredCandidate> rows) {
        var first = planned.getFirst();
        var statuses = new EnumMap<TerminalStatus, Integer>(TerminalStatus.class);
        var evidence = new StringBuilder();
        var work = PolynomialTheoryUtilityWorkBreakdown.zero();
        int transitions = 0;
        int hits = 0;
        int misses = 0;
        int insertions = 0;
        int replays = 0;
        for (var row : rows) {
            var result = row.result();
            var measurement = row.measurements();
            statuses.merge(result.terminalStatus(), 1, Integer::sum);
            work = work.plus(result.work());
            transitions += measurement.generatedTransitionCount();
            hits += measurement.cacheHitCount();
            misses += measurement.cacheMissCount();
            insertions += measurement.cacheInsertionCount();
            replays += measurement.cacheReplayCount();
            row(evidence, result.input().inputId(), result.resultId(), measurement.measurementId());
        }
        row(report, first.checkpointId(), first.profileId(), "OBSERVED", planned.size(), rows.size(),
            statuses.getOrDefault(TerminalStatus.VALIDATED_TRANSITION, 0),
            statuses.getOrDefault(TerminalStatus.NO_TRANSITION, 0),
            statuses.getOrDefault(TerminalStatus.UNSUPPORTED, 0),
            statuses.getOrDefault(TerminalStatus.BUDGET_INCONCLUSIVE, 0),
            statuses.getOrDefault(TerminalStatus.TECHNICAL_FAILURE, 0),
            statuses.getOrDefault(TerminalStatus.MIXED_OUTCOMES, 0),
            transitions, work.primitiveWork(), work.mechanicalWork(), work.factorizationWork(),
            hits, misses, insertions, replays,
            PolynomialTheoryUtilityExecutionIdentity.sha256(evidence.toString().getBytes(StandardCharsets.UTF_8)));
    }

    private static void row(StringBuilder target, Object... values) {
        target.append(String.join("\t", Arrays.stream(values).map(Object::toString).toList())).append('\n');
    }
}
