package de.regelsuche.search.convergence;

/** Metadata for generated discovery artifacts. */
public record ArtifactMetadata(String dataSource, String title) {
    public ArtifactMetadata {
        dataSource = dataSource == null ? "" : dataSource;
        title = title == null ? "" : title;
    }
}
