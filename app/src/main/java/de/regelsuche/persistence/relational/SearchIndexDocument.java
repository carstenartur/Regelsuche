package de.regelsuche.persistence.relational;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.List;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.search.mapper.pojo.mapping.definition.annotation.DocumentId;
import org.hibernate.search.mapper.pojo.mapping.definition.annotation.FullTextField;
import org.hibernate.search.mapper.pojo.mapping.definition.annotation.Indexed;
import org.hibernate.search.mapper.pojo.mapping.definition.annotation.KeywordField;
import org.hibernate.type.SqlTypes;

@Entity
@Indexed
@Table(name = "search_index_documents", indexes = @Index(name = "idx_search_index_documents_type", columnList = "type"))
public class SearchIndexDocument {
    @Id
    @DocumentId
    @Column(name = "document_id", nullable = false)
    private String documentId;
    @Enumerated(EnumType.STRING)
    @KeywordField
    @Column(name = "type", nullable = false)
    private SearchEntityType type;
    @KeywordField
    @Column(name = "entity_id", nullable = false)
    private String entityId;
    @FullTextField
    @Column(name = "title", nullable = false, columnDefinition = "text")
    private String title;
    @FullTextField
    @Column(name = "body", nullable = false, columnDefinition = "text")
    private String body;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "facets", nullable = false, columnDefinition = "jsonb")
    private String facetsJson;
    @KeywordField(name = "facet")
    @FullTextField(name = "facets")
    @Column(name = "facets_text", nullable = false, columnDefinition = "text")
    private String facetsText;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected SearchIndexDocument() {
    }

    public SearchIndexDocument(SearchEntityType type, String entityId, String title, String body,
        List<SearchFacet> facets, Instant updatedAt) {
        if (type == null) {
            throw new IllegalArgumentException("type must not be null");
        }
        this.type = type;
        this.entityId = SearchRunEntity.requireId(entityId, "entityId");
        this.documentId = type + ":" + this.entityId;
        this.title = title == null ? "" : title;
        this.body = body == null ? "" : body;
        this.facetsJson = RelationalJson.object(facets);
        this.facetsText = RelationalJson.joinFacets(facets);
        this.updatedAt = updatedAt == null ? Instant.now() : updatedAt;
    }

    public boolean hasFacet(SearchFacet facet) {
        return facets().contains(facet);
    }

    public String documentId() { return documentId; }
    public SearchEntityType type() { return type; }
    public String entityId() { return entityId; }
    public String title() { return title; }
    public String body() { return body; }
    public List<SearchFacet> facets() { return RelationalJson.facets(facetsJson); }
    public Instant updatedAt() { return updatedAt; }

}
