package de.regelsuche.evolution;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** Actual private files from native public synthetic cases; no study reveal resources. */
class EvolutionRewriteProgramPrivateLedgerUtf8ReviewTest {
    private static final String REASON = "synthetic byte control: \uFFFD; Gr\u00FCnde; \u03BB; \uD83D\uDD12";
    @TempDir Path directory;

    @ParameterizedTest
    @EnumSource(AuthorityFile.class)
    void malformedBytesCannotAliasValidUnicodeAuthority(AuthorityFile authorityFile) throws Exception {
        var fixture = unicodeFixture(directory);
        var validationPlan = fixture.handoff().selection().plan();
        var finalStore = new FileEvolutionRewriteProgramFinalTestAttemptStore(fixture.finalDirectory());
        var evaluation = authorityFile.finalTest ? fixture.execute() : null;
        if (authorityFile.finalTest) {
            assertEquals(evaluation, finalStore.readEvaluation(fixture.plan()));
        } else {
            assertEquals(fixture.handoff().selection(), fixture.validationStore().readSelection(validationPlan));
        }

        Path path = authorityFile.path(fixture, finalStore);
        byte[] original = Files.readAllBytes(path);
        byte[] corrupted = replaceFirstReplacementCharacter(original);
        assertFalse(Arrays.equals(original, corrupted));
        assertEquals(new String(original, StandardCharsets.UTF_8), new String(corrupted, StandardCharsets.UTF_8),
            "replacement decoding aliases changed bytes to the original fully hashed object");
        assertThrows(CharacterCodingException.class, () -> strictDecode(corrupted));
        Files.write(path, corrupted);
        assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(path));

        if (authorityFile.finalTest) {
            assertThrows(IOException.class, () -> finalStore.readEvaluation(fixture.plan()),
                "the actual FINAL authority reader must reject malformed UTF-8 before semantic import");
        } else {
            assertThrows(IOException.class, () -> fixture.validationStore().readSelection(validationPlan),
                "the actual VALIDATION authority reader must reject malformed UTF-8 before semantic import");
            assertThrows(IOException.class, () -> finalStore.reserve(fixture.plan(), fixture.validationStore()));
            assertFalse(Files.exists(finalStore.reservationPath(fixture.plan())),
                "an undecodable predecessor cannot issue a FINAL reservation or reveal capability");
        }
    }

    @Test
    void validReplacementCharacterAndMultibyteUnicodeRoundTripWithoutChangingAuthorityBytes() throws Exception {
        var fixture = unicodeFixture(directory);
        var validation = fixture.handoff().selection();
        var plan = fixture.plan();
        var evaluation = fixture.execute();
        var finalStore = new FileEvolutionRewriteProgramFinalTestAttemptStore(fixture.finalDirectory());
        assertEquals(validation, fixture.validationStore().readSelection(validation.plan()));
        assertEquals(evaluation, finalStore.readEvaluation(plan));
        assertEquals(EvolutionFinalTestReservation.runIdentity(
            validation.plan().studyPlanHash(), validation.plan().splitManifestHash()), plan.runIdentity());
        assertTrue(evaluation.qualificationEligible(), "the native synthetic controls remain successful");
        for (AuthorityFile authorityFile : AuthorityFile.values()) {
            Path path = authorityFile.path(fixture, finalStore);
            byte[] before = Files.readAllBytes(path);
            assertTrue(strictDecode(before).contains(REASON));
            if (authorityFile.finalTest) {
                assertEquals(evaluation, finalStore.readEvaluation(plan));
            } else {
                assertEquals(validation, fixture.validationStore().readSelection(validation.plan()));
            }
            String canonical = switch (authorityFile) {
                case VALIDATION_RESERVATION -> validation.plan().toCanonicalJson();
                case VALIDATION_SELECTION -> validation.toCanonicalJson();
                case FINAL_RESERVATION -> EvolutionRewriteProgramFinalTestReservation.create(plan).toCanonicalJson();
                case FINAL_EVALUATION -> evaluation.toCanonicalJson();
            };
            assertArrayEquals(canonical.getBytes(StandardCharsets.UTF_8), before);
            assertArrayEquals(before, Files.readAllBytes(path), "authority reads do not rewrite retained bytes");
            assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(path));
        }
        assertThrows(IOException.class, () -> fixture.validationStore().reserve(validation.plan()));
        assertThrows(IOException.class, () -> finalStore.reserve(plan, fixture.validationStore()));
    }

    private static ProgramFinalTestFixtures.Fixture unicodeFixture(Path directory) throws IOException {
        return ProgramFinalTestFixtures.create(directory, "synthetic_private_ledger_unicode", false, 4, List.of(1, 2),
            source -> new EvolutionRewriteProgramPlan.Prune("synthetic_unicode_reason", source, 80, REASON));
    }

    private static String strictDecode(byte[] bytes) throws CharacterCodingException {
        return StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes)).toString();
    }

    private static byte[] replaceFirstReplacementCharacter(byte[] original) {
        for (int index = 0; index + 2 < original.length; index++) {
            if (original[index] == (byte) 0xef && original[index + 1] == (byte) 0xbf
                    && original[index + 2] == (byte) 0xbd) {
                byte[] corrupted = new byte[original.length - 2];
                System.arraycopy(original, 0, corrupted, 0, index);
                corrupted[index] = (byte) 0xff;
                System.arraycopy(original, index + 3, corrupted, index + 1, original.length - index - 3);
                return corrupted;
            }
        }
        throw new AssertionError("native retained authority must contain the valid UTF-8 replacement character");
    }

    private enum AuthorityFile {
        VALIDATION_RESERVATION(false), VALIDATION_SELECTION(false), FINAL_RESERVATION(true), FINAL_EVALUATION(true);

        private final boolean finalTest;
        AuthorityFile(boolean finalTest) { this.finalTest = finalTest; }

        private Path path(ProgramFinalTestFixtures.Fixture fixture,
            FileEvolutionRewriteProgramFinalTestAttemptStore finalStore) {
            var validationPlan = fixture.handoff().selection().plan();
            return switch (this) {
                case VALIDATION_RESERVATION -> fixture.validationStore().reservationPath(validationPlan);
                case VALIDATION_SELECTION -> fixture.validationStore().selectionPath(validationPlan);
                case FINAL_RESERVATION -> finalStore.reservationPath(fixture.plan());
                case FINAL_EVALUATION -> finalStore.evaluationPath(fixture.plan());
            };
        }
    }
}
