package de.regelsuche.experiments.autopilot;

import de.regelsuche.experiments.autopilot.AutonomousResearchBriefV2.EvidenceStage;
import de.regelsuche.experiments.autopilot.AutonomousResearchBriefV2.ResourceKind;
import de.regelsuche.json.JsonWriter;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Balanced factual resource receipts for downstream Autopilot stages. */
public final class AutonomousStageResourceLedger {
    public static final String SCHEMA =
        "regelsuche.autonomous-stage-resource-ledger/v2";

    private AutonomousStageResourceLedger() {
    }

    public static StageResourceReceipt completed(
        AutonomousResearchBriefV2 brief,
        EvidenceStage stage,
        ResourceKind resource,
        long executed,
        String evidenceHash
    ) {
        Objects.requireNonNull(brief, "brief");
        long configured = brief.budget(stage).configured(resource);
        if (executed < 1L || executed > configured) {
            throw new IllegalArgumentException(
                "executed stage work is outside the configured budget: "
                    + stage + '/' + resource + '=' + executed + '/' + configured);
        }
        long remaining = configured - executed;
        String contentHash = AutonomousResearchBriefV2.hash(
            "regelsuche.autonomous-stage-resource-receipt/v2"
                + "\nbrief=" + brief.contentHash()
                + "\nstage=" + stage.name()
                + "\nresource=" + resource.name()
                + "\nconfigured=" + configured
                + "\nexecuted=" + executed
                + "\nskipped=0"
                + "\nremaining=" + remaining
                + "\nevidence=" + evidenceHash
                + "\ndisposition=COMPLETED");
        return new StageResourceReceipt(
            stage,
            resource,
            configured,
            executed,
            0L,
            remaining,
            "COMPLETED",
            evidenceHash,
            contentHash);
    }

    public static StageResourceLedger create(
        AutonomousResearchBriefV2 brief,
        List<StageResourceReceipt> suppliedReceipts
    ) {
        Objects.requireNonNull(brief, "brief");
        List<StageResourceReceipt> receipts = suppliedReceipts == null
            ? List.of()
            : suppliedReceipts.stream()
                .sorted(Comparator.comparing(StageResourceReceipt::stage))
                .toList();
        String contentHash = AutonomousResearchBriefV2.hash(
            SCHEMA
                + "\nbrief=" + brief.contentHash()
                + "\nreceipts=" + receipts.stream()
                    .map(StageResourceReceipt::contentHash).toList());
        return new StageResourceLedger(
            SCHEMA,
            brief.contentHash(),
            receipts,
            false,
            false,
            "NOT_EVALUATED",
            "NOT_EVALUATED",
            contentHash);
    }

    public record StageResourceReceipt(
        EvidenceStage stage,
        ResourceKind resource,
        long configured,
        long executed,
        long skipped,
        long remaining,
        String disposition,
        String evidenceHash,
        String contentHash
    ) {
        public StageResourceReceipt {
            Objects.requireNonNull(stage, "stage");
            Objects.requireNonNull(resource, "resource");
            if (configured < 1L || executed < 1L || skipped < 0L || remaining < 0L
                    || configured != Math.addExact(
                        Math.addExact(executed, skipped), remaining)) {
                throw new IllegalArgumentException(
                    "stage resource receipt must be positive and balanced");
            }
            if (!"COMPLETED".equals(disposition)) {
                throw new IllegalArgumentException(
                    "successful production lifecycle stages must be COMPLETED");
            }
            requireSha256(evidenceHash, "evidenceHash");
            requireSha256(contentHash, "contentHash");
        }
    }

    public record StageResourceLedger(
        String schema,
        String briefHash,
        List<StageResourceReceipt> receipts,
        boolean ledgerIsMathematicalEvidence,
        boolean externalNoveltyEvaluated,
        String promotionStatus,
        String publicEvidenceStatus,
        String contentHash
    ) {
        public StageResourceLedger {
            if (!SCHEMA.equals(schema)) {
                throw new IllegalArgumentException("unsupported stage ledger schema");
            }
            requireSha256(briefHash, "briefHash");
            receipts = receipts == null
                ? List.of()
                : receipts.stream()
                    .sorted(Comparator.comparing(StageResourceReceipt::stage))
                    .toList();
            Set<EvidenceStage> expected = EnumSet.of(
                EvidenceStage.VALIDATION,
                EvidenceStage.COUNTEREXAMPLE_SEARCH,
                EvidenceStage.PROJECT_NOVELTY,
                EvidenceStage.PROOF,
                EvidenceStage.LIFECYCLE_HANDOFF);
            Set<EvidenceStage> actual = receipts.stream()
                .map(StageResourceReceipt::stage)
                .collect(java.util.stream.Collectors.toCollection(
                    () -> EnumSet.noneOf(EvidenceStage.class)));
            if (!actual.equals(expected) || actual.size() != receipts.size()) {
                throw new IllegalArgumentException(
                    "stage ledger requires one receipt for every downstream stage");
            }
            if (ledgerIsMathematicalEvidence || externalNoveltyEvaluated) {
                throw new IllegalArgumentException(
                    "resource ledger is neither mathematical nor external-novelty evidence");
            }
            requireNotEvaluated(promotionStatus, "promotionStatus");
            requireNotEvaluated(publicEvidenceStatus, "publicEvidenceStatus");
            requireSha256(contentHash, "contentHash");
        }

        public StageResourceReceipt receipt(EvidenceStage stage) {
            return receipts.stream()
                .filter(item -> item.stage() == stage)
                .findFirst()
                .orElseThrow();
        }

        public String toCanonicalJson() {
            return new JsonWriter().beginObject()
                .property("schema", schema)
                .property("briefHash", briefHash)
                .array("receipts", array -> receipts.forEach(item ->
                    array.objectValue(object -> object
                        .property("stage", item.stage().name())
                        .property("resource", item.resource().name())
                        .property("configured", item.configured())
                        .property("executed", item.executed())
                        .property("skipped", item.skipped())
                        .property("remaining", item.remaining())
                        .property("disposition", item.disposition())
                        .property("evidenceHash", item.evidenceHash())
                        .property("contentHash", item.contentHash()))))
                .property("ledgerIsMathematicalEvidence",
                    ledgerIsMathematicalEvidence)
                .property("externalNoveltyEvaluated", externalNoveltyEvaluated)
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
