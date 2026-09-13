package de.regelsuche.evolution;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Independent controls use only temporary public synthetic study and replay inputs. */
class LearnedSelectedProgramRuntimeReviewTest {
    private static final Instant ISSUED = Instant.parse("2026-09-01T00:00:00Z");
    private static final Instant EXPIRES = ISSUED.plusSeconds(60);
    @TempDir Path directory;

    @Test
    void expiryDuringRealReplayPreventsCapabilityIssuance() throws Exception {
        var evidence = evidence();
        var leaves = leaves(evidence);
        var service = new LearnedRewriteProgramAuthorizationService();
        var replay = service.evaluateSelectedReplay(evidence.fixture().plan(), leaves,
            List.of(input("synthetic_replay", "q+0", "q", List.of())), ProgramFinalTestFixtures.REVISION, ISSUED);
        assertTrue(replay.cases().getFirst().programReplay().complete());
        assertTrue(replay.cases().getFirst().search().complete());
        var clock = new ExpiringClock();

        assertThrows(IllegalArgumentException.class, () -> service.authorizeSelected(evidence.fixture().plan(),
            leaves, replay, ProgramFinalTestFixtures.REVISION, clock));
        assertEquals(2, clock.calls, "expiry must be checked again after the real replay");
    }

    @Test
    void aGenuineRetainedAssessmentCannotReplaceTheActualFinalReservation() throws Exception {
        var evidence = evidence();
        assertTrue(evidence.assessment().nativeGatesPassed());
        String retainedAssessment = evidence.assessment().toCanonicalJson();
        Path reservation = evidence.store().reservationPath(evidence.fixture().plan());
        Files.delete(reservation);

        assertThrows(IOException.class, () -> new LearnedPatternRuleAuthorizationService().authorizeSelected(
            evidence.fixture().plan(), evidence.store(), retainedAssessment, evidence.fixture().manifest(),
            evidence.bundle(), "add_zero", ProgramFinalTestFixtures.REVISION, ISSUED));
        assertFalse(Files.exists(reservation));
    }

    @Test
    void everyUseAuditsTheFreshInputAssumptionsWithoutReusingEarlierDeclarations() throws Exception {
        var evidence = evidence();
        var leaves = leaves(evidence);
        var service = new LearnedRewriteProgramAuthorizationService();
        var replay = service.evaluateSelectedReplay(evidence.fixture().plan(), leaves,
            List.of(input("synthetic_replay", "q+0", "q", List.of())), ProgramFinalTestFixtures.REVISION, ISSUED);
        var capability = service.authorizeSelected(evidence.fixture().plan(), leaves, replay,
            ProgramFinalTestFixtures.REVISION, Clock.fixed(ISSUED, ZoneOffset.UTC));
        var undeclared = input("synthetic_undeclared", "(u*v)/u", "v", List.of());
        var declared = input("synthetic_declared", "(u*v)/u", "v", List.of("u != 0"));

        var missing = capability.execute(undeclared, ProgramFinalTestFixtures.REVISION);
        assertTrue(missing.reached());
        assertEquals(EvolutionRewriteProgramTrainFitnessEvidence.PathCorrectness.MISSING_ASSUMPTION, missing.correctness());
        var confirmed = capability.execute(declared, ProgramFinalTestFixtures.REVISION);
        assertTrue(confirmed.reached());
        assertEquals(EvolutionRewriteProgramTrainFitnessEvidence.PathCorrectness.CONFIRMED, confirmed.correctness());
        assertEquals(EvolutionRewriteProgramTrainFitnessEvidence.PathCorrectness.MISSING_ASSUMPTION,
            capability.execute(undeclared, ProgramFinalTestFixtures.REVISION).correctness());
    }

    private Evidence evidence() throws Exception {
        var fixture = ProgramFinalTestFixtures.create(directory, "synthetic_independent_runtime_review", false);
        fixture.execute();
        var store = new FileEvolutionRewriteProgramFinalTestAttemptStore(fixture.finalDirectory());
        var assessment = new EvolutionRewriteProgramQualificationService().assess(fixture.plan(), store,
            ProgramFinalTestFixtures.REVISION);
        return new Evidence(fixture, store, assessment,
            LearnedSelectedProgramAuthorizationBundle.create(assessment, fixture.manifest(), ISSUED, EXPIRES));
    }

    private static List<LearnedPatternRuleAuthorizationService.SelectedLeafAuthorization> leaves(Evidence evidence)
        throws IOException {
        var leaves = new ArrayList<LearnedPatternRuleAuthorizationService.SelectedLeafAuthorization>();
        for (var gene : evidence.fixture().plan().selectedConfiguration().candidate().genome().rewrites()) {
            leaves.add(new LearnedPatternRuleAuthorizationService().authorizeSelected(evidence.fixture().plan(),
                evidence.store(), evidence.assessment().toCanonicalJson(), evidence.fixture().manifest(), evidence.bundle(),
                gene.geneId(), ProgramFinalTestFixtures.REVISION, ISSUED));
        }
        return List.copyOf(leaves);
    }

    private static LearnedSelectedProgramReplayEvidence.Input input(String id, String source, String target,
        List<String> assumptions) {
        return new LearnedSelectedProgramReplayEvidence.Input(id, source, target, assumptions);
    }

    private record Evidence(ProgramFinalTestFixtures.Fixture fixture,
        FileEvolutionRewriteProgramFinalTestAttemptStore store, EvolutionRewriteProgramQualificationAssessment assessment,
        LearnedSelectedProgramAuthorizationBundle bundle) { }

    private static final class ExpiringClock extends Clock {
        private int calls;
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return calls++ == 0 ? ISSUED : EXPIRES; }
    }
}
