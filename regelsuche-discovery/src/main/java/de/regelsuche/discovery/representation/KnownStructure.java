package de.regelsuche.discovery.representation;

import de.regelsuche.transform.ExprMatcher;
import de.regelsuche.transform.PatternExpr;
import de.regelsuche.transform.RecognitionProfile;
import java.util.List;
import java.util.Objects;

/** One provenance-bearing known mathematical structure and its matcher. */
public record KnownStructure(
    String id,
    String domainId,
    ExprMatcher matcher,
    List<String> requiredAssumptions,
    List<String> consequenceIds,
    String provenance
) {
    /** Compatibility constructor for an exact instantiable pattern. */
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
            ExprMatcher.pattern(pattern),
            requiredAssumptions,
            consequenceIds,
            provenance
        );
    }

    /** Compatibility constructor for a pattern plus recognition policy. */
    public KnownStructure(
        String id,
        String domainId,
        PatternExpr pattern,
        RecognitionProfile recognitionProfile,
        List<String> requiredAssumptions,
        List<String> consequenceIds,
        String provenance
    ) {
        this(
            id,
            domainId,
            ExprMatcher.pattern(pattern, recognitionProfile),
            requiredAssumptions,
            consequenceIds,
            provenance
        );
    }

    public KnownStructure {
        id = RepresentationCandidateAssessment.requireText(id, "id");
        domainId = RepresentationCandidateAssessment.requireText(
            domainId, "domainId");
        matcher = Objects.requireNonNull(matcher, "matcher");
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
        KnownStructureCatalog.appendCanonicalField(
            descriptor,
            matcher.canonicalDescriptor()
        );
        KnownStructureCatalog.appendCanonicalList(
            descriptor,
            requiredAssumptions
        );
        KnownStructureCatalog.appendCanonicalList(descriptor, consequenceIds);
        KnownStructureCatalog.appendCanonicalField(descriptor, provenance);
        return descriptor.toString();
    }
}
