package de.regelsuche.discovery.representation;

import static de.regelsuche.discovery.representation.RepresentationDiscoveryRunContractSupport.requireSha256;
import static de.regelsuche.discovery.representation.RepresentationDiscoveryRunContractSupport.requireText;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import de.regelsuche.util.AtomicJsonFile;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/** Derives the bounded scientific conclusion of the frozen target-free study. */
final class TargetFreeRepresentationStudyConclusion {
    static final String SCHEMA =
        "regelsuche.target-free-representation-study-conclusion/v1";
    static final String FILE_NAME =
        "representation-discovery-study-conclusion.json";
    static final String EVIDENCE_STATUS =
        "POST_FREEZE_STUDY_CONCLUSION_RETAINED";
    static final String RESULT_CLASSIFICATION =
        "INFRASTRUCTURE_AND_KNOWN_BRIDGE_EVIDENCE_ONLY";
    static final String POLICY_COMPARISON_STATUS =
        "NO_POLICY_SUCCESS_DIFFERENTIATION_AND_NO_ALL_POLICY_MATCHED_WORK_CASE";
    static final String DIFFICULTY_STATUS =
        "QUALIFIED_RESULTS_ARE_DEPTH_ONE_ONLY";
    static final String NEXT_REQUIRED_STAGE =
        "PREREGISTER_MULTI_STEP_HELD_OUT_DIFFICULTY_TRANCHE";
    static final String CLAIM_BOUNDARY =
        "This conclusion summarizes the exact frozen formation and post-freeze "
            + "qualification evidence. It does not establish external novelty, "
            + "multi-step discovery, matched-work policy superiority, global "
            + "optimality or usefulness outside the frozen matrix.";

    private static final String QUALIFIED = "QUALIFIED";
    private static final String ALL_POLICIES = "QUALIFIED_BY_ALL_POLICIES";
    private static final String SOME_POLICIES = "QUALIFIED_BY_SOME_POLICIES";
    private static final String NO_POLICIES = "NO_POLICY_QUALIFIED";
    private static final JsonMapper JSON = JsonMapper.builder()
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
        .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
        .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
        .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
        .build();

    private TargetFreeRepresentationStudyConclusion() {
    }

    static ConclusionArtifact conclude(
        Path freezeFile,
        Path qualificationFile
    ) throws IOException {
        return conclude(
            TargetFreeRepresentationCandidateFreeze.FreezeArtifact
                .fromCanonicalJson(Files.readString(
                    Objects.requireNonNull(freezeFile, "freezeFile"),
                    StandardCharsets.UTF_8
                )),
            TargetFreeRepresentationPostFreezeQualification
                .QualificationArtifact.fromCanonicalJson(Files.readString(
                    Objects.requireNonNull(
                        qualificationFile,
                        "qualificationFile"
                    ),
                    StandardCharsets.UTF_8
                ))
        );
    }

    static ConclusionArtifact conclude(
        TargetFreeRepresentationCandidateFreeze.FreezeArtifact freeze,
        TargetFreeRepresentationPostFreezeQualification
            .QualificationArtifact qualification
    ) {
        Objects.requireNonNull(freeze, "freeze");
        Objects.requireNonNull(qualification, "qualification");
        requireAuthority(freeze, qualification);

        List<RowStats> rows = new ArrayList<>();
        for (int index = 0; index < freeze.content().entries().size(); index++) {
            rows.add(row(
                freeze.content().entries().get(index),
                qualification.content().entries().get(index)
            ));
        }
        List<CaseConclusion> cases = groupCases(rows);
        List<PolicyConclusion> policies = groupPolicies(rows);
        Summary summary = Summary.from(cases, policies);
        Decision decision = new Decision(
            RESULT_CLASSIFICATION,
            summary.policyDifferentiatingCaseCount() == 0
                    && summary.allPolicyMatchedWorkCaseCount() == 0
                ? POLICY_COMPARISON_STATUS
                : "POLICY_DIFFERENCE_REQUIRES_SEPARATE_INTERPRETATION",
            summary.qualifiedCandidatesAtDepthAtLeastThree() == 0
                ? DIFFICULTY_STATUS
                : "MULTI_STEP_QUALIFIED_RESULT_PRESENT",
            NEXT_REQUIRED_STAGE
        );
        return ConclusionArtifact.create(new ConclusionContent(
            SCHEMA,
            EVIDENCE_STATUS,
            freeze.content().repositoryRevision(),
            freeze.contentHash(),
            qualification.contentHash(),
            cases,
            policies,
            summary,
            decision,
            CLAIM_BOUNDARY
        ));
    }

    static ConclusionArtifact write(
        Path outputDirectory,
        Path freezeFile,
        Path qualificationFile
    ) throws IOException {
        Path directory = Objects.requireNonNull(
            outputDirectory,
            "outputDirectory"
        ).toAbsolutePath().normalize();
        Files.createDirectories(directory);
        ConclusionArtifact artifact = conclude(freezeFile, qualificationFile);
        Path target = directory.resolve(FILE_NAME);
        String canonical = artifact.toCanonicalJson();
        AtomicJsonFile.writeUtf8(target, canonical);
        if (!canonical.equals(Files.readString(
                target,
                StandardCharsets.UTF_8
            ))
                || !artifact.equals(
                    ConclusionArtifact.fromCanonicalJson(canonical))) {
            throw new IllegalStateException(
                "target-free study conclusion changed while writing");
        }
        return artifact;
    }

    public static void main(String[] args) throws IOException {
        if (args.length != 3) {
            throw new IllegalArgumentException(
                "usage: <candidate-freeze-file> "
                    + "<post-freeze-qualification-file> "
                    + "<output-directory>"
            );
        }
        ConclusionArtifact artifact = write(
            Path.of(args[2]),
            Path.of(args[0]),
            Path.of(args[1])
        );
        Summary summary = artifact.content().summary();
        System.out.println(
            "targetFreeStudyConclusionHash=" + artifact.contentHash());
        System.out.println(
            "targetFreeStudyQualifiedEntries="
                + summary.qualifiedEntries());
        System.out.println(
            "targetFreeStudyPolicyDifferentiatingCases="
                + summary.policyDifferentiatingCaseCount());
        System.out.println(
            "targetFreeStudyQualifiedDepthAtLeastThree="
                + summary.qualifiedCandidatesAtDepthAtLeastThree());
    }

    private static RowStats row(
        TargetFreeRepresentationCandidateFreeze.ExecutionEntry frozen,
        TargetFreeRepresentationPostFreezeQualification
            .QualificationEntry qualification
    ) {
        requireEntryBinding(frozen, qualification);
        Map<String, TargetFreeRepresentationCandidateFreeze.CandidateEvidence>
            candidates = new TreeMap<>();
        frozen.candidates().forEach(candidate -> {
            if (candidates.put(candidate.candidateHash(), candidate) != null) {
                throw new IllegalArgumentException(
                    "duplicate frozen candidate hash");
            }
        });

        int minimumDepth = Integer.MAX_VALUE;
        int maximumDepth = 0;
        int depthOne = 0;
        int depthAtLeastThree = 0;
        int assumptionViolations = 0;
        int directRuleLeaks = 0;
        for (var result : qualification.candidates()) {
            var candidate = Objects.requireNonNull(
                candidates.remove(result.candidateHash()),
                "frozen candidate " + result.candidateHash()
            );
            if (!candidate.expression().equals(result.expression())
                    || !candidate.assumptions().equals(
                        result.assumptions())) {
                throw new IllegalArgumentException(
                    "qualified candidate differs from frozen candidate");
            }
            if (result.qualified()) {
                minimumDepth = Math.min(minimumDepth, candidate.depth());
                maximumDepth = Math.max(maximumDepth, candidate.depth());
                depthOne += candidate.depth() == 1 ? 1 : 0;
                depthAtLeastThree += candidate.depth() >= 3 ? 1 : 0;
            }
            if (result.forbiddenOutcomes().contains("ASSUMPTION_DROPPED")
                    || result.disqualificationReasons().contains(
                        "REQUIRED_ASSUMPTION_MISSING")) {
                assumptionViolations++;
            }
            if (result.forbiddenOutcomes().contains(
                    "FORMATION_USED_POST_FREEZE_RULE")) {
                directRuleLeaks++;
            }
        }
        if (!candidates.isEmpty()) {
            throw new IllegalArgumentException(
                "qualification omitted frozen candidates");
        }
        return new RowStats(
            frozen.caseId(),
            frozen.policyId(),
            qualification.status(),
            frozen.candidateCount(),
            qualification.qualifyingCandidateCount(),
            minimumDepth == Integer.MAX_VALUE ? 0 : minimumDepth,
            maximumDepth,
            depthOne,
            depthAtLeastThree,
            frozen.work().engineCalls(),
            frozen.work().generatedPrimitiveSteps(),
            assumptionViolations,
            directRuleLeaks
        );
    }

    private static void requireAuthority(
        TargetFreeRepresentationCandidateFreeze.FreezeArtifact freeze,
        TargetFreeRepresentationPostFreezeQualification
            .QualificationArtifact qualification
    ) {
        var frozen = freeze.content();
        var classified = qualification.content();
        if (!classified.repositoryRevision().equals(
                frozen.repositoryRevision())
                || !classified.evaluationPlanHash().equals(
                    frozen.evaluationPlanHash())
                || !classified.candidateFreezeHash().equals(
                    freeze.contentHash())
                || classified.summary().configuredEntries()
                    != frozen.summary().configuredEntryCount()
                || classified.summary().evaluatedCandidates()
                    != frozen.summary().candidateCount()
                || classified.entries().size() != frozen.entries().size()) {
            throw new IllegalArgumentException(
                "qualification is not bound to the supplied freeze");
        }
    }

    private static void requireEntryBinding(
        TargetFreeRepresentationCandidateFreeze.ExecutionEntry frozen,
        TargetFreeRepresentationPostFreezeQualification
            .QualificationEntry qualification
    ) {
        if (frozen.sequence() != qualification.sequence()
                || !frozen.configurationId().equals(
                    qualification.configurationId())
                || !frozen.caseId().equals(qualification.caseId())
                || !frozen.policyId().equals(qualification.policyId())
                || !frozen.candidateBatchHash().equals(
                    qualification.candidateBatchHash())
                || !frozen.candidateSetHash().equals(
                    qualification.candidateSetHash())
                || !frozen.candidateFreezeReceiptHash().equals(
                    qualification.candidateFreezeReceiptHash())
                || !frozen.work().contentHash().equals(
                    qualification.workLedgerHash())
                || !frozen.workAuthorityHash().equals(
                    qualification.workAuthorityHash())) {
            throw new IllegalArgumentException(
                "qualification entry changed frozen evidence");
        }
    }

    private static List<CaseConclusion> groupCases(List<RowStats> rows) {
        Map<String, List<RowStats>> grouped = new LinkedHashMap<>();
        rows.forEach(row -> grouped.computeIfAbsent(
            row.caseId(),
            ignored -> new ArrayList<>()
        ).add(row));
        return grouped.entrySet().stream()
            .map(entry -> CaseConclusion.from(
                entry.getKey(),
                entry.getValue()
            ))
            .toList();
    }

    private static List<PolicyConclusion> groupPolicies(List<RowStats> rows) {
        Map<String, List<RowStats>> grouped = new LinkedHashMap<>();
        rows.forEach(row -> grouped.computeIfAbsent(
            row.policyId(),
            ignored -> new ArrayList<>()
        ).add(row));
        return grouped.entrySet().stream()
            .map(entry -> PolicyConclusion.from(
                entry.getKey(),
                entry.getValue()
            ))
            .toList();
    }

    private static String json(Object value) {
        try {
            return JSON.writeValueAsString(
                Objects.requireNonNull(value, "value"));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(
                "cannot render target-free study conclusion",
                exception
            );
        }
    }

    private record RowStats(
        String caseId,
        String policyId,
        String qualificationStatus,
        int evaluatedCandidates,
        int qualifiedCandidates,
        int minimumQualifiedDepth,
        int maximumQualifiedDepth,
        int qualifiedCandidatesAtDepthOne,
        int qualifiedCandidatesAtDepthAtLeastThree,
        int engineCalls,
        int generatedPrimitiveSteps,
        int assumptionViolationCandidates,
        int directRuleLeakCandidates
    ) {
        private RowStats {
            caseId = requireText(caseId, "caseId");
            policyId = requireText(policyId, "policyId");
            qualificationStatus = requireText(
                qualificationStatus,
                "qualificationStatus"
            );
            if (evaluatedCandidates < 0
                    || qualifiedCandidates < 0
                    || qualifiedCandidates > evaluatedCandidates
                    || minimumQualifiedDepth < 0
                    || maximumQualifiedDepth < minimumQualifiedDepth
                    || qualifiedCandidatesAtDepthOne < 0
                    || qualifiedCandidatesAtDepthAtLeastThree < 0
                    || engineCalls < 0
                    || generatedPrimitiveSteps < 0
                    || assumptionViolationCandidates < 0
                    || directRuleLeakCandidates < 0
                    || (qualifiedCandidates == 0
                        && (minimumQualifiedDepth != 0
                            || maximumQualifiedDepth != 0))
                    || (QUALIFIED.equals(qualificationStatus)
                        != (qualifiedCandidates > 0))) {
                throw new IllegalArgumentException(
                    "row statistics do not balance");
            }
        }

        boolean qualified() {
            return qualifiedCandidates > 0;
        }
    }

    record CaseConclusion(
        String caseId,
        int configuredPolicies,
        int qualifiedPolicies,
        int evaluatedCandidates,
        int qualifiedCandidates,
        int minimumQualifiedDepth,
        int maximumQualifiedDepth,
        int qualifiedCandidatesAtDepthOne,
        int qualifiedCandidatesAtDepthAtLeastThree,
        int assumptionViolationCandidates,
        int directRuleLeakCandidates,
        boolean allPolicyWorkMatched,
        String status
    ) {
        static CaseConclusion from(String caseId, List<RowStats> rows) {
            int qualifiedPolicies = Math.toIntExact(rows.stream()
                .filter(RowStats::qualified).count());
            Set<String> work = new TreeSet<>();
            rows.forEach(row -> work.add(
                row.engineCalls() + ":" + row.generatedPrimitiveSteps()));
            return new CaseConclusion(
                caseId,
                rows.size(),
                qualifiedPolicies,
                rows.stream().mapToInt(
                    RowStats::evaluatedCandidates).sum(),
                rows.stream().mapToInt(
                    RowStats::qualifiedCandidates).sum(),
                rows.stream().mapToInt(RowStats::minimumQualifiedDepth)
                    .filter(value -> value > 0).min().orElse(0),
                rows.stream().mapToInt(RowStats::maximumQualifiedDepth)
                    .max().orElse(0),
                rows.stream().mapToInt(
                    RowStats::qualifiedCandidatesAtDepthOne).sum(),
                rows.stream().mapToInt(
                    RowStats::qualifiedCandidatesAtDepthAtLeastThree).sum(),
                rows.stream().mapToInt(
                    RowStats::assumptionViolationCandidates).sum(),
                rows.stream().mapToInt(
                    RowStats::directRuleLeakCandidates).sum(),
                work.size() == 1,
                qualifiedPolicies == rows.size()
                    ? ALL_POLICIES
                    : qualifiedPolicies == 0
                        ? NO_POLICIES
                        : SOME_POLICIES
            );
        }

        CaseConclusion {
            caseId = requireText(caseId, "caseId");
            status = requireText(status, "status");
            if (configuredPolicies < 1
                    || qualifiedPolicies < 0
                    || qualifiedPolicies > configuredPolicies
                    || evaluatedCandidates < 0
                    || qualifiedCandidates < 0
                    || minimumQualifiedDepth < 0
                    || maximumQualifiedDepth < minimumQualifiedDepth
                    || qualifiedCandidatesAtDepthOne < 0
                    || qualifiedCandidatesAtDepthAtLeastThree < 0
                    || assumptionViolationCandidates < 0
                    || directRuleLeakCandidates < 0
                    || !Set.of(
                        ALL_POLICIES,
                        SOME_POLICIES,
                        NO_POLICIES
                    ).contains(status)
                    || (ALL_POLICIES.equals(status)
                        != (qualifiedPolicies == configuredPolicies))
                    || (NO_POLICIES.equals(status)
                        != (qualifiedPolicies == 0))) {
                throw new IllegalArgumentException(
                    "case conclusion does not balance");
            }
        }
    }

    record PolicyConclusion(
        String policyId,
        int configuredCases,
        int qualifiedCases,
        int evaluatedCandidates,
        int qualifiedCandidates,
        int engineCalls,
        int generatedPrimitiveSteps
    ) {
        static PolicyConclusion from(String policyId, List<RowStats> rows) {
            return new PolicyConclusion(
                policyId,
                rows.size(),
                Math.toIntExact(rows.stream()
                    .filter(RowStats::qualified).count()),
                rows.stream().mapToInt(
                    RowStats::evaluatedCandidates).sum(),
                rows.stream().mapToInt(
                    RowStats::qualifiedCandidates).sum(),
                rows.stream().mapToInt(RowStats::engineCalls).sum(),
                rows.stream().mapToInt(
                    RowStats::generatedPrimitiveSteps).sum()
            );
        }

        PolicyConclusion {
            policyId = requireText(policyId, "policyId");
            if (configuredCases < 1
                    || qualifiedCases < 0
                    || qualifiedCases > configuredCases
                    || evaluatedCandidates < 0
                    || qualifiedCandidates < 0
                    || engineCalls < 0
                    || generatedPrimitiveSteps < 0) {
                throw new IllegalArgumentException(
                    "policy conclusion does not balance");
            }
        }
    }

    record Summary(
        int configuredEntries,
        int evaluatedCandidates,
        int qualifiedEntries,
        int qualifiedCandidates,
        int configuredCases,
        int uniformlyQualifiedCaseCount,
        int uniformlyUnqualifiedCaseCount,
        int policyDifferentiatingCaseCount,
        int configuredPolicies,
        int allPolicyMatchedWorkCaseCount,
        int qualifiedCandidatesAtDepthOne,
        int qualifiedCandidatesAtDepthAtLeastThree,
        int assumptionViolationCandidates,
        int directRuleLeakCandidates
    ) {
        static Summary from(
            List<CaseConclusion> cases,
            List<PolicyConclusion> policies
        ) {
            Summary summary = new Summary(
                cases.stream().mapToInt(
                    CaseConclusion::configuredPolicies).sum(),
                cases.stream().mapToInt(
                    CaseConclusion::evaluatedCandidates).sum(),
                cases.stream().mapToInt(
                    CaseConclusion::qualifiedPolicies).sum(),
                cases.stream().mapToInt(
                    CaseConclusion::qualifiedCandidates).sum(),
                cases.size(),
                Math.toIntExact(cases.stream()
                    .filter(value -> ALL_POLICIES.equals(value.status()))
                    .count()),
                Math.toIntExact(cases.stream()
                    .filter(value -> NO_POLICIES.equals(value.status()))
                    .count()),
                Math.toIntExact(cases.stream()
                    .filter(value -> SOME_POLICIES.equals(value.status()))
                    .count()),
                policies.size(),
                Math.toIntExact(cases.stream()
                    .filter(CaseConclusion::allPolicyWorkMatched).count()),
                cases.stream().mapToInt(
                    CaseConclusion::qualifiedCandidatesAtDepthOne).sum(),
                cases.stream().mapToInt(
                    CaseConclusion::qualifiedCandidatesAtDepthAtLeastThree)
                    .sum(),
                cases.stream().mapToInt(
                    CaseConclusion::assumptionViolationCandidates).sum(),
                cases.stream().mapToInt(
                    CaseConclusion::directRuleLeakCandidates).sum()
            );
            int policyCases = policies.stream().mapToInt(
                PolicyConclusion::configuredCases).sum();
            int policyQualified = policies.stream().mapToInt(
                PolicyConclusion::qualifiedCases).sum();
            int policyCandidates = policies.stream().mapToInt(
                PolicyConclusion::evaluatedCandidates).sum();
            int policyQualifiedCandidates = policies.stream().mapToInt(
                PolicyConclusion::qualifiedCandidates).sum();
            if (policyCases != summary.configuredEntries()
                    || policyQualified != summary.qualifiedEntries()
                    || policyCandidates != summary.evaluatedCandidates()
                    || policyQualifiedCandidates
                        != summary.qualifiedCandidates()) {
                throw new IllegalArgumentException(
                    "case and policy conclusions disagree");
            }
            return summary;
        }

        Summary {
            if (configuredEntries < 1
                    || evaluatedCandidates < 0
                    || qualifiedEntries < 0
                    || qualifiedEntries > configuredEntries
                    || qualifiedCandidates < 0
                    || configuredCases < 1
                    || uniformlyQualifiedCaseCount < 0
                    || uniformlyUnqualifiedCaseCount < 0
                    || policyDifferentiatingCaseCount < 0
                    || uniformlyQualifiedCaseCount
                        + uniformlyUnqualifiedCaseCount
                        + policyDifferentiatingCaseCount
                        != configuredCases
                    || configuredPolicies < 1
                    || allPolicyMatchedWorkCaseCount < 0
                    || allPolicyMatchedWorkCaseCount > configuredCases
                    || qualifiedCandidatesAtDepthOne < 0
                    || qualifiedCandidatesAtDepthAtLeastThree < 0
                    || assumptionViolationCandidates < 0
                    || directRuleLeakCandidates < 0) {
                throw new IllegalArgumentException(
                    "study conclusion summary does not balance");
            }
        }
    }

    record Decision(
        String evidenceClassification,
        String policyComparisonStatus,
        String difficultyStatus,
        String nextRequiredStage
    ) {
        Decision {
            evidenceClassification = requireText(
                evidenceClassification,
                "evidenceClassification"
            );
            policyComparisonStatus = requireText(
                policyComparisonStatus,
                "policyComparisonStatus"
            );
            difficultyStatus = requireText(
                difficultyStatus,
                "difficultyStatus"
            );
            nextRequiredStage = requireText(
                nextRequiredStage,
                "nextRequiredStage"
            );
        }
    }

    record ConclusionContent(
        String schema,
        String evidenceStatus,
        String repositoryRevision,
        String candidateFreezeHash,
        String postFreezeQualificationHash,
        List<CaseConclusion> cases,
        List<PolicyConclusion> policies,
        Summary summary,
        Decision decision,
        String claimBoundary
    ) {
        ConclusionContent {
            cases = List.copyOf(Objects.requireNonNull(cases, "cases"));
            policies = List.copyOf(
                Objects.requireNonNull(policies, "policies"));
            if (!SCHEMA.equals(schema)
                    || !EVIDENCE_STATUS.equals(evidenceStatus)
                    || !repositoryRevision.matches("[0-9a-f]{40}")
                    || cases.isEmpty()
                    || policies.isEmpty()
                    || !Objects.requireNonNull(summary, "summary").equals(
                        Summary.from(cases, policies))) {
                throw new IllegalArgumentException(
                    "study conclusion content does not balance");
            }
            requireSha256(candidateFreezeHash, "candidateFreezeHash");
            requireSha256(
                postFreezeQualificationHash,
                "postFreezeQualificationHash"
            );
            if (cases.stream().map(CaseConclusion::caseId)
                    .distinct().count() != cases.size()
                    || policies.stream().map(PolicyConclusion::policyId)
                        .distinct().count() != policies.size()) {
                throw new IllegalArgumentException(
                    "study conclusion identities are not unique");
            }
            decision = Objects.requireNonNull(decision, "decision");
            claimBoundary = requireText(claimBoundary, "claimBoundary");
        }
    }

    record ConclusionArtifact(
        ConclusionContent content,
        String contentHash
    ) {
        ConclusionArtifact {
            content = Objects.requireNonNull(content, "content");
            contentHash = requireSha256(contentHash, "contentHash");
            if (!KnownStructureCatalog.sha256(json(content))
                    .equals(contentHash)) {
                throw new IllegalArgumentException(
                    "study conclusion content hash mismatch");
            }
        }

        static ConclusionArtifact create(ConclusionContent content) {
            return new ConclusionArtifact(
                content,
                KnownStructureCatalog.sha256(json(content))
            );
        }

        String toCanonicalJson() {
            return json(this);
        }

        static ConclusionArtifact fromCanonicalJson(String source) {
            try {
                ConclusionArtifact artifact = JSON.readValue(
                    Objects.requireNonNull(source, "source"),
                    ConclusionArtifact.class
                );
                if (!artifact.toCanonicalJson().equals(source)) {
                    throw new IllegalArgumentException(
                        "study conclusion JSON is not canonical");
                }
                return artifact;
            } catch (JsonProcessingException exception) {
                throw new IllegalArgumentException(
                    "invalid target-free study conclusion JSON",
                    exception
                );
            }
        }
    }
}
