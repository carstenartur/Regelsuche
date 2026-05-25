package de.regelsuche.persistence.relational;

/** Versioned SQL migration resource. */
public record DatabaseMigration(int version, String name, String resourcePath) {
    public DatabaseMigration {
        if (version <= 0) {
            throw new IllegalArgumentException("version must be positive");
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        if (resourcePath == null || resourcePath.isBlank()) {
            throw new IllegalArgumentException("resourcePath must not be blank");
        }
    }
}
