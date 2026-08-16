package de.regelsuche.discovery.representation;

import static de.regelsuche.discovery.representation.RepresentationDiscoveryRunContractSupport.WORKSPACE_SCHEMA;
import static de.regelsuche.discovery.representation.RepresentationDiscoveryRunContractSupport.append;
import static de.regelsuche.discovery.representation.RepresentationDiscoveryRunContractSupport.optionalSha256;
import static de.regelsuche.discovery.representation.RepresentationDiscoveryRunContractSupport.optionalText;
import static de.regelsuche.discovery.representation.RepresentationDiscoveryRunContractSupport.requireSha256;
import static de.regelsuche.discovery.representation.RepresentationDiscoveryRunContractSupport.requireText;
import static de.regelsuche.discovery.representation.RepresentationDiscoveryRunContractSupport.sha256;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import de.regelsuche.discovery.representation.RepresentationDiscoveryArtifactReference.ArtifactRole;
import de.regelsuche.discovery.representation.RepresentationDiscoveryArtifactReference.ArtifactStatus;
import de.regelsuche.discovery.representation.RepresentationDiscoveryRunOutcome.TerminalState;
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
    RepresentationDiscoveryRunInput input,
    RepresentationDiscoveryRunPlan plan,
    RepresentationDiscoveryRunOutcome outcome,
    List<RepresentationDiscoveryArtifactReference> artifacts,
    RepresentationDiscoveryRevisionEvidence revisions,
    String claimBoundary,
    String contentHash
) {
    public static final String SCHEMA = WORKSPACE_SCHEMA;
    public static final String CLAIM_BOUNDARY =
        "Immutable run correlation, artifact availability and reproduction "
            + "evidence; not mathematical truth, proof, novelty, "
            + "interestingness or search superiority.";

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
        RepresentationDiscoveryRunInput input,
        RepresentationDiscoveryRunPlan plan,
        RepresentationDiscoveryRunOutcome outcome,
        List<RepresentationDiscoveryArtifactReference> artifacts,
        RepresentationDiscoveryRevisionEvidence revisions
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
        RepresentationDiscoveryRunPlan revisedPlan,
        RepresentationDiscoveryRevisionEvidence revisions
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
            RepresentationDiscoveryRunOutcome.created(),
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
        RepresentationDiscoveryRunOutcome outcome,
        List<RepresentationDiscoveryArtifactReference> artifacts,
        RepresentationDiscoveryRevisionEvidence revisions
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
    public RepresentationDiscoveryArtifactReference requireArtifact(
        ArtifactRole role,
        String expectedSchema
    ) {
        Objects.requireNonNull(role, "role");
        String schemaName = requireText(expectedSchema, "expectedSchema");
        RepresentationDiscoveryArtifactReference reference = artifacts.stream()
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
    public RepresentationDiscoveryRunSelection selection(
        String candidateId,
        String stateId,
        String edgeId,
        String occurrencePath,
        String proofObligationId
    ) {
        return RepresentationDiscoveryRunSelection.create(
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

    public static List<RepresentationDiscoveryArtifactReference>
            notProducedArtifacts() {
        return EnumSet.allOf(ArtifactRole.class).stream()
            .map(RepresentationDiscoveryArtifactReference::notProduced)
            .toList();
    }

    private static RepresentationDiscoveryRunWorkspace createInternal(
        RunRelation relation,
        String parentRunId,
        String parentPlanHash,
        String changedPlanParameter,
        RepresentationDiscoveryRunInput input,
        RepresentationDiscoveryRunPlan plan,
        RepresentationDiscoveryRunOutcome outcome,
        List<RepresentationDiscoveryArtifactReference> artifacts,
        RepresentationDiscoveryRevisionEvidence revisions
    ) {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(outcome, "outcome");
        List<RepresentationDiscoveryArtifactReference> complete =
            completeArtifacts(artifacts);
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
        RepresentationDiscoveryRunInput input,
        RepresentationDiscoveryRunPlan plan,
        RepresentationDiscoveryRunOutcome outcome,
        List<RepresentationDiscoveryArtifactReference> artifacts,
        RepresentationDiscoveryRevisionEvidence revisions
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
        return sha256(descriptor.toString());
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
            case ROOT -> validateRootRelation(
                parentRunId,
                parentPlanHash,
                changedPlanParameter
            );
            case CONTINUATION -> validateContinuationRelation(
                parentRunId,
                parentPlanHash,
                changedPlanParameter
            );
            case DUPLICATED_ONE_PARAMETER -> validateDuplicationRelation(
                parentRunId,
                parentPlanHash,
                changedPlanParameter
            );
        }
    }

    private static void validateRootRelation(
        String parentRunId,
        String parentPlanHash,
        String changedPlanParameter
    ) {
        if (!parentRunId.isEmpty()
                || !parentPlanHash.isEmpty()
                || !changedPlanParameter.isEmpty()) {
            throw new IllegalArgumentException(
                "a root run cannot carry parent evidence");
        }
    }

    private static void validateContinuationRelation(
        String parentRunId,
        String parentPlanHash,
        String changedPlanParameter
    ) {
        requireParent(parentRunId, parentPlanHash);
        if (!changedPlanParameter.isEmpty()) {
            throw new IllegalArgumentException(
                "a continuation cannot claim a plan change");
        }
    }

    private static void validateDuplicationRelation(
        String parentRunId,
        String parentPlanHash,
        String changedPlanParameter
    ) {
        requireParent(parentRunId, parentPlanHash);
        if (!RepresentationDiscoveryRunPlan.PARAMETERS.contains(
                changedPlanParameter)) {
            throw new IllegalArgumentException(
                "unsupported changed plan parameter: "
                    + changedPlanParameter);
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

    private static List<RepresentationDiscoveryArtifactReference>
            completeArtifacts(
        List<RepresentationDiscoveryArtifactReference> references
    ) {
        Objects.requireNonNull(references, "artifacts");
        List<RepresentationDiscoveryArtifactReference> sorted =
            references.stream()
                .map(reference -> Objects.requireNonNull(
                    reference, "artifact"))
                .sorted(Comparator.comparing(reference ->
                    reference.role().name()))
                .toList();
        Set<ArtifactRole> roles = new HashSet<>();
        for (RepresentationDiscoveryArtifactReference reference : sorted) {
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

    public enum RunRelation {
        ROOT,
        DUPLICATED_ONE_PARAMETER,
        CONTINUATION
    }
}
