package de.regelsuche.experiments.autopilot;

import de.regelsuche.experiments.autopilot.AutonomousEvidenceDagV2.AggregateExecutionReceipt;
import de.regelsuche.experiments.autopilot.AutonomousEvidenceDagV2.BranchLineage;
import de.regelsuche.json.JsonWriter;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** Standalone canonical lineage DAG artifact for one aggregate execution. */
public record AutonomousLineageDagReportV2(
    String schema,
    String executionHash,
    List<BranchLineage> lineages,
    String contentHash
) {
    public AutonomousLineageDagReportV2 {
        if (!AutonomousEvidenceDagV2.LINEAGE_SCHEMA.equals(schema)) {
            throw new IllegalArgumentException("unsupported lineage DAG schema");
        }
        requireSha256(executionHash, "executionHash");
        lineages = lineages == null
            ? List.of()
            : lineages.stream()
                .sorted(Comparator.comparing(BranchLineage::outputBranchId))
                .toList();
        requireSha256(contentHash, "contentHash");
    }

    public static AutonomousLineageDagReportV2 create(
        AggregateExecutionReceipt execution
    ) {
        Objects.requireNonNull(execution, "execution");
        List<BranchLineage> lineages = execution.lineages().stream()
            .sorted(Comparator.comparing(BranchLineage::outputBranchId))
            .toList();
        String hash = AutonomousResearchBrief.hash(
            AutonomousEvidenceDagV2.LINEAGE_SCHEMA
                + "\nlineages=" + lineages.stream()
                    .map(BranchLineage::lineageHash).toList());
        return new AutonomousLineageDagReportV2(
            AutonomousEvidenceDagV2.LINEAGE_SCHEMA,
            execution.contentHash(),
            lineages,
            hash);
    }

    public String toCanonicalJson() {
        return new JsonWriter().beginObject()
            .property("schema", schema)
            .property("executionHash", executionHash)
            .array("lineages", array -> lineages.forEach(lineage ->
                array.objectValue(object -> object
                    .property("outputBranchId", lineage.outputBranchId())
                    .property("candidateId", lineage.candidateId())
                    .property("decisionHash", lineage.decisionHash())
                    .property("miningReportHash", lineage.miningReportHash())
                    .property("convergenceEvidenceHash",
                        lineage.convergenceEvidenceHash())
                    .array("sourceBranches", sources -> lineage.sourceBranches()
                        .forEach(source -> sources.objectValue(item -> item
                            .property("branchId", source.branchId())
                            .property("snapshotHash", source.snapshotHash())
                            .property("evidenceHash", source.evidenceHash()))))
                    .property("lineageHash", lineage.lineageHash()))))
            .property("contentHash", contentHash)
            .endObject()
            .toString();
    }

    private static void requireSha256(String value, String name) {
        if (value == null || !value.matches("sha256:[0-9a-f]{64}")) {
            throw new IllegalArgumentException(name + " must be SHA-256");
        }
    }
}
