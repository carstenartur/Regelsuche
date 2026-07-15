package de.regelsuche.experiments.autopilot;

import de.regelsuche.experiments.autopilot.AutonomousEvidenceDagV2.MiningCandidate;
import de.regelsuche.experiments.autopilot.AutonomousEvidenceDagV2.MiningReport;
import de.regelsuche.experiments.autopilot.AutonomousEvidenceDagV2.RejectedCluster;
import java.util.Comparator;
import java.util.List;

/** Factory for canonical aggregate mining reports consumed by Autopilot v2. */
public final class AutonomousMiningReportV2 {
    public static final String SCHEMA = "regelsuche.autonomous-mining-report/v2";

    private AutonomousMiningReportV2() {
    }

    public static MiningReport create(
        String campaignId,
        String ruleInventoryHash,
        List<MiningCandidate> suppliedCandidates,
        List<RejectedCluster> suppliedRejectedClusters
    ) {
        List<MiningCandidate> candidates = suppliedCandidates == null
            ? List.of()
            : suppliedCandidates.stream()
                .sorted(Comparator.comparing(MiningCandidate::candidateId))
                .toList();
        List<RejectedCluster> rejected = suppliedRejectedClusters == null
            ? List.of()
            : suppliedRejectedClusters.stream()
                .sorted(Comparator.comparing(RejectedCluster::clusterId))
                .toList();
        String material = SCHEMA
            + "\ncampaign=" + campaignId
            + "\ninventory=" + ruleInventoryHash
            + "\ncandidates=" + candidates.stream()
                .map(MiningCandidate::identityMaterial).toList()
            + "\nrejected=" + rejected.stream()
                .map(RejectedCluster::identityMaterial).toList();
        return new MiningReport(
            campaignId,
            ruleInventoryHash,
            candidates,
            rejected,
            AutonomousResearchBrief.hash(material));
    }
}
