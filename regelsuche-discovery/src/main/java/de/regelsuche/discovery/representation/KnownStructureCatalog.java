package de.regelsuche.discovery.representation;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Content-addressed catalog used for post-hoc or visible known-form matching. */
public final class KnownStructureCatalog {
    private final String revision;
    private final List<KnownStructure> structures;
    private final String contentHash;

    public KnownStructureCatalog(String revision, List<KnownStructure> structures) {
        this.revision = RepresentationCandidateAssessment.requireText(
            revision, "revision");
        Objects.requireNonNull(structures, "structures");
        this.structures = structures.stream()
            .map(structure -> Objects.requireNonNull(structure, "structure"))
            .sorted(Comparator.comparing(KnownStructure::id))
            .toList();
        rejectDuplicateIds(this.structures);
        this.contentHash = sha256(canonicalDescriptor());
    }

    public static KnownStructureCatalog empty() {
        return new KnownStructureCatalog("none", List.of());
    }

    public String revision() {
        return revision;
    }

    public List<KnownStructure> structures() {
        return structures;
    }

    public String contentHash() {
        return contentHash;
    }

    private String canonicalDescriptor() {
        StringBuilder descriptor = new StringBuilder(revision);
        structures.forEach(structure -> descriptor
            .append('\n')
            .append(structure.canonicalDescriptor()));
        return descriptor.toString();
    }

    private static void rejectDuplicateIds(List<KnownStructure> structures) {
        Set<String> ids = new HashSet<>();
        for (KnownStructure structure : structures) {
            if (!ids.add(structure.id())) {
                throw new IllegalArgumentException(
                    "duplicate known-structure id: " + structure.id());
            }
        }
    }

    private static String sha256(String value) {
        try {
            return "sha256:" + HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8))
            );
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
