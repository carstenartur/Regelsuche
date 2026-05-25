package de.regelsuche.persistence.relational;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.List;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "search_runs", indexes = {
    @Index(name = "idx_search_runs_status", columnList = "status"),
    @Index(name = "idx_search_runs_started_at", columnList = "started_at")
})
public class SearchRunEntity {
    @Id
    @Column(name = "id", nullable = false)
    private String id;
    @Column(name = "source_expression", nullable = false, columnDefinition = "text")
    private String sourceExpression;
    @Column(name = "target_expression", nullable = false, columnDefinition = "text")
    private String targetExpression;
    @Column(name = "strategy", nullable = false)
    private String strategy;
    @Column(name = "status", nullable = false)
    private String status;
    @Column(name = "visited_states", nullable = false)
    private int visitedStates;
    @Column(name = "frontier_size", nullable = false)
    private int frontierSize;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "best_path_ids", nullable = false, columnDefinition = "jsonb")
    private String bestPathIdsJson;
    @Column(name = "started_at", nullable = false)
    private Instant startedAt;
    @Column(name = "finished_at")
    private Instant finishedAt;

    protected SearchRunEntity() {
    }

    public SearchRunEntity(String id, String sourceExpression, String targetExpression, String strategy, String status,
        int visitedStates, int frontierSize, List<String> bestPathIds, Instant startedAt, Instant finishedAt) {
        this.id = requireId(id, "id");
        this.sourceExpression = sourceExpression == null ? "" : sourceExpression;
        this.targetExpression = targetExpression == null ? "" : targetExpression;
        this.strategy = strategy == null ? "" : strategy;
        this.status = status == null ? "CREATED" : status;
        if (visitedStates < 0 || frontierSize < 0) {
            throw new IllegalArgumentException("search counters must not be negative");
        }
        this.visitedStates = visitedStates;
        this.frontierSize = frontierSize;
        this.bestPathIdsJson = RelationalJson.array(bestPathIds);
        this.startedAt = startedAt == null ? Instant.now() : startedAt;
        this.finishedAt = finishedAt;
    }

    static String requireId(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    public String id() { return id; }
    public String sourceExpression() { return sourceExpression; }
    public String targetExpression() { return targetExpression; }
    public String strategy() { return strategy; }
    public String status() { return status; }
    public int visitedStates() { return visitedStates; }
    public int frontierSize() { return frontierSize; }
    public List<String> bestPathIds() { return RelationalJson.arrayValues(bestPathIdsJson); }
    public Instant startedAt() { return startedAt; }
    public Instant finishedAt() { return finishedAt; }
}
