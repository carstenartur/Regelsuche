package de.regelsuche.quality.jmh;

import java.util.List;
import java.util.Map;

record JmhHistory(
    String claimBoundary,
    String historyPolicyDigest,
    String regressionPolicyDigest,
    List<Snapshot> snapshots,
    Map<String, BenchmarkContract> benchmarks
) {
    JmhHistory {
        snapshots = List.copyOf(snapshots);
        benchmarks = Map.copyOf(benchmarks);
    }

    record Snapshot(
        String label,
        String recordedAt,
        String sourceRevision,
        String sourceArtifactDigest,
        String snapshotPath,
        String snapshotDigest,
        Map<String, Measurement> measurements
    ) {
        Snapshot {
            measurements = Map.copyOf(measurements);
        }
    }

    record BenchmarkContract(String family, String sourceUnit) {
    }

    record Measurement(double scoreMsPerOp, double scoreErrorMsPerOp) {
    }
}
