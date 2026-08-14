package de.regelsuche.discovery.representation;

import de.regelsuche.knowledge.KnowledgePackRegistry;
import de.regelsuche.knowledge.KnowledgePackSelection;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** Adapts explicitly selected pack knowledge to representation discovery. */
public final class KnowledgePackKnownStructureCatalog {
    private KnowledgePackKnownStructureCatalog() {
    }

    public static KnownStructureCatalog fromSelection(
        KnowledgePackSelection selection
    ) {
        return from(new KnowledgePackRegistry(), selection);
    }

    public static KnownStructureCatalog from(
        KnowledgePackRegistry registry, KnowledgePackSelection selection
    ) {
        Objects.requireNonNull(registry);
        Objects.requireNonNull(selection);
        List<KnownStructure> structures =
            registry.enabledKnownStructures(selection).stream()
                .map(definition -> new KnownStructure(
                    definition.id(), definition.domainId(),
                    definition.matcher(), definition.requiredAssumptions(),
                    definition.consequenceIds(),
                    definition.metadata().provenanceSummary(),
                    definition.metadata()))
                .sorted(Comparator.comparing(KnownStructure::id)).toList();
        return new KnownStructureCatalog(
            "knowledge-pack-known-structures/v1", structures);
    }
}
