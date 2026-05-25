package de.regelsuche.persistence.relational;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.List;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.search.mapper.pojo.mapping.definition.annotation.FullTextField;
import org.hibernate.search.mapper.pojo.mapping.definition.annotation.Indexed;
import org.hibernate.search.mapper.pojo.mapping.definition.annotation.KeywordField;
import org.hibernate.type.SqlTypes;

@Entity
@Indexed
@Table(name = "seed_expressions", indexes = @Index(name = "idx_seed_expressions_domain", columnList = "domain"))
public class SeedExpressionEntity {
    @Id
    private String id;
    @FullTextField
    @Column(name = "expression", nullable = false, columnDefinition = "text")
    private String expression;
    @KeywordField
    @Column(name = "domain", nullable = false)
    private String domain;
    @KeywordField
    @Column(name = "difficulty", nullable = false)
    private String difficulty;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "tags", nullable = false, columnDefinition = "jsonb")
    private String tagsJson;
    @FullTextField(name = "tags")
    @Column(name = "tags_text", nullable = false, columnDefinition = "text")
    private String tagsText;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected SeedExpressionEntity() {
    }

    public SeedExpressionEntity(String id, String expression, String domain, String difficulty, List<String> tags, Instant createdAt) {
        this.id = SearchRunEntity.requireId(id, "id");
        this.expression = SearchRunEntity.requireId(expression, "expression");
        this.domain = domain == null ? "general" : domain;
        this.difficulty = difficulty == null ? "unknown" : difficulty;
        this.tagsJson = RelationalJson.array(tags);
        this.tagsText = RelationalJson.join(tags);
        this.createdAt = createdAt == null ? Instant.now() : createdAt;
    }

    public String id() { return id; }
    public String expression() { return expression; }
    public String domain() { return domain; }
    public String difficulty() { return difficulty; }
    public List<String> tags() { return RelationalJson.arrayValues(tagsJson); }
    public Instant createdAt() { return createdAt; }
}
