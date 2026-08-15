package de.regelsuche.discovery.representation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import de.regelsuche.parse.ExpressionFormatter;
import de.regelsuche.parse.ExpressionParser;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Immutable, content-addressed backend contract for one representation-
 * discovery run workspace.
 *
 * <p>The workspace binds input, information boundary, executable inventories,
 * search controls, backend identities, terminal accounting, every product
 * artifact role and code revisions. Artifact absence is explicit; consumers
 * cannot silently combine a graph, candidate dossier or proof object from a
 * different run.</p>
 */
public record RepresentationDiscoveryRunWorkspace(
    String schema,
    String runId,
    RunRelation relation,
    String parentRunId,
    String parentPlanHash,
    String changedPlanParameter,
    RunInput input,
    RunPlan plan,
    RunOutcome outcome,
    List<ArtifactReference> artifacts,
    RevisionEvidence revisions,
    String claimBoundary,
    String contentHash
) {
    public static final String SCHEMA =
        "regelsuche.representation-discovery-run-workspace/v1";
    public static final String EXPRESSION_INPUT_SCHEMA =
        "regelsuche.representation-discovery-expression-input/v1";
    public static final String CLAIM_BOUNDARY =
        "Immutable run correlation, artifact availability and reproduction "
            + "evidence; not mathematical truth, proof, novelty, "
            + "interestingness or search superiority.";

    private static final String NOT_AVAILABLE_SCHEMA = "NOT_AVAILABLE";
    private static final JsonMapper JSON = JsonMapper.builder()
        .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
        .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
        .build();

    public RepresentationDiscoveryRunWorkspace {
        schema = requireText(schema, "schema");
        if (!SCHEMA.equals(schema)) {
            throw new IllegalArgumentException(
                "unsupported run-workspace schema: " + schema);
        }
        runId = requireSha256(runId, "runId");
        relation = Objects.requireNonNull(relation, "relation");
        parentRunId = optionalSha256(parentRunId, "parentRunId");
        parentPlanHash = optionalSha256(parentPlanHash, "parentPlanHash");
        changedPlanParameter = optionalText(
            changedPlanParameter, "changedPlanParameter");
        validateRelation(
            relation,
            runId,
            parentRunId,
            parentPlanHash,
            changedPlanParameter
        );
        input = Objects.requireNonNull(input, "input");
        plan = Objects.requireNonNull(plan, "plan");
        outcome = Objects.requireNonNull(outcome, "outcome");
        artifacts = completeArtifacts(artifacts);
        revisions = Objects.requireNonNull(revisions, "revisions");
        claimBoundary = requireText(claimBoundary, "claimBoundary");
        if (!CLAIM_BOUNDARY.equals(claimBoundary)) {
            throw new IllegalArgumentException(
                "unsupported run-workspace claim boundary");
        }
        contentHash = requireSha256(contentHash, "contentHash");
        String expected = workspaceHash(
            relation,
            parentRunId,
            parentPlanHash,
            changedPlanParameter,
            input,
            plan,
            outcome,
            artifacts,
            revisions
        );
        if (!expected.equals(contentHash) || !runId.equals(contentHash)) {
            throw new IllegalArgumentException(
                "runId/contentHash does not match workspace content");
        }
    }

    /** Creates an independent root run. */
    public static RepresentationDiscoveryRunWorkspace create(
        RunInput input,
        RunPlan plan,
        RunOutcome outcome,
        List<ArtifactReference> artifacts,
        RevisionEvidence revisions
    ) {
        return createInternal(
            RunRelation.ROOT,
            "",
            "",
            "",
            input,
            plan,
            outcome,
            artifacts,
            revisions
        );
    }

    /**
     * Duplicates a run while permitting exactly one visible plan parameter to
     * change. The new run starts with zero consumed work and every artifact role
     * explicitly marked {@link ArtifactStatus#NOT_PRODUCED}.
     */
    public static RepresentationDiscoveryRunWorkspace duplicateWithOnePlanChange(
        RepresentationDiscoveryRunWorkspace parent,
        RunPlan revisedPlan,
        RevisionEvidence revisions
    ) {
        Objects.requireNonNull(parent, "parent");
        Objects.requireNonNull(revisedPlan, "revisedPlan");
        String changed = parent.plan().singleChangedParameter(revisedPlan);
        return createInternal(
            RunRelation.DUPLICATED_ONE_PARAMETER,
            parent.runId(),
            parent.plan().contentHash(),
            changed,
            parent.input(),
            revisedPlan,
            RunOutcome.created(),
            notProducedArtifacts(),
            revisions
        );
    }

    /**
     * Creates an immutable continuation revision with the same input and plan.
     * The parent remains unchanged and the continuation receives its own Run ID.
     */
    public static RepresentationDiscoveryRunWorkspace continueFrom(
        RepresentationDiscoveryRunWorkspace parent,
        RunOutcome outcome,
        List<ArtifactReference> artifacts,
        RevisionEvidence revisions
    ) {
        Objects.requireNonNull(parent, "parent");
        if (Objects.requireNonNull(outcome, "outcome").state()
                == TerminalState.CREATED) {
            throw new IllegalArgumentException(
                "a continuation must advance beyond CREATED");
        }
        return createInternal(
            RunRelation.CONTINUATION,
            parent.runId(),
            parent.plan().contentHash(),
            "",
            parent.input(),
            parent.plan(),
            outcome,
            artifacts,
            revisions
        );
    }

    /** Returns an available same-run artifact or fails visibly. */
    public ArtifactReference requireArtifact(
        ArtifactRole role,
        String expectedSchema
    ) {
        Objects.requireNonNull(role, "role");
        String schemaName = requireText(expectedSchema, "expectedSchema");
        ArtifactReference reference = artifacts.stream()
            .filter(candidate -> candidate.role() == role)
            .findFirst()
            .orElseThrow();
        if (reference.status() != ArtifactStatus.AVAILABLE) {
            throw new IllegalStateException(
                "artifact " + role + " is " + reference.status()
                    + ": " + reference.detail());
        }
        if (!reference.artifactSchema().equals(schemaName)) {
            throw new IllegalStateException(
                "artifact " + role + " has schema "
                    + reference.artifactSchema() + ", expected "
                    + schemaName);
        }
        return reference;
    }

    /** Creates an immutable selection correlated to this exact Run ID. */
    public RunSelection selection(
        String candidateId,
        String stateId,
        String edgeId,
        String occurrencePath,
        String proofObligationId
    ) {
        return RunSelection.create(
            runId,
            candidateId,
            stateId,
            edgeId,
            occurrencePath,
            proofObligationId
        );
    }

    public String toCanonicalJson() {
        try {
            return JSON.writeValueAsString(this);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(
                "Unable to render representation-discovery workspace",
                exception
            );
        }
    }

    public static List<ArtifactReference> notProducedArtifacts() {
        return EnumSet.allOf(ArtifactRole.class).stream()
            .map(ArtifactReference::notProduced)
            .toList();
    }

    private static RepresentationDiscoveryRunWorkspace createInternal(
        RunRelation relation,
        String parentRunId,
        String parentPlanHash,
        String changedPlanParameter,
        RunInput input,
        RunPlan plan,
        RunOutcome outcome,
        List<ArtifactReference> artifacts,
        RevisionEvidence revisions
    ) {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(outcome, "outcome");
        List<ArtifactReference> complete = completeArtifacts(artifacts);
        Objects.requireNonNull(revisions, "revisions");
        String hash = workspaceHash(
            relation,
            parentRunId,
            parentPlanHash,
            changedPlanParameter,
            input,
            plan,
            outcome,
            complete,
            revisions
        );
        return new RepresentationDiscoveryRunWorkspace(
            SCHEMA,
            hash,
            relation,
            parentRunId,
            parentPlanHash,
            changedPlanParameter,
            input,
            plan,
            outcome,
            complete,
            revisions,
            CLAIM_BOUNDARY,
            hash
        );
    }

    private static String workspaceHash(
        RunRelation relation,
        String parentRunId,
        String parentPlanHash,
        String changedPlanParameter,
        RunInput input,
        RunPlan plan,
        RunOutcome outcome,
        List<ArtifactReference> artifacts,
        RevisionEvidence revisions
    ) {
        StringBuilder descriptor = new StringBuilder();
        append(descriptor, SCHEMA);
        append(descriptor, relation.name());
        append(descriptor, parentRunId);
        append(descriptor, parentPlanHash);
        append(descriptor, changedPlanParameter);
        append(descriptor, input.contentHash());
        append(descriptor, plan.contentHash());
        append(descriptor, outcome.contentHash());
        append(descriptor, Integer.toString(artifacts.size()));
        artifacts.forEach(reference ->
            append(descriptor, reference.contentHash()));
        append(descriptor, revisions.contentHash());
        append(descriptor, CLAIM_BOUNDARY);
        return KnownStructureCatalog.sha256(descriptor.toString());
    }

    private static void validateRelation(
        RunRelation relation,
        String runId,
        String parentRunId,
        String parentPlanHash,
        String changedPlanParameter
    ) {
        if (!parentRunId.isEmpty() && parentRunId.equals(runId)) {
            throw new IllegalArgumentException(
                "a workspace cannot be its own parent");
        }
        switch (relation) {
            case ROOT -> {
                if (!parentRunId.isEmpty()
                        || !parentPlanHash.isEmpty()
                        || !changedPlanParameter.isEmpty()) {
                    throw new IllegalArgumentException(
                        "a root run cannot carry parent evidence");
                }
            }
            case CONTINUATION -> {
                requireParent(parentRunId, parentPlanHash);
                if (!changedPlanParameter.isEmpty()) {
                    throw new IllegalArgumentException(
                        "a continuation cannot claim a plan change");
                }
            }
            case DUPLICATED_ONE_PARAMETER -> {
                requireParent(parentRunId, parentPlanHash);
                if (!RunPlan.PARAMETERS.contains(changedPlanParameter)) {
                    throw new IllegalArgumentException(
                        "unsupported changed plan parameter: "
                            + changedPlanParameter);
                }
            }
        }
    }

    private static void requireParent(
        String parentRunId,
        String parentPlanHash
    ) {
        if (parentRunId.isEmpty() || parentPlanHash.isEmpty()) {
            throw new IllegalArgumentException(
                "derived runs require parent run and plan identities");
        }
    }

    private static List<ArtifactReference> completeArtifacts(
        List<ArtifactReference> references
    ) {
        Objects.requireNonNull(references, "artifacts");
        List<ArtifactReference> sorted = references.stream()
            .map(reference -> Objects.requireNonNull(
                reference, "artifact"))
            .sorted(Comparator.comparing(reference ->
                reference.role().name()))
            .toList();
        Set<ArtifactRole> roles = new HashSet<>();
        for (ArtifactReference reference : sorted) {
            if (!roles.add(reference.role())) {
                throw new IllegalArgumentException(
                    "duplicate artifact role: " + reference.role());
            }
        }
        Set<ArtifactRole> missing = EnumSet.allOf(ArtifactRole.class);
        missing.removeAll(roles);
        if (!missing.isEmpty()) {
            throw new IllegalArgumentException(
                "missing explicit artifact roles: " + missing);
        }
        return sorted;
    }

    private static void append(StringBuilder descriptor, String value) {
        KnownStructureCatalog.appendCanonicalField(descriptor, value);
    }

    private static String requireText(String value, String field) {
        return RepresentationCandidateAssessment.requireText(value, field);
    }

    private static String optionalText(String value, String field) {
        Objects.requireNonNull(value, field);
        return value.trim();
    }

    private static String requireSha256(String value, String field) {
        String normalized = requireText(value, field);
        if (!normalized.matches("sha256:[0-9a-f]{64}")) {
            throw new IllegalArgumentException(
                field + " must be a lowercase SHA-256 identity");
        }
        return normalized;
    }

    private static String optionalSha256(String value, String field) {
        String normalized = optionalText(value, field);
        return normalized.isEmpty()
            ? ""
            : requireSha256(normalized, field);
    }

    private static List<String> sortedRequiredStrings(
        List<String> values,
        String field
    ) {
        List<String> result = RepresentationCandidateAssessment.sortedUnique(
            Objects.requireNonNull(values, field), field);
        if (result.isEmpty()) {
            throw new IllegalArgumentException(field + " must not be empty");
        }
        return result;
    }

    private static List<String> sortedStrings(
        List<String> values,
        String field
    ) {
        return RepresentationCandidateAssessment.sortedUnique(
            Objects.requireNonNull(values, field), field);
    }

    public enum RunRelation {
        ROOT,
        DUPLICATED_ONE_PARAMETER,
        CONTINUATION
    }

    public enum TerminalState {
        CREATED,
        RUNNING,
        COMPLETED,
        BUDGET_EXHAUSTED,
        NO_RESULT,
        CANCELLED,
        FAILED,
        UNSUPPORTED
    }

    public enum ArtifactRole {
        SEARCH_GRAPH,
        REPRESENTATION_CANDIDATES,
        CANDIDATE_DOSSIERS,
        PATH_REPLAY,
        RULE_RADAR,
        PROOF_OBLIGATIONS,
        EXPORT_BUNDLE,
        PROGRESS_LEDGER
    }

    public enum ArtifactStatus {
        AVAILABLE,
        NOT_PRODUCED,
        UNSUPPORTED,
        FAILED
    }

    /** Domain-neutral, content-addressed input reference with a display form. */
    public record RunInput(
        String domainId,
        String inputSchema,
        String canonicalInputHash,
        String displayText,
        List<String> assumptions,
        String contentHash
    ) {
        public RunInput {
            domainId = requireText(domainId, "domainId");
            inputSchema = requireText(inputSchema, "inputSchema");
            canonicalInputHash = requireSha256(
                canonicalInputHash, "canonicalInputHash");
            displayText = requireText(displayText, "displayText");
            assumptions = sortedStrings(assumptions, "assumptions");
            contentHash = requireSha256(contentHash, "contentHash");
            if (EXPRESSION_INPUT_SCHEMA.equals(inputSchema)) {
                String normalized = normalizeExpression(displayText);
                if (!normalized.equals(displayText)) {
                    throw new IllegalArgumentException(
                        "expression input displayText is not normalized");
                }
                String expectedInputHash = expressionInputHash(
                    normalized, assumptions);
                if (!expectedInputHash.equals(canonicalInputHash)) {
                    throw new IllegalArgumentException(
                        "canonical expression input hash mismatch");
                }
            }
            String expected = inputHash(
                domainId,
                inputSchema,
                canonicalInputHash,
                displayText,
                assumptions
            );
            if (!expected.equals(contentHash)) {
                throw new IllegalArgumentException(
                    "run input content hash mismatch");
            }
        }

        public static RunInput create(
            String domainId,
            String inputSchema,
            String canonicalInputHash,
            String displayText,
            List<String> assumptions
        ) {
            List<String> normalizedAssumptions = sortedStrings(
                assumptions, "assumptions");
            String hash = inputHash(
                domainId,
                inputSchema,
                canonicalInputHash,
                displayText,
                normalizedAssumptions
            );
            return new RunInput(
                domainId,
                inputSchema,
                canonicalInputHash,
                displayText,
                normalizedAssumptions,
                hash
            );
        }

        public static RunInput expression(
            String expression,
            List<String> assumptions
        ) {
            String normalized = normalizeExpression(expression);
            List<String> normalizedAssumptions = sortedStrings(
                assumptions, "assumptions");
            return create(
                "expression-rewrite",
                EXPRESSION_INPUT_SCHEMA,
                expressionInputHash(normalized, normalizedAssumptions),
                normalized,
                normalizedAssumptions
            );
        }

        private static String inputHash(
            String domainId,
            String inputSchema,
            String canonicalInputHash,
            String displayText,
            List<String> assumptions
        ) {
            StringBuilder descriptor = new StringBuilder();
            append(descriptor, SCHEMA + "/input");
            append(descriptor, domainId);
            append(descriptor, inputSchema);
            append(descriptor, canonicalInputHash);
            append(descriptor, displayText);
            append(descriptor, Integer.toString(assumptions.size()));
            assumptions.forEach(value -> append(descriptor, value));
            return KnownStructureCatalog.sha256(descriptor.toString());
        }

        private static String expressionInputHash(
            String expression,
            List<String> assumptions
        ) {
            StringBuilder descriptor = new StringBuilder();
            append(descriptor, EXPRESSION_INPUT_SCHEMA);
            append(descriptor, expression);
            append(descriptor, Integer.toString(assumptions.size()));
            assumptions.forEach(value -> append(descriptor, value));
            return KnownStructureCatalog.sha256(descriptor.toString());
        }

        private static String normalizeExpression(String expression) {
            return ExpressionFormatter.format(
                new ExpressionParser().parseTerm(requireText(
                    expression, "expression")));
        }
    }

    /** Frozen formation, search and backend configuration. */
    public record RunPlan(
        RepresentationDiscoveryInformationBoundary.Track informationTrack,
        String informationBoundaryHash,
        String ruleInventoryHash,
        String knowledgePackSelectionHash,
        String knownStructureCatalogHash,
        String searchStrategyId,
        String searchProfileId,
        String objectiveId,
        String budgetHash,
        long deterministicSeed,
        List<String> backendIdentities,
        String contentHash
    ) {
        private static final Set<String> PARAMETERS = Set.of(
            "informationTrack",
            "informationBoundaryHash",
            "ruleInventoryHash",
            "knowledgePackSelectionHash",
            "knownStructureCatalogHash",
            "searchStrategyId",
            "searchProfileId",
            "objectiveId",
            "budgetHash",
            "deterministicSeed",
            "backendIdentities"
        );

        public RunPlan {
            informationTrack = Objects.requireNonNull(
                informationTrack, "informationTrack");
            informationBoundaryHash = requireSha256(
                informationBoundaryHash, "informationBoundaryHash");
            ruleInventoryHash = requireSha256(
                ruleInventoryHash, "ruleInventoryHash");
            knowledgePackSelectionHash = requireSha256(
                knowledgePackSelectionHash,
                "knowledgePackSelectionHash"
            );
            knownStructureCatalogHash = requireSha256(
                knownStructureCatalogHash,
                "knownStructureCatalogHash"
            );
            searchStrategyId = requireText(
                searchStrategyId, "searchStrategyId");
            searchProfileId = requireText(
                searchProfileId, "searchProfileId");
            objectiveId = requireText(objectiveId, "objectiveId");
            budgetHash = requireSha256(budgetHash, "budgetHash");
            backendIdentities = sortedRequiredStrings(
                backendIdentities, "backendIdentities");
            contentHash = requireSha256(contentHash, "contentHash");
            String expected = planHash(
                informationTrack,
                informationBoundaryHash,
                ruleInventoryHash,
                knowledgePackSelectionHash,
                knownStructureCatalogHash,
                searchStrategyId,
                searchProfileId,
                objectiveId,
                budgetHash,
                deterministicSeed,
                backendIdentities
            );
            if (!expected.equals(contentHash)) {
                throw new IllegalArgumentException(
                    "run plan content hash mismatch");
            }
        }

        public static RunPlan create(
            RepresentationDiscoveryInformationBoundary.Track informationTrack,
            String informationBoundaryHash,
            String ruleInventoryHash,
            String knowledgePackSelectionHash,
            String knownStructureCatalogHash,
            String searchStrategyId,
            String searchProfileId,
            String objectiveId,
            String budgetHash,
            long deterministicSeed,
            List<String> backendIdentities
        ) {
            List<String> backends = sortedRequiredStrings(
                backendIdentities, "backendIdentities");
            String hash = planHash(
                informationTrack,
                informationBoundaryHash,
                ruleInventoryHash,
                knowledgePackSelectionHash,
                knownStructureCatalogHash,
                searchStrategyId,
                searchProfileId,
                objectiveId,
                budgetHash,
                deterministicSeed,
                backends
            );
            return new RunPlan(
                informationTrack,
                informationBoundaryHash,
                ruleInventoryHash,
                knowledgePackSelectionHash,
                knownStructureCatalogHash,
                searchStrategyId,
                searchProfileId,
                objectiveId,
                budgetHash,
                deterministicSeed,
                backends,
                hash
            );
        }

        String singleChangedParameter(RunPlan revised) {
            Objects.requireNonNull(revised, "revisedPlan");
            List<String> changes = new ArrayList<>();
            changed(changes, "informationTrack",
                informationTrack != revised.informationTrack);
            changed(changes, "informationBoundaryHash",
                !informationBoundaryHash.equals(
                    revised.informationBoundaryHash));
            changed(changes, "ruleInventoryHash",
                !ruleInventoryHash.equals(revised.ruleInventoryHash));
            changed(changes, "knowledgePackSelectionHash",
                !knowledgePackSelectionHash.equals(
                    revised.knowledgePackSelectionHash));
            changed(changes, "knownStructureCatalogHash",
                !knownStructureCatalogHash.equals(
                    revised.knownStructureCatalogHash));
            changed(changes, "searchStrategyId",
                !searchStrategyId.equals(revised.searchStrategyId));
            changed(changes, "searchProfileId",
                !searchProfileId.equals(revised.searchProfileId));
            changed(changes, "objectiveId",
                !objectiveId.equals(revised.objectiveId));
            changed(changes, "budgetHash",
                !budgetHash.equals(revised.budgetHash));
            changed(changes, "deterministicSeed",
                deterministicSeed != revised.deterministicSeed);
            changed(changes, "backendIdentities",
                !backendIdentities.equals(revised.backendIdentities));
            if (changes.size() != 1) {
                throw new IllegalArgumentException(
                    "run duplication requires exactly one changed plan "
                        + "parameter, found " + changes);
            }
            return changes.getFirst();
        }

        private static void changed(
            List<String> changes,
            String parameter,
            boolean changed
        ) {
            if (changed) {
                changes.add(parameter);
            }
        }

        private static String planHash(
            RepresentationDiscoveryInformationBoundary.Track track,
            String boundaryHash,
            String inventoryHash,
            String selectionHash,
            String catalogHash,
            String strategyId,
            String profileId,
            String objectiveId,
            String budgetHash,
            long seed,
            List<String> backends
        ) {
            StringBuilder descriptor = new StringBuilder();
            append(descriptor, SCHEMA + "/plan");
            append(descriptor, track.id());
            append(descriptor, boundaryHash);
            append(descriptor, inventoryHash);
            append(descriptor, selectionHash);
            append(descriptor, catalogHash);
            append(descriptor, strategyId);
            append(descriptor, profileId);
            append(descriptor, objectiveId);
            append(descriptor, budgetHash);
            append(descriptor, Long.toString(seed));
            append(descriptor, Integer.toString(backends.size()));
            backends.forEach(value -> append(descriptor, value));
            return KnownStructureCatalog.sha256(descriptor.toString());
        }
    }

    /** Terminal or current state with canonical work and runtime kept separate. */
    public record RunOutcome(
        TerminalState state,
        String terminalReason,
        long configuredWork,
        long consumedWork,
        String canonicalWorkLedgerHash,
        String runtimeDiagnosticsHash,
        String contentHash
    ) {
        public RunOutcome {
            state = Objects.requireNonNull(state, "state");
            terminalReason = requireText(
                terminalReason, "terminalReason");
            if (configuredWork < 0 || consumedWork < 0
                    || consumedWork > configuredWork) {
                throw new IllegalArgumentException(
                    "configured and consumed work do not balance");
            }
            if (state == TerminalState.CREATED
                    && (configuredWork != 0
                        || consumedWork != 0
                        || !"NOT_STARTED".equals(terminalReason))) {
                throw new IllegalArgumentException(
                    "CREATED outcome must be NOT_STARTED with zero work");
            }
            if (state == TerminalState.BUDGET_EXHAUSTED
                    && (configuredWork == 0
                        || consumedWork != configuredWork)) {
                throw new IllegalArgumentException(
                    "BUDGET_EXHAUSTED must consume all configured work");
            }
            canonicalWorkLedgerHash = requireSha256(
                canonicalWorkLedgerHash,
                "canonicalWorkLedgerHash"
            );
            runtimeDiagnosticsHash = requireSha256(
                runtimeDiagnosticsHash,
                "runtimeDiagnosticsHash"
            );
            contentHash = requireSha256(contentHash, "contentHash");
            String expected = outcomeHash(
                state,
                terminalReason,
                configuredWork,
                consumedWork,
                canonicalWorkLedgerHash,
                runtimeDiagnosticsHash
            );
            if (!expected.equals(contentHash)) {
                throw new IllegalArgumentException(
                    "run outcome content hash mismatch");
            }
        }

        public static RunOutcome create(
            TerminalState state,
            String terminalReason,
            long configuredWork,
            long consumedWork,
            String canonicalWorkLedgerHash,
            String runtimeDiagnosticsHash
        ) {
            String hash = outcomeHash(
                state,
                terminalReason,
                configuredWork,
                consumedWork,
                canonicalWorkLedgerHash,
                runtimeDiagnosticsHash
            );
            return new RunOutcome(
                state,
                terminalReason,
                configuredWork,
                consumedWork,
                canonicalWorkLedgerHash,
                runtimeDiagnosticsHash,
                hash
            );
        }

        public static RunOutcome created() {
            return create(
                TerminalState.CREATED,
                "NOT_STARTED",
                0,
                0,
                KnownStructureCatalog.sha256(
                    SCHEMA + "/work/NOT_STARTED"),
                KnownStructureCatalog.sha256(
                    SCHEMA + "/runtime/NOT_EVALUATED")
            );
        }

        private static String outcomeHash(
            TerminalState state,
            String reason,
            long configuredWork,
            long consumedWork,
            String workHash,
            String runtimeHash
        ) {
            StringBuilder descriptor = new StringBuilder();
            append(descriptor, SCHEMA + "/outcome");
            append(descriptor, state.name());
            append(descriptor, reason);
            append(descriptor, Long.toString(configuredWork));
            append(descriptor, Long.toString(consumedWork));
            append(descriptor, workHash);
            append(descriptor, runtimeHash);
            return KnownStructureCatalog.sha256(descriptor.toString());
        }
    }

    /** One role in the workspace, including explicit non-availability. */
    public record ArtifactReference(
        ArtifactRole role,
        ArtifactStatus status,
        String artifactSchema,
        String targetContentHash,
        String detail,
        String contentHash
    ) {
        public ArtifactReference {
            role = Objects.requireNonNull(role, "role");
            status = Objects.requireNonNull(status, "status");
            artifactSchema = requireText(
                artifactSchema, "artifactSchema");
            targetContentHash = requireSha256(
                targetContentHash, "targetContentHash");
            detail = optionalText(detail, "detail");
            if (status == ArtifactStatus.AVAILABLE) {
                if (NOT_AVAILABLE_SCHEMA.equals(artifactSchema)
                        || !detail.isEmpty()) {
                    throw new IllegalArgumentException(
                        "available artifacts require a real schema and no "
                            + "unavailability detail");
                }
            } else {
                if (!NOT_AVAILABLE_SCHEMA.equals(artifactSchema)
                        || detail.isEmpty()) {
                    throw new IllegalArgumentException(
                        "unavailable artifacts require NOT_AVAILABLE schema "
                            + "and a detail");
                }
                String expectedTarget = unavailableTargetHash(
                    role, status, detail);
                if (!expectedTarget.equals(targetContentHash)) {
                    throw new IllegalArgumentException(
                        "unavailable artifact target hash mismatch");
                }
            }
            contentHash = requireSha256(contentHash, "contentHash");
            String expected = referenceHash(
                role,
                status,
                artifactSchema,
                targetContentHash,
                detail
            );
            if (!expected.equals(contentHash)) {
                throw new IllegalArgumentException(
                    "artifact reference content hash mismatch");
            }
        }

        public static ArtifactReference available(
            ArtifactRole role,
            String artifactSchema,
            String targetContentHash
        ) {
            return create(
                role,
                ArtifactStatus.AVAILABLE,
                artifactSchema,
                targetContentHash,
                ""
            );
        }

        public static ArtifactReference notProduced(ArtifactRole role) {
            return unavailable(
                role,
                ArtifactStatus.NOT_PRODUCED,
                "NOT_PRODUCED_FOR_THIS_RUN"
            );
        }

        public static ArtifactReference unavailable(
            ArtifactRole role,
            ArtifactStatus status,
            String detail
        ) {
            if (status == ArtifactStatus.AVAILABLE) {
                throw new IllegalArgumentException(
                    "use available(...) for available artifacts");
            }
            String normalizedDetail = requireText(detail, "detail");
            return create(
                role,
                status,
                NOT_AVAILABLE_SCHEMA,
                unavailableTargetHash(role, status, normalizedDetail),
                normalizedDetail
            );
        }

        private static ArtifactReference create(
            ArtifactRole role,
            ArtifactStatus status,
            String artifactSchema,
            String targetContentHash,
            String detail
        ) {
            String hash = referenceHash(
                role,
                status,
                artifactSchema,
                targetContentHash,
                detail
            );
            return new ArtifactReference(
                role,
                status,
                artifactSchema,
                targetContentHash,
                detail,
                hash
            );
        }

        private static String unavailableTargetHash(
            ArtifactRole role,
            ArtifactStatus status,
            String detail
        ) {
            StringBuilder descriptor = new StringBuilder();
            append(descriptor, SCHEMA + "/unavailable-artifact");
            append(descriptor, role.name());
            append(descriptor, status.name());
            append(descriptor, detail);
            return KnownStructureCatalog.sha256(descriptor.toString());
        }

        private static String referenceHash(
            ArtifactRole role,
            ArtifactStatus status,
            String artifactSchema,
            String targetHash,
            String detail
        ) {
            StringBuilder descriptor = new StringBuilder();
            append(descriptor, SCHEMA + "/artifact-reference");
            append(descriptor, role.name());
            append(descriptor, status.name());
            append(descriptor, artifactSchema);
            append(descriptor, targetHash);
            append(descriptor, detail);
            return KnownStructureCatalog.sha256(descriptor.toString());
        }
    }

    /** Exact repository/application revision identity used by the run. */
    public record RevisionEvidence(
        String repositoryCommit,
        String applicationRevision,
        String contentHash
    ) {
        public RevisionEvidence {
            repositoryCommit = requireText(
                repositoryCommit, "repositoryCommit");
            if (!repositoryCommit.matches("[0-9a-f]{40}")) {
                throw new IllegalArgumentException(
                    "repositoryCommit must be a lowercase Git commit SHA");
            }
            applicationRevision = requireText(
                applicationRevision, "applicationRevision");
            contentHash = requireSha256(contentHash, "contentHash");
            String expected = revisionHash(
                repositoryCommit, applicationRevision);
            if (!expected.equals(contentHash)) {
                throw new IllegalArgumentException(
                    "revision evidence content hash mismatch");
            }
        }

        public static RevisionEvidence create(
            String repositoryCommit,
            String applicationRevision
        ) {
            return new RevisionEvidence(
                repositoryCommit,
                applicationRevision,
                revisionHash(repositoryCommit, applicationRevision)
            );
        }

        private static String revisionHash(
            String repositoryCommit,
            String applicationRevision
        ) {
            StringBuilder descriptor = new StringBuilder();
            append(descriptor, SCHEMA + "/revision");
            append(descriptor, repositoryCommit);
            append(descriptor, applicationRevision);
            return KnownStructureCatalog.sha256(descriptor.toString());
        }
    }

    /** Immutable active selection; it never mutates the historical workspace. */
    public record RunSelection(
        String runId,
        String candidateId,
        String stateId,
        String edgeId,
        String occurrencePath,
        String proofObligationId,
        String contentHash
    ) {
        public RunSelection {
            runId = requireSha256(runId, "runId");
            candidateId = optionalText(candidateId, "candidateId");
            stateId = optionalText(stateId, "stateId");
            edgeId = optionalText(edgeId, "edgeId");
            occurrencePath = optionalText(
                occurrencePath, "occurrencePath");
            proofObligationId = optionalText(
                proofObligationId, "proofObligationId");
            if (List.of(
                    candidateId,
                    stateId,
                    edgeId,
                    occurrencePath,
                    proofObligationId
                ).stream().allMatch(String::isEmpty)) {
                throw new IllegalArgumentException(
                    "a run selection must select at least one object");
            }
            contentHash = requireSha256(contentHash, "contentHash");
            String expected = selectionHash(
                runId,
                candidateId,
                stateId,
                edgeId,
                occurrencePath,
                proofObligationId
            );
            if (!expected.equals(contentHash)) {
                throw new IllegalArgumentException(
                    "run selection content hash mismatch");
            }
        }

        public static RunSelection create(
            String runId,
            String candidateId,
            String stateId,
            String edgeId,
            String occurrencePath,
            String proofObligationId
        ) {
            String hash = selectionHash(
                runId,
                candidateId,
                stateId,
                edgeId,
                occurrencePath,
                proofObligationId
            );
            return new RunSelection(
                runId,
                candidateId,
                stateId,
                edgeId,
                occurrencePath,
                proofObligationId,
                hash
            );
        }

        private static String selectionHash(
            String runId,
            String candidateId,
            String stateId,
            String edgeId,
            String occurrencePath,
            String proofObligationId
        ) {
            StringBuilder descriptor = new StringBuilder();
            append(descriptor, SCHEMA + "/selection");
            append(descriptor, runId);
            append(descriptor, candidateId);
            append(descriptor, stateId);
            append(descriptor, edgeId);
            append(descriptor, occurrencePath);
            append(descriptor, proofObligationId);
            return KnownStructureCatalog.sha256(descriptor.toString());
        }
    }
}
