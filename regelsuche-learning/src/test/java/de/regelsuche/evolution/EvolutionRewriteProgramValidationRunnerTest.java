package de.regelsuche.evolution;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.node.ObjectNode;
import de.regelsuche.evolution.DeterministicRewriteProgramMutator.MutationCatalog;
import de.regelsuche.evolution.EvolutionRewriteProgramHeldOutCommitment.Split;
import de.regelsuche.evolution.EvolutionRewriteProgramHeldOutRevealBundle.DifficultyTier;
import de.regelsuche.evolution.EvolutionRewriteProgramHeldOutRevealBundle.ExpectedTerminalClass;
import de.regelsuche.evolution.EvolutionRewriteProgramHeldOutRevealBundle.RevealCase;
import de.regelsuche.evolution.EvolutionRewriteProgramTrainSuite.PrimitiveWorkBudget;
import de.regelsuche.evolution.EvolutionStudyPlan.FitnessComponent;
import de.regelsuche.math.algorithms.equivalence.RationalFunctionNormalFormEquivalencePortAdapter;
import de.regelsuche.search.SearchHeuristic;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** All expressions here are public synthetic controls, never study heldouts. */
class EvolutionRewriteProgramValidationRunnerTest {
    @TempDir Path directory;
    private static final PrimitiveWorkBudget BUDGET =
        new PrimitiveWorkBudget(4, 32, 80, 4, 10_000);

    @Test
    void realTerminalPopulationReservesBeforeRevealAndSelectsCompleteProgram() throws Exception {
        Fixture fixture = fixture();
        var plan = fixture.plan(List.of(BUDGET));
        assertEquals(2, plan.configurations().size());
        assertEquals(1, plan.configurations().stream()
            .map(item -> item.candidate().genome().contentHash()).distinct().count());
        assertEquals(2, plan.configurations().stream()
            .map(EvolutionRewriteProgramValidationPlan.Configuration::contentHash)
            .distinct().count(), "different programs on one genome must not collapse");
        var store = new FileEvolutionRewriteProgramValidationAttemptStore(directory);

        var selection = new EvolutionRewriteProgramValidationRunner().executeOnce(
            plan, fixture.study(), fixture.manifest(), fixture.train(),
            () -> {
                assertEquals(plan.toCanonicalJson(),
                    Files.readString(store.reservationPath(plan)));
                return fixture.validation();
            }, store);

        assertEquals(2, selection.candidates().size());
        assertTrue(selection.hasSelection());
        for (var candidate : selection.candidates()) {
            assertEquals(List.of("validation_add_zero", "validation_mul_one"),
                candidate.cases().stream().map(
                    EvolutionRewriteProgramValidationEvidence.PairedCase::caseId).toList());
            assertTrue(candidate.cases().stream().allMatch(row ->
                row.baseline().complete() && row.candidate().complete()));
            assertTrue(candidate.cases().stream().allMatch(row ->
                row.baseline().reached() && row.candidate().reached()));
            assertTrue(candidate.cases().stream().allMatch(row ->
                row.candidate().totalWorkUnits() > 0));
            assertEquals(2, candidate.validationMetrics().reachedCases());
        }
        assertEquals(selection.toCanonicalJson(),
            Files.readString(store.selectionPath(plan)));
        if (Files.getFileStore(directory).supportsFileAttributeView("posix")) {
            assertEquals(java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"),
                Files.getPosixFilePermissions(store.selectionPath(plan)),
                "post-reveal paths remain private until the study permits publication");
        }
        var decoded = EvolutionRewriteProgramValidationSelection.fromCanonicalJson(
            selection.toCanonicalJson());
        assertEquals(selection, decoded);
        var handoff = decoded.handoff();
        assertEquals(selection.selectedConfigurationHash(),
            handoff.selectedConfiguration().contentHash());
        assertEquals("NOT_EVALUATED", handoff.finalTestStatus());
        assertEquals("NOT_EVALUATED", handoff.proofStatus());
        assertEquals("NOT_EVALUATED", handoff.promotionStatus());
        assertEquals(handoff, EvolutionRewriteProgramValidationHandoff.fromCanonicalJson(
            handoff.toCanonicalJson()));
        assertThrows(IOException.class, () -> new EvolutionRewriteProgramValidationRunner()
            .executeOnce(plan, fixture.study(), fixture.manifest(), fixture.train(),
                () -> { fail("a consumed study must not reveal again"); return null; }, store));
    }

    @Test
    void revealFailureRetainsUnknownWorkAndCannotBeReplacedByAnotherBudget() throws Exception {
        Fixture fixture = fixture();
        var plan = fixture.plan(List.of(BUDGET));
        var store = new FileEvolutionRewriteProgramValidationAttemptStore(directory);
        var failed = new EvolutionRewriteProgramValidationRunner().executeOnce(
            plan, fixture.study(), fixture.manifest(), fixture.train(),
            () -> { throw new IOException("synthetic unreadable reveal"); }, store);
        assertFalse(failed.hasSelection());
        assertEquals(2, failed.candidates().size());
        assertTrue(failed.candidates().stream().allMatch(item ->
            item.validationMetrics() == null && item.cases().size() == 2));
        var row = failed.candidates().getFirst().cases().getFirst();
        assertFalse(row.baseline().complete());
        assertNull(row.baseline().totalWorkUnits(), "unknown work must never become zero");
        assertTrue(row.baseline().failure().contains("REVEAL_FAILED"));
        assertThrows(IllegalStateException.class, failed::handoff);
        var replacement = fixture.plan(List.of(new PrimitiveWorkBudget(3, 16, 80, 3, 5_000)));
        assertNotEquals(plan.contentHash(), replacement.contentHash());
        assertEquals(store.reservationPath(plan), store.reservationPath(replacement));
        assertThrows(IOException.class, () -> new EvolutionRewriteProgramValidationRunner()
            .executeOnce(replacement, fixture.study(), fixture.manifest(), fixture.train(),
                () -> { fail("replacement must not reopen VALIDATION"); return null; },
                new FileEvolutionRewriteProgramValidationAttemptStore(directory)));
    }

    @Test
    void planRequiresCompletePopulationAndRetainsEveryBudgetDimension() throws Exception {
        Fixture fixture = fixture();
        var plan = fixture.plan(List.of(BUDGET));
        var changedWork = fixture.plan(List.of(new PrimitiveWorkBudget(4, 32, 80, 4, 9_000)));
        assertNotEquals(plan.configurations().getFirst().contentHash(),
            changedWork.configurations().getFirst().contentHash());
        assertEquals(plan, EvolutionRewriteProgramValidationPlan.fromCanonicalJson(
            plan.toCanonicalJson()));
        ObjectNode incomplete = object(plan.toCanonicalJson());
        ((com.fasterxml.jackson.databind.node.ArrayNode) incomplete.get("configurations")).remove(0);
        rehash(incomplete);
        var substituted = EvolutionRewriteProgramValidationPlan.fromCanonicalJson(incomplete.toString());
        assertThrows(IllegalArgumentException.class, () ->
            substituted.requireInputs(fixture.study(), fixture.manifest(), fixture.train()));

        ObjectNode changedProgram = object(plan.toCanonicalJson());
        ObjectNode retained = (ObjectNode) changedProgram.at("/configurations/0/retainedCandidate");
        String originalProgram = retained.get("planJson").asText();
        String mutatedProgram = originalProgram.replace("seed_", "changed_");
        assertNotEquals(originalProgram, mutatedProgram);
        retained.put("planJson", mutatedProgram);
        rehash(changedProgram);
        assertThrows(IllegalArgumentException.class, () ->
            EvolutionRewriteProgramValidationPlan.fromCanonicalJson(changedProgram.toString()));

        ObjectNode changedFamily = object(plan.toCanonicalJson());
        ((ObjectNode) changedFamily.at("/cases/0")).put("familyId", "substituted_family");
        rehash(changedFamily);
        assertThrows(IllegalArgumentException.class, () ->
            EvolutionRewriteProgramValidationPlan.fromCanonicalJson(changedFamily.toString()));
    }

    @Test
    void auditFailureKeepsRealSearchWorkAndCannotBecomeZeroCostFitness() throws Exception {
        Fixture fixture = fixture();
        var plan = fixture.plan(List.of(BUDGET));
        var reservation = new FileEvolutionRewriteProgramValidationAttemptStore(directory).reserve(plan);
        var authorization = EvolutionRewriteProgramHeldOutRevealAuthorization.validation(
            fixture.study(), fixture.manifest(), fixture.train(), reservation);
        var opened = fixture.validation().open(authorization, plan.commitment());
        var nativeAudit = new RationalFunctionNormalFormEquivalencePortAdapter();
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        var evaluator = new NativeEvolutionRewriteProgramValidationEvaluator((left, right, assumptions) -> {
            if (calls.incrementAndGet() == 2) { throw new IllegalStateException("synthetic audit failure"); }
            return nativeAudit.evaluate(left, right, assumptions);
        });
        var configuration = plan.configurations().getFirst();
        var rows = evaluator.evaluate(configuration, opened.cases());
        assertTrue(rows.getFirst().baseline().complete());
        assertFalse(rows.getFirst().candidate().complete());
        assertTrue(rows.getFirst().candidate().searchWork().exploredStates() > 0);
        assertEquals(1, rows.getFirst().candidate().pathAuditCalls());
        assertNull(rows.getFirst().candidate().totalWorkUnits());
        var evidence = EvolutionRewriteProgramValidationEvidence.CandidateEvidence.create(configuration, rows);
        assertNull(evidence.validationMetrics());
        assertFalse(evidence.eligible());
        assertEquals(2, rows.size(), "an audit failure must not remove later paired rows");
    }

    @Test
    void currentNativeMeasurementsMatchTheExistingPairedEvaluator() throws Exception {
        Fixture fixture = fixture();
        var plan = fixture.plan(List.of(BUDGET));
        var receipt = new FileEvolutionRewriteProgramValidationAttemptStore(directory).reserve(plan);
        var opened = fixture.validation().open(EvolutionRewriteProgramHeldOutRevealAuthorization.validation(
            fixture.study(), fixture.manifest(), fixture.train(), receipt), plan.commitment());
        // Reusing these PUBLIC synthetic expressions in a parity control reveals no held-out values.
        var syntheticSuite = EvolutionRewriteProgramTrainSuite.create("public_adapter_parity",
            EvolutionRewriteProgramTrainSuite.EvaluatorProfile.EXACT_RATIONAL_NORMAL_FORM_WITH_DECLARED_ASSUMPTIONS,
            opened.cases().stream().map(item -> new EvolutionRewriteProgramTrainSuite.TrainCase(
                item.caseId(), item.familyId(), item.inputExpression(), item.targetExpression(), item.assumptions())).toList(),
            new SearchHeuristic(4, 32, 1, 4, 80, 16), BUDGET);
        for (var configuration : plan.configurations()) {
            var current = new InformationParityRewriteProgramTrainFitnessEvaluator(syntheticSuite,
                Set.of(FitnessComponent.TRAIN_CASES_NEWLY_SOLVED),
                new RationalFunctionNormalFormEquivalencePortAdapter()).evaluate(configuration.candidate());
            var actual = new NativeEvolutionRewriteProgramValidationEvaluator().evaluate(configuration, opened.cases());
            for (int index = 0; index < actual.size(); index++) {
                var expected = current.cases().get(index);
                var row = actual.get(index);
                assertEquals(expected.baselineStatus(), row.baseline().terminalReason());
                assertEquals(expected.candidateStatus(), row.candidate().terminalReason());
                assertEquals(expected.baselineTotalWorkUnits(), row.baseline().totalWorkUnits());
                assertEquals(expected.candidateTotalWorkUnits(), row.candidate().totalWorkUnits());
                assertEquals(expected.baselineOuterSearchWork(), row.baseline().searchWork());
                assertEquals(expected.candidateOuterSearchWork(), row.candidate().searchWork());
                assertEquals(expected.baselineTransformationWork(), row.baseline().transformationWork());
                assertEquals(expected.candidateTransformationWork(), row.candidate().transformationWork());
                assertEquals(expected.candidatePathCorrectness(), row.candidate().correctness());
            }
        }
    }

    @Test
    void failedResultWriteDoesNotReopenTheStudy() throws Exception {
        Fixture fixture = fixture();
        var plan = fixture.plan(List.of(BUDGET));
        var store = new FileEvolutionRewriteProgramValidationAttemptStore(directory);
        Files.createDirectory(store.selectionPath(plan));
        assertThrows(IOException.class, () -> new EvolutionRewriteProgramValidationRunner().executeOnce(
            plan, fixture.study(), fixture.manifest(), fixture.train(), fixture::validation, store));
        assertEquals(plan.toCanonicalJson(), Files.readString(store.reservationPath(plan)));
        assertThrows(IOException.class, () -> new EvolutionRewriteProgramValidationRunner().executeOnce(
            plan, fixture.study(), fixture.manifest(), fixture.train(),
            () -> { fail("write failure cannot authorize another observation"); return null; }, store));
    }

    @Test
    void importRequiresCompleteRowsAndWorkFieldsAgainstTheExpectedPlan() throws Exception {
        Fixture fixture = fixture();
        var plan = fixture.plan(List.of(BUDGET));
        var selection = new EvolutionRewriteProgramValidationRunner().executeOnce(
            plan, fixture.study(), fixture.manifest(), fixture.train(), fixture::validation,
            new FileEvolutionRewriteProgramValidationAttemptStore(directory));
        ObjectNode missingRow = object(selection.toCanonicalJson());
        ((com.fasterxml.jackson.databind.node.ArrayNode) missingRow.at("/candidates/0/cases")).remove(0);
        rehash(missingRow);
        assertThrows(IllegalArgumentException.class, () ->
            EvolutionRewriteProgramValidationSelection.fromCanonicalJson(missingRow.toString(), plan));
        ObjectNode missingWork = object(selection.toCanonicalJson());
        ((ObjectNode) missingWork.at("/candidates/0/cases/0/candidate/searchWork")).remove("expandedStates");
        rehash(missingWork);
        assertThrows(IllegalArgumentException.class, () ->
            EvolutionRewriteProgramValidationSelection.fromCanonicalJson(missingWork.toString(), plan));
        ObjectNode substitutedPath = object(selection.toCanonicalJson());
        var path = (com.fasterxml.jackson.databind.node.ArrayNode) substitutedPath.at("/candidates/0/cases/0/candidate/path");
        path.set(path.size() - 1, new com.fasterxml.jackson.databind.node.TextNode("unrelated_target"));
        rehash(substitutedPath);
        assertThrows(IllegalArgumentException.class, () ->
            EvolutionRewriteProgramValidationSelection.fromCanonicalJson(substitutedPath.toString(), plan));
        var otherPlan = fixture.plan(List.of(new PrimitiveWorkBudget(4, 32, 80, 4, 9_000)));
        assertThrows(IllegalArgumentException.class, () ->
            EvolutionRewriteProgramValidationSelection.fromCanonicalJson(selection.toCanonicalJson(), otherPlan));
    }

    @Test
    void recomputedHashesCannotHideSelectedIdentityOrPrematurePromotion() throws Exception {
        Fixture fixture = fixture();
        var selection = new EvolutionRewriteProgramValidationRunner().executeOnce(
            fixture.plan(List.of(BUDGET)), fixture.study(), fixture.manifest(), fixture.train(),
            fixture::validation, new FileEvolutionRewriteProgramValidationAttemptStore(directory));
        ObjectNode switched = object(selection.toCanonicalJson());
        String other = selection.plan().configurations().stream()
            .map(EvolutionRewriteProgramValidationPlan.Configuration::contentHash)
            .filter(hash -> !hash.equals(selection.selectedConfigurationHash())).findFirst().orElseThrow();
        switched.put("selectedConfigurationHash", other);
        rehash(switched);
        assertThrows(IllegalArgumentException.class, () ->
            EvolutionRewriteProgramValidationSelection.fromCanonicalJson(switched.toString()));

        ObjectNode advanced = object(selection.handoff().toCanonicalJson());
        advanced.put("promotionStatus", "QUALIFIED");
        rehash(advanced);
        assertThrows(IllegalArgumentException.class, () ->
            EvolutionRewriteProgramValidationHandoff.fromCanonicalJson(advanced.toString()));

        ObjectNode forged = object(selection.toCanonicalJson());
        ((ObjectNode) forged.at("/candidates/0/validationMetrics")).put("reachedCases", 999);
        rehash(forged);
        assertThrows(IllegalArgumentException.class, () ->
            EvolutionRewriteProgramValidationSelection.fromCanonicalJson(forged.toString()));
    }

    private static ObjectNode object(String json) throws Exception {
        return (ObjectNode) EvolutionValidationArtifactSupport.JSON.readTree(json);
    }

    @Test
    void confirmedPathCannotDiscardItsAuditCallsWhileRehashingTheTotal() throws Exception {
        Fixture fixture = fixture();
        var plan = fixture.plan(List.of(BUDGET));
        var selection = new EvolutionRewriteProgramValidationRunner().executeOnce(
            plan, fixture.study(), fixture.manifest(), fixture.train(), fixture::validation,
            new FileEvolutionRewriteProgramValidationAttemptStore(directory));
        for (String side : List.of("baseline", "candidate")) {
            ObjectNode forged = object(selection.toCanonicalJson());
            ObjectNode measured = (ObjectNode) forged.at("/candidates/0/cases/0/" + side);
            long calls = measured.get("pathAuditCalls").asLong();
            assertTrue(calls > 0);
            measured.put("pathAuditCalls", 0);
            measured.put("totalWorkUnits", measured.get("totalWorkUnits").asLong() - calls);
            rehash(forged);
            assertThrows(IllegalArgumentException.class, () ->
                EvolutionRewriteProgramValidationSelection.fromCanonicalJson(forged.toString(), plan), side);
        }
    }

    @Test
    void rehashedMeasurementCannotExceedItsBoundPrimitiveAllowance() throws Exception {
        Fixture fixture = fixture();
        var plan = fixture.plan(List.of(BUDGET));
        var selection = new EvolutionRewriteProgramValidationRunner().executeOnce(
            plan, fixture.study(), fixture.manifest(), fixture.train(), fixture::validation,
            new FileEvolutionRewriteProgramValidationAttemptStore(directory));
        for (String side : List.of("baseline", "candidate")) {
            ObjectNode forged = object(selection.toCanonicalJson());
            ((ObjectNode) forged.at("/candidates/0/cases/0/" + side))
                .put("primitiveSteps", BUDGET.maxPrimitiveSteps() + 1);
            rehash(forged);
            assertThrows(IllegalArgumentException.class, () ->
                EvolutionRewriteProgramValidationSelection.fromCanonicalJson(forged.toString(), plan), side);
        }
    }

    @Test
    void rehashedCompleteWorkCannotExceedItsBoundTotalBudget() throws Exception {
        Fixture fixture = fixture();
        var plan = fixture.plan(List.of(BUDGET));
        var selection = new EvolutionRewriteProgramValidationRunner().executeOnce(
            plan, fixture.study(), fixture.manifest(), fixture.train(), fixture::validation,
            new FileEvolutionRewriteProgramValidationAttemptStore(directory));
        for (String side : List.of("baseline", "candidate")) {
            ObjectNode forged = object(selection.toCanonicalJson());
            ObjectNode measured = (ObjectNode) forged.at("/candidates/0/cases/0/" + side);
            ObjectNode work = (ObjectNode) measured.get("searchWork");
            work.put("enqueuedStates", work.get("enqueuedStates").asLong() + BUDGET.maxWorkUnits());
            measured.put("totalWorkUnits", measured.get("totalWorkUnits").asLong() + BUDGET.maxWorkUnits());
            rehash(forged);
            assertThrows(IllegalArgumentException.class, () ->
                EvolutionRewriteProgramValidationSelection.fromCanonicalJson(forged.toString(), plan), side);
        }
    }

    @Test
    void nativeWorkBudgetStopsRetainMeasuredBatchOverrun() throws Exception {
        Fixture fixture = fixture();
        var budget = new PrimitiveWorkBudget(4, 32, 80, 4, 5);
        var plan = fixture.plan(List.of(budget));
        var selection = new EvolutionRewriteProgramValidationRunner().executeOnce(
            plan, fixture.study(), fixture.manifest(), fixture.train(), fixture::validation,
            new FileEvolutionRewriteProgramValidationAttemptStore(directory));
        for (var candidate : selection.candidates()) {
            for (var row : candidate.cases()) {
                for (var measurement : List.of(row.baseline(), row.candidate())) {
                    assertFalse(measurement.complete());
                    assertFalse(measurement.reached());
                    assertEquals("WORK_BUDGET", measurement.terminalReason());
                    assertEquals("WORK_BUDGET", measurement.failure());
                    assertNull(measurement.totalWorkUnits());
                    assertTrue(EvolutionRewriteProgramValidationEvidence.sum(measurement.searchWork().totalWorkUnits(),
                        measurement.transformationWork().totalWorkUnits()) > budget.maxWorkUnits(),
                        "v1 stops after charging the entire observed transformation batch");
                }
            }
        }
        assertEquals(selection, EvolutionRewriteProgramValidationSelection.fromCanonicalJson(
            selection.toCanonicalJson(), plan));
    }

    @Test
    void nonPosixLedgerCannotAuthorizeLazyReveal() throws Exception {
        Fixture fixture = fixture();
        var plan = fixture.plan(List.of(BUDGET));
        try (var fileSystem = FileSystems.newFileSystem(directory.resolve("public-non-posix-control.zip"),
                Map.of("create", "true"))) {
            Path ledger = Files.createDirectory(fileSystem.getPath("/ledger"));
            assertFalse(Files.getFileStore(ledger).supportsFileAttributeView("posix"));
            var store = new FileEvolutionRewriteProgramValidationAttemptStore(ledger);
            var reveals = new AtomicInteger();
            assertThrows(IOException.class, () -> new EvolutionRewriteProgramValidationRunner().executeOnce(
                plan, fixture.study(), fixture.manifest(), fixture.train(),
                () -> { reveals.incrementAndGet(); return fixture.validation(); }, store),
                () -> "unsupported private custody must reject before reveal; reveals=" + reveals.get());
            assertEquals(0, reveals.get());
            assertFalse(Files.exists(store.reservationPath(plan)));
        }
    }

    @Test
    void failedDirectoryForceCannotIssueARevealCapability() throws Exception {
        Fixture fixture = fixture();
        var plan = fixture.plan(List.of(BUDGET));
        try (var fileSystem = FileSystems.newFileSystem(directory.resolve("public-force-failure-control.zip"),
                Map.of("create", "true", "enablePosixFileAttributes", "true",
                    "defaultPermissions", "rwx------"))) {
            Path ledger = Files.createDirectory(fileSystem.getPath("/ledger"));
            assertTrue(Files.getFileStore(ledger).supportsFileAttributeView("posix"));
            var store = new FileEvolutionRewriteProgramValidationAttemptStore(ledger);
            var reveals = new AtomicInteger();
            assertThrows(IOException.class, () -> new EvolutionRewriteProgramValidationRunner().executeOnce(
                plan, fixture.study(), fixture.manifest(), fixture.train(),
                () -> { reveals.incrementAndGet(); return fixture.validation(); }, store),
                () -> "failed directory force must reject before reveal; reveals=" + reveals.get());
            assertEquals(0, reveals.get());
            assertEquals(plan.toCanonicalJson(), Files.readString(store.reservationPath(plan)),
                "the file was written and forced before this provider rejected the directory force");
            assertFalse(Files.exists(store.selectionPath(plan)));
            assertThrows(IOException.class, () -> new FileEvolutionRewriteProgramValidationAttemptStore(ledger)
                .reserve(plan), "a failed force must leave the attempt consumed");
        }
    }

    @Test
    void writableLedgerDirectoryCannotAuthorizeLazyReveal() throws Exception {
        Fixture fixture = fixture();
        var plan = fixture.plan(List.of(BUDGET));
        Path ledger = Files.createDirectory(directory.resolve("public-writable-ledger"));
        Files.setPosixFilePermissions(ledger, PosixFilePermissions.fromString("rwxrwxrwx"));
        var store = new FileEvolutionRewriteProgramValidationAttemptStore(ledger);
        var reveals = new AtomicInteger();
        assertThrows(IOException.class, () -> new EvolutionRewriteProgramValidationRunner().executeOnce(
            plan, fixture.study(), fixture.manifest(), fixture.train(),
            () -> { reveals.incrementAndGet(); return fixture.validation(); }, store),
            () -> "other writers must not control the attempt ledger; reveals=" + reveals.get());
        assertEquals(0, reveals.get());
        assertFalse(Files.exists(store.reservationPath(plan)));
    }

    @Test
    void symbolicLinkAncestorCannotRedirectTheAuthoritativeLedger() throws Exception {
        Fixture fixture = fixture();
        var plan = fixture.plan(List.of(BUDGET));
        Path actual = Files.createDirectory(directory.resolve("public-actual-directory"));
        Path alias = Files.createSymbolicLink(directory.resolve("public-directory-alias"), actual);
        var store = new FileEvolutionRewriteProgramValidationAttemptStore(alias.resolve("ledger"));
        var reveals = new AtomicInteger();
        assertThrows(IOException.class, () -> new EvolutionRewriteProgramValidationRunner().executeOnce(
            plan, fixture.study(), fixture.manifest(), fixture.train(),
            () -> { reveals.incrementAndGet(); return fixture.validation(); }, store),
            () -> "a linked ancestor must not redirect custody; reveals=" + reveals.get());
        assertEquals(0, reveals.get());
        assertFalse(Files.exists(store.reservationPath(plan)));
    }

    @Test
    void changedReservationPermissionsCannotAuthorizeASelectionWrite() throws Exception {
        Fixture fixture = fixture();
        var plan = fixture.plan(List.of(BUDGET));
        var store = new FileEvolutionRewriteProgramValidationAttemptStore(directory.resolve("ledger"));
        assertThrows(IOException.class, () -> new EvolutionRewriteProgramValidationRunner().executeOnce(
            plan, fixture.study(), fixture.manifest(), fixture.train(), () -> {
                Files.setPosixFilePermissions(store.reservationPath(plan),
                    PosixFilePermissions.fromString("rw-r--r--"));
                return fixture.validation();
            }, store));
        assertFalse(Files.exists(store.selectionPath(plan)));
        assertThrows(IOException.class, () -> new FileEvolutionRewriteProgramValidationAttemptStore(
            directory.resolve("ledger")).reserve(plan));
    }

    @Test
    void retainedSelectionRequiresPrivateRootAndBothBoundPrivateRecords() throws Exception {
        Fixture fixture = fixture();
        var plan = fixture.plan(List.of(BUDGET));
        Path ledger = directory.resolve("new-parent/new-ledger");
        var store = new FileEvolutionRewriteProgramValidationAttemptStore(ledger);
        var selection = new EvolutionRewriteProgramValidationRunner().executeOnce(
            plan, fixture.study(), fixture.manifest(), fixture.train(), fixture::validation, store);
        assertEquals(PosixFilePermissions.fromString("rwx------"), Files.getPosixFilePermissions(ledger));
        assertEquals(PosixFilePermissions.fromString("rwx------"), Files.getPosixFilePermissions(ledger.getParent()));
        assertEquals(selection, store.readSelection(plan));

        Files.setPosixFilePermissions(ledger, PosixFilePermissions.fromString("rwxrwxrwx"));
        assertThrows(IOException.class, () -> store.readSelection(plan));
        Files.setPosixFilePermissions(ledger, PosixFilePermissions.fromString("rwx------"));

        var differentPlan = fixture.plan(List.of(new PrimitiveWorkBudget(4, 32, 80, 4, 5)));
        Files.writeString(store.reservationPath(plan), differentPlan.toCanonicalJson());
        assertThrows(IOException.class, () -> store.readSelection(plan));
        Files.writeString(store.reservationPath(plan), plan.toCanonicalJson());
        Files.setPosixFilePermissions(store.reservationPath(plan), PosixFilePermissions.fromString("rw-r--r--"));
        assertThrows(IOException.class, () -> store.readSelection(plan));
        Files.setPosixFilePermissions(store.reservationPath(plan), PosixFilePermissions.fromString("rw-------"));

        Files.setPosixFilePermissions(store.selectionPath(plan), PosixFilePermissions.fromString("rw-r--r--"));
        assertThrows(IOException.class, () -> store.readSelection(plan));
        Files.setPosixFilePermissions(store.selectionPath(plan), PosixFilePermissions.fromString("rw-------"));
        Path retained = directory.resolve("public-retained-selection.json");
        Files.move(store.selectionPath(plan), retained);
        Files.createSymbolicLink(store.selectionPath(plan), retained);
        assertThrows(IOException.class, () -> store.readSelection(plan));
    }

    @Test
    void nativeRefutationOverridesFixtureLabelsAndBlocksEveryConfiguration() throws Exception {
        Fixture fixture = fixture(true);
        var selection = new EvolutionRewriteProgramValidationRunner().executeOnce(
            fixture.plan(List.of(BUDGET)), fixture.study(), fixture.manifest(), fixture.train(),
            fixture::validation, new FileEvolutionRewriteProgramValidationAttemptStore(directory));
        assertFalse(selection.hasSelection());
        assertEquals(2, selection.candidates().size());
        for (var candidate : selection.candidates()) {
            var row = candidate.cases().getFirst();
            assertTrue(row.candidate().reached());
            assertEquals(EvolutionRewriteProgramTrainFitnessEvidence.PathCorrectness.REFUTED,
                row.candidate().correctness(), "the public case label says CONFIRMED; actual native audit refutes it");
            assertEquals(1, candidate.validationMetrics().correctnessFailures());
            assertFalse(candidate.eligible());
            assertTrue(row.candidate().totalWorkUnits() > 0);
        }
    }

    @Test
    void incompleteOrInventedSearchOutcomesCannotBecomeCompleteNoMatchEvidence() throws Exception {
        Fixture fixture = fixture();
        var plan = fixture.plan(List.of(BUDGET));
        var selection = new EvolutionRewriteProgramValidationRunner().executeOnce(
            plan, fixture.study(), fixture.manifest(), fixture.train(), fixture::validation,
            new FileEvolutionRewriteProgramValidationAttemptStore(directory));
        var measured = selection.candidates().getFirst().cases().getFirst().candidate();
        for (String terminal : List.of("INCOMPLETE_EXPANSION", "INVENTED_SUCCESS", "REACHED")) {
            assertThrows(IllegalArgumentException.class, () ->
                new EvolutionRewriteProgramValidationEvidence.Measurement(terminal, false, -1, 0,
                    List.of(), false, EvolutionRewriteProgramTrainFitnessEvidence.PathCorrectness.NOT_EVALUATED,
                    measured.searchWork(), measured.transformationWork(), measured.pathAuditCalls(),
                    measured.totalWorkUnits(), ""), terminal);
        }
    }

    private static void rehash(ObjectNode value) throws Exception {
        value.remove("contentHash");
        var material = EvolutionValidationArtifactSupport.JSON.convertValue(
            value, new com.fasterxml.jackson.core.type.TypeReference<java.util.TreeMap<String, Object>>() {});
        value.put("contentHash", EvolutionValidationArtifactSupport.hash(material));
    }

    private static Fixture fixture() {
        return fixture(false);
    }

    private static Fixture fixture(boolean refuted) {
        return fixture(refuted, false);
    }

    private static Fixture fixture(boolean refuted, boolean evolve) {
        String studyId = "public_program_validation_bridge";
        var validation = EvolutionRewriteProgramHeldOutRevealBundle.create(studyId, Split.VALIDATION,
            List.of(RevealCase.create("validation_add_zero", "validation_zero_family", "a+0", refuted ? "a+1" : "a",
                    List.of(), DifficultyTier.STANDARD, ExpectedTerminalClass.CONFIRMED),
                RevealCase.create("validation_mul_one", "validation_one_family", "b*1", "b",
                    List.of(), DifficultyTier.STANDARD, ExpectedTerminalClass.CONFIRMED)));
        var finalBundle = EvolutionRewriteProgramHeldOutRevealBundle.create(studyId, Split.FINAL_TEST,
            List.of(RevealCase.create("final_public_case", "final_public_family", "c-c", "0",
                List.of(), DifficultyTier.STANDARD, ExpectedTerminalClass.CONFIRMED)));
        var manifest = EvolutionSplitManifest.create(studyId, hash("public-corpus"), hash("public-features"),
            List.of(new EvolutionSplitManifest.CaseReference("train_public_case", "train_public_family",
                hash("train-exact"), hash("train-alpha"), hash("train-input"), hash("train-target"))),
            validation.splitReferences(), finalBundle.splitReferences());
        var genome = EvolutionGenome.create(EvolutionGenome.Objective.OPEN_TARGET_OPERATOR,
            manifest.trainingScope(), List.of(EvolutionGenomeTestFixtures.gene("add_zero", "?A+0", refuted ? "?A+1" : "?A"),
                EvolutionGenomeTestFixtures.gene("mul_one", "?A*1", "?A")),
            List.of(new EvolutionGenome.FeatureWeight(EvolutionGenome.FitnessSignal.UNSEEN_TRAIN_CASES_SOLVED, 500),
                new EvolutionGenome.FeatureWeight(EvolutionGenome.FitnessSignal.COUNTEREXAMPLE_RISK, -500)),
            EvolutionGenome.GuardPolicy.strictDefault(), new EvolutionGenome.ResourceBudget(16, 128, 12, 32, 80),
            List.of("core.ast-rewrite"), List.of());
        var seeds = List.of(EvolutionRewriteProgramCandidate.create(genome,
                EvolutionRewriteProgramPlan.create(genome,
                    new EvolutionRewriteProgramPlan.Source("seed_add_zero", List.of("add_zero")), 12, 12)),
            EvolutionRewriteProgramCandidate.create(genome,
                EvolutionRewriteProgramPlan.create(genome,
                    new EvolutionRewriteProgramPlan.Repeat("repeat_mul_one",
                        new EvolutionRewriteProgramPlan.Source("seed_mul_one", List.of("mul_one")), 1, 2), 12, 12)));
        var suite = EvolutionRewriteProgramTrainSuite.create("public_bridge_train",
            EvolutionRewriteProgramTrainSuite.EvaluatorProfile.EXACT_RATIONAL_NORMAL_FORM_WITH_DECLARED_ASSUMPTIONS,
            List.of(new EvolutionRewriteProgramTrainSuite.TrainCase(
                "train_public_case", "train_public_family", "x*x", "x^2", List.of())),
            new SearchHeuristic(4, 32, 1, 4, 80, 16), BUDGET);
        var catalog = new MutationCatalog(List.of(), List.of(), List.of(), List.of(), List.of("mul_one"));
        var study = EvolutionRewriteProgramStudyPlan.create(studyId, manifest, suite, catalog, seeds,
            List.of(EvolutionRewriteProgramMutationKind.values()),
            new EvolutionStudyPlan.PopulationPolicy(evolve ? 4 : 2, evolve ? 2 : 1, 1, 2, 1, 1, 1234L),
            List.of(new EvolutionStudyPlan.FitnessWeight(FitnessComponent.TRAIN_CASES_NEWLY_SOLVED, 700),
                new EvolutionStudyPlan.FitnessWeight(FitnessComponent.CANDIDATE_COMPLEXITY, 300)),
            new EvolutionStudyPlan.StudyBudget(evolve ? 32 : 1, evolve ? 32 : 2, 8, 1, 1));
        var train = new RetainedProtocolBoundEvolutionRewriteProgramPopulationRunner().run(
            study, manifest, suite, seeds, catalog,
            new ProtocolBoundInformationParityRewriteProgramTrainFitnessEvaluator(suite,
                Set.of(FitnessComponent.TRAIN_CASES_NEWLY_SOLVED, FitnessComponent.CANDIDATE_COMPLEXITY),
                new RationalFunctionNormalFormEquivalencePortAdapter()));
        return new Fixture(study, manifest, train, validation);
    }

    @Test
    void actualMutatedTrainSurvivorsAllEnterValidationWithTheirProgramPayloads() throws Exception {
        Fixture fixture = fixture(false, true);
        var run = fixture.train().retainedPopulation().populationRun();
        assertTrue(run.generationReports().stream().anyMatch(report -> !report.lineage().isEmpty()));
        assertTrue(run.finalCandidateHashes().stream().anyMatch(hash ->
            !fixture.study().seedCandidateHashes().contains(hash)), "exercise a real evolved terminal program");
        var plan = fixture.plan(List.of(BUDGET));
        assertEquals(run.finalCandidateHashes(), plan.configurations().stream()
            .map(item -> item.retainedCandidate().candidateHash()).sorted().toList());
        var selection = new EvolutionRewriteProgramValidationRunner().executeOnce(
            plan, fixture.study(), fixture.manifest(), fixture.train(), fixture::validation,
            new FileEvolutionRewriteProgramValidationAttemptStore(directory));
        assertEquals(run.finalCandidateHashes().size(), selection.candidates().size());
        assertTrue(selection.candidates().stream().allMatch(item -> item.cases().size() == 2));
        assertTrue(selection.hasSelection());
        assertEquals(selection.selectedConfigurationHash(), selection.handoff().selectedConfiguration().contentHash());
    }

    private static String hash(String value) { return EvolutionGenome.hash(value); }

    private record Fixture(EvolutionRewriteProgramStudyPlan study, EvolutionSplitManifest manifest,
        ProtocolBoundRetainedEvolutionRewriteProgramPopulationRun train,
        EvolutionRewriteProgramHeldOutRevealBundle validation) {
        EvolutionRewriteProgramValidationPlan plan(List<PrimitiveWorkBudget> budgets) {
            return EvolutionRewriteProgramValidationPlan.create(study, manifest, train,
                validation.commitment(), budgets);
        }
    }
}
