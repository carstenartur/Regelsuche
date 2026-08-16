package de.regelsuche.discovery.representation;

import static de.regelsuche.discovery.representation.RepresentationDiscoveryRunContractSupport.append;
import static de.regelsuche.discovery.representation.RepresentationDiscoveryRunContractSupport.requireSha256;
import static de.regelsuche.discovery.representation.RepresentationDiscoveryRunContractSupport.requireText;
import static de.regelsuche.discovery.representation.RepresentationDiscoveryRunContractSupport.sha256;

import de.regelsuche.discovery.representation.RepresentationDiscoveryArtifactReference.ArtifactRole;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Content-addressed, non-ranking comparison of two immutable discovery runs. */
public record RepresentationDiscoveryRunComparison(
    String schema,
    String leftRunId,
    String rightRunId,
    Relationship relationship,
    List<Entry> entries,
    String claimBoundary,
    String contentHash
) {
    public static final String SCHEMA =
        "regelsuche.representation-discovery-run-comparison/v1";
    public static final String CLAIM_BOUNDARY =
        "Raw equality and difference report for two immutable run manifests; "
            + "not a ranking, mathematical proof, novelty or superiority claim.";

    private static final Comparator<Entry> ENTRY_ORDER = Comparator
        .comparing(Entry::category)
        .thenComparing(Entry::field);

    public RepresentationDiscoveryRunComparison {
        schema = requireText(schema, "schema");
        if (!SCHEMA.equals(schema)) {
            throw new IllegalArgumentException(
                "unsupported discovery-run comparison schema: " + schema);
        }
        leftRunId = requireSha256(leftRunId, "leftRunId");
        rightRunId = requireSha256(rightRunId, "rightRunId");
        relationship = Objects.requireNonNull(relationship, "relationship");
        entries = normalizeEntries(entries);
        claimBoundary = requireText(claimBoundary, "claimBoundary");
        if (!CLAIM_BOUNDARY.equals(claimBoundary)) {
            throw new IllegalArgumentException(
                "unsupported discovery-run comparison claim boundary");
        }
        Relationship expectedRelationship = relationship(
            leftRunId,
            rightRunId,
            entryValue(entries, Category.LINEAGE, "parentRunId", true),
            entryValue(entries, Category.LINEAGE, "parentRunId", false)
        );
        if (relationship != expectedRelationship) {
            throw new IllegalArgumentException(
                "discovery-run comparison relationship mismatch");
        }
        if (relationship == Relationship.SAME_RUN
                && entries.stream().anyMatch(entry -> !entry.equal())) {
            throw new IllegalArgumentException(
                "the same content-addressed run cannot differ from itself");
        }
        contentHash = requireSha256(contentHash, "contentHash");
        String expectedHash = comparisonHash(
            leftRunId, rightRunId, relationship, entries);
        if (!expectedHash.equals(contentHash)) {
            throw new IllegalArgumentException(
                "discovery-run comparison content hash mismatch");
        }
    }

    public static RepresentationDiscoveryRunComparison compare(
        RepresentationDiscoveryRunWorkspace left,
        RepresentationDiscoveryRunWorkspace right
    ) {
        RepresentationDiscoveryRunWorkspace normalizedLeft =
            Objects.requireNonNull(left, "left");
        RepresentationDiscoveryRunWorkspace normalizedRight =
            Objects.requireNonNull(right, "right");
        List<Entry> entries = new ArrayList<>();
        addWorkspaceEntries(entries, normalizedLeft, normalizedRight);
        addInputEntries(entries, normalizedLeft, normalizedRight);
        addLineageEntries(entries, normalizedLeft, normalizedRight);
        addPlanEntries(entries, normalizedLeft, normalizedRight);
        addOutcomeEntries(entries, normalizedLeft, normalizedRight);
        addRevisionEntries(entries, normalizedLeft, normalizedRight);
        addArtifactEntries(entries, normalizedLeft, normalizedRight);
        entries.sort(ENTRY_ORDER);
        Relationship relation = relationship(
            normalizedLeft.runId(),
            normalizedRight.runId(),
            normalizedLeft.parentRunId(),
            normalizedRight.parentRunId()
        );
        String hash = comparisonHash(
            normalizedLeft.runId(), normalizedRight.runId(), relation, entries);
        return new RepresentationDiscoveryRunComparison(
            SCHEMA,
            normalizedLeft.runId(),
            normalizedRight.runId(),
            relation,
            entries,
            CLAIM_BOUNDARY,
            hash
        );
    }

    public List<Entry> changedEntries() {
        return entries.stream().filter(entry -> !entry.equal()).toList();
    }

    public List<Entry> canonicalChangedEntries() {
        return changedEntries().stream()
            .filter(entry -> entry.category() != Category.RUNTIME_DIAGNOSTICS)
            .toList();
    }

    public boolean sameCanonicalEvidence() {
        return canonicalChangedEntries().isEmpty();
    }

    public List<Entry> changedEntries(Category category) {
        Category normalizedCategory = Objects.requireNonNull(category, "category");
        return changedEntries().stream()
            .filter(entry -> entry.category() == normalizedCategory)
            .toList();
    }

    private static void addWorkspaceEntries(
        List<Entry> entries,
        RepresentationDiscoveryRunWorkspace left,
        RepresentationDiscoveryRunWorkspace right
    ) {
        add(entries, Category.WORKSPACE, "schema", left.schema(), right.schema());
        add(entries, Category.CLAIM_BOUNDARY, "workspaceClaimBoundary",
            left.claimBoundary(), right.claimBoundary());
    }

    private static void addInputEntries(
        List<Entry> entries,
        RepresentationDiscoveryRunWorkspace left,
        RepresentationDiscoveryRunWorkspace right
    ) {
        RepresentationDiscoveryRunInput leftInput = left.input();
        RepresentationDiscoveryRunInput rightInput = right.input();
        add(entries, Category.INPUT, "domainId",
            leftInput.domainId(), rightInput.domainId());
        add(entries, Category.INPUT, "inputSchema",
            leftInput.inputSchema(), rightInput.inputSchema());
        add(entries, Category.INPUT, "canonicalInputHash",
            leftInput.canonicalInputHash(), rightInput.canonicalInputHash());
        add(entries, Category.INPUT, "displayText",
            leftInput.displayText(), rightInput.displayText());
        add(entries, Category.INPUT, "assumptionsHash",
            listHash("assumptions", leftInput.assumptions()),
            listHash("assumptions", rightInput.assumptions()));
    }

    private static void addLineageEntries(
        List<Entry> entries,
        RepresentationDiscoveryRunWorkspace left,
        RepresentationDiscoveryRunWorkspace right
    ) {
        add(entries, Category.LINEAGE, "relation",
            left.relation().name(), right.relation().name());
        add(entries, Category.LINEAGE, "parentRunId",
            left.parentRunId(), right.parentRunId());
        add(entries, Category.LINEAGE, "parentPlanHash",
            left.parentPlanHash(), right.parentPlanHash());
        add(entries, Category.LINEAGE, "changedPlanParameter",
            left.changedPlanParameter(), right.changedPlanParameter());
    }

    private static void addPlanEntries(
        List<Entry> entries,
        RepresentationDiscoveryRunWorkspace left,
        RepresentationDiscoveryRunWorkspace right
    ) {
        RepresentationDiscoveryRunPlan leftPlan = left.plan();
        RepresentationDiscoveryRunPlan rightPlan = right.plan();
        add(entries, Category.INFORMATION_BOUNDARY, "informationTrack",
            leftPlan.informationTrack().name(), rightPlan.informationTrack().name());
        add(entries, Category.INFORMATION_BOUNDARY, "informationBoundaryHash",
            leftPlan.informationBoundaryHash(), rightPlan.informationBoundaryHash());
        add(entries, Category.PLAN, "ruleInventoryHash",
            leftPlan.ruleInventoryHash(), rightPlan.ruleInventoryHash());
        add(entries, Category.PLAN, "knowledgePackSelectionHash",
            leftPlan.knowledgePackSelectionHash(), rightPlan.knowledgePackSelectionHash());
        add(entries, Category.PLAN, "knownStructureCatalogHash",
            leftPlan.knownStructureCatalogHash(), rightPlan.knownStructureCatalogHash());
        add(entries, Category.PLAN, "searchStrategyId",
            leftPlan.searchStrategyId(), rightPlan.searchStrategyId());
        add(entries, Category.PLAN, "searchProfileId",
            leftPlan.searchProfileId(), rightPlan.searchProfileId());
        add(entries, Category.PLAN, "objectiveId",
            leftPlan.objectiveId(), rightPlan.objectiveId());
        add(entries, Category.PLAN, "budgetHash",
            leftPlan.budgetHash(), rightPlan.budgetHash());
        add(entries, Category.PLAN, "deterministicSeed",
            Long.toString(leftPlan.deterministicSeed()),
            Long.toString(rightPlan.deterministicSeed()));
        add(entries, Category.PLAN, "backendIdentitiesHash",
            listHash("backendIdentities", leftPlan.backendIdentities()),
            listHash("backendIdentities", rightPlan.backendIdentities()));
    }

    private static void addOutcomeEntries(
        List<Entry> entries,
        RepresentationDiscoveryRunWorkspace left,
        RepresentationDiscoveryRunWorkspace right
    ) {
        RepresentationDiscoveryRunOutcome leftOutcome = left.outcome();
        RepresentationDiscoveryRunOutcome rightOutcome = right.outcome();
        add(entries, Category.OUTCOME, "state",
            leftOutcome.state().name(), rightOutcome.state().name());
        add(entries, Category.OUTCOME, "terminalReason",
            leftOutcome.terminalReason(), rightOutcome.terminalReason());
        add(entries, Category.OUTCOME, "configuredWork",
            Long.toString(leftOutcome.configuredWork()),
            Long.toString(rightOutcome.configuredWork()));
        add(entries, Category.OUTCOME, "consumedWork",
            Long.toString(leftOutcome.consumedWork()),
            Long.toString(rightOutcome.consumedWork()));
        add(entries, Category.OUTCOME, "canonicalWorkLedgerHash",
            leftOutcome.canonicalWorkLedgerHash(), rightOutcome.canonicalWorkLedgerHash());
        add(entries, Category.RUNTIME_DIAGNOSTICS, "runtimeDiagnosticsHash",
            leftOutcome.runtimeDiagnosticsHash(), rightOutcome.runtimeDiagnosticsHash());
    }

    private static void addRevisionEntries(
        List<Entry> entries,
        RepresentationDiscoveryRunWorkspace left,
        RepresentationDiscoveryRunWorkspace right
    ) {
        RepresentationDiscoveryRevisionEvidence leftRevision = left.revisions();
        RepresentationDiscoveryRevisionEvidence rightRevision = right.revisions();
        add(entries, Category.REVISION, "repositoryCommit",
            leftRevision.repositoryCommit(), rightRevision.repositoryCommit());
        add(entries, Category.REVISION, "applicationRevision",
            leftRevision.applicationRevision(), rightRevision.applicationRevision());
    }

    private static void addArtifactEntries(
        List<Entry> entries,
        RepresentationDiscoveryRunWorkspace left,
        RepresentationDiscoveryRunWorkspace right
    ) {
        for (ArtifactRole role : ArtifactRole.values()) {
            RepresentationDiscoveryArtifactReference leftReference = artifact(left, role);
            RepresentationDiscoveryArtifactReference rightReference = artifact(right, role);
            String prefix = role.name() + ".";
            add(entries, Category.ARTIFACT, prefix + "status",
                leftReference.status().name(), rightReference.status().name());
            add(entries, Category.ARTIFACT, prefix + "artifactSchema",
                leftReference.artifactSchema(), rightReference.artifactSchema());
            add(entries, Category.ARTIFACT, prefix + "targetContentHash",
                leftReference.targetContentHash(), rightReference.targetContentHash());
            add(entries, Category.ARTIFACT, prefix + "detail",
                leftReference.detail(), rightReference.detail());
        }
    }

    private static RepresentationDiscoveryArtifactReference artifact(
        RepresentationDiscoveryRunWorkspace workspace,
        ArtifactRole role
    ) {
        return workspace.artifacts().stream()
            .filter(reference -> reference.role() == role)
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException(
                "workspace is missing artifact role " + role));
    }

    private static void add(
        List<Entry> entries,
        Category category,
        String field,
        String leftValue,
        String rightValue
    ) {
        entries.add(Entry.create(category, field, leftValue, rightValue));
    }

    private static List<Entry> normalizeEntries(List<Entry> values) {
        Objects.requireNonNull(values, "entries");
        List<Entry> sorted = values.stream()
            .map(entry -> Objects.requireNonNull(entry, "entry"))
            .sorted(ENTRY_ORDER)
            .toList();
        if (!values.equals(sorted)) {
            throw new IllegalArgumentException(
                "comparison entries must use canonical order");
        }
        Set<String> actualKeys = new HashSet<>();
        for (Entry entry : sorted) {
            if (!actualKeys.add(entry.key())) {
                throw new IllegalArgumentException(
                    "duplicate comparison entry: " + entry.key());
            }
        }
        Set<String> requiredKeys = requiredEntryKeys();
        if (!actualKeys.equals(requiredKeys)) {
            Set<String> missing = new HashSet<>(requiredKeys);
            missing.removeAll(actualKeys);
            Set<String> unexpected = new HashSet<>(actualKeys);
            unexpected.removeAll(requiredKeys);
            throw new IllegalArgumentException(
                "incomplete comparison entries: missing=" + missing
                    + ", unexpected=" + unexpected);
        }
        return List.copyOf(sorted);
    }

    private static Set<String> requiredEntryKeys() {
        Set<String> keys = new HashSet<>();
        addKeys(keys, Category.WORKSPACE, "schema");
        addKeys(keys, Category.INPUT,
            "domainId", "inputSchema", "canonicalInputHash",
            "displayText", "assumptionsHash");
        addKeys(keys, Category.LINEAGE,
            "relation", "parentRunId", "parentPlanHash", "changedPlanParameter");
        addKeys(keys, Category.INFORMATION_BOUNDARY,
            "informationTrack", "informationBoundaryHash");
        addKeys(keys, Category.PLAN,
            "ruleInventoryHash", "knowledgePackSelectionHash",
            "knownStructureCatalogHash", "searchStrategyId", "searchProfileId",
            "objectiveId", "budgetHash", "deterministicSeed", "backendIdentitiesHash");
        addKeys(keys, Category.OUTCOME,
            "state", "terminalReason", "configuredWork", "consumedWork",
            "canonicalWorkLedgerHash");
        addKeys(keys, Category.RUNTIME_DIAGNOSTICS, "runtimeDiagnosticsHash");
        addKeys(keys, Category.REVISION, "repositoryCommit", "applicationRevision");
        for (ArtifactRole role : ArtifactRole.values()) {
            String prefix = role.name() + ".";
            addKeys(keys, Category.ARTIFACT,
                prefix + "status", prefix + "artifactSchema",
                prefix + "targetContentHash", prefix + "detail");
        }
        addKeys(keys, Category.CLAIM_BOUNDARY, "workspaceClaimBoundary");
        return Set.copyOf(keys);
    }

    private static void addKeys(
        Set<String> keys,
        Category category,
        String... fields
    ) {
        for (String field : fields) {
            keys.add(category.name() + "/" + field);
        }
    }

    private static String entryValue(
        List<Entry> entries,
        Category category,
        String field,
        boolean left
    ) {
        Entry entry = entries.stream()
            .filter(candidate -> candidate.category() == category)
            .filter(candidate -> candidate.field().equals(field))
            .findFirst()
            .orElseThrow();
        return left ? entry.leftValue() : entry.rightValue();
    }

    private static Relationship relationship(
        String leftRunId,
        String rightRunId,
        String leftParentRunId,
        String rightParentRunId
    ) {
        if (leftRunId.equals(rightRunId)) {
            return Relationship.SAME_RUN;
        }
        if (leftRunId.equals(rightParentRunId)
                || rightRunId.equals(leftParentRunId)) {
            return Relationship.DIRECT_LINEAGE;
        }
        if (!leftParentRunId.isEmpty()
                && leftParentRunId.equals(rightParentRunId)) {
            return Relationship.SIBLING_LINEAGE;
        }
        return Relationship.UNRELATED;
    }

    private static String listHash(String field, List<String> values) {
        StringBuilder descriptor = new StringBuilder();
        append(descriptor, SCHEMA + "/list/" + field);
        append(descriptor, Integer.toString(values.size()));
        values.forEach(value -> append(descriptor, value));
        return sha256(descriptor.toString());
    }

    private static String comparisonHash(
        String leftRunId,
        String rightRunId,
        Relationship relationship,
        List<Entry> entries
    ) {
        StringBuilder descriptor = new StringBuilder();
        append(descriptor, SCHEMA);
        append(descriptor, leftRunId);
        append(descriptor, rightRunId);
        append(descriptor, relationship.name());
        append(descriptor, Integer.toString(entries.size()));
        entries.forEach(entry -> entry.appendTo(descriptor));
        append(descriptor, CLAIM_BOUNDARY);
        return sha256(descriptor.toString());
    }

    public record Entry(
        Category category,
        String field,
        String leftValue,
        String rightValue,
        boolean equal
    ) {
        public Entry {
            category = Objects.requireNonNull(category, "category");
            field = requireText(field, "field");
            leftValue = Objects.requireNonNull(leftValue, "leftValue");
            rightValue = Objects.requireNonNull(rightValue, "rightValue");
            if (equal != leftValue.equals(rightValue)) {
                throw new IllegalArgumentException(
                    "comparison entry equality flag mismatch: " + field);
            }
        }

        public static Entry create(
            Category category,
            String field,
            String leftValue,
            String rightValue
        ) {
            return new Entry(
                category, field, leftValue, rightValue,
                leftValue.equals(rightValue));
        }

        String key() {
            return category.name() + "/" + field;
        }

        void appendTo(StringBuilder descriptor) {
            append(descriptor, category.name());
            append(descriptor, field);
            append(descriptor, leftValue);
            append(descriptor, rightValue);
            append(descriptor, Boolean.toString(equal));
        }
    }

    public enum Category {
        WORKSPACE,
        INPUT,
        LINEAGE,
        INFORMATION_BOUNDARY,
        PLAN,
        OUTCOME,
        RUNTIME_DIAGNOSTICS,
        REVISION,
        ARTIFACT,
        CLAIM_BOUNDARY
    }

    public enum Relationship {
        SAME_RUN,
        DIRECT_LINEAGE,
        SIBLING_LINEAGE,
        UNRELATED
    }
}
