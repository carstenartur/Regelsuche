package de.regelsuche.discovery.representation;

import de.regelsuche.knowledge.KnowledgePack;
import de.regelsuche.knowledge.KnowledgePackRegistry;
import de.regelsuche.knowledge.KnowledgePackSelection;
import de.regelsuche.knowledge.KnownStructureDefinition;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Adapts explicitly selected knowledge-pack structures to the discovery
 * catalog without activating a Python runtime or implicit external oracle.
 */
public final class KnowledgePackKnownStructureCatalog {
    private static final String REVISION_PREFIX =
        "knowledge-pack-known-structures/v1";

    private KnowledgePackKnownStructureCatalog() {
    }

    public static KnownStructureCatalog fromSelection(
        KnowledgePackSelection selection
    ) {
        return from(new KnowledgePackRegistry(), selection);
    }

    public static KnownStructureCatalog from(
        KnowledgePackRegistry registry,
        KnowledgePackSelection selection
    ) {
        Objects.requireNonNull(registry, "registry");
        Objects.requireNonNull(selection, "selection");

        List<KnowledgePack> contributingPacks = registry.enabledPacks(selection)
            .stream()
            .filter(pack -> !pack.knownStructures().isEmpty())
            .sorted(Comparator.comparing(KnowledgePack::packId))
            .toList();

        List<KnownStructure> structures = contributingPacks.stream()
            .flatMap(pack -> pack.knownStructures().stream())
            .sorted(Comparator.comparing(KnownStructureDefinition::id))
            .map(KnowledgePackKnownStructureCatalog::adapt)
            .toList();

        return new KnownStructureCatalog(
            revision(contributingPacks),
            structures
        );
    }

    private static KnownStructure adapt(KnownStructureDefinition definition) {
        return new KnownStructure(
            definition.id(),
            definition.domainId(),
            definition.matcher(),
            definition.requiredAssumptions(),
            definition.consequenceIds(),
            definition.metadata().provenanceSummary(),
            definition.metadata()
        );
    }

    private static String revision(List<KnowledgePack> packs) {
        StringBuilder revision = new StringBuilder(REVISION_PREFIX);
        for (KnowledgePack pack : packs) {
            append(revision, pack.packId());
            append(revision, pack.sourceVersion());
            append(revision, pack.sourceReference());
        }
        return revision.toString();
    }

    private static void append(StringBuilder target, String value) {
        target.append('|').append(value.length()).append(':').append(value);
    }
}
