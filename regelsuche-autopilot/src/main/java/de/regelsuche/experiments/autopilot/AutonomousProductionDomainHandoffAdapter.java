package de.regelsuche.experiments.autopilot;

import de.regelsuche.discovery.domain.DiscoveryLifecycleHandoff;
import de.regelsuche.discovery.domain.DiscoveryLifecycleHandoff.Disposition;
import de.regelsuche.discovery.domain.DiscoveryLifecycleHandoff.ResourceAccount;
import de.regelsuche.discovery.domain.DiscoveryLifecycleHandoff.SourceKind;
import de.regelsuche.discovery.domain.DiscoveryLifecycleHandoff.Stage;
import de.regelsuche.experiments.autopilot.AutonomousProductionGenerationRunner.GenerationReceipt;
import de.regelsuche.experiments.autopilot.AutonomousProductionGenerationRunner.GenerationRun;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Transitional adapter from the unchanged algebraic production generator into
 * the domain-neutral lifecycle handoff contract.
 */
public final class AutonomousProductionDomainHandoffAdapter {
    private static final String CONTRACT_ID =
        "regelsuche.autonomous-production-domain-handoff-adapter/v1";
    private static final String CONTRACT_MATERIAL = CONTRACT_ID
        + "\nsource=" + AutonomousProductionGenerationRunner.GENERATION_RUN_SCHEMA
        + "\nsearch=existing-target-free-best-first"
        + "\ninput=production-seed-catalog-hash"
        + "\noutput=discovery-lifecycle-handoff-v1"
        + "\nrawDomainObjectsExcluded=true";

    public static final String DOMAIN_ID = "expression-rewrite";
    public static final String DOMAIN_REVISION = "production-v2";
    public static final String DOMAIN_CONTRACT_HASH =
        AutonomousResearchBrief.hash(CONTRACT_MATERIAL);

    public DiscoveryLifecycleHandoff adapt(GenerationRun run) {
        Objects.requireNonNull(run, "run");
        if (run.targetProvided() || run.generationIsMathematicalEvidence()) {
            throw new IllegalArgumentException(
                "production generation handoff must remain target-free and non-mathematical");
        }
        if (!DiscoveryLifecycleHandoff.NOT_EVALUATED.equals(run.promotionStatus())
                || !DiscoveryLifecycleHandoff.NOT_EVALUATED.equals(
                    run.publicEvidenceStatus())) {
            throw new IllegalArgumentException(
                "production generation cannot perform promotion or public evidence");
        }
        GenerationReceipt receipt = run.receipt();
        List<ResourceAccount> resources = receipt.configuredResources().keySet().stream()
            .sorted()
            .map(resource -> new ResourceAccount(
                resource.name(),
                receipt.configuredResources().getOrDefault(resource, 0L),
                receipt.executedResources().getOrDefault(resource, 0L),
                receipt.skippedResources().getOrDefault(resource, 0L),
                receipt.remainingResources().getOrDefault(resource, 0L)))
            .toList();
        Map<String, String> metadata = Map.ofEntries(
            Map.entry("adapterContractId", CONTRACT_ID),
            Map.entry("briefHash", run.brief().contentHash()),
            Map.entry("discoveryReportHash", run.discoveryReportHash()),
            Map.entry("generationReceiptHash", receipt.contentHash()),
            Map.entry("observationBundleHash", run.observationBundle().contentHash()),
            Map.entry("observationCount", Integer.toString(run.observations().size())),
            Map.entry("seedCatalogHash", run.seedCatalog().contentHash()),
            Map.entry("seedCount", Integer.toString(run.seedCatalog().seeds().size())),
            Map.entry("targetProvided", Boolean.toString(run.targetProvided())),
            Map.entry("generationIsMathematicalEvidence",
                Boolean.toString(run.generationIsMathematicalEvidence())));
        return DiscoveryLifecycleHandoff.create(
            "autonomous-production-generation-lifecycle-handoff",
            SourceKind.PRODUCTION_GENERATION_RUN,
            "autonomous-production-generation",
            DOMAIN_ID,
            DOMAIN_REVISION,
            DOMAIN_CONTRACT_HASH,
            run.seedCatalog().contentHash(),
            run.contentHash(),
            Stage.GENERATION,
            Disposition.COMPLETED,
            "",
            "",
            resources,
            metadata,
            DiscoveryLifecycleHandoff.NOT_EVALUATED,
            DiscoveryLifecycleHandoff.NOT_EVALUATED,
            run.promotionStatus(),
            run.publicEvidenceStatus());
    }
}
