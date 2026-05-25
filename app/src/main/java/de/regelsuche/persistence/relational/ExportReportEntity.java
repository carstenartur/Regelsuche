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
@Table(name = "export_reports", indexes = {
    @Index(name = "idx_export_reports_experiment", columnList = "experiment_id"),
    @Index(name = "idx_export_reports_format", columnList = "format")
})
public class ExportReportEntity {
    @Id
    private String id;
    @Column(name = "experiment_id")
    private String experimentId;
    @FullTextField
    @Column(name = "title", nullable = false)
    private String title;
    @KeywordField
    @Column(name = "format", nullable = false)
    private String format;
    @Column(name = "storage_uri", nullable = false)
    private String storageUri;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "referenced_search_run_ids", nullable = false, columnDefinition = "jsonb")
    private String referencedSearchRunIdsJson;
    @FullTextField
    @Column(name = "body", nullable = false, columnDefinition = "text")
    private String body;
    @KeywordField
    @Column(name = "domain", nullable = false)
    private String domain;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "facets", nullable = false, columnDefinition = "jsonb")
    private String facetsJson;
    @FullTextField(name = "facets")
    @Column(name = "facets_text", nullable = false, columnDefinition = "text")
    private String facetsText;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected ExportReportEntity() {
    }

    public ExportReportEntity(String id, String experimentId, String title, String format, String storageUri,
        List<String> referencedSearchRunIds, Instant createdAt) {
        this(id, experimentId, title, "", "general", List.of(), format, storageUri, referencedSearchRunIds, createdAt);
    }

    public ExportReportEntity(String id, String experimentId, String title, String body, String domain,
        List<SearchFacet> facets, String format, String storageUri, List<String> referencedSearchRunIds, Instant createdAt) {
        this.id = SearchRunEntity.requireId(id, "id");
        this.experimentId = experimentId == null ? "" : experimentId;
        this.title = title == null ? id : title;
        this.body = body == null ? "" : body;
        this.domain = domain == null ? "general" : domain;
        this.facetsJson = RelationalJson.object(facets);
        this.facetsText = RelationalJson.joinFacets(facets);
        this.format = format == null ? "markdown" : format;
        this.storageUri = storageUri == null ? "" : storageUri;
        this.referencedSearchRunIdsJson = RelationalJson.array(referencedSearchRunIds);
        this.createdAt = createdAt == null ? Instant.now() : createdAt;
    }

    public String id() { return id; }
    public String experimentId() { return experimentId; }
    public String title() { return title; }
    public String body() { return body; }
    public String domain() { return domain; }
    public List<SearchFacet> facets() { return RelationalJson.facets(facetsJson); }
    public String format() { return format; }
    public String storageUri() { return storageUri; }
    public List<String> referencedSearchRunIds() { return RelationalJson.arrayValues(referencedSearchRunIdsJson); }
    public Instant createdAt() { return createdAt; }
}
