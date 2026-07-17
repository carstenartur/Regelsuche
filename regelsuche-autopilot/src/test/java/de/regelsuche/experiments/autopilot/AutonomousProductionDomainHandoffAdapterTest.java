package de.regelsuche.experiments.autopilot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.discovery.domain.DiscoveryLifecycleHandoff;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class AutonomousProductionDomainHandoffAdapterTest {
    @Test
    void adaptsUnchangedTargetFreeProductionGenerationWithoutDomainObjects()
            throws Exception {
        AutonomousProductionGenerationRunner.GenerationRun run =
            new AutonomousProductionGenerationRunner().runPinned(1);

        DiscoveryLifecycleHandoff handoff =
            new AutonomousProductionDomainHandoffAdapter().adapt(run);

        assertEquals(DiscoveryLifecycleHandoff.SourceKind.PRODUCTION_GENERATION_RUN,
            handoff.sourceKind());
        assertEquals(DiscoveryLifecycleHandoff.Stage.GENERATION, handoff.stage());
        assertEquals(DiscoveryLifecycleHandoff.Disposition.COMPLETED,
            handoff.disposition());
        assertEquals(run.seedCatalog().contentHash(), handoff.inputHash());
        assertEquals(run.contentHash(), handoff.sourceEvidenceHash());
        assertEquals(AutonomousProductionDomainHandoffAdapter.DOMAIN_CONTRACT_HASH,
            handoff.domainContractHash());
        assertTrue(handoff.selectedCandidateHash().isEmpty());
        assertTrue(handoff.certificateHash().isEmpty());
        assertTrue(handoff.resources().stream().allMatch(account ->
            account.configured() == account.executed()
                + account.skipped() + account.remaining()));
        assertEquals(Integer.toString(run.observations().size()),
            handoff.metadata().get("observationCount"));

        String json = handoff.toCanonicalJson();
        assertFalse(json.contains("\"seedExpression\""));
        assertFalse(json.contains("\"selectedExpression\""));
        assertFalse(json.contains("\"states\""));
        assertFalse(json.contains("\"path\""));
        assertTrue(json.contains("\"proofStatus\":\"NOT_EVALUATED\""));
        assertTrue(json.contains("\"promotionStatus\":\"NOT_EVALUATED\""));

        Path output = Path.of(
            "build", "reports", "domain-lifecycle-handoff",
            "production-generation-handoff.json");
        Files.createDirectories(output.getParent());
        Files.writeString(output, json, StandardCharsets.UTF_8);
        assertEquals(json, Files.readString(output));

        assertEquals(handoff,
            new AutonomousProductionDomainHandoffAdapter().adapt(run));
    }
}
