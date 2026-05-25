package de.regelsuche.persistence.relational;

import java.time.Instant;

public record BenchmarkResultEntity(
    String id,
    String experimentId,
    String benchmarkName,
    long durationMillis,
    int solvedCount,
    int totalCount,
    double qualityScore,
    Instant measuredAt
) {
    public BenchmarkResultEntity {
        id = SearchRunEntity.requireId(id, "id");
        experimentId = experimentId == null ? "" : experimentId;
        benchmarkName = benchmarkName == null ? id : benchmarkName;
        if (durationMillis < 0 || solvedCount < 0 || totalCount < 0) {
            throw new IllegalArgumentException("benchmark counters must not be negative");
        }
        qualityScore = Math.max(0.0, Math.min(1.0, qualityScore));
        measuredAt = measuredAt == null ? Instant.now() : measuredAt;
    }
}
