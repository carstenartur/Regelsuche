package de.regelsuche.persistence.relational;

import java.time.Instant;
import java.util.List;

public record ExportReportEntity(
    String id,
    String experimentId,
    String title,
    String format,
    String storageUri,
    List<String> referencedSearchRunIds,
    Instant createdAt
) {
    public ExportReportEntity {
        id = SearchRunEntity.requireId(id, "id");
        experimentId = experimentId == null ? "" : experimentId;
        title = title == null ? id : title;
        format = format == null ? "markdown" : format;
        storageUri = storageUri == null ? "" : storageUri;
        referencedSearchRunIds = referencedSearchRunIds == null ? List.of() : List.copyOf(referencedSearchRunIds);
        createdAt = createdAt == null ? Instant.now() : createdAt;
    }
}
