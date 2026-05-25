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
@Table(name = "discovery_experiments", indexes = @Index(name = "idx_discovery_experiments_status", columnList = "status"))
public class DiscoveryExperimentEntity {
    @Id
    private String id;
    @Column(nullable = false)
    private String name;
    @Column(nullable = false, columnDefinition = "text")
    private String description;
    @Column(nullable = false)
    private String status;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "search_run_ids", nullable = false, columnDefinition = "jsonb")
    private String searchRunIdsJson;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected DiscoveryExperimentEntity() {
    }

    public DiscoveryExperimentEntity(String id, String name, String description, String status,
        List<String> searchRunIds, Instant createdAt, Instant updatedAt) {
        this.id = SearchRunEntity.requireId(id, "id");
        this.name = name == null ? id : name;
        this.description = description == null ? "" : description;
        this.status = status == null ? "DRAFT" : status;
        this.searchRunIdsJson = RelationalJson.array(searchRunIds);
        this.createdAt = createdAt == null ? Instant.now() : createdAt;
        this.updatedAt = updatedAt == null ? this.createdAt : updatedAt;
    }

    public String id() { return id; }
    public String name() { return name; }
    public String description() { return description; }
    public String status() { return status; }
    public List<String> searchRunIds() { return RelationalJson.arrayValues(searchRunIdsJson); }
    public Instant createdAt() { return createdAt; }
    public Instant updatedAt() { return updatedAt; }
}
