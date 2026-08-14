package de.regelsuche.discovery.representation;

import de.regelsuche.transform.PatternExpr;
import java.util.List;
import java.util.Objects;

/** One provenance-bearing known mathematical structure. */
public record KnownStructure(
    String id,
    String domainId,
    PatternExpr pattern,
    List<String> requiredAssumptions,
    List<String> consequenceIds,
    String provenance
) {
    public KnownStructure {
        id = RepresentationContracts.text(id, "id");
        domainId = RepresentationContracts.text(domainId, "domainId");
        pattern = Objects.requireNonNull(pattern, "pattern");
        requiredAssumptions = RepresentationContracts.sortedUnique(
            requiredAssumptions, "requiredAssumptions");
        consequenceIds = RepresentationContracts.sortedUnique(
            consequenceIds, "consequenceIds");
        provenance = RepresentationContracts.text(provenance, "provenance");
    }

    String canonicalDescriptor() {
        return String.join("\u0000",
            id,
            domainId,
            pattern.toString(),
            String.join("\u0001", requiredAssumptions),
            String.join("\u0001", consequenceIds),
            provenance
        );
    }
}
