package de.regelsuche.discovery.representation;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/** One exact occurrence-local match against a known-structure catalog entry. */
public record KnownStructureMatch(
    String structureId,
    String domainId,
    ExpressionOccurrencePath occurrencePath,
    Map<String, String> bindings,
    List<String> requiredAssumptions,
    List<String> consequenceIds,
    String provenance
) {
    public KnownStructureMatch {
        structureId = RepresentationCandidateAssessment.requireText(
            structureId, "structureId");
        domainId = RepresentationCandidateAssessment.requireText(
            domainId, "domainId");
        occurrencePath = Objects.requireNonNull(occurrencePath, "occurrencePath");
        bindings = Collections.unmodifiableMap(new LinkedHashMap<>(
            new TreeMap<>(Objects.requireNonNull(bindings, "bindings"))));
        requiredAssumptions = RepresentationCandidateAssessment.sortedUnique(
            requiredAssumptions, "requiredAssumptions");
        consequenceIds = RepresentationCandidateAssessment.sortedUnique(
            consequenceIds, "consequenceIds");
        provenance = RepresentationCandidateAssessment.requireText(
            provenance, "provenance");
    }

    public boolean wholeExpression() {
        return occurrencePath.isRoot();
    }

    public String identity() {
        return structureId + "\u0000" + occurrencePath.canonical()
            + "\u0000" + bindings;
    }
}
