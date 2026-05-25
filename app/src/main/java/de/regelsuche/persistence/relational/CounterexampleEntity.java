package de.regelsuche.persistence.relational;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.List;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "counterexamples", indexes = @Index(name = "idx_counterexamples_hypothesis", columnList = "hypothesis_id"))
public class CounterexampleEntity {
    @Id
    private String id;
    @Column(name = "hypothesis_id", nullable = false, insertable = false, updatable = false)
    private String hypothesisId;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "hypothesis_id", nullable = false)
    private HypothesisCandidateEntity hypothesis;
    @Column(name = "input_expression", nullable = false, columnDefinition = "text")
    private String inputExpression;
    @Column(name = "expected_expression", nullable = false, columnDefinition = "text")
    private String expectedExpression;
    @Column(name = "actual_expression", nullable = false, columnDefinition = "text")
    private String actualExpression;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "assumptions", nullable = false, columnDefinition = "jsonb")
    private String assumptionsJson;
    @Column(name = "found_at", nullable = false)
    private Instant foundAt;

    protected CounterexampleEntity() {
    }

    public CounterexampleEntity(String id, String hypothesisId, String inputExpression, String expectedExpression,
        String actualExpression, List<String> assumptions, Instant foundAt) {
        this.id = SearchRunEntity.requireId(id, "id");
        this.hypothesisId = SearchRunEntity.requireId(hypothesisId, "hypothesisId");
        this.inputExpression = inputExpression == null ? "" : inputExpression;
        this.expectedExpression = expectedExpression == null ? "" : expectedExpression;
        this.actualExpression = actualExpression == null ? "" : actualExpression;
        this.assumptionsJson = RelationalJson.array(assumptions);
        this.foundAt = foundAt == null ? Instant.now() : foundAt;
    }

    void attach(HypothesisCandidateEntity hypothesis) {
        this.hypothesis = hypothesis;
        this.hypothesisId = hypothesis.id();
    }

    public String id() { return id; }
    public String hypothesisId() { return hypothesisId; }
    public String inputExpression() { return inputExpression; }
    public String expectedExpression() { return expectedExpression; }
    public String actualExpression() { return actualExpression; }
    public List<String> assumptions() { return RelationalJson.arrayValues(assumptionsJson); }
    public Instant foundAt() { return foundAt; }
}
