package de.regelsuche.inventory;

import static org.junit.jupiter.api.Assertions.*;
import de.regelsuche.export.DefaultTransformationExportService;
import de.regelsuche.export.DefaultTransformationImportService;
import de.regelsuche.mining.DynamicOperatorCompiler;
import de.regelsuche.mining.RuleStatus;
import de.regelsuche.search.moves.SearchMove;
import de.regelsuche.validation.CandidateProofStatus;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RuleUtilityPersistenceTest {
    /** Codec fixture only; its serialized claim is deliberately not a verifier-issued certificate. */
    private static RuleUtilityEvidence utility() {
        return new RuleUtilityEvidence(RuleUtilityEvidence.REVISION, 20, 2, true, 1, 2, 20,
            7, 3, 2, 1, 12, List.of("factor-provider"), 1, 0.9, 10,
            new RuleUtilityEvidence.ReferenceScope("fixture-inventory", "x + 0", "x", "fixture-scope",
                "{\"states\":100}", "fixture-assessment", true, 1234));
    }

    private static ReusableRule rule() {
        return new ReusableRule("factor", "A * B + A * C", "A * (B + C)", List.of(), CandidateProofStatus.OBSERVED,
            RuleStatus.NEW, 3, 20, Instant.EPOCH, "fixture-pattern", Instant.EPOCH, 4, 5,
            List.of("p1", "p2"), 0.9, List.of("x != 0"), utility());
    }

    @Test
    void snapshotsAndExportImportKeepUtilityAndPreviouslyLostLearningMetadata(@TempDir Path directory) throws Exception {
        var rule = rule();
        var repository = new InMemoryRuleInventoryRepository();
        repository.save(rule);
        repository.setEnabled(rule.id(), false);
        repository.addTag(rule.id(), "cold-codec-fixture");
        Path file = directory.resolve("inventory.json");
        repository.persistTo(file);
        var restored = InMemoryRuleInventoryRepository.loadFrom(file);
        assertEquals(rule, restored.findById(rule.id()).orElseThrow());
        assertFalse(restored.isEnabled(rule.id()));
        var exported = new DefaultTransformationExportService().exportJson(List.of(), List.of(), List.of(rule));
        assertEquals(List.of(rule), new DefaultTransformationImportService().importJson(exported).reusableRules());
        assertEquals(utility(), rule.withUsage(Instant.now(), 99).utilityEvidence());
        assertEquals(utility(), rule.withLearningProgress(99, 1000, List.of("p3"), 1).utilityEvidence());
        assertEquals(utility(), rule.withAssumptions(List.of()).utilityEvidence());
        var compiled = new DynamicOperatorCompiler().compile(rule.withUtilityEvidence(utility())).operator().orElseThrow();
        assertEquals(utility(), compiled.reusableRuleEvidence().orElseThrow().utilityEvidence());
        assertEquals(1, compiled.descriptor().valueEvidence().knownDepthCompression());
        assertEquals(SearchMove.ProofStrength.EMPIRICAL, compiled.descriptor().proofStrength(), "utility import grants no proof authority");
        assertTrue(Files.readString(file).contains("\"primitiveInventoryHash\""));
    }

    @Test
    void oldSnapshotsHaveUnknownUtilityAndMalformedMinimumClaimsFailClosed(@TempDir Path directory) throws Exception {
        Path file = directory.resolve("legacy.json");
        Files.writeString(file, "{\"rules\":[{\"id\":\"legacy\",\"leftPattern\":\"A+0\",\"rightPattern\":\"A\"}]}");
        assertEquals(RuleUtilityEvidence.UNKNOWN, InMemoryRuleInventoryRepository.loadFrom(file).findAll().getFirst().utilityEvidence());
        assertEquals(RuleUtilityEvidence.UNKNOWN, RuleUtilityEvidence.fromValue(null));
        assertEquals(RuleUtilityEvidence.UNKNOWN, RuleUtilityEvidence.fromJson("null"));
        assertThrows(IllegalArgumentException.class, () -> RuleUtilityEvidence.fromJson("{}"));
        assertThrows(IllegalArgumentException.class, () -> RuleUtilityEvidence.fromJson(
            RuleUtilityEvidence.UNKNOWN.toCanonicalJson().replace("\"boundedMinimumProved\":false", "\"boundedMinimumProved\":true")));
        assertThrows(IllegalArgumentException.class, () -> RuleUtilityEvidence.fromJson(
            utility().toCanonicalJson().replace("\"bestKnownPrimitiveSteps\":2", "\"bestKnownPrimitiveSteps\":21")));
    }
}
