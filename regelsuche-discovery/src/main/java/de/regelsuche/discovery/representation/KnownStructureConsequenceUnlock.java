package de.regelsuche.discovery.representation;

import java.util.Objects;

/** One newly available consequence at one exact known-structure occurrence. */
public record KnownStructureConsequenceUnlock(
    String consequenceId,
    String structureId,
    ExpressionOccurrencePath occurrencePath,
    String matchIdentity
) implements Comparable<KnownStructureConsequenceUnlock> {
    public KnownStructureConsequenceUnlock {
        consequenceId = requireText(consequenceId, "consequenceId");
        structureId = requireText(structureId, "structureId");
        occurrencePath = Objects.requireNonNull(occurrencePath, "occurrencePath");
        matchIdentity = requireText(matchIdentity, "matchIdentity");
    }

    public String opportunityIdentity() {
        return consequenceId + "|" + matchIdentity;
    }

    @Override
    public int compareTo(KnownStructureConsequenceUnlock other) {
        Objects.requireNonNull(other, "other");
        int consequence = consequenceId.compareTo(other.consequenceId);
        if (consequence != 0) {
            return consequence;
        }
        int structure = structureId.compareTo(other.structureId);
        if (structure != 0) {
            return structure;
        }
        int occurrence = occurrencePath.compareTo(other.occurrencePath);
        if (occurrence != 0) {
            return occurrence;
        }
        return matchIdentity.compareTo(other.matchIdentity);
    }

    private static String requireText(String value, String field) {
        Objects.requireNonNull(value, field);
        String normalized = value.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return normalized;
    }
}
