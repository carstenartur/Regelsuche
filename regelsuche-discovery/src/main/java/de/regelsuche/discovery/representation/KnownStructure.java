package de.regelsuche.discovery.representation;

import de.regelsuche.transform.PatternExpr;
import de.regelsuche.transform.RecognitionProfile;
import java.util.List;
import java.util.Objects;

/** One provenance-bearing known mathematical structure and its recognition policy. */
public record KnownStructure(
    String id,
    String domainId,
    PatternExpr pattern,
    RecognitionProfile recognitionProfile,
    List<String> requiredAssumptions,
    List<String> consequenceIds,
    String provenance
) {
    public KnownStructure(
        String id,
        String domainId,
        PatternExpr pattern,
        List<String> requiredAssumptions,
        List<String> consequenceIds,
        String provenance
    ) {
        this(
            id,
            domainId,
            pattern,
            RecognitionProfile.exact(),
            requiredAssumptions,
            consequenceIds,
            provenance
        );
    }

    public KnownStructure {
        id = RepresentationCandidateAssessment.requireText(id, "id");
        domainId = RepresentationCandidateAssessment.requireText(
            domainId, "domainId");
        pattern = Objects.requireNonNull(pattern, "pattern");
        recognitionProfile = recognitionProfile == null
            ? RecognitionProfile.exact()
            : recognitionProfile;
        requiredAssumptions = RepresentationCandidateAssessment.sortedUnique(
            requiredAssumptions, "requiredAssumptions");
        consequenceIds = RepresentationCandidateAssessment.sortedUnique(
            consequenceIds, "consequenceIds");
        provenance = RepresentationCandidateAssessment.requireText(
            provenance, "provenance");
    }

    String canonicalDescriptor() {
        StringBuilder descriptor = new StringBuilder();
        KnownStructureCatalog.appendCanonicalField(descriptor, id);
        KnownStructureCatalog.appendCanonicalField(descriptor, domainId);
        KnownStructureCatalog.appendCanonicalField(descriptor, pattern.toString());
        KnownStructureCatalog.appendCanonicalList(
            descriptor,
            recognitionProfile.associativeOperators().stream()
                .map(operator -> operator.name())
                .sorted()
                .toList()
        );
        KnownStructureCatalog.appendCanonicalList(
            descriptor,
            recognitionProfile.commutativeOperators().stream()
                .map(operator -> operator.name())
                .sorted()
                .toList()
        );
        KnownStructureCatalog.appendCanonicalField(
            descriptor,
            Boolean.toString(recognitionProfile.inferAlgebraicBindings())
        );
        KnownStructureCatalog.appendCanonicalList(
            descriptor,
            recognitionProfile.recognitionRuleIds().stream().sorted().toList()
        );
        KnownStructureCatalog.appendCanonicalField(
            descriptor,
            Integer.toString(recognitionProfile.maxEquivalenceDepth())
        );
        KnownStructureCatalog.appendCanonicalList(descriptor, requiredAssumptions);
        KnownStructureCatalog.appendCanonicalList(descriptor, consequenceIds);
        KnownStructureCatalog.appendCanonicalField(descriptor, provenance);
        return descriptor.toString();
    }
}
