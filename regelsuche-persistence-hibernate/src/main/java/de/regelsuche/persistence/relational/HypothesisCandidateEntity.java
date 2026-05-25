package de.regelsuche.persistence.relational;

import de.regelsuche.mining.HypothesisCandidate;
import de.regelsuche.validation.CandidateProofStatus;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.search.mapper.pojo.mapping.definition.annotation.FullTextField;
import org.hibernate.search.mapper.pojo.mapping.definition.annotation.GenericField;
import org.hibernate.search.mapper.pojo.mapping.definition.annotation.Indexed;
import org.hibernate.search.mapper.pojo.mapping.definition.annotation.KeywordField;
import org.hibernate.type.SqlTypes;

@Entity
@Indexed
@Table(name = "hypothesis_candidates", indexes = {
    @Index(name = "idx_hypothesis_candidates_proof_status", columnList = "proof_status"),
    @Index(name = "idx_hypothesis_candidates_experiment", columnList = "experiment_id")
})
public class HypothesisCandidateEntity {
    @Id
    private String id;
    @Column(name = "experiment_id")
    private String experimentId;
    @FullTextField
    @Column(name = "left_pattern", nullable = false, columnDefinition = "text")
    private String leftPattern;
    @FullTextField
    @Column(name = "right_pattern", nullable = false, columnDefinition = "text")
    private String rightPattern;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "assumptions", nullable = false, columnDefinition = "jsonb")
    private String assumptionsJson;
    @FullTextField(name = "assumptions")
    @Column(name = "assumptions_text", nullable = false, columnDefinition = "text")
    private String assumptionsText;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "supporting_paths", nullable = false, columnDefinition = "jsonb")
    private String supportingPathsJson;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "supporting_expressions", nullable = false, columnDefinition = "jsonb")
    private String supportingExpressionsJson;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "parameter_relations", nullable = false, columnDefinition = "jsonb")
    private String parameterRelationsJson;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "expression_placeholders", nullable = false, columnDefinition = "jsonb")
    private String expressionPlaceholdersJson;
    @KeywordField
    @Column(name = "proof_status", nullable = false)
    private String proofStatus;
    @Column(name = "counterexample_found")
    private Boolean counterexampleFound;
    @GenericField
    @Column(name = "novelty_score", nullable = false)
    private double noveltyScore;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
    @OneToMany(mappedBy = "hypothesis", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<CounterexampleEntity> counterexamples = new ArrayList<>();

    protected HypothesisCandidateEntity() {
    }

    public HypothesisCandidateEntity(String id, String experimentId, String leftPattern, String rightPattern,
        List<String> assumptions, List<String> supportingPaths,
        List<HypothesisCandidate.ExpressionPair> supportingExpressions, List<String> parameterRelations,
        java.util.Map<String, List<String>> expressionPlaceholders, String proofStatus,
        Boolean counterexampleFound, double noveltyScore, Instant createdAt) {
        this.id = SearchRunEntity.requireId(id, "id");
        this.experimentId = experimentId == null || experimentId.isBlank() ? null : experimentId;
        if (leftPattern == null || rightPattern == null) {
            throw new IllegalArgumentException("patterns must not be null");
        }
        this.leftPattern = leftPattern;
        this.rightPattern = rightPattern;
        this.assumptionsJson = RelationalJson.array(assumptions);
        this.assumptionsText = RelationalJson.join(assumptions);
        this.supportingPathsJson = RelationalJson.array(supportingPaths);
        this.supportingExpressionsJson = RelationalJson.expressionPairs(supportingExpressions);
        this.parameterRelationsJson = RelationalJson.array(parameterRelations);
        this.expressionPlaceholdersJson = RelationalJson.placeholderEntries(expressionPlaceholders);
        this.proofStatus = proofStatus == null ? CandidateProofStatus.OBSERVED.name() : proofStatus;
        this.counterexampleFound = counterexampleFound;
        this.noveltyScore = Math.max(0.0, Math.min(1.0, noveltyScore));
        this.createdAt = createdAt == null ? Instant.now() : createdAt;
    }

    public static HypothesisCandidateEntity from(HypothesisCandidate candidate) {
        return new HypothesisCandidateEntity(candidate.id(), "", candidate.leftPattern(), candidate.rightPattern(),
            candidate.assumptions(), candidate.supportingPaths(), candidate.supportingExpressions(),
            candidate.parameterRelations(), candidate.expressionPlaceholders(),
            candidate.proofStatus().name(), candidate.counterexampleStatus(), candidate.noveltyScore(), candidate.createdAt());
    }

    public HypothesisCandidate toHypothesisCandidate() {
        return new HypothesisCandidate(id, leftPattern, rightPattern, supportingPaths(), supportingExpressions(), assumptions(),
            noveltyScore, CandidateProofStatus.valueOf(proofStatus), counterexampleFound,
            parameterRelations(), expressionPlaceholders(), createdAt);
    }

    public String id() { return id; }
    public String experimentId() { return experimentId; }
    public String leftPattern() { return leftPattern; }
    public String rightPattern() { return rightPattern; }
    public List<String> assumptions() { return RelationalJson.arrayValues(assumptionsJson); }
    public List<String> supportingPaths() { return RelationalJson.arrayValues(supportingPathsJson); }
    public List<HypothesisCandidate.ExpressionPair> supportingExpressions() {
        return RelationalJson.expressionPairsValues(supportingExpressionsJson);
    }
    public List<String> parameterRelations() { return RelationalJson.arrayValues(parameterRelationsJson); }
    public java.util.Map<String, List<String>> expressionPlaceholders() {
        return RelationalJson.placeholderEntriesValues(expressionPlaceholdersJson);
    }
    public String proofStatus() { return proofStatus; }
    public Boolean counterexampleFound() { return counterexampleFound; }
    public double noveltyScore() { return noveltyScore; }
    public Instant createdAt() { return createdAt; }
    public List<CounterexampleEntity> counterexamples() { return List.copyOf(counterexamples); }
}
