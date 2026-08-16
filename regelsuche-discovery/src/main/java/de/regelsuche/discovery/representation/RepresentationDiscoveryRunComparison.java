package de.regelsuche.discovery.representation;

import static de.regelsuche.discovery.representation.RepresentationDiscoveryRunContractSupport.append;
import static de.regelsuche.discovery.representation.RepresentationDiscoveryRunContractSupport.requireSha256;
import static de.regelsuche.discovery.representation.RepresentationDiscoveryRunContractSupport.requireText;
import static de.regelsuche.discovery.representation.RepresentationDiscoveryRunContractSupport.sha256;

import de.regelsuche.discovery.representation.RepresentationDiscoveryArtifactReference.ArtifactRole;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

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
    private static final List<String> REQUIRED_KEYS = requiredKeys();

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
        Relationship expected = relationship(
            leftRunId,
            rightRunId,
            entryValue(entries, Category.LINEAGE, "parentRunId", true),
            entryValue(entries, Category.LINEAGE, "parentRunId", false)
        );
        if (relationship != expected) {
            throw new IllegalArgumentException(
                "discovery-run comparison relationship mismatch");
        }
        if (!relationship.accepts(entries)) {
            throw new IllegalArgumentException(
                "the same content-addressed run cannot differ from itself");
        }
        contentHash = requireSha256(contentHash, "contentHash");
        if (!comparisonHash(
                leftRunId,
                rightRunId,
                relationship,
                entries
            ).equals(contentHash)) {
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
        List<Entry> entries = entries(normalizedLeft, normalizedRight);
        Relationship relationship = relationship(
            normalizedLeft.runId(),
            normalizedRight.runId(),
            normalizedLeft.parentRunId(),
            normalizedRight.parentRunId()
        );
        return new RepresentationDiscoveryRunComparison(
            SCHEMA,
            normalizedLeft.runId(),
            normalizedRight.runId(),
            relationship,
            entries,
            CLAIM_BOUNDARY,
            comparisonHash(
                normalizedLeft.runId(),
                normalizedRight.runId(),
                relationship,
                entries
            )
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

    private static List<Entry> entries(
        RepresentationDiscoveryRunWorkspace left,
        RepresentationDiscoveryRunWorkspace right
    ) {
        List<Entry> entries = new ArrayList<>();
        add(entries, Category.WORKSPACE, "schema", left.schema(), right.schema());
        add(entries, Category.CLAIM_BOUNDARY, "workspaceClaimBoundary",
            left.claimBoundary(), right.claimBoundary());
        addInputEntries(entries, left.input(), right.input());
        addLineageEntries(entries, left, right);
        addPlanEntries(entries, left.plan(), right.plan());
        addOutcomeEntries(entries, left.outcome(), right.outcome());
        addRevisionEntries(entries, left.revisions(), right.revisions());
        addArtifactEntries(entries, left, right);
        entries.sort(ENTRY_ORDER);
        return List.copyOf(entries);
    }

    private static void addInputEntries(
        List<Entry> entries,
        RepresentationDiscoveryRunInput left,
        RepresentationDiscoveryRunInput right
    ) {
        add(entries, Category.INPUT, "domainId",
            left.domainId(), right.domainId());
        add(entries, Category.INPUT, "inputSchema",
            left.inputSchema(), right.inputSchema());
        add(entries, Category.INPUT, "canonicalInputHash",
            left.canonicalInputHash(), right.canonicalInputHash());
        add(entries, Category.INPUT, "displayText",
            left.displayText(), right.displayText());
        add(entries, Category.INPUT, "assumptionsHash",
            listHash("assumptions", left.assumptions()),
            listHash("assumptions", right.assumptions()));
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
        RepresentationDiscoveryRunPlan left,
        RepresentationDiscoveryRunPlan right
    ) {
        add(entries, Category.INFORMATION_BOUNDARY, "informationTrack",
            left.informationTrack().name(), right.informationTrack().name());
        add(entries, Category.INFORMATION_BOUNDARY, "informationBoundaryHash",
            left.informationBoundaryHash(), right.informationBoundaryHash());
        add(entries, Category.PLAN, "ruleInventoryHash",
            left.ruleInventoryHash(), right.ruleInventoryHash());
        add(entries, Category.PLAN, "knowledgePackSelectionHash",
            left.knowledgePackSelectionHash(), right.knowledgePackSelectionHash());
        add(entries, Category.PLAN, "knownStructureCatalogHash",
            left.knownStructureCatalogHash(), right.knownStructureCatalogHash());
        add(entries, Category.PLAN, "searchStrategyId",
            left.searchStrategyId(), right.searchStrategyId());
        add(entries, Category.PLAN, "searchProfileId",
            left.searchProfileId(), right.searchProfileId());
        add(entries, Category.PLAN, "objectiveId",
            left.objectiveId(), right.objectiveId());
        add(entries, Category.PLAN, "budgetHash",
            left.budgetHash(), right.budgetHash());
        add(entries, Category.PLAN, "deterministicSeed",
            Long.toString(left.deterministicSeed()),
            Long.toString(right.deterministicSeed()));
        add(entries, Category.PLAN, "backendIdentitiesHash",
            listHash("backendIdentities", left.backendIdentities()),
            listHash("backendIdentities", right.backendIdentities()));
    }

    private static void addOutcomeEntries(
        List<Entry> entries,
        RepresentationDiscoveryRunOutcome left,
        RepresentationDiscoveryRunOutcome right
    ) {
        add(entries, Category.OUTCOME, "state",
            left.state().name(), right.state().name());
        add(entries, Category.OUTCOME, "terminalReason",
            left.terminalReason(), right.terminalReason());
        add(entries, Category.OUTCOME, "configuredWork",
            Long.toString(left.configuredWork()),
            Long.toString(right.configuredWork()));
        add(entries, Category.OUTCOME, "consumedWork",
            Long.toString(left.consumedWork()),
            Long.toString(right.consumedWork()));
        add(entries, Category.OUTCOME, "canonicalWorkLedgerHash",
            left.canonicalWorkLedgerHash(), right.canonicalWorkLedgerHash());
        add(entries, Category.RUNTIME_DIAGNOSTICS, "runtimeDiagnosticsHash",
            left.runtimeDiagnosticsHash(), right.runtimeDiagnosticsHash());
    }

    private static void addRevisionEntries(
        List<Entry> entries,
        RepresentationDiscoveryRevisionEvidence left,
        RepresentationDiscoveryRevisionEvidence right
    ) {
        add(entries, Category.REVISION, "repositoryCommit",
            left.repositoryCommit(), right.repositoryCommit());
        add(entries, Category.REVISION, "applicationRevision",
            left.applicationRevision(), right.applicationRevision());
    }

    private static void addArtifactEntries(
        List<Entry> entries,
        RepresentationDiscoveryRunWorkspace left,
        RepresentationDiscoveryRunWorkspace right
    ) {
        for (ArtifactRole role : ArtifactRole.values()) {
            RepresentationDiscoveryArtifactReference leftReference =
                artifact(left, role);
            RepresentationDiscoveryArtifactReference rightReference =
                artifact(right, role);
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
        List<Entry> copy = List.copyOf(
            Objects.requireNonNull(values, "entries"));
        List<String> keys = copy.stream().map(Entry::key).toList();
        if (!keys.equals(REQUIRED_KEYS)) {
            throw new IllegalArgumentException(
                "comparison entries are incomplete, duplicated or unordered");
        }
        return copy;
    }

    private static List<String> requiredKeys() {
        List<String> keys = new ArrayList<>();
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
        keys.sort(String::compareTo);
        return List.copyOf(keys);
    }

    private static void addKeys(
        List<String> keys,
        Category category,
        String... fields
    ) {
        for (String field : fields) {
            keys.add(key(category, field));
        }
    }

    private static String entryValue(
        List<Entry> entries,
        Category category,
        String field,
        boolean left
    ) {
        Entry entry = entries.get(REQUIRED_KEYS.indexOf(key(category, field)));
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

    private static String key(Category category, String field) {
        return category.ordinal() + "/" + field;
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
                category,
                field,
                leftValue,
                rightValue,
                leftValue.equals(rightValue)
            );
        }

        String key() {
            return RepresentationDiscoveryRunComparison.key(category, field);
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
        SAME_RUN {
            @Override
            boolean accepts(List<Entry> entries) {
                return entries.stream().allMatch(Entry::equal);
            }
        },
        DIRECT_LINEAGE,
        SIBLING_LINEAGE,
        UNRELATED;

        boolean accepts(List<Entry> entries) {
            return true;
        }
    }
}
