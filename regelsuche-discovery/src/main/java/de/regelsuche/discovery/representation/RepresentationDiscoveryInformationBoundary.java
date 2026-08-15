package de.regelsuche.discovery.representation;

import de.regelsuche.knowledge.KnowledgePackRegistry;
import de.regelsuche.knowledge.KnowledgePackSelection;
import de.regelsuche.knowledge.RuleInventoryFingerprint;
import de.regelsuche.transform.AstRewriteTransformationEngine;
import de.regelsuche.transform.RewriteRule;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
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
 * Catalog-blind and hidden-structure runs additionally withhold every rule pack
 * declared as a consequence of knowledge that formation is not allowed to see.</p>
 */
public final class RepresentationDiscoveryInformationBoundary {
    private static final String REVISION =
        "regelsuche.representation-discovery-information-boundary/v3";
    private static final String CANDIDATE_FREEZE_REVISION =
        "regelsuche.representation-candidate-freeze/v1";
    private static final String POST_FREEZE_REVISION =
        "regelsuche.representation-post-freeze-disclosure/v3";

    private final Track track;
    private final KnowledgePackSelection visibleSelection;
    private final KnowledgePackSelection formationSelection;
    private final KnownStructureCatalog formationCatalog;
    private final KnownStructureCatalog postFreezeCatalog;
    private final List<RewriteRule> formationRules;
    private final String formationRuleInventoryHash;
    private final String postFreezeRuleInventoryHash;
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
        List<RewriteRule> formationRules,
        String postFreezeRuleInventoryHash,
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
        Objects.requireNonNull(formationRules, "formationRules");
        this.formationRules = formationRules.stream()
            .map(rule -> Objects.requireNonNull(rule, "formationRule"))
            .sorted(Comparator.comparing(RewriteRule::id)
                .thenComparing(rule -> rule.descriptor().packId())
                .thenComparing(RuleInventoryFingerprint::ruleContentHash))
            .toList();
        this.formationRuleInventoryHash = RuleInventoryFingerprint.contentHash(
            this.formationRules);
        this.postFreezeRuleInventoryHash = requireSha256(
            postFreezeRuleInventoryHash, "postFreezeRuleInventoryHash");
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
        Set<String> withheldPacks = switch (track) {
            case R2_CATALOG_BLIND_POST_HOC_BRIDGE ->
                governingRulePacks(visibleCatalog.structures());
            case R4_HIDDEN_STRUCTURE_REDISCOVERY ->
                governingRulePacks(holdoutStructures);
            case R1_TARGET_FREE_COMPRESSION,
                 R3_CATALOG_VISIBLE_KNOWLEDGE_NAVIGATION -> Set.of();
        };

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

        List<RewriteRule> formationRules = astRuleInventory(
            registry, formationSelection);
        List<RewriteRule> postFreezeRules = astRuleInventory(
            registry, visibleSelection);
        return new RepresentationDiscoveryInformationBoundary(
            track,
            visibleSelection,
            formationSelection,
            formationCatalog,
            postFreezeCatalog,
            formationRules,
            RuleInventoryFingerprint.contentHash(postFreezeRules),
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

    /** Opaque identity of the rule-pack selection used for formation. */
    public String candidateFormationSelectionCommitment() {
        return KnownStructureCatalog.sha256(
            selectionDescriptor(formationSelection));
    }

    /**
     * Exact AST rewrite rules visible to candidate formation.
     *
     * <p>The inventory combines enabled built-in core rules and enabled
     * Knowledge-Pack rules in canonical order. It does not disclose which
     * post-freeze packs were withheld; R2 and R4 holdout details are released
     * only after freeze.</p>
     */
    public List<RewriteRule> candidateFormationRules() {
        return formationRules;
    }

    /** Opaque commitment to the complete post-freeze pack selection. */
    public String postFreezeSelectionCommitment() {
        return KnownStructureCatalog.sha256(
            selectionDescriptor(visibleSelection));
    }

    /** Catalog candidate formation is permitted to inspect. */
    public KnownStructureCatalog candidateFormationCatalog() {
        return formationCatalog;
    }

    /** Exact AST rewrite inventory available during candidate formation. */
    public String candidateFormationRuleInventoryHash() {
        return formationRuleInventoryHash;
    }

    /** Content commitment for the catalog disclosed after candidate freeze. */
    public String postFreezeCatalogCommitment() {
        return postFreezeCatalog.contentHash();
    }

    /** Exact AST rewrite inventory associated with post-freeze knowledge. */
    public String postFreezeRuleInventoryCommitment() {
        return postFreezeRuleInventoryHash;
    }

    /** Opaque pre-freeze commitment to all holdout and exclusion details. */
    public String holdoutCommitmentHash() {
        return holdoutCommitmentHash;
    }

    /** Identity of track, actual inventories, catalogs and holdout commitment. */
    public String contentHash() {
        return contentHash;
    }

    /** Freezes candidate content without consulting post-freeze knowledge. */
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
     * Reveals classification knowledge and holdout details only for a receipt
     * issued under this exact information boundary.
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
            formationRuleInventoryHash,
            postFreezeRuleInventoryHash,
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

    /** Opaque receipt issued only by {@link #freezeCandidates(Collection)}. */
    public static final class CandidateFreezeReceipt {
        private final String boundaryHash;
        private final String candidateSetHash;
        private final int candidateCount;

        private CandidateFreezeReceipt(
            String boundaryHash,
            String candidateSetHash,
            int candidateCount
        ) {
            this.boundaryHash = requireSha256(
                boundaryHash, "boundaryHash");
            this.candidateSetHash = requireSha256(
                candidateSetHash, "candidateSetHash");
            if (candidateCount < 0) {
                throw new IllegalArgumentException(
                    "candidateCount must be non-negative");
            }
            this.candidateCount = candidateCount;
        }

        public String boundaryHash() {
            return boundaryHash;
        }

        public String candidateSetHash() {
            return candidateSetHash;
        }

        public int candidateCount() {
            return candidateCount;
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
        String formationRuleInventoryHash,
        String classificationRuleInventoryHash,
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
            formationRuleInventoryHash = requireSha256(
                formationRuleInventoryHash, "formationRuleInventoryHash");
            classificationRuleInventoryHash = requireSha256(
                classificationRuleInventoryHash,
                "classificationRuleInventoryHash"
            );
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
            KnownStructureCatalog.appendCanonicalField(
                descriptor, formationRuleInventoryHash);
            KnownStructureCatalog.appendCanonicalField(
                descriptor, classificationRuleInventoryHash);
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
            descriptor, formationCatalog.contentHash());
        KnownStructureCatalog.appendCanonicalField(
            descriptor, postFreezeCatalog.contentHash());
        KnownStructureCatalog.appendCanonicalField(
            descriptor, formationRuleInventoryHash);
        KnownStructureCatalog.appendCanonicalField(
            descriptor, postFreezeRuleInventoryHash);
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
            descriptor, formationRuleInventoryHash);
        KnownStructureCatalog.appendCanonicalField(
            descriptor, postFreezeRuleInventoryHash);
        KnownStructureCatalog.appendCanonicalField(
            descriptor, holdoutCommitmentHash);
        return KnownStructureCatalog.sha256(descriptor.toString());
    }

    private static List<RewriteRule> astRuleInventory(
        KnowledgePackRegistry registry,
        KnowledgePackSelection selection
    ) {
        List<RewriteRule> rules = new ArrayList<>(
            AstRewriteTransformationEngine.defaultRules(selection));
        rules.addAll(registry.enabledRules(selection));
        return List.copyOf(rules);
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
        Collection<KnownStructure> structures
    ) {
        TreeSet<String> packIds = new TreeSet<>();
        for (KnownStructure structure : structures) {
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
