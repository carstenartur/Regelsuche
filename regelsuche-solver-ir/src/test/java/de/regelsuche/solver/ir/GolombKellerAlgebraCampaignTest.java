package de.regelsuche.solver.ir;

import static org.junit.jupiter.api.Assertions.*;
import de.regelsuche.solver.ir.SolverIr.*;
import de.regelsuche.solver.ir.examples.GolombKellerAlgebra;
import de.regelsuche.solver.ir.examples.GolombKellerAlgebra.Lemma;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GolombKellerAlgebraCampaignTest {
    @TempDir Path temporary;

    @Test void typedManuscriptObligationsAreAvailable() {
        assertDoesNotThrow(() -> Class.forName(
            "de.regelsuche.solver.ir.examples.GolombKellerAlgebra"));
    }

    @Test void allGeneralIdentitiesAreConfirmedByTheActualExactBackend() {
        var backend = new PolynomialNormalFormSolverBackend();
        for (Lemma lemma : Lemma.values()) {
            var obligation = GolombKellerAlgebra.identity(lemma);
            var result = backend.execute(obligation);
            assertEquals(ResultStatus.CONFIRMED, result.result().status(), lemma.name());
            assertEquals(TranslationStatus.LOSSLESS, result.translation().status());
            assertFalse(result.result().certificateHash().isEmpty());
            assertEquals(obligation.contentHash(), result.obligationHash());
            assertEquals(obligation.goalHash(), result.result().goalHash());
            assertEquals(obligation.assumptionsHash(), result.result().assumptionsHash());
            assertTrue(obligation.assumptions().isEmpty());
        }
    }

    @Test void everyPlusOneMutationIsRefuted() {
        var backend = new PolynomialNormalFormSolverBackend();
        for (Lemma lemma : Lemma.values()) {
            var original = GolombKellerAlgebra.identity(lemma);
            var mutated = GolombKellerAlgebra.falseIdentity(lemma);
            assertNotEquals(original.contentHash(), mutated.contentHash());
            assertNotEquals(original.goalHash(), mutated.goalHash());
            var execution = backend.execute(mutated);
            assertEquals(ResultStatus.REFUTED, execution.result().status(), lemma.name());
            assertFalse(execution.result().certificateHash().isEmpty());
        }
    }

    @Test void unsupportedMathematicsAndEvidenceAreNotPromoted() {
        var backend = new PolynomialNormalFormSolverBackend();
        for (var obligation : GolombKellerAlgebra.unsupportedControls()) {
            var result = backend.execute(obligation);
            assertEquals(ResultStatus.UNSUPPORTED, result.result().status(), obligation.obligationId());
            assertEquals(TranslationStatus.REJECTED, result.translation().status());
            assertFalse(result.translation().issues().isEmpty());
            assertTrue(result.result().certificateHash().isEmpty());
        }
    }

    @Test void actualCampaignRetainsFifteenCompleteCanonicalChains() throws Exception {
        // Persistent CI evidence, distinct from JUnit's disposable fixture directory.
        Path run = GolombKellerAlgebra.run(Path.of("target/golomb-keller"));
        try (var paths = Files.list(run)) {
            List<Path> directories = paths.filter(Files::isDirectory).toList();
            assertEquals(15, directories.size());
            for (Path directory : directories) {
                for (String file : List.of("obligation.json", "translation.json", "result.json", "execution.json")) {
                    assertTrue(Files.size(directory.resolve(file)) > 0, directory + "/" + file);
                }
            }
        }
        assertTrue(Files.readString(run.resolve("completed.json")).contains("\"analyticTheoremProved\":false"));
        assertFalse(Files.exists(run.resolve("FAILED.txt")));
    }

    @Test void aSecondCampaignNeverReplacesTheFirstEvidence() throws Exception {
        Path first = GolombKellerAlgebra.run(temporary);
        byte[] before = Files.readAllBytes(first.resolve("completed.json"));
        Path second = GolombKellerAlgebra.run(temporary);
        assertNotEquals(first, second);
        assertArrayEquals(before, Files.readAllBytes(first.resolve("completed.json")));
    }

    @Test void wrongObligationEvidenceIsRetainedButRejected() throws Exception {
        var real = new PolynomialNormalFormSolverBackend();
        SolverBackend wrong = new SolverBackend() {
            @Override public BackendDescriptor descriptor() { return real.descriptor(); }
            @Override public SolverExecution execute(Obligation ignored) {
                return real.execute(GolombKellerAlgebra.identity(Lemma.GAP_FOUR_FACTOR));
            }
        };
        assertThrows(IllegalStateException.class, () -> GolombKellerAlgebra.run(temporary, wrong));
        Path run = singleRun();
        assertTrue(Files.exists(run.resolve("FAILED.txt")));
        assertFalse(Files.exists(run.resolve("completed.json")));
        Path attempt = run.resolve("gk-loss_numerator");
        assertTrue(Files.exists(attempt.resolve("obligation.json")));
        assertTrue(Files.exists(attempt.resolve("result.json")));
        assertTrue(Files.exists(attempt.resolve("execution.json")));
    }

    @Test void backendFailureLeavesTheExactAttemptAndNoSuccessReceipt() throws Exception {
        SolverBackend failure = new SolverBackend() {
            @Override public BackendDescriptor descriptor() {
                return new PolynomialNormalFormSolverBackend().descriptor();
            }
            @Override public SolverExecution execute(Obligation obligation) {
                throw new IllegalStateException("intentional backend failure");
            }
        };
        assertThrows(IllegalStateException.class, () -> GolombKellerAlgebra.run(temporary, failure));
        Path run = singleRun();
        assertTrue(Files.readString(run.resolve("FAILED.txt")).contains("intentional backend failure"));
        assertTrue(Files.exists(run.resolve("gk-loss_numerator/obligation.json")));
        assertFalse(Files.exists(run.resolve("completed.json")));
    }

    private Path singleRun() throws IOException {
        try (var paths = Files.list(temporary)) {
            var runs = paths.toList();
            assertEquals(1, runs.size());
            return runs.get(0);
        }
    }
}
