package de.regelsuche.experiments.autopilot;

import de.regelsuche.experiments.autopilot.AutonomousProductionGenerationRunner.GenerationReceipt;
import de.regelsuche.experiments.autopilot.AutonomousProductionLifecycleRunner.LifecycleRun;
import de.regelsuche.experiments.autopilot.AutonomousProductionMiningRunner.CandidateFormationReceipt;
import de.regelsuche.experiments.autopilot.AutonomousResearchBriefV2.EvidenceStage;
import de.regelsuche.experiments.autopilot.AutonomousResearchBriefV2.ResourceKind;
import de.regelsuche.experiments.autopilot.AutonomousStageResourceLedger.StageResourceReceipt;
import de.regelsuche.json.JsonWriter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/** Complete factual resource ledger across every production campaign stage. */
public final class AutonomousCampaignResourceLedger {
    public static final String SCHEMA =
        "regelsuche.autonomous-campaign-resource-ledger/v2";

    private AutonomousCampaignResourceLedger() {
    }

    public static CampaignResourceLedger create(LifecycleRun lifecycle) {
        Objects.requireNonNull(lifecycle, "lifecycle");
        AutonomousResearchBriefV2 brief = lifecycle.mining().generation().brief();
        List<CampaignResourceEntry> entries = new ArrayList<>();
        addReceiptEntries(entries, lifecycle.mining().generation().receipt());
        addReceiptEntries(entries, lifecycle.mining().formationReceipt());
        lifecycle.stageLedger().receipts().forEach(receipt -> entries.add(
            new CampaignResourceEntry(
                receipt.stage(),
                receipt.resource(),
                receipt.configured(),
                receipt.executed(),
                receipt.skipped(),
                receipt.remaining(),
                receipt.contentHash())));
        List<CampaignResourceEntry> ordered = entries.stream()
            .sorted(Comparator
                .comparing(CampaignResourceEntry::stage)
                .thenComparing(CampaignResourceEntry::resource))
            .toList();
        String contentHash = hash(brief.contentHash(), ordered);
        return new CampaignResourceLedger(
            SCHEMA,
            brief.contentHash(),
            ordered,
            false,
            "NOT_EVALUATED",
            "NOT_EVALUATED",
            contentHash);
    }

    private static void addReceiptEntries(
        List<CampaignResourceEntry> entries,
        GenerationReceipt receipt
    ) {
        receipt.configuredResources().forEach((resource, configured) -> entries.add(
            new CampaignResourceEntry(
                receipt.stage(),
                resource,
                configured,
                receipt.executedResources().getOrDefault(resource, 0L),
                receipt.skippedResources().getOrDefault(resource, 0L),
                receipt.remainingResources().getOrDefault(resource, 0L),
                receipt.contentHash())));
    }

    private static void addReceiptEntries(
        List<CampaignResourceEntry> entries,
        CandidateFormationReceipt receipt
    ) {
        receipt.configuredResources().forEach((resource, configured) -> entries.add(
            new CampaignResourceEntry(
                receipt.stage(),
                resource,
                configured,
                receipt.executedResources().getOrDefault(resource, 0L),
                receipt.skippedResources().getOrDefault(resource, 0L),
                receipt.remainingResources().getOrDefault(resource, 0L),
                receipt.contentHash())));
    }

    private static String hash(
        String briefHash,
        List<CampaignResourceEntry> entries
    ) {
        return AutonomousResearchBriefV2.hash(
            SCHEMA
                + "\nbrief=" + briefHash
                + "\nentries=" + entries.stream()
                    .map(CampaignResourceEntry::canonicalMaterial).toList());
    }

    public record CampaignResourceEntry(
        EvidenceStage stage,
        ResourceKind resource,
        long configured,
        long executed,
        long skipped,
        long remaining,
        String sourceReceiptHash
    ) {
        public CampaignResourceEntry {
            Objects.requireNonNull(stage, "stage");
            Objects.requireNonNull(resource, "resource");
            if (configured < 1L || executed < 0L || skipped < 0L || remaining < 0L
                    || configured != Math.addExact(
                        Math.addExact(executed, skipped), remaining)) {
                throw new IllegalArgumentException(
                    "campaign resource entry must be non-negative and balanced");
            }
            requireSha256(sourceReceiptHash, "sourceReceiptHash");
        }

        String canonicalMaterial() {
            return stage.name() + '|' + resource.name() + '|' + configured + '|'
                + executed + '|' + skipped + '|' + remaining + '|'
                + sourceReceiptHash;
        }
    }

    public record CampaignResourceLedger(
        String schema,
        String briefHash,
        List<CampaignResourceEntry> entries,
        boolean ledgerIsMathematicalEvidence,
        String promotionStatus,
        String publicEvidenceStatus,
        String contentHash
    ) {
        public CampaignResourceLedger {
            if (!SCHEMA.equals(schema)) {
                throw new IllegalArgumentException(
                    "unsupported campaign resource ledger schema");
            }
            requireSha256(briefHash, "briefHash");
            entries = entries == null
                ? List.of()
                : entries.stream()
                    .sorted(Comparator
                        .comparing(CampaignResourceEntry::stage)
                        .thenComparing(CampaignResourceEntry::resource))
                    .toList();
            if (entries.isEmpty()) {
                throw new IllegalArgumentException(
                    "campaign resource ledger must retain stage receipts");
            }
            Set<String> keys = entries.stream()
                .map(entry -> entry.stage().name() + '/' + entry.resource().name())
                .collect(java.util.stream.Collectors.toCollection(TreeSet::new));
            if (keys.size() != entries.size()) {
                throw new IllegalArgumentException(
                    "campaign resource ledger stage/resource pairs must be unique");
            }
            Set<EvidenceStage> stages = entries.stream()
                .map(CampaignResourceEntry::stage)
                .collect(java.util.stream.Collectors.toCollection(TreeSet::new));
            if (!stages.equals(Set.of(EvidenceStage.values()))) {
                throw new IllegalArgumentException(
                    "campaign resource ledger must retain all evidence stages");
            }
            if (ledgerIsMathematicalEvidence) {
                throw new IllegalArgumentException(
                    "campaign resource ledger cannot be mathematical evidence");
            }
            requireNotEvaluated(promotionStatus, "promotionStatus");
            requireNotEvaluated(publicEvidenceStatus, "publicEvidenceStatus");
            requireSha256(contentHash, "contentHash");
            if (!hash(briefHash, entries).equals(contentHash)) {
                throw new IllegalArgumentException(
                    "campaign resource ledger content hash is inconsistent");
            }
        }

        public CampaignResourceEntry entry(
            EvidenceStage stage,
            ResourceKind resource
        ) {
            return entries.stream()
                .filter(item -> item.stage() == stage && item.resource() == resource)
                .findFirst()
                .orElseThrow();
        }

        public String toCanonicalJson() {
            return new JsonWriter().beginObject()
                .property("schema", schema)
                .property("briefHash", briefHash)
                .array("entries", array -> entries.forEach(entry ->
                    array.objectValue(object -> object
                        .property("stage", entry.stage().name())
                        .property("resource", entry.resource().name())
                        .property("configured", entry.configured())
                        .property("executed", entry.executed())
                        .property("skipped", entry.skipped())
                        .property("remaining", entry.remaining())
                        .property("sourceReceiptHash", entry.sourceReceiptHash()))))
                .property("ledgerIsMathematicalEvidence",
                    ledgerIsMathematicalEvidence)
                .property("promotionStatus", promotionStatus)
                .property("publicEvidenceStatus", publicEvidenceStatus)
                .property("contentHash", contentHash)
                .endObject()
                .toString();
        }
    }

    private static void requireSha256(String value, String name) {
        if (value == null || !value.matches("sha256:[0-9a-f]{64}")) {
            throw new IllegalArgumentException(name + " must be SHA-256");
        }
    }

    private static void requireNotEvaluated(String value, String name) {
        if (!"NOT_EVALUATED".equals(value)) {
            throw new IllegalArgumentException(name + " must be NOT_EVALUATED");
        }
    }
}
