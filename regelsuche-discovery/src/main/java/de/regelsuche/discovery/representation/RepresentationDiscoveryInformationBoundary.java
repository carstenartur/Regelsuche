package de.regelsuche.discovery.representation;

import de.regelsuche.knowledge.KnowledgePackRegistry;
import de.regelsuche.knowledge.KnowledgePackSelection;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * Content-addressed information boundary for the four representation-discovery
 * tracks defined by issue #663.
 *
 * <p>The post-freeze catalog is deliberately not exposed directly. Callers must
 * first freeze the complete candidate set and present the resulting receipt.
 * Hidden-structure runs additionally disable every rule pack declared by the
 * held-out structure so a direct executable consequence cannot leak into
 * candidate formation.</p>
 */
public final class RepresentationDiscoveryInformationBoundary {
    private static final String REVISION =
        "regelsuche.representation-discovery-information-boundary/v1";
    private static final String CANDIDATE_FREEZE_REVISION =
        "regelsuche.representation-candidate-freeze/v1";
    private static final String POST_FREEZE_REVISION =
        "regelsuche.representation-post-freeze-disclosure/v1";

    private final Track track;
    private final KnowledgePackSelection visibleSelection;
    private final KnowledgePackSelection formationSelection;
    private final KnownStructureCatalog formationCatalog;
    private final KnownStructureCatalog postFreezeCatalog;
    private final Set<String> requestedHoldoutStructureIds;
    private final Set<String> formationExcludedStructureIds;
    private final Set<String> withheldRulePackIds;
    private final String holdoutCommitmentHash;
    private final String contentHash;

    private RepresentationDiscoveryInformationBoundary(
        Track track,
        KnowledgePackSelection visibleSelection,
        KnowledgePackSelection formationSelection,
        KnownStructureCatalog formationCatalog,
        KnownStructureCatalog postFreezeCatalog,
        Set<String> requestedHoldoutStructureIds,
        Set<String> formationExcludedStructureIds,
        Set<String> withheldRulePackIds
    ) {
        this.track = Objects.requireNonNull(track, "track");
        this.visibleSelection = Objects.requireNonNull(
            visibleSelection, "visibleSelection");
        this.formationSelection = Objects.requireNonNull(
            formationSelection, "formationSelection");
        this.formationCatalog = Objects.requireNonNull(
            formationCatalog, "formationCatalog");
        this.postFreezeCatalog = Objects.requireNonNull(
            postFreezeCatalog, "postFreezeCatalog");
        this.requestedHoldoutStructureIds = immutableSortedSet(
            requestedHoldoutStructureIds, "requestedHoldoutStructureIds");
        this.formationExcludedStructureIds = immutableSortedSet(
            formationExcludedStructureIds, "formationExcludedStructureIds");
        this.withheldRulePackIds = immutableSortedSet(
            withheldRulePackIds, "withheldRulePackIds");
        this.holdoutCommitmentHash = holdoutCommitment();
        this.contentHash = boundaryHash();
    }

    public static RepresentationDiscoveryInformationBoundary fromKnowledgePacks(
        Track track,
        KnowledgePackSelection visibleSelection
    ) {
        return fromKnowledgePacks(
            new KnowledgePackRegistry(),
            track,
            visibleSelection,
            Set.of()
        );
    }

    public static RepresentationDiscoveryInformationBoundary fromKnowledgePacks(
        Track track,
        KnowledgePackSelection visibleSelection,
        Set<String> hiddenStructureIds
    ) {
        return fromKnowledgePacks(
            new KnowledgePackRegistry(),
            track,
            visibleSelection,
            hiddenStructureIds
        );
    }

    public static RepresentationDiscoveryInformationBoundary fromKnowledgePacks(
        KnowledgePackRegistry registry,
        Track track,
        KnowledgePackSelection visibleSelection,
        Set<String> hiddenStructureIds
    ) {
        Objects.requireNonNull(registry, "registry");
        Objects.requireNonNull(track, "track");
        Objects.requireNonNull(visibleSelection, "visibleSelection");
        Set<String> requestedHoldouts = immutableSortedSet(
            hiddenStructureIds, "hiddenStructureIds");
        validateRequestedHoldouts(track, requestedHoldouts);

        KnownStructureCatalog visibleCatalog =
            KnownStructureCatalog.fromKnowledgePacks(
                registry, visibleSelection);
        List<KnownStructure> holdoutStructures = holdoutStructures(
            visibleCatalog, requestedHoldouts);
        Set<String> withheldPacks = governingRulePacks(holdoutStructures);

        KnowledgePackSelection formationSelection = visibleSelection;
        for (String packId : withheldPacks) {
            formationSelection = formationSelection.disablePack(packId);
        }

        KnownStructureCatalog formationCatalog = switch (track) {
            case R1_TARGET_FREE_COMPRESSION,
                 R2_CATALOG_BLIND_POST_HOC_BRIDGE ->
                KnownStructureCatalog.empty();
            case R3_CATALOG_VISIBLE_KNOWLEDGE_NAVIGATION ->
                visibleCatalog;
            case R4_HIDDEN_STRUCTURE_REDISCOVERY ->
                KnownStructureCatalog.fromKnowledgePacks(
                    registry, formationSelection);
        };
        KnownStructureCatalog postFreezeCatalog = switch (track) {
            case R1_TARGET_FREE_COMPRESSION ->
                KnownStructureCatalog.empty();
            case R2_CATALOG_BLIND_POST_HOC_BRIDGE,
                 R3_CATALOG_VISIBLE_KNOWLEDGE_NAVIGATION,
                 R4_HIDDEN_STRUCTURE_REDISCOVERY ->
                visibleCatalog;
        };

        Set<String> excluded = difference(
            structureIds(postFreezeCatalog),
            structureIds(formationCatalog)
        );
        if (track == Track.R4_HIDDEN_STRUCTURE_REDISCOVERY
                && !excluded.containsAll(requestedHoldouts)) {
            Set<String> leaked = new TreeSet<>(requestedHoldouts);
            leaked.removeAll(excluded);
            throw new IllegalArgumentException(
                "Hidden structures remain visible during formation: " + leaked);
        }

        return new RepresentationDiscoveryInformationBoundary(
            track,
            visibleSelection,
            formationSelection,
            formationCatalog,
            postFreezeCatalog,
            requestedHoldouts,
            excluded,
            withheldPacks
        );
    }

    public String revision() {
        return REVISION;
    }

    public Track track() {
        return track;
    }

    /**
     * Rule inventory that candidate formation may use.
     *
     * <p>For R4 this selection has every governing pack of a hidden structure
     * disabled, thereby withholding its direct executable consequences.</p>
     */
    public KnowledgePackSelection formationSelection() {
        return formationSelection;
    }

    /** Opaque commitment to the full post-freeze pack selection. */
    public String postFreezeSelectionCommitment() {
        return KnownStructureCatalog.sha256(
            selectionDescriptor(visibleSelection));
    }

    /** Catalog candidate formation is permitted to inspect. */
    public KnownStructureCatalog candidateFormationCatalog() {
        return formationCatalog;
    }

    /** Content commitment for the catalog that may be disclosed after freeze. */
    public String postFreezeCatalogCommitment() {
        return postFreezeCatalog.contentHash();
    }

    /** Opaque pre-freeze commitment to all holdout and exclusion details. */
    public String holdoutCommitmentHash() {
        return holdoutCommitmentHash;
    }

    /** Identity of track, inventories, catalogs and holdout commitment. */
    public String contentHash() {
        return contentHash;
    }

    /**
     * Freezes candidate content without consulting the post-freeze catalog.
     */
    public CandidateFreezeReceipt freezeCandidates(
        Collection<RepresentationCandidateProposal> candidates
    ) {
        Objects.requireNonNull(candidates, "candidates");
        List<String> descriptors = new ArrayList<>();
        for (RepresentationCandidateProposal candidate : candidates) {
            descriptors.add(candidateDescriptor(
                Objects.requireNonNull(candidate, "candidate")));
        }
        Collections.sort(descriptors);
        StringBuilder candidateSet = new StringBuilder();
        KnownStructureCatalog.appendCanonicalField(
            candidateSet, CANDIDATE_FREEZE_REVISION);
        KnownStructureCatalog.appendCanonicalField(
            candidateSet, Integer.toString(descriptors.size()));
        descriptors.forEach(descriptor ->
            KnownStructureCatalog.appendCanonicalField(
                candidateSet, descriptor));
        return new CandidateFreezeReceipt(
            contentHash,
            KnownStructureCatalog.sha256(candidateSet.toString()),
            descriptors.size()
        );
    }

    /**
     * Reveals the classification catalog and holdout details only for a receipt
     * produced under this exact information boundary.
     */
    public PostFreezeDisclosure disclosePostFreeze(
        CandidateFreezeReceipt receipt
    ) {
        Objects.requireNonNull(receipt, "receipt");
        if (!contentHash.equals(receipt.boundaryHash())) {
            throw new IllegalArgumentException(
                "Candidate freeze receipt belongs to a different "
                    + "information boundary");
        }
        return new PostFreezeDisclosure(
            track,
            contentHash,
            receipt.contentHash(),
            visibleSelection,
            postFreezeCatalog,
            requestedHoldoutStructureIds,
            formationExcludedStructureIds,
            withheldRulePackIds,
            holdoutCommitmentHash
        );
    }

    public enum Track {
        R1_TARGET_FREE_COMPRESSION("R1"),
        R2_CATALOG_BLIND_POST_HOC_BRIDGE("R2"),
        R3_CATALOG_VISIBLE_KNOWLEDGE_NAVIGATION("R3"),
        R4_HIDDEN_STRUCTURE_REDISCOVERY("R4");

        private final String id;

        Track(String id) {
            this.id = id;
        }

        public String id() {
            return id;
        }
    }

    public record CandidateFreezeReceipt(
        String boundaryHash,
        String candidateSetHash,
        int candidateCount
    ) {
        public CandidateFreezeReceipt {
            boundaryHash = requireSha256(boundaryHash, "boundaryHash");
            candidateSetHash = requireSha256(
                candidateSetHash, "candidateSetHash");
            if (candidateCount < 0) {
                throw new IllegalArgumentException(
                    "candidateCount must be non-negative");
            }
        }

        public String contentHash() {
            StringBuilder descriptor = new StringBuilder();
            KnownStructureCatalog.appendCanonicalField(
                descriptor, CANDIDATE_FREEZE_REVISION);
            KnownStructureCatalog.appendCanonicalField(
                descriptor, boundaryHash);
            KnownStructureCatalog.appendCanonicalField(
                descriptor, candidateSetHash);
            KnownStructureCatalog.appendCanonicalField(
                descriptor, Integer.toString(candidateCount));
            return KnownStructureCatalog.sha256(descriptor.toString());
        }
    }

    public record PostFreezeDisclosure(
        Track track,
        String boundaryHash,
        String freezeReceiptHash,
        KnowledgePackSelection classificationSelection,
        KnownStructureCatalog classificationCatalog,
        Set<String> requestedHoldoutStructureIds,
        Set<String> formationExcludedStructureIds,
        Set<String> withheldRulePackIds,
        String holdoutCommitmentHash
    ) {
        public PostFreezeDisclosure {
            track = Objects.requireNonNull(track, "track");
            boundaryHash = requireSha256(boundaryHash, "boundaryHash");
            freezeReceiptHash = requireSha256(
                freezeReceiptHash, "freezeReceiptHash");
            classificationSelection = Objects.requireNonNull(
                classificationSelection, "classificationSelection");
            classificationCatalog = Objects.requireNonNull(
                classificationCatalog, "classificationCatalog");
            requestedHoldoutStructureIds = immutableSortedSet(
                requestedHoldoutStructureIds,
                "requestedHoldoutStructureIds"
            );
            formationExcludedStructureIds = immutableSortedSet(
                formationExcludedStructureIds,
                "formationExcludedStructureIds"
            );
            withheldRulePackIds = immutableSortedSet(
                withheldRulePackIds,
                "withheldRulePackIds"
            );
            holdoutCommitmentHash = requireSha256(
                holdoutCommitmentHash, "holdoutCommitmentHash");
        }

        public String contentHash() {
            StringBuilder descriptor = new StringBuilder();
            KnownStructureCatalog.appendCanonicalField(
                descriptor, POST_FREEZE_REVISION);
            KnownStructureCatalog.appendCanonicalField(
                descriptor, track.id());
            KnownStructureCatalog.appendCanonicalField(
                descriptor, boundaryHash);
            KnownStructureCatalog.appendCanonicalField(
                descriptor, freezeReceiptHash);
            KnownStructureCatalog.appendCanonicalField(
                descriptor, selectionDescriptor(classificationSelection));
            KnownStructureCatalog.appendCanonicalField(
                descriptor, classificationCatalog.contentHash());
            appendSet(descriptor, requestedHoldoutStructureIds);
            appendSet(descriptor, formationExcludedStructureIds);
            appendSet(descriptor, withheldRulePackIds);
            KnownStructureCatalog.appendCanonicalField(
                descriptor, holdoutCommitmentHash);
            return KnownStructureCatalog.sha256(descriptor.toString());
        }
    }

    private String holdoutCommitment() {
        StringBuilder descriptor = new StringBuilder();
        KnownStructureCatalog.appendCanonicalField(
            descriptor, REVISION + "/holdout");
        KnownStructureCatalog.appendCanonicalField(
            descriptor, track.id());
        KnownStructureCatalog.appendCanonicalField(
            descriptor, postFreezeCatalog.contentHash());
        appendSet(descriptor, requestedHoldoutStructureIds);
        appendSet(descriptor, formationExcludedStructureIds);
        appendSet(descriptor, withheldRulePackIds);
        return KnownStructureCatalog.sha256(descriptor.toString());
    }

    private String boundaryHash() {
        StringBuilder descriptor = new StringBuilder();
        KnownStructureCatalog.appendCanonicalField(
            descriptor, REVISION);
        KnownStructureCatalog.appendCanonicalField(
            descriptor, track.id());
        KnownStructureCatalog.appendCanonicalField(
            descriptor, selectionDescriptor(visibleSelection));
        KnownStructureCatalog.appendCanonicalField(
            descriptor, selectionDescriptor(formationSelection));
        KnownStructureCatalog.appendCanonicalField(
            descriptor, formationCatalog.contentHash());
        KnownStructureCatalog.appendCanonicalField(
            descriptor, postFreezeCatalog.contentHash());
        KnownStructureCatalog.appendCanonicalField(
            descriptor, holdoutCommitmentHash);
        return KnownStructureCatalog.sha256(descriptor.toString());
    }

    private static void validateRequestedHoldouts(
        Track track,
        Set<String> hiddenStructureIds
    ) {
        if (track == Track.R4_HIDDEN_STRUCTURE_REDISCOVERY
                && hiddenStructureIds.isEmpty()) {
            throw new IllegalArgumentException(
                "R4 requires at least one hidden structure");
        }
        if (track != Track.R4_HIDDEN_STRUCTURE_REDISCOVERY
                && !hiddenStructureIds.isEmpty()) {
            throw new IllegalArgumentException(
                "Hidden structures are permitted only for R4");
        }
    }

    private static List<KnownStructure> holdoutStructures(
        KnownStructureCatalog catalog,
        Set<String> hiddenStructureIds
    ) {
        if (hiddenStructureIds.isEmpty()) {
            return List.of();
        }
        List<KnownStructure> structures = catalog.structures().stream()
            .filter(structure -> hiddenStructureIds.contains(structure.id()))
            .toList();
        Set<String> found = structureIds(structures);
        if (!found.containsAll(hiddenStructureIds)) {
            Set<String> missing = new TreeSet<>(hiddenStructureIds);
            missing.removeAll(found);
            throw new IllegalArgumentException(
                "Unknown or disabled hidden structures: " + missing);
        }
        return structures;
    }

    private static Set<String> governingRulePacks(
        List<KnownStructure> holdoutStructures
    ) {
        TreeSet<String> packIds = new TreeSet<>();
        for (KnownStructure structure : holdoutStructures) {
            boolean hasDirectRule = structure.consequenceIds().stream()
                .anyMatch(consequence -> consequence.startsWith("rule:"));
            List<String> declaredPacks = structure.metadata()
                .enabledRulePackIds();
            if (hasDirectRule && declaredPacks.isEmpty()) {
                throw new IllegalArgumentException(
                    "Hidden structure " + structure.id()
                        + " has a direct rule consequence but no governing "
                        + "rule pack");
            }
            packIds.addAll(declaredPacks);
        }
        return immutableSortedSet(packIds, "withheldRulePackIds");
    }

    private static String candidateDescriptor(
        RepresentationCandidateProposal candidate
    ) {
        StringBuilder descriptor = new StringBuilder();
        KnownStructureCatalog.appendCanonicalField(
            descriptor, candidate.sourceExpression());
        KnownStructureCatalog.appendCanonicalField(
            descriptor, candidate.candidateExpression());
        KnownStructureCatalog.appendCanonicalField(
            descriptor, candidate.occurrencePath().canonical());
        KnownStructureCatalog.appendCanonicalList(
            descriptor, candidate.assumptions());
        KnownStructureCatalog.appendCanonicalField(
            descriptor, candidate.validationStatus().name());
        return descriptor.toString();
    }

    private static String selectionDescriptor(
        KnowledgePackSelection selection
    ) {
        StringBuilder descriptor = new StringBuilder();
        KnownStructureCatalog.appendCanonicalField(
            descriptor, selection.profile().id());
        appendSet(descriptor, selection.enabledPacks());
        appendSet(descriptor, selection.disabledPacks());
        return descriptor.toString();
    }

    private static Set<String> structureIds(
        KnownStructureCatalog catalog
    ) {
        return structureIds(catalog.structures());
    }

    private static Set<String> structureIds(
        Collection<KnownStructure> structures
    ) {
        TreeSet<String> ids = new TreeSet<>();
        structures.forEach(structure -> ids.add(structure.id()));
        return immutableSortedSet(ids, "structureIds");
    }

    private static Set<String> difference(
        Set<String> left,
        Set<String> right
    ) {
        TreeSet<String> values = new TreeSet<>(left);
        values.removeAll(right);
        return immutableSortedSet(values, "difference");
    }

    private static Set<String> immutableSortedSet(
        Collection<String> values,
        String field
    ) {
        Objects.requireNonNull(values, field);
        TreeSet<String> sorted = new TreeSet<>();
        for (String value : values) {
            String normalized = Objects.requireNonNull(value, field).trim();
            if (normalized.isEmpty()) {
                throw new IllegalArgumentException(
                    field + " must not contain blank entries");
            }
            sorted.add(normalized);
        }
        return Collections.unmodifiableSet(
            new LinkedHashSet<>(sorted));
    }

    private static void appendSet(
        StringBuilder descriptor,
        Collection<String> values
    ) {
        KnownStructureCatalog.appendCanonicalList(
            descriptor, values.stream().sorted().toList());
    }

    private static String requireSha256(
        String value,
        String field
    ) {
        String normalized = Objects.requireNonNull(value, field).trim();
        if (!normalized.matches("sha256:[0-9a-f]{64}")) {
            throw new IllegalArgumentException(
                field + " must be a lowercase SHA-256 identity");
        }
        return normalized;
    }
}
