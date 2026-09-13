package de.regelsuche.evolution;

import static org.junit.jupiter.api.Assertions.*;

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

class LearnedSelectedProgramAuthorizationTest {
    private static final Instant ISSUED = Instant.parse("2026-09-01T00:00:00Z");
    private static final Instant EXPIRES = ISSUED.plusSeconds(3_600);
    @TempDir Path directory;

    @Test
    void oneCombinedStudyCanAuthorizeRealInternalExecutionWithoutAnotherFinalAttempt() throws Exception {
        var fixture = ProgramFinalTestFixtures.create(directory);
        fixture.execute();
        var store = new FileEvolutionRewriteProgramFinalTestAttemptStore(fixture.finalDirectory());
        byte[] reserved = Files.readAllBytes(store.reservationPath(fixture.plan()));
        byte[] finalBytes = Files.readAllBytes(store.evaluationPath(fixture.plan()));
        var assessment = new EvolutionRewriteProgramQualificationService().assess(fixture.plan(), store,
            ProgramFinalTestFixtures.REVISION);
        var bundle = LearnedSelectedProgramAuthorizationBundle.create(assessment, fixture.manifest(), ISSUED, EXPIRES);
        assertEquals(bundle, LearnedSelectedProgramAuthorizationBundle.fromCanonicalJson(bundle.toCanonicalJson()));
        var leaves = leaves(fixture, store, assessment, bundle);
        var service = new LearnedRewriteProgramAuthorizationService();
        var inputs = List.of(new LearnedSelectedProgramReplayEvidence.Input("synthetic_runtime_one", "(q+0)+0", "q", List.of()));
        var replay = service.evaluateSelectedReplay(fixture.plan(), leaves, inputs, ProgramFinalTestFixtures.REVISION, ISSUED);
        assertTrue(replay.cases().getFirst().programReplay().complete());
        assertFalse(replay.cases().getFirst().programReplay().candidates().isEmpty());
        assertTrue(replay.cases().getFirst().programReplay().candidates().stream()
            .allMatch(item -> !item.primitiveRuleIds().isEmpty() && item.assumptions().isEmpty()));
        assertEquals(fixture.plan().selectedConfiguration().effectiveBudget().maxPrimitiveSteps(),
            replay.cases().getFirst().programReplay().pathBudget().primitiveRewriteUnits());
        var authorized = service.authorizeSelected(fixture.plan(), leaves, replay, ProgramFinalTestFixtures.REVISION,
            Clock.fixed(ISSUED, ZoneOffset.UTC));
        var actual = authorized.execute(inputs.getFirst(), ProgramFinalTestFixtures.REVISION);
        assertTrue(actual.reached());
        assertEquals(EvolutionRewriteProgramTrainFitnessEvidence.PathCorrectness.CONFIRMED, actual.correctness());
        assertTrue(actual.totalWorkUnits() > 0);
        assertTrue(replay.cases().getFirst().totalReplayWorkUnits() > replay.cases().getFirst().search().totalWorkUnits());
        assertEquals("INTERNAL_EXECUTION_ONLY", authorized.scope());
        assertTrue(authorized.claimStatuses().values().stream().allMatch("NOT_EVALUATED"::equals));
        assertEquals(List.of("externalNovelty", "projectNovelty", "publicEvidence", "publicPromotion", "release"),
            authorized.claimStatuses().keySet().stream().sorted().toList());
        assertFalse(authorized.bindingHash().isBlank());
        assertThrows(UnsupportedOperationException.class, () -> authorized.claimStatuses().put("publicPromotion", "READY"));
        assertEquals(replay, LearnedSelectedProgramReplayEvidence.fromCanonicalJson(replay.toCanonicalJson()));
        assertArrayEquals(reserved, Files.readAllBytes(store.reservationPath(fixture.plan())));
        assertArrayEquals(finalBytes, Files.readAllBytes(store.evaluationPath(fixture.plan())));
        assertThrows(java.io.IOException.class, fixture::execute);
        assertEquals(0, LearnedPatternRuleAuthorizationService.SelectedLeafAuthorization.class.getConstructors().length);
        assertEquals(0, LearnedRewriteProgramAuthorizationService.SelectedAuthorization.class.getConstructors().length);
    }

    @Test
    void falseNativeGeneCannotProduceAnySelectedLeafAuthority() throws Exception {
        var fixture = ProgramFinalTestFixtures.create(directory, "synthetic_false_selected_authority", true);
        fixture.execute();
        var store = new FileEvolutionRewriteProgramFinalTestAttemptStore(fixture.finalDirectory());
        var assessed = new EvolutionRewriteProgramQualificationService().assess(fixture.plan(), store, ProgramFinalTestFixtures.REVISION);
        var bundle = LearnedSelectedProgramAuthorizationBundle.create(assessed, fixture.manifest(), ISSUED, EXPIRES);
        assertThrows(IllegalArgumentException.class, () -> new LearnedPatternRuleAuthorizationService().authorizeSelected(
            fixture.plan(), store, assessed.toCanonicalJson(), fixture.manifest(), bundle, "add_zero",
            ProgramFinalTestFixtures.REVISION, ISSUED));
    }

    @Test
    void omittedFlatGeneAndForeignStudyAuthoritiesCannotBeSubstituted() throws Exception {
        var first = prepared(directory.resolve("first"), "synthetic_selected_authority_first");
        var other = prepared(directory.resolve("other"), "synthetic_selected_authority_other");
        var service = new LearnedRewriteProgramAuthorizationService();
        var inputs = List.of(new LearnedSelectedProgramReplayEvidence.Input("synthetic_replay", "z+0", "z", List.of()));
        assertThrows(IllegalArgumentException.class, () -> service.evaluateSelectedReplay(first.fixture().plan(),
            first.leaves().subList(0, 1), inputs, ProgramFinalTestFixtures.REVISION, ISSUED));
        var foreign = new ArrayList<>(first.leaves());
        foreign.set(1, other.leaves().get(1));
        assertThrows(IllegalArgumentException.class, () -> service.evaluateSelectedReplay(first.fixture().plan(),
            foreign, inputs, ProgramFinalTestFixtures.REVISION, ISSUED));
    }

    @Test
    void capabilityRechecksExpiryAndRevisionOnEveryUse() throws Exception {
        var prepared = prepared(directory, "synthetic_selected_expiry");
        var service = new LearnedRewriteProgramAuthorizationService();
        var input = new LearnedSelectedProgramReplayEvidence.Input("synthetic_replay", "w+0", "w", List.of());
        var replay = service.evaluateSelectedReplay(prepared.fixture().plan(), prepared.leaves(), List.of(input),
            ProgramFinalTestFixtures.REVISION, ISSUED);
        var clock = new MutableClock(ISSUED);
        var authorized = service.authorizeSelected(prepared.fixture().plan(), prepared.leaves(), replay,
            ProgramFinalTestFixtures.REVISION, clock);
        assertThrows(IllegalArgumentException.class, () -> authorized.execute(input, "2".repeat(40)));
        clock.now = EXPIRES;
        assertThrows(IllegalArgumentException.class, () -> authorized.execute(input, ProgramFinalTestFixtures.REVISION));
        assertThrows(IllegalArgumentException.class, () -> service.authorizeSelected(prepared.fixture().plan(),
            prepared.leaves(), replay, ProgramFinalTestFixtures.REVISION, clock));
    }

    @Test
    void anExactlyReproducibleIncompleteReplayCannotAuthorizeExecution() throws Exception {
        var prepared = prepared(ProgramFinalTestFixtures.create(directory, "synthetic_incomplete_runtime_replay", false, 2, List.of(3, 4)));
        var service = new LearnedRewriteProgramAuthorizationService();
        var replay = service.evaluateSelectedReplay(prepared.fixture().plan(), prepared.leaves(),
            List.of(new LearnedSelectedProgramReplayEvidence.Input("synthetic_incomplete", "((p+0)+0)+0", "p", List.of())),
            ProgramFinalTestFixtures.REVISION, ISSUED);
        assertTrue(replay.cases().stream().anyMatch(item -> !item.search().complete() || !item.programReplay().complete()),
            "the real public replay must actually exhaust a bounded execution surface: "
                + replay.cases().getFirst().search().terminalReason() + "/"
                + prepared.fixture().plan().selectedConfiguration().candidate().plan().root().getClass().getSimpleName());
        assertThrows(IllegalArgumentException.class, () -> service.authorizeSelected(prepared.fixture().plan(),
            prepared.leaves(), replay, ProgramFinalTestFixtures.REVISION, Clock.fixed(ISSUED, ZoneOffset.UTC)));
    }

    @Test
    void capabilityCannotBeUsedBeforeItsActualProgramAuthorization() throws Exception {
        var prepared = prepared(directory, "synthetic_runtime_authorized_at");
        var service = new LearnedRewriteProgramAuthorizationService();
        var input = new LearnedSelectedProgramReplayEvidence.Input("synthetic_backdate", "t+0", "t", List.of());
        var replay = service.evaluateSelectedReplay(prepared.fixture().plan(), prepared.leaves(), List.of(input),
            ProgramFinalTestFixtures.REVISION, ISSUED);
        var clock = new MutableClock(ISSUED.plusSeconds(120));
        var authorized = service.authorizeSelected(prepared.fixture().plan(), prepared.leaves(), replay,
            ProgramFinalTestFixtures.REVISION, clock);
        clock.now = ISSUED.plusSeconds(60);
        assertThrows(IllegalArgumentException.class, () -> authorized.execute(input, ProgramFinalTestFixtures.REVISION));
    }

    @Test
    void fullyRehashedPrimitiveLineageAssumptionsAndMeasuredWorkStillRequireActualReplay() throws Exception {
        var prepared = prepared(directory, "synthetic_rehashed_runtime_lineage");
        var service = new LearnedRewriteProgramAuthorizationService();
        var replay = service.evaluateSelectedReplay(prepared.fixture().plan(), prepared.leaves(),
            List.of(new LearnedSelectedProgramReplayEvidence.Input("synthetic_tamper", "s+0", "s", List.of())),
            ProgramFinalTestFixtures.REVISION, ISSUED);
        for (String field : List.of("primitiveRuleIds", "assumptions")) {
            var forged = EvolutionRewriteProgramFinalTestRunnerTest.object(replay.toCanonicalJson());
            var candidate = (com.fasterxml.jackson.databind.node.ObjectNode) forged.at("/cases/0/programReplay/candidates/0");
            candidate.putArray(field).add(field.equals("assumptions") ? "s!=0" : "unproved_foreign_leaf");
            rehashLegacy(candidate);
            rehashLegacy((com.fasterxml.jackson.databind.node.ObjectNode) forged.at("/cases/0/programReplay"));
            rehashRuntime(forged, replay.bundle());
            var imported = LearnedSelectedProgramReplayEvidence.fromCanonicalJson(forged.toString());
            assertThrows(IllegalArgumentException.class, () -> service.authorizeSelected(prepared.fixture().plan(),
                prepared.leaves(), imported, ProgramFinalTestFixtures.REVISION, Clock.fixed(ISSUED, ZoneOffset.UTC)));
        }
        var alteredWork = EvolutionRewriteProgramFinalTestRunnerTest.object(replay.toCanonicalJson());
        var search = (com.fasterxml.jackson.databind.node.ObjectNode) alteredWork.at("/cases/0/search");
        var searchWork = (com.fasterxml.jackson.databind.node.ObjectNode) search.get("searchWork");
        searchWork.put("exploredStates", searchWork.get("exploredStates").longValue() + 1);
        search.put("totalWorkUnits", search.get("totalWorkUnits").longValue() + 1);
        rehashRuntime(alteredWork, replay.bundle());
        var importedWork = LearnedSelectedProgramReplayEvidence.fromCanonicalJson(alteredWork.toString());
        assertThrows(IllegalArgumentException.class, () -> service.authorizeSelected(prepared.fixture().plan(),
            prepared.leaves(), importedWork, ProgramFinalTestFixtures.REVISION, Clock.fixed(ISSUED, ZoneOffset.UTC)));
    }

    @Test
    void rehashedBroaderWorkConfigurationCannotReplaceTheActualSelectedAuthority() throws Exception {
        var prepared = prepared(directory, "synthetic_rehashed_runtime_budget");
        var service = new LearnedRewriteProgramAuthorizationService();
        var replay = service.evaluateSelectedReplay(prepared.fixture().plan(), prepared.leaves(),
            List.of(new LearnedSelectedProgramReplayEvidence.Input("synthetic_budget", "r+0", "r", List.of())),
            ProgramFinalTestFixtures.REVISION, ISSUED);
        var old = replay.configuration();
        var budget = old.budget();
        var broader = EvolutionRewriteProgramValidationPlan.Configuration.create(old.retainedCandidate(),
            new EvolutionRewriteProgramTrainSuite.PrimitiveWorkBudget(budget.maxPrimitiveSteps(), budget.maxExploredStates(),
                budget.maxCandidatesPerState(), budget.maxExpandingSteps(), budget.maxWorkUnits() + 1));
        var forged = EvolutionRewriteProgramFinalTestRunnerTest.object(replay.toCanonicalJson());
        forged.set("configuration", EvolutionValidationArtifactSupport.JSON.valueToTree(broader));
        var bundle = (com.fasterxml.jackson.databind.node.ObjectNode) forged.get("bundle");
        bundle.put("selectedConfigurationHash", broader.contentHash());
        rehashRuntime(bundle, replay.bundle());
        rehashRuntime(forged, replay.bundle());
        var imported = LearnedSelectedProgramReplayEvidence.fromCanonicalJson(forged.toString());
        assertThrows(IllegalArgumentException.class, () -> service.authorizeSelected(prepared.fixture().plan(),
            prepared.leaves(), imported, ProgramFinalTestFixtures.REVISION, Clock.fixed(ISSUED, ZoneOffset.UTC)));
    }

    private static void rehashLegacy(com.fasterxml.jackson.databind.node.ObjectNode node) {
        node.remove("contentHash");
        var material = EvolutionValidationArtifactSupport.JSON.convertValue(node,
            new com.fasterxml.jackson.core.type.TypeReference<java.util.TreeMap<String, Object>>() {});
        node.put("contentHash", LearnedPatternAuthorizationJson.hash(material));
    }

    private static void rehashRuntime(com.fasterxml.jackson.databind.node.ObjectNode node,
        LearnedSelectedProgramAuthorizationBundle retainedTimes) {
        node.remove("contentHash");
        var material = EvolutionValidationArtifactSupport.JSON.convertValue(node,
            new com.fasterxml.jackson.core.type.TypeReference<java.util.TreeMap<String, Object>>() {});
        // Preserve the exact unmodified Instant hash material; generic JSON numbers otherwise become doubles.
        @SuppressWarnings("unchecked")
        var temporal = material.containsKey("bundle") ? (java.util.Map<String, Object>) material.get("bundle") : material;
        temporal.put("issuedAt", retainedTimes.issuedAt());
        temporal.put("expiresAt", retainedTimes.expiresAt());
        node.put("contentHash", EvolutionProgramValidationJson.hash(material));
    }

    private Prepared prepared(Path root, String id) throws Exception {
        return prepared(ProgramFinalTestFixtures.create(root, id, false));
    }

    private Prepared prepared(ProgramFinalTestFixtures.Fixture fixture) throws Exception {
        fixture.execute();
        var store = new FileEvolutionRewriteProgramFinalTestAttemptStore(fixture.finalDirectory());
        var assessed = new EvolutionRewriteProgramQualificationService().assess(fixture.plan(), store, ProgramFinalTestFixtures.REVISION);
        var bundle = LearnedSelectedProgramAuthorizationBundle.create(assessed, fixture.manifest(), ISSUED, EXPIRES);
        return new Prepared(fixture, leaves(fixture, store, assessed, bundle));
    }

    private static List<LearnedPatternRuleAuthorizationService.SelectedLeafAuthorization> leaves(
        ProgramFinalTestFixtures.Fixture fixture, FileEvolutionRewriteProgramFinalTestAttemptStore store,
        EvolutionRewriteProgramQualificationAssessment assessed, LearnedSelectedProgramAuthorizationBundle bundle) throws Exception {
        var leaves = new ArrayList<LearnedPatternRuleAuthorizationService.SelectedLeafAuthorization>();
        for (var gene : fixture.plan().selectedConfiguration().candidate().genome().rewrites()) {
            leaves.add(new LearnedPatternRuleAuthorizationService().authorizeSelected(fixture.plan(), store,
                assessed.toCanonicalJson(), fixture.manifest(), bundle, gene.geneId(), ProgramFinalTestFixtures.REVISION, ISSUED));
        }
        return List.copyOf(leaves);
    }

    private record Prepared(ProgramFinalTestFixtures.Fixture fixture,
        List<LearnedPatternRuleAuthorizationService.SelectedLeafAuthorization> leaves) { }

    private static final class MutableClock extends Clock {
        private Instant now;
        MutableClock(Instant now) { this.now = now; }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
