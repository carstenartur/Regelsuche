package de.regelsuche.discovery.representation;

import java.util.Comparator;
import java.util.Objects;

/** One newly available consequence at one exact known-structure occurrence. */
public record KnownStructureConsequenceUnlock(
    String consequenceId,
    String structureId,
    ExpressionOccurrencePath occurrencePath,
    String matchIdentity
) implements Comparable<KnownStructureConsequenceUnlock> {
    private static final Comparator<KnownStructureConsequenceUnlock> ORDER =
        Comparator.comparing(KnownStructureConsequenceUnlock::consequenceId)
            .thenComparing(KnownStructureConsequenceUnlock::structureId)
            .thenComparing(KnownStructureConsequenceUnlock::occurrencePath)
            .thenComparing(KnownStructureConsequenceUnlock::matchIdentity);

    public KnownStructureConsequenceUnlock {
        consequenceId = RepresentationContracts.text(
            consequenceId, "consequenceId");
        structureId = RepresentationContracts.text(structureId, "structureId");
        occurrencePath = Objects.requireNonNull(occurrencePath, "occurrencePath");
        matchIdentity = RepresentationContracts.text(matchIdentity, "matchIdentity");
    }

    public String opportunityIdentity() {
        return consequenceId + "|" + matchIdentity;
    }

    @Override
    public int compareTo(KnownStructureConsequenceUnlock other) {
        return ORDER.compare(this, Objects.requireNonNull(other, "other"));
    }
}
