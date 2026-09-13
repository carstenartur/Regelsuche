package de.regelsuche.evolution;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EvolutionRewriteProgramFinalTestRunnerTest {
    @TempDir Path directory;

    @Test
    void reservesTheCompleteSelectedProgramBeforeLazyRevealAndRunsNativePairs() throws Exception {
        var fixture = ProgramFinalTestFixtures.create(directory);
        var plan = fixture.plan();
        var store = new FileEvolutionRewriteProgramFinalTestAttemptStore(fixture.finalDirectory());
        var result = new EvolutionRewriteProgramFinalTestRunner().executeOnce(plan, fixture.study(), fixture.manifest(),
            fixture.train(), fixture.validationStore(), () -> {
                var reserved = EvolutionRewriteProgramFinalTestReservation.fromCanonicalJson(
                    Files.readString(store.reservationPath(plan)));
                assertEquals(plan, reserved.plan());
                assertEquals(fixture.handoff().selectedConfiguration(), reserved.plan().selectedConfiguration());
                return fixture.finalBundle();
            }, store);
        assertEquals(List.of("final_one", "final_two"), result.cases().stream()
            .map(EvolutionRewriteProgramValidationEvidence.PairedCase::caseId).toList());
        assertEquals(2, result.summary().completedCases());
        assertEquals(0, result.summary().technicalFailures());
        assertEquals(2, result.summary().reachedCases());
        assertTrue(result.summary().candidateWorkUnits() > 0);
        assertTrue(result.qualificationEligible());
        assertEquals("COMPLETED", result.finalTestStatus());
        assertEquals("NOT_EVALUATED", result.proofStatus());
        assertEquals("NOT_EVALUATED", result.promotionStatus());
        assertEquals(result, store.readEvaluation(plan));
        assertEquals(result, EvolutionRewriteProgramFinalTestEvaluation.fromCanonicalJson(result.toCanonicalJson(), plan));
        if (Files.getFileStore(directory).supportsFileAttributeView("posix")) {
            assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(store.evaluationPath(plan)));
            assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(store.reservationPath(plan)));
        }
        assertThrows(IOException.class, fixture::execute);
    }

    @Test
    void concurrentCallersCannotObserveTheFinalBundleMoreThanOnce() throws Exception {
        var fixture = ProgramFinalTestFixtures.create(directory);
        AtomicInteger reveals = new AtomicInteger();
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(4)) {
            Callable<Boolean> attempt = () -> {
                start.await();
                try {
                    new EvolutionRewriteProgramFinalTestRunner().executeOnce(fixture.plan(), fixture.study(),
                        fixture.manifest(), fixture.train(), fixture.validationStore(), () -> {
                            reveals.incrementAndGet();
                            return fixture.finalBundle();
                        }, new FileEvolutionRewriteProgramFinalTestAttemptStore(fixture.finalDirectory()));
                    return true;
                } catch (java.nio.file.FileAlreadyExistsException expected) { return false; }
            };
            var futures = List.of(pool.submit(attempt), pool.submit(attempt), pool.submit(attempt), pool.submit(attempt));
            start.countDown();
            int completed = 0;
            for (var future : futures) { if (future.get(20, TimeUnit.SECONDS)) { completed++; } }
            assertEquals(1, completed);
        }
        assertEquals(1, reveals.get());
    }

    @Test
    void processCrashAfterForcedReservationStillConsumesTheStudy() throws Exception {
        var fixture = ProgramFinalTestFixtures.create(directory);
        Path planPath = directory.resolve("synthetic-plan.json");
        Files.writeString(planPath, fixture.plan().toCanonicalJson());
        Process child = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "-cp", childClasspath(), SyntheticProgramFinalReservationCrash.class.getName(),
            planPath.toString(), fixture.finalDirectory().toString()).redirectErrorStream(true).start();
        assertTrue(child.waitFor(20, TimeUnit.SECONDS));
        assertEquals(0, child.exitValue(), new String(child.getInputStream().readAllBytes()));
        var store = new FileEvolutionRewriteProgramFinalTestAttemptStore(fixture.finalDirectory());
        assertTrue(Files.exists(store.reservationPath(fixture.plan())));
        assertFalse(Files.exists(store.evaluationPath(fixture.plan())));
        assertThrows(IOException.class, fixture::execute);
    }

    @Test
    void revealAndResultWriteFailuresDoNotPermitReplacementOrRetry() throws Exception {
        var fixture = ProgramFinalTestFixtures.create(directory.resolve("reveal"));
        var store = new FileEvolutionRewriteProgramFinalTestAttemptStore(fixture.finalDirectory());
        var result = new EvolutionRewriteProgramFinalTestRunner().executeOnce(fixture.plan(), fixture.study(),
            fixture.manifest(), fixture.train(), fixture.validationStore(),
            () -> { throw new IOException("synthetic reveal failure"); }, store);
        assertEquals(2, result.cases().size());
        assertEquals(2, result.summary().technicalFailures());
        assertNull(result.summary().candidateWorkUnits());
        assertFalse(result.qualificationEligible());
        assertEquals(result, store.readEvaluation(fixture.plan()));
        assertThrows(IOException.class, fixture::execute);

        var writeFixture = ProgramFinalTestFixtures.create(directory.resolve("write"));
        var writeStore = new FileEvolutionRewriteProgramFinalTestAttemptStore(writeFixture.finalDirectory());
        Files.createDirectories(writeStore.evaluationPath(writeFixture.plan()));
        assertThrows(IOException.class, writeFixture::execute);
        assertTrue(Files.exists(writeStore.reservationPath(writeFixture.plan())));
        assertThrows(IOException.class, writeFixture::execute);
    }

    @Test
    void oldAndCombinedAdaptersShareOneStudyAttemptIdentity() throws Exception {
        var fixture = ProgramFinalTestFixtures.create(directory);
        var plan = fixture.plan();
        var store = new FileEvolutionRewriteProgramFinalTestAttemptStore(fixture.finalDirectory());
        var selected = fixture.handoff().selection().candidates().stream()
            .filter(item -> item.configurationHash().equals(fixture.handoff().selection().selectedConfigurationHash()))
            .findFirst().orElseThrow().validationMetrics();
        // Separate historical genome-only contract, never used as combined program evidence.
        var legacySelection = EvolutionValidationSelection.create(fixture.study().contentHash(), fixture.manifest().contentHash(),
            fixture.train().retainedPopulation().populationRun().contentHash(),
            fixture.handoff().selection().plan().commitment().contentHash(),
            selected.cases().stream().map(EvolutionValidationCaseEvidence::caseId).toList(), List.of(selected));
        var legacySuite = EvolutionFinalTestSuite.create(fixture.study().contentHash(), fixture.manifest().contentHash(),
            EvolutionGenome.hash("synthetic-legacy-baseline"), fixture.finalBundle().commitment().cases().stream()
                .map(item -> new EvolutionFinalTestSuite.CaseDefinition(item.caseId(),
                    fixture.manifest().finalTestCases().stream().filter(ref -> ref.caseId().equals(item.caseId()))
                        .findFirst().orElseThrow().familyId(), item.revealEntryHash())).toList());
        var legacyReservation = EvolutionFinalTestReservation.create(legacySelection, legacySuite);
        var legacyStore = new FileEvolutionFinalTestAttemptStore(fixture.finalDirectory());
        assertEquals(legacyStore.reservationPath(legacyReservation.runIdentity()), store.reservationPath(plan));
        legacyStore.reserve(legacyReservation);
        assertThrows(IOException.class, fixture::execute);

        Path otherDirectory = directory.resolve("opposite-direction");
        var combinedFirst = new FileEvolutionRewriteProgramFinalTestAttemptStore(otherDirectory);
        combinedFirst.reserve(plan);
        assertThrows(IOException.class, () -> new FileEvolutionFinalTestAttemptStore(otherDirectory).reserve(legacyReservation));
    }

    @Test
    void missingPersistedValidationIsRejectedBeforeFinalReservation() throws Exception {
        var fixture = ProgramFinalTestFixtures.create(directory);
        Files.delete(fixture.validationStore().selectionPath(fixture.handoff().selection().plan()));
        assertThrows(IOException.class, fixture::execute);
        var finalStore = new FileEvolutionRewriteProgramFinalTestAttemptStore(fixture.finalDirectory());
        assertFalse(Files.exists(finalStore.reservationPath(fixture.plan())));
    }

    @Test
    void anOrphanedValidationSelectionCannotAuthorizeFinalTest() throws Exception {
        var fixture = ProgramFinalTestFixtures.create(directory);
        Files.delete(fixture.validationStore().reservationPath(fixture.handoff().selection().plan()));
        AtomicInteger reveals = new AtomicInteger();
        var store = new FileEvolutionRewriteProgramFinalTestAttemptStore(fixture.finalDirectory());
        assertThrows(IOException.class, () -> new EvolutionRewriteProgramFinalTestRunner().executeOnce(fixture.plan(),
            fixture.study(), fixture.manifest(), fixture.train(), fixture.validationStore(), () -> {
                reveals.incrementAndGet();
                return fixture.finalBundle();
            }, store));
        assertEquals(0, reveals.get());
        assertFalse(Files.exists(store.reservationPath(fixture.plan())));
    }

    @Test
    void rehashedPathSummaryAndPrematureGateClaimsAreRejected() throws Exception {
        var fixture = ProgramFinalTestFixtures.create(directory);
        var result = fixture.execute();
        ObjectNode changedPath = object(result.toCanonicalJson());
        var path = (ArrayNode) changedPath.at("/cases/0/candidate/path");
        path.set(path.size() - 1, new com.fasterxml.jackson.databind.node.TextNode("wrong_final_target"));
        rehash(changedPath);
        assertThrows(IllegalArgumentException.class, () ->
            EvolutionRewriteProgramFinalTestEvaluation.fromCanonicalJson(changedPath.toString(), fixture.plan()));
        ObjectNode changedAggregate = object(result.toCanonicalJson());
        ((ObjectNode) changedAggregate.get("summary")).put("reachedCases", 999);
        rehash(changedAggregate);
        assertThrows(IllegalArgumentException.class, () ->
            EvolutionRewriteProgramFinalTestEvaluation.fromCanonicalJson(changedAggregate.toString(), fixture.plan()));
        ObjectNode advanced = object(result.toCanonicalJson());
        advanced.put("promotionStatus", "QUALIFIED");
        rehash(advanced);
        assertThrows(IllegalArgumentException.class, () ->
            EvolutionRewriteProgramFinalTestEvaluation.fromCanonicalJson(advanced.toString(), fixture.plan()));
    }

    @Test
    void finalEvidenceCannotClaimSuccessBeyondTheFullSelectedBudget() throws Exception {
        var fixture = ProgramFinalTestFixtures.create(directory);
        var result = fixture.execute();
        ObjectNode changed = object(result.toCanonicalJson());
        ((ObjectNode) changed.at("/cases/0/candidate")).put("primitiveSteps",
            fixture.plan().selectedConfiguration().effectiveBudget().maxPrimitiveSteps() + 1);
        rehash(changed);
        assertThrows(IllegalArgumentException.class, () ->
            EvolutionRewriteProgramFinalTestEvaluation.fromCanonicalJson(changed.toString(), fixture.plan()));
    }

    @Test
    void replacingTheCompleteSearchBudgetCannotResetTheFinalAttemptOrImportIdentity() throws Exception {
        var fixture = ProgramFinalTestFixtures.create(directory);
        var evaluation = fixture.execute();
        var budget = fixture.plan().selectedConfiguration().budget();
        var changedBudget = new EvolutionRewriteProgramTrainSuite.PrimitiveWorkBudget(budget.maxPrimitiveSteps(),
            budget.maxExploredStates(), budget.maxCandidatesPerState(), budget.maxExpandingSteps(), budget.maxWorkUnits() + 1);
        var changedValidationPlan = EvolutionRewriteProgramValidationPlan.create(fixture.study(), fixture.manifest(),
            fixture.train(), fixture.validationBundle().commitment(), List.of(changedBudget));
        // An independently stored synthetic alternative cannot substitute for the authoritative selection/ledger.
        var changedValidation = new EvolutionRewriteProgramValidationRunner().executeOnce(changedValidationPlan,
            fixture.study(), fixture.manifest(), fixture.train(), () -> fixture.validationBundle(),
            new FileEvolutionRewriteProgramValidationAttemptStore(directory.resolve("synthetic-alternative")));
        var changedPlan = EvolutionRewriteProgramFinalTestPlan.create(fixture.study(), fixture.manifest(), fixture.train(),
            changedValidation.handoff(), fixture.finalBundle().commitment());
        assertNotEquals(fixture.plan().selectedConfiguration().contentHash(), changedPlan.selectedConfiguration().contentHash());
        assertEquals(fixture.plan().runIdentity(), changedPlan.runIdentity());
        assertThrows(IllegalArgumentException.class, () -> EvolutionRewriteProgramFinalTestEvaluation.fromCanonicalJson(
            evaluation.toCanonicalJson(), changedPlan));
        var store = new FileEvolutionRewriteProgramFinalTestAttemptStore(fixture.finalDirectory());
        assertThrows(IOException.class, () -> store.reserve(changedPlan));
        assertThrows(IOException.class, () -> store.readEvaluation(changedPlan));
        assertThrows(IOException.class, () -> new EvolutionRewriteProgramFinalTestRunner().executeOnce(
            changedPlan, fixture.study(), fixture.manifest(), fixture.train(), fixture.validationStore(),
            () -> { fail("substituted selection must not reveal"); return fixture.finalBundle(); }, store));
    }

    @Test
    void symbolicLinksAndReadableEvidenceCannotBecomePrivateLedgerAuthority() throws Exception {
        var fixture = ProgramFinalTestFixtures.create(directory);
        fixture.execute();
        var store = new FileEvolutionRewriteProgramFinalTestAttemptStore(fixture.finalDirectory());
        var evidence = store.evaluationPath(fixture.plan());
        Path retained = directory.resolve("synthetic-retained-evidence.json");
        Files.move(evidence, retained);
        Files.createSymbolicLink(evidence, retained);
        assertThrows(IOException.class, () -> store.readEvaluation(fixture.plan()));
        Files.delete(evidence);
        Files.move(retained, evidence);
        Files.setPosixFilePermissions(evidence, PosixFilePermissions.fromString("rw-r--r--"));
        assertThrows(IOException.class, () -> store.readEvaluation(fixture.plan()));
        Path linkedDirectory = directory.resolve("synthetic-ledger-link");
        Files.createSymbolicLink(linkedDirectory, fixture.finalDirectory());
        assertThrows(IOException.class, () -> new FileEvolutionRewriteProgramFinalTestAttemptStore(linkedDirectory)
            .readEvaluation(fixture.plan()));
    }

    static ObjectNode object(String json) throws Exception {
        return (ObjectNode) EvolutionValidationArtifactSupport.JSON.readTree(json);
    }

    static void rehash(ObjectNode value) {
        value.remove("contentHash");
        var material = EvolutionValidationArtifactSupport.JSON.convertValue(value,
            new com.fasterxml.jackson.core.type.TypeReference<java.util.TreeMap<String, Object>>() {});
        value.put("contentHash", EvolutionValidationArtifactSupport.hash(material));
    }

    private static String childClasspath() throws Exception {
        // Gradle workers can keep the test classpath in a separate URL loader rather than java.class.path.
        var entries = new java.util.LinkedHashSet<String>();
        entries.add(System.getProperty("java.class.path"));
        for (ClassLoader loader = SyntheticProgramFinalReservationCrash.class.getClassLoader();
                loader != null; loader = loader.getParent()) {
            if (loader instanceof java.net.URLClassLoader urls) {
                for (var url : urls.getURLs()) { entries.add(Path.of(url.toURI()).toString()); }
            }
        }
        return String.join(java.io.File.pathSeparator, entries);
    }
}
