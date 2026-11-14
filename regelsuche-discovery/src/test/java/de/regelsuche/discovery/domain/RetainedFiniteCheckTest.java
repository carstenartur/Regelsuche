package de.regelsuche.discovery.domain;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RetainedFiniteCheckTest {
    @TempDir Path temporary;

    @Test void rejectsCounterexampleAndWitnessClaimsThatDisagreeWithTheRetainedRows() throws Exception {
        var source = retain(false);
        var receipt = DomainDownstreamValidation.validate(source);
        assertRejects(source, receipt, check -> check.put("firstCounterexampleIndex", 0));
        assertRejects(source, receipt, check -> row(check, 1).put("witness", "99"));
        assertRejects(source, receipt, check -> row(check, 1).put("dataAgrees", false));
        assertRejects(source, receipt, check -> row(check, 1).put("residual", "42"));
        assertRejects(source, receipt, check -> {
            check.put("status", "REFUTED_FINITE_DATA").put("counterexampleStatus", "FOUND").put("firstCounterexampleIndex", 2);
            row(check, 1).put("computed", "3").put("dataAgrees", false).put("witnessAgrees", false).put("residual", "1");
        });
    }

    @Test void checksDomainContractSourceTermsAndPartitionsEvenForPartialReceipts() throws Exception {
        for (boolean recurrence : new boolean[]{false, true}) {
            var source = retain(recurrence);
            var budget = new DomainDownstreamValidation.Budget(1, 512, new DomainFiniteDataCheck.Budget(3, 1000, 1024));
            var receipt = DomainDownstreamValidation.validate(source, budget);
            var repository = new DomainExportWorkspaceRepository(temporary.resolve("retained"));
            repository.retainValidation(source, receipt);
            assertArrayEquals(receipt.toCanonicalJson().getBytes(StandardCharsets.UTF_8),
                new DomainExportWorkspaceRepository(temporary.resolve("retained")).findValidation(source.runId(), receipt.contentHash()).orElseThrow());
            assertRejects(source, receipt, check -> check.put("checkerContract",
                recurrence ? DomainFiniteDataCheck.NEWTON_CONTRACT : DomainFiniteDataCheck.RESIDUAL_CONTRACT));
            assertRejects(source, receipt, check -> row(check, 0).put("index", 1));
            assertRejects(source, receipt, check -> row(check, 0).put("partition", "HOLDOUT"));
            assertRejects(source, receipt, check -> row(check, 0).put("retained", "99").put("computed", "99").put("witness", "99"));
            assertRejects(source, receipt, check -> check.put("holdoutCount", 0));
            assertRejects(source, receipt, check -> row(check, 0).put("kind", recurrence ? "NEWTON_TERM" : "SEED_IDENTITY"));
            assertRejects(source, receipt, check -> row(check, 2).put("windowStartInclusive", 1));
            assertRejects(source, receipt, check -> check.put("complete", true));
        }
    }

    @Test void preservesARealPartialCounterexampleWithoutUpgradingTheOutcome() throws Exception {
        var source = retain(false);
        var observed = List.of(1L, 4L, 9L, 16L);
        var holdout = List.of(25L, 36L);
        var differentModel = List.of(1L, 2L, 2L);
        var candidate = new FiniteDifferenceSequenceDomain.FiniteDifferenceCandidate(2, differentModel, observed, holdout);
        var witness = new FiniteDifferenceSequenceDomain.FiniteDifferenceCertificate(2, differentModel,
            List.of(1L, 4L, 9L, 16L, 25L, 36L), 4, 2, "FINITE_DIFFERENCE_VALIDATION_NOT_FORMAL_PROOF");
        var check = DomainFiniteDataCheck.check(candidate, witness, new DomainFiniteDataCheck.Budget(3, 1000, 1024));
        var retained = DomainExportWorkspace.read(check.toCanonicalJson());
        assertEquals(DomainFiniteDataCheck.Status.INCONCLUSIVE, check.status());
        assertFalse(check.complete());
        assertEquals(1, retained.required("firstCounterexampleIndex").intValue());
        // Only row structure is exercised here. A live receipt additionally binds the selected model by replay.
        assertDoesNotThrow(() -> RetainedFiniteCheck.verify(source.evidence(), retained, 3));
    }

    private DomainExportWorkspace retain(boolean recurrence) throws Exception {
        var evidence = DomainDownstreamValidationTest.source(recurrence, false);
        Path source = temporary.resolve(evidence.campaignId());
        new DomainDiscoveryExport().write(source, evidence);
        return new DomainExportWorkspaceRepository(temporary.resolve("retained")).retain(
            new DomainDiscoveryExportVerifier().requireVerified(source));
    }

    private void assertRejects(DomainExportWorkspace source, DomainDownstreamValidation.Receipt receipt,
            Consumer<ObjectNode> change) throws Exception {
        var value = (ObjectNode) DomainExportWorkspace.read(receipt.toCanonicalJson());
        var check = (ObjectNode) value.required("check");
        change.accept(check);
        value.put("status", check.path("status").asText()).put("counterexampleStatus", check.path("counterexampleStatus").asText());
        if ("REFUTED_FINITE_DATA".equals(check.path("status").asText())) value.put("evaluationStatus", "REFUTED");
        String checkHash = rehash(check);
        for (var role : value.required("artifacts")) {
            if (role.path("artifactSchema").asText().equals(DomainFiniteDataCheck.SCHEMA)) {
                ((ObjectNode) role).put("targetContentHash", checkHash);
            }
        }
        String hash = rehash(value);
        Path directory = temporary.resolve("retained-validations").resolve(source.runId().substring(7));
        Files.createDirectories(directory);
        Files.writeString(directory.resolve(hash.substring(7) + ".json"), DomainExportWorkspace.write(value));
        var repository = new DomainExportWorkspaceRepository(temporary.resolve("retained"));
        assertThrows(IllegalStateException.class, () -> repository.findValidation(source.runId(), hash));
    }

    private static ObjectNode row(ObjectNode check, int index) {
        return (ObjectNode) check.required("rows").get(index);
    }

    private static String rehash(ObjectNode value) {
        value.remove("contentHash");
        String hash = DomainCanonical.sha256(DomainExportWorkspace.write(value));
        value.put("contentHash", hash);
        return hash;
    }
}
