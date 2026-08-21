package de.regelsuche.discovery.representation;

import static de.regelsuche.discovery.representation.RepresentationDiscoveryRunContractSupport.requireSha256;
import static de.regelsuche.discovery.representation.RepresentationDiscoveryRunContractSupport.requireText;
import static de.regelsuche.discovery.representation.TargetFreeHeldOutMatrixRunner.JSON;
import static de.regelsuche.discovery.representation.TargetFreeHeldOutMatrixRunner.PLAN_SCHEMA;
import static de.regelsuche.discovery.representation.TargetFreeHeldOutMatrixRunner.PLAN_STATUS;
import static de.regelsuche.discovery.representation.TargetFreeHeldOutMatrixRunner.QUALIFICATION_NOT_DISCLOSED;
import static de.regelsuche.discovery.representation.TargetFreeHeldOutMatrixRunner.canonical;

import com.fasterxml.jackson.core.JsonProcessingException;
import de.regelsuche.discovery.representation.RepresentationDiscoveryInformationBoundary.Track;
import de.regelsuche.knowledge.RuleProfile;
import java.util.List;
import java.util.Objects;
import java.util.TreeSet;

record PreExecutionValidity(
    String directMacroEdgeToReference,
    String directPrimitiveEdgeToReference,
    int distractorRulePackPerPositiveCaseMinimum,
    int maximumPositiveWitnessDepth,
    int minimumPositiveWitnessDepth,
    int negativeControlCaseCount,
    int positiveCaseCount,
    int temporaryComplexityValleyCaseCountMinimum
) {
}

record Preregistration(
    String schema,
    String evidenceStatus,
    String formationResource,
    String formationSha256,
    long formationByteLength,
    String qualificationResource,
    String qualificationSha256,
    long qualificationByteLength,
    int configuredCaseCount,
    int configuredPolicyCount,
    int configuredCheckpointCount,
    int configuredEntryCount,
    String requiredEntryStatus,
    String requiredTerminalReason,
    PreExecutionValidity preExecutionValidity,
    String claimBoundary
) {
}

record Formation(
    String schema,
    String evidenceStatus,
    String informationBoundary,
    List<CaseSpec> cases,
    List<PolicySpec> policies,
    WorkMatching workMatching
) {
    public Formation {
        cases = List.copyOf(cases);
        policies = List.copyOf(policies);
    }
}

record CaseSpec(
    String id,
    String sourceExpression,
    List<String> assumptions,
    Track informationTrack,
    RuleProfile ruleProfile,
    List<String> enabledRulePackIds,
    List<String> distractorRulePackIds,
    WorkBudget budget
) {
    public CaseSpec {
        id = requireText(id, "case id");
        sourceExpression = requireText(
            sourceExpression, "sourceExpression");
        assumptions = List.copyOf(new TreeSet<>(assumptions));
        Objects.requireNonNull(informationTrack, "informationTrack");
        Objects.requireNonNull(ruleProfile, "ruleProfile");
        enabledRulePackIds = List.copyOf(new TreeSet<>(
            enabledRulePackIds));
        distractorRulePackIds = List.copyOf(new TreeSet<>(
            distractorRulePackIds));
        Objects.requireNonNull(budget, "budget");
    }
}

record WorkBudget(
    int maxDepth,
    int maxExploredStates,
    int maxRetainedStates,
    int maxGeneratedTransitions,
    int maxCandidatesPerState,
    int maxAstSizeIncreasePerStep,
    int significantImprovementThreshold,
    int maxExpandingSteps,
    int beamWidth
) {
    public WorkBudget {
        if (maxDepth < 1 || maxExploredStates < 1
                || maxRetainedStates < 1
                || maxGeneratedTransitions < 1
                || maxCandidatesPerState < 1
                || maxAstSizeIncreasePerStep < 0
                || significantImprovementThreshold < 1
                || maxExpandingSteps < 0
                || maxExpandingSteps > maxDepth
                || beamWidth < 1
                || beamWidth > maxRetainedStates) {
            throw new IllegalArgumentException(
                "invalid held-out work budget");
        }
    }
}

record PolicySpec(
    String id,
    String adapter,
    String adapterConstructor,
    String adapterInterface,
    String initialAssumptionPolicy,
    long deterministicSeed,
    String selectionBoundary
) {
    public PolicySpec {
        id = requireText(id, "policy id");
        adapter = requireText(adapter, "adapter");
        adapterConstructor = requireText(
            adapterConstructor, "adapterConstructor");
        adapterInterface = requireText(
            adapterInterface, "adapterInterface");
        initialAssumptionPolicy = requireText(
            initialAssumptionPolicy, "initialAssumptionPolicy");
        selectionBoundary = requireText(
            selectionBoundary, "selectionBoundary");
    }
}

record WorkMatching(
    String authority,
    List<Integer> checkpoints,
    String comparisonEligibility,
    String earlyExhaustionStatus,
    String engineCalls,
    List<String> entryIdentityDimensions,
    String generatedTransitions,
    String stopSemantics,
    String wallClock
) {
    public WorkMatching {
        checkpoints = List.copyOf(checkpoints);
        entryIdentityDimensions = List.copyOf(
            entryIdentityDimensions);
    }
}

record PlanRow(
    int sequence,
    String configurationId,
    String caseId,
    String policyId,
    int checkpoint,
    String status,
    String terminalReason
) {
    public PlanRow {
        if (sequence < 1 || checkpoint < 1) {
            throw new IllegalArgumentException(
                "invalid plan-row sequence or checkpoint");
        }
        configurationId = requireSha256(
            configurationId, "configurationId");
        caseId = requireText(caseId, "caseId");
        policyId = requireText(policyId, "policyId");
        status = requireText(status, "status");
        terminalReason = requireText(
            terminalReason, "terminalReason");
    }
}

record PlanContent(
    String schema,
    String evidenceStatus,
    String repositoryRevision,
    String preregistrationResource,
    String preregistrationHash,
    String formationResource,
    String formationHash,
    long formationByteLength,
    String qualificationResource,
    String qualificationHash,
    long qualificationByteLength,
    String qualificationDisclosure,
    String informationBoundary,
    WorkMatching workMatching,
    List<CaseSpec> cases,
    List<PolicySpec> policies,
    List<PlanRow> rows,
    String claimBoundary
) {
    public PlanContent {
        if (!PLAN_SCHEMA.equals(schema)
                || !PLAN_STATUS.equals(evidenceStatus)
                || !repositoryRevision.matches("[0-9a-f]{40}")
                || !QUALIFICATION_NOT_DISCLOSED.equals(
                    qualificationDisclosure)
                || formationByteLength < 1
                || qualificationByteLength < 1) {
            throw new IllegalArgumentException(
                "held-out plan content is invalid");
        }
        preregistrationHash = requireSha256(
            preregistrationHash, "preregistrationHash");
        formationHash = requireSha256(
            formationHash, "formationHash");
        qualificationHash = requireSha256(
            qualificationHash, "qualificationHash");
        cases = List.copyOf(cases);
        policies = List.copyOf(policies);
        rows = List.copyOf(rows);
        if (rows.size() != 144) {
            throw new IllegalArgumentException(
                "held-out plan must contain 144 rows");
        }
        for (int index = 0; index < rows.size(); index++) {
            if (rows.get(index).sequence() != index + 1) {
                throw new IllegalArgumentException(
                    "plan row sequence is not canonical");
            }
        }
        claimBoundary = requireText(
            claimBoundary, "claimBoundary");
    }
}

record PlanArtifact(
    PlanContent content,
    String contentHash
) {
    public PlanArtifact {
        Objects.requireNonNull(content, "content");
        contentHash = requireSha256(contentHash, "contentHash");
        if (!KnownStructureCatalog.sha256(canonical(content))
                .equals(contentHash)) {
            throw new IllegalArgumentException(
                "plan content hash mismatch");
        }
    }

    static PlanArtifact create(PlanContent content) {
        return new PlanArtifact(
            content,
            KnownStructureCatalog.sha256(canonical(content)));
    }

    public String toCanonicalJson() {
        return canonical(this);
    }

    public static PlanArtifact fromCanonicalJson(String source) {
        try {
            PlanArtifact artifact = JSON.readValue(
                Objects.requireNonNull(source, "source"),
                PlanArtifact.class);
            if (!artifact.toCanonicalJson().equals(source)) {
                throw new IllegalArgumentException(
                    "plan JSON is not canonical");
            }
            return artifact;
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException(
                "invalid held-out plan JSON", exception);
        }
    }
}
