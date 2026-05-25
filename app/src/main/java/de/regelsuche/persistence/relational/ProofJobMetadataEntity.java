package de.regelsuche.persistence.relational;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "proof_job_metadata", indexes = {
    @Index(name = "idx_proof_job_metadata_hypothesis", columnList = "hypothesis_id"),
    @Index(name = "idx_proof_job_metadata_status", columnList = "status")
})
public class ProofJobMetadataEntity {
    @Id
    private String id;
    @Column(name = "hypothesis_id")
    private String hypothesisId;
    @Column(nullable = false)
    private String prover;
    @Column(nullable = false)
    private String status;
    @Column(name = "artifact_uri", nullable = false)
    private String artifactUri;
    @Column(name = "submitted_at", nullable = false)
    private Instant submittedAt;
    @Column(name = "completed_at")
    private Instant completedAt;

    protected ProofJobMetadataEntity() {
    }

    public ProofJobMetadataEntity(String id, String hypothesisId, String prover, String status,
        String artifactUri, Instant submittedAt, Instant completedAt) {
        this.id = SearchRunEntity.requireId(id, "id");
        this.hypothesisId = hypothesisId == null ? "" : hypothesisId;
        this.prover = prover == null ? "unknown" : prover;
        this.status = status == null ? "QUEUED" : status;
        this.artifactUri = artifactUri == null ? "" : artifactUri;
        this.submittedAt = submittedAt == null ? Instant.now() : submittedAt;
        this.completedAt = completedAt;
    }

    public String id() { return id; }
    public String hypothesisId() { return hypothesisId; }
    public String prover() { return prover; }
    public String status() { return status; }
    public String artifactUri() { return artifactUri; }
    public Instant submittedAt() { return submittedAt; }
    public Instant completedAt() { return completedAt; }
}
