package de.regelsuche.persistence.relational;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.search.mapper.pojo.mapping.definition.annotation.FullTextField;
import org.hibernate.search.mapper.pojo.mapping.definition.annotation.GenericField;
import org.hibernate.search.mapper.pojo.mapping.definition.annotation.Indexed;
import org.hibernate.search.mapper.pojo.mapping.definition.annotation.KeywordField;

@Entity
@Indexed
@Table(name = "benchmark_results", indexes = {
    @Index(name = "idx_benchmark_results_experiment", columnList = "experiment_id"),
    @Index(name = "idx_benchmark_results_name", columnList = "benchmark_name")
})
public class BenchmarkResultEntity {
    @Id
    private String id;
    @Column(name = "experiment_id")
    private String experimentId;
    @FullTextField
    @KeywordField(name = "benchmarkNameExact")
    @Column(name = "benchmark_name", nullable = false)
    private String benchmarkName;
    @GenericField
    @Column(name = "duration_millis", nullable = false)
    private long durationMillis;
    @GenericField
    @Column(name = "solved_count", nullable = false)
    private int solvedCount;
    @GenericField
    @Column(name = "total_count", nullable = false)
    private int totalCount;
    @GenericField
    @Column(name = "quality_score", nullable = false)
    private double qualityScore;
    @Column(name = "measured_at", nullable = false)
    private Instant measuredAt;
    @FullTextField(name = "metrics")
    @Column(name = "metrics_text", nullable = false, columnDefinition = "text")
    private String metricsText;

    protected BenchmarkResultEntity() {
    }

    public BenchmarkResultEntity(String id, String experimentId, String benchmarkName, long durationMillis,
        int solvedCount, int totalCount, double qualityScore, Instant measuredAt) {
        this.id = SearchRunEntity.requireId(id, "id");
        this.experimentId = experimentId == null ? "" : experimentId;
        this.benchmarkName = benchmarkName == null ? id : benchmarkName;
        if (durationMillis < 0 || solvedCount < 0 || totalCount < 0) {
            throw new IllegalArgumentException("benchmark counters must not be negative");
        }
        this.durationMillis = durationMillis;
        this.solvedCount = solvedCount;
        this.totalCount = totalCount;
        this.qualityScore = Math.max(0.0, Math.min(1.0, qualityScore));
        this.measuredAt = measuredAt == null ? Instant.now() : measuredAt;
        this.metricsText = "duration " + durationMillis + " solved " + solvedCount + " total " + totalCount
            + " quality " + this.qualityScore;
    }

    public String id() { return id; }
    public String experimentId() { return experimentId; }
    public String benchmarkName() { return benchmarkName; }
    public long durationMillis() { return durationMillis; }
    public int solvedCount() { return solvedCount; }
    public int totalCount() { return totalCount; }
    public double qualityScore() { return qualityScore; }
    public Instant measuredAt() { return measuredAt; }
}
