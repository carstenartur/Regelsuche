package de.regelsuche.discovery.representation;

import de.regelsuche.knowledge.KnownStructureMetadata;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/** One occurrence-local match against a known-structure catalog entry. */
public record KnownStructureMatch(
    String structureId,
    String domainId,
    ExpressionOccurrencePath occurrencePath,
    Map<String, String> bindings,
    List<String> requiredAssumptions,
    List<String> consequenceIds,
    String provenance,
    KnownStructureMetadata metadata,
    String recognitionMode,
    String matchedRepresentative,
    int representativeIndex
) {
    public static final String RECOGNITION_EXACT = "EXACT";
    public static final String RECOGNITION_EQUIVALENCE_AWARE =
        "EQUIVALENCE_AWARE";
    public static final String RECOGNITION_BOUNDED_REPRESENTATIVE =
        "BOUNDED_REPRESENTATIVE";

    private static final Set<String> RECOGNITION_MODES = Set.of(
        RECOGNITION_EXACT,
        RECOGNITION_EQUIVALENCE_AWARE,
        RECOGNITION_BOUNDED_REPRESENTATIVE
    );

    public KnownStructureMatch {
        structureId = RepresentationCandidateAssessment.requireText(
            structureId, "structureId");
        domainId = RepresentationCandidateAssessment.requireText(
            domainId, "domainId");
        occurrencePath = Objects.requireNonNull(occurrencePath, "occurrencePath");
        bindings = normalizedBindings(bindings);
        requiredAssumptions = RepresentationCandidateAssessment.sortedUnique(
            requiredAssumptions, "requiredAssumptions");
        consequenceIds = RepresentationCandidateAssessment.sortedUnique(
            consequenceIds, "consequenceIds");
        provenance = RepresentationCandidateAssessment.requireText(
            provenance, "provenance");
        metadata = Objects.requireNonNull(metadata, "metadata");
        recognitionMode = RepresentationCandidateAssessment.requireText(
            recognitionMode, "recognitionMode");
        if (!RECOGNITION_MODES.contains(recognitionMode)) {
            throw new IllegalArgumentException(
                "unsupported recognitionMode: " + recognitionMode);
        }
        matchedRepresentative = RepresentationCandidateAssessment.requireText(
            matchedRepresentative, "matchedRepresentative");
        if (representativeIndex < 0) {
            throw new IllegalArgumentException(
                "representativeIndex must not be negative");
        }
    }

    public boolean wholeExpression() {
        return occurrencePath.isRoot();
    }

    public String identity() {
        StringBuilder descriptor = new StringBuilder();
        KnownStructureCatalog.appendCanonicalField(descriptor, structureId);
        KnownStructureCatalog.appendCanonicalField(descriptor, domainId);
        KnownStructureCatalog.appendCanonicalField(
            descriptor, occurrencePath.canonical());
        KnownStructureCatalog.appendCanonicalField(
            descriptor, Integer.toString(bindings.size()));
        bindings.forEach((name, expression) -> {
            KnownStructureCatalog.appendCanonicalField(descriptor, name);
            KnownStructureCatalog.appendCanonicalField(descriptor, expression);
        });
        return KnownStructureCatalog.sha256(descriptor.toString());
    }

    private static Map<String, String> normalizedBindings(
        Map<String, String> values
    ) {
        TreeMap<String, String> sorted = new TreeMap<>();
        for (Map.Entry<String, String> entry
                : Objects.requireNonNull(values, "bindings").entrySet()) {
            String name = RepresentationCandidateAssessment.requireText(
                entry.getKey(), "binding name");
            String expression = RepresentationCandidateAssessment.requireText(
                entry.getValue(), "binding expression");
            if (sorted.put(name, expression) != null) {
                throw new IllegalArgumentException(
                    "duplicate normalized binding name: " + name);
            }
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(sorted));
    }
}
