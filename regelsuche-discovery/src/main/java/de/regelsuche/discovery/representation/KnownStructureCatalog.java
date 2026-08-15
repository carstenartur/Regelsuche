package de.regelsuche.discovery.representation;

import de.regelsuche.knowledge.KnowledgePackRegistry;
import de.regelsuche.knowledge.KnowledgePackSelection;
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
    private static final String KNOWLEDGE_PACK_REVISION =
        "knowledge-pack-known-structures/v1";

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

    /** Builds a catalog from the explicitly selected knowledge packs. */
    public static KnownStructureCatalog fromKnowledgePacks(
        KnowledgePackSelection selection
    ) {
        return fromKnowledgePacks(new KnowledgePackRegistry(), selection);
    }

    /** Builds a catalog from a supplied registry and explicit pack selection. */
    public static KnownStructureCatalog fromKnowledgePacks(
        KnowledgePackRegistry registry,
        KnowledgePackSelection selection
    ) {
        Objects.requireNonNull(registry, "registry");
        Objects.requireNonNull(selection, "selection");
        List<KnownStructure> structures = registry
            .enabledKnownStructures(selection)
            .stream()
            .map(definition -> new KnownStructure(
                definition.id(),
                definition.domainId(),
                definition.matcher(),
                definition.requiredAssumptions(),
                definition.consequenceIds(),
                definition.metadata().provenanceSummary(),
                definition.metadata()
            ))
            .sorted(Comparator.comparing(KnownStructure::id))
            .toList();
        return new KnownStructureCatalog(KNOWLEDGE_PACK_REVISION, structures);
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
        StringBuilder descriptor = new StringBuilder();
        appendCanonicalField(descriptor, revision);
        appendCanonicalField(descriptor, Integer.toString(structures.size()));
        structures.forEach(structure ->
            appendCanonicalField(descriptor, structure.canonicalDescriptor()));
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

    static void appendCanonicalList(
        StringBuilder descriptor,
        List<String> values
    ) {
        appendCanonicalField(descriptor, Integer.toString(values.size()));
        values.forEach(value -> appendCanonicalField(descriptor, value));
    }

    static void appendCanonicalField(StringBuilder descriptor, String value) {
        Objects.requireNonNull(descriptor, "descriptor");
        Objects.requireNonNull(value, "value");
        descriptor.append(value.length()).append(':').append(value);
    }

    static String sha256(String value) {
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
