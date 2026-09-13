package de.regelsuche.evolution;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Independent controls over temporary public synthetic studies only. */
class EvolutionRewriteProgramFinalTestReviewTest {
    @TempDir Path directory;

    @Test
    void aWritableValidationLedgerCannotAuthorizeFinalReveal() throws Exception {
        var fixture = ProgramFinalTestFixtures.create(directory);
        Path validationRoot = fixture.validationStore().selectionPath(fixture.handoff().selection().plan()).getParent();
        Files.setPosixFilePermissions(validationRoot, PosixFilePermissions.fromString("rwxrwxrwx"));
        var store = new FileEvolutionRewriteProgramFinalTestAttemptStore(fixture.finalDirectory());
        var reveals = new AtomicInteger();
        try {
            assertThrows(IOException.class, () -> new EvolutionRewriteProgramFinalTestRunner().executeOnce(
                fixture.plan(), fixture.study(), fixture.manifest(), fixture.train(), fixture.validationStore(),
                () -> { reveals.incrementAndGet(); return fixture.finalBundle(); }, store));
            assertEquals(0, reveals.get());
            assertFalse(Files.exists(store.reservationPath(fixture.plan())));
        } finally {
            Files.setPosixFilePermissions(validationRoot, PosixFilePermissions.fromString("rwx------"));
        }
    }

    @Test
    void aLowLevelFinalReservationAloneCannotAuthorizeAnOrphanedValidationHandoff() throws Exception {
        var fixture = ProgramFinalTestFixtures.create(directory);
        Files.delete(fixture.validationStore().reservationPath(fixture.handoff().selection().plan()));
        Files.delete(fixture.validationStore().selectionPath(fixture.handoff().selection().plan()));
        var store = new FileEvolutionRewriteProgramFinalTestAttemptStore(fixture.finalDirectory());
        var reservation = store.reserve(fixture.plan());
        assertThrows(IllegalArgumentException.class, () -> {
            var authorization = EvolutionRewriteProgramHeldOutRevealAuthorization.finalTest(
                fixture.study(), fixture.manifest(), fixture.train(), reservation);
            fixture.finalBundle().open(authorization, fixture.plan().commitment());
        });
    }

    @Test
    void thePublicExecutionReservationRequiresTheDurableValidationRecords() throws Exception {
        var fixture = ProgramFinalTestFixtures.create(directory);
        Files.delete(fixture.validationStore().reservationPath(fixture.handoff().selection().plan()));
        var store = new FileEvolutionRewriteProgramFinalTestAttemptStore(fixture.finalDirectory());
        assertThrows(IOException.class, () -> store.reserve(fixture.plan(), fixture.validationStore()));
        assertFalse(Files.exists(store.reservationPath(fixture.plan())));
    }

    @Test
    void aVerifiedExecutionReservationOpensOnlyTheBoundFinalBundle() throws Exception {
        var fixture = ProgramFinalTestFixtures.create(directory);
        var store = new FileEvolutionRewriteProgramFinalTestAttemptStore(fixture.finalDirectory());
        var reservation = store.reserve(fixture.plan(), fixture.validationStore());
        var authorization = EvolutionRewriteProgramHeldOutRevealAuthorization.finalTest(
            fixture.study(), fixture.manifest(), fixture.train(), reservation);
        assertEquals(fixture.plan().commitment().contentHash(), authorization.heldOutCommitmentHash());
        assertEquals(reservation.record().contentHash(), authorization.prerequisiteArtifactHash());
        assertEquals(fixture.handoff().selection().contentHash(), authorization.validationSelectionHash());
        assertEquals(fixture.plan().contentHash(), authorization.heldOutSuiteHash());
        assertEquals(2, fixture.finalBundle().open(authorization, fixture.plan().commitment()).cases().size());
        assertThrows(IllegalArgumentException.class, () -> fixture.validationBundle().open(
            authorization, fixture.validationBundle().commitment()));
        assertThrows(IOException.class, () -> store.reserve(fixture.plan(), fixture.validationStore()));
    }

    @Test
    void aReservationWithoutPredecessorVerificationCannotPersistOtherwiseValidFinalEvidence() throws Exception {
        var fixture = ProgramFinalTestFixtures.create(directory);
        var evaluation = fixture.execute();
        var bareStore = new FileEvolutionRewriteProgramFinalTestAttemptStore(directory.resolve("synthetic-reservation-only"));
        var bare = bareStore.reserve(fixture.plan());
        assertThrows(IOException.class, () -> bareStore.writeEvaluation(bare, evaluation));
        assertFalse(Files.exists(bareStore.evaluationPath(fixture.plan())));
        assertThrows(IllegalArgumentException.class, () -> EvolutionRewriteProgramHeldOutRevealAuthorization.finalTest(
            fixture.study(), fixture.manifest(), fixture.train(), bare));
    }

    @Test
    void aProviderWithoutNoFollowDirectoryForceCannotIssueAnExecutionReceipt() throws Exception {
        var fixture = ProgramFinalTestFixtures.create(directory);
        try (var zip = FileSystems.newFileSystem(directory.resolve("synthetic-final-ledger.zip"),
                Map.of("create", "true", "enablePosixFileAttributes", "true"))) {
            var store = new FileEvolutionRewriteProgramFinalTestAttemptStore(zip.getPath("/nested/final"));
            var reveals = new AtomicInteger();
            assertThrows(IOException.class, () -> new EvolutionRewriteProgramFinalTestRunner().executeOnce(
                fixture.plan(), fixture.study(), fixture.manifest(), fixture.train(), fixture.validationStore(),
                () -> { reveals.incrementAndGet(); return fixture.finalBundle(); }, store));
            assertTrue(Files.exists(store.reservationPath(fixture.plan())), "file creation and force preceded directory refusal");
            assertEquals(0, reveals.get());
            assertFalse(Files.exists(store.evaluationPath(fixture.plan())));
            assertThrows(IOException.class, () -> store.reserve(fixture.plan(), fixture.validationStore()));
        }
    }
}
