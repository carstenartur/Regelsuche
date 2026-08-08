package de.regelsuche.evolution;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable pre-execution contract for the public proof-carrying showcase.
 *
 * <p>The contract is parsed and checked by production Java code. JUnit exercises
 * the same constructors and cross-artifact rules; no external verifier owns
 * showcase semantics.</p>
 */
public record ProofCarryingShowcasePlan(
    String schema,
    String showcaseId,
    int issue,
    String status,
    String claimPolicy,
    String publicationGradeFlagship,
    CandidateFormation candidateFormation,
    PublicRandomness publicRandomness,
    ChallengeGenerator challengeGenerator,
    Comparison comparison,
    Acceptance acceptance,
    List<String> requiredArtifacts,
    StageStates stageStates,
    String contentHash
) {
    public static final String SCHEMA =
        "regelsuche.proof-carrying-self-improvement-showcase-plan/v1";
    public static final String SHOWCASE_ID =
        "proof-carrying-self-improvement-2026-08/v1";
    public static final String STATUS = "CONTRACT_FROZEN_NOT_RUN";
    public static final String CLAIM_POLICY =
        "SHOWCASE_CONFIRMED_DOES_NOT_IMPLY_EXPERT_REVIEW_OR_EXTERNAL_NOVELTY";
    public static final String PUBLICATION_GRADE_FLAGSHIP =
        "DEFERRED_PENDING_INDEPENDENT_REVIEW";
    public static final String GENERATOR_ID =
        "proof-carrying-symbolic-stress-ladders/v1";
    public static final String CASE_IDENTITY =
        "SHA256_CANONICAL_CASE_V1";
    public static final String DRAND_CHAIN_HASH =
        "8990e7a9aaed2ffed73dbd7092123d6f289930540d7651336225dc172e51b2ce";

    public static final List<String> FAMILIES = List.of(
        "nested-rational-cancellation",
        "factor-cancel-collect",
        "multi-stage-rational-polynomial");
    public static final List<Integer> DIFFICULTY_LEVELS =
        List.of(3, 4, 5, 6);
    public static final List<String> CONFIGURATIONS = List.of(
        "primitive-best-first",
        "preregistered-handwritten-program",
        "random-valid-program",
        "no-composition-ablation",
        "no-decision-ablation",
        "learned-program");
    public static final List<String> AUTHORITATIVE_METRICS = List.of(
        "reachedCases",
        "newlyReachedCases",
        "distinctImprovedFamilies",
        "canonicalPrimitiveWork",
        "canonicalTotalWork",
        "exploredStates",
        "generatedCandidates",
        "pathPrimitiveSteps",
        "correctnessRegressions",
        "hiddenAssumptionRegressions",
        "technicalFailures");
    public static final List<String> REQUIRED_ARTIFACTS = List.of(
        "showcase-plan.json",
        "candidate-freeze.json",
        "public-randomness-receipt.json",
        "showcase-seed-receipt.json",
        "generated-final-test.json",
        "baseline-results.json",
        "learned-program-results.json",
        "showcase-result-card.json",
        "showcase-run.json");

    public ProofCarryingShowcasePlan {
        if (!SCHEMA.equals(schema)) {
            throw new IllegalArgumentException(
                "unsupported showcase-plan schema");
        }
        if (!SHOWCASE_ID.equals(showcaseId) || issue != 597) {
            throw new IllegalArgumentException(
                "showcase identity or issue binding drift");
        }
        if (!STATUS.equals(status)) {
            throw new IllegalArgumentException(
                "committed showcase contract must remain unexecuted");
        }
        if (!CLAIM_POLICY.equals(claimPolicy)) {
            throw new IllegalArgumentException(
                "showcase claim policy drift");
        }
        if (!PUBLICATION_GRADE_FLAGSHIP.equals(
                publicationGradeFlagship)) {
            throw new IllegalArgumentException(
                "publication-grade flagship boundary drift");
        }
        candidateFormation = Objects.requireNonNull(
            candidateFormation, "candidateFormation");
        publicRandomness = Objects.requireNonNull(
            publicRandomness, "publicRandomness");
        challengeGenerator = Objects.requireNonNull(
            challengeGenerator, "challengeGenerator");
        comparison = Objects.requireNonNull(comparison, "comparison");
        acceptance = Objects.requireNonNull(acceptance, "acceptance");
        requiredArtifacts = ProofCarryingShowcaseJsonSupport
            .immutableStrings(
                requiredArtifacts,
                "requiredArtifacts",
                false,
                true);
        if (!REQUIRED_ARTIFACTS.equals(requiredArtifacts)) {
            throw new IllegalArgumentException(
                "required showcase artifacts drift");
        }
        stageStates = Objects.requireNonNull(stageStates, "stageStates");
        ProofCarryingShowcaseJsonSupport.requireSha256(
            contentHash, "contentHash");
        String expected = ProofCarryingShowcaseJsonSupport.hashPayload(
            payload(
                schema,
                showcaseId,
                issue,
                status,
                claimPolicy,
                publicationGradeFlagship,
                candidateFormation,
                publicRandomness,
                challengeGenerator,
                comparison,
                acceptance,
                requiredArtifacts,
                stageStates));
        if (!expected.equals(contentHash)) {
            throw new IllegalArgumentException(
                "showcase plan contentHash mismatch");
        }
    }

    public static ProofCarryingShowcasePlan fromCanonicalJson(String json) {
        return ProofCarryingShowcaseJsonSupport.read(
            json,
            ProofCarryingShowcasePlan.class,
            "proof-carrying showcase plan");
    }

    public static ProofCarryingShowcasePlan read(Path path) {
        return ProofCarryingShowcaseJsonSupport.read(
            path,
            ProofCarryingShowcasePlan.class,
            "proof-carrying showcase plan");
    }

    public String toCanonicalJson() {
        return ProofCarryingShowcaseJsonSupport.toCanonicalJson(this);
    }

    private static Map<String, Object> payload(
        String schema,
        String showcaseId,
        int issue,
        String status,
        String claimPolicy,
        String publicationGradeFlagship,
        CandidateFormation candidateFormation,
        PublicRandomness publicRandomness,
        ChallengeGenerator challengeGenerator,
        Comparison comparison,
        Acceptance acceptance,
        List<String> requiredArtifacts,
        StageStates stageStates
    ) {
        return ProofCarryingShowcaseJsonSupport.payload(
            "schema", schema,
            "showcaseId", showcaseId,
            "issue", issue,
            "status", status,
            "claimPolicy", claimPolicy,
            "publicationGradeFlagship", publicationGradeFlagship,
            "candidateFormation", candidateFormation,
            "publicRandomness", publicRandomness,
            "challengeGenerator", challengeGenerator,
            "comparison", comparison,
            "acceptance", acceptance,
            "requiredArtifacts", requiredArtifacts,
            "stageStates", stageStates);
    }

    public record CandidateFormation(
        List<String> visibleSplits,
        List<String> prohibitedInformation,
        boolean candidateFreezeRequiredBeforeRandomnessRound,
        RequiredCandidateProperties requiredCandidateProperties
    ) {
        private static final List<String> PROHIBITED_INFORMATION = List.of(
            "FINAL_TEST_SEED",
            "FINAL_TEST_CASES",
            "DRAND_RANDOMNESS",
            "EXPERT_LABELS",
            "EXTERNAL_NOVELTY_RESULTS");

        public CandidateFormation {
            visibleSplits = ProofCarryingShowcaseJsonSupport
                .immutableStrings(
                    visibleSplits,
                    "visibleSplits",
                    false,
                    true);
            prohibitedInformation = ProofCarryingShowcaseJsonSupport
                .immutableStrings(
                    prohibitedInformation,
                    "prohibitedInformation",
                    false,
                    true);
            if (!List.of("TRAIN").equals(visibleSplits)
                    || !PROHIBITED_INFORMATION.equals(
                        prohibitedInformation)
                    || !candidateFreezeRequiredBeforeRandomnessRound) {
                throw new IllegalArgumentException(
                    "candidate formation must remain TRAIN-only");
            }
            requiredCandidateProperties = Objects.requireNonNull(
                requiredCandidateProperties,
                "requiredCandidateProperties");
        }
    }

    public record RequiredCandidateProperties(
        boolean notSeedEquivalent,
        boolean compositionTopology,
        boolean decisionTopology,
        int minimumPrimitiveStepsOnSuccessfulPath
    ) {
        public RequiredCandidateProperties {
            if (!notSeedEquivalent
                    || !compositionTopology
                    || !decisionTopology
                    || minimumPrimitiveStepsOnSuccessfulPath != 3) {
                throw new IllegalArgumentException(
                    "required candidate properties drift");
            }
        }
    }

    public record PublicRandomness(
        String provider,
        String network,
        String chainHash,
        String apiVersion,
        String roundEndpointTemplate,
        String roundSelection,
        String signatureVerification,
        String seedDerivation,
        int minimumDelaySecondsAfterCandidateFreeze
    ) {
        public PublicRandomness {
            if (!"DRAND_LEAGUE_OF_ENTROPY".equals(provider)
                    || !"default".equals(network)
                    || !DRAND_CHAIN_HASH.equals(chainHash)
                    || !"v1".equals(apiVersion)
                    || !"/{chainHash}/public/{round}".equals(
                        roundEndpointTemplate)
                    || !"FIRST_VERIFIED_ROUND_STRICTLY_AFTER_CANDIDATE_NOT_BEFORE"
                        .equals(roundSelection)
                    || !"PINNED_DRAND_CLIENT_AND_CHAIN_INFO_REQUIRED"
                        .equals(signatureVerification)
                    || !"SHA256_DOMAIN_SEPARATED_V1".equals(
                        seedDerivation)
                    || minimumDelaySecondsAfterCandidateFreeze != 300) {
                throw new IllegalArgumentException(
                    "public-randomness contract drift");
            }
        }
    }

    public record ChallengeGenerator(
        String generatorId,
        int caseCount,
        List<Family> families,
        boolean sameGeneratedCasesForAllConfigurations,
        boolean assumptionsRetained,
        String caseIdentity,
        String manualReplacementOrPruning
    ) {
        public ChallengeGenerator {
            Objects.requireNonNull(families, "families");
            families = List.copyOf(families);
            if (!GENERATOR_ID.equals(generatorId)
                    || caseCount != 24
                    || families.size() != 3
                    || !FAMILIES.equals(families.stream()
                        .map(Family::familyId)
                        .toList())
                    || families.stream().anyMatch(
                        family -> family.caseCount() != 8
                            || !DIFFICULTY_LEVELS.equals(
                                family.difficultyLevels()))
                    || !sameGeneratedCasesForAllConfigurations
                    || !assumptionsRetained
                    || !CASE_IDENTITY.equals(caseIdentity)
                    || !"FORBIDDEN".equals(manualReplacementOrPruning)) {
                throw new IllegalArgumentException(
                    "challenge-generator contract drift");
            }
        }
    }

    public record Family(
        String familyId,
        int caseCount,
        List<Integer> difficultyLevels
    ) {
        public Family {
            familyId = ProofCarryingShowcaseJsonSupport.requireText(
                familyId, "familyId");
            difficultyLevels = ProofCarryingShowcaseJsonSupport
                .immutableIntegers(
                    difficultyLevels,
                    "difficultyLevels");
            if (!FAMILIES.contains(familyId)
                    || caseCount != 8
                    || !DIFFICULTY_LEVELS.equals(difficultyLevels)) {
                throw new IllegalArgumentException(
                    "showcase family contract drift");
            }
        }
    }

    public record Comparison(
        boolean matchedInformation,
        boolean matchedMechanicalWork,
        List<String> configurations,
        List<String> authoritativeMetrics,
        String elapsedTimeRole
    ) {
        public Comparison {
            configurations = ProofCarryingShowcaseJsonSupport
                .immutableStrings(
                    configurations,
                    "configurations",
                    false,
                    true);
            authoritativeMetrics = ProofCarryingShowcaseJsonSupport
                .immutableStrings(
                    authoritativeMetrics,
                    "authoritativeMetrics",
                    false,
                    true);
            if (!matchedInformation
                    || !matchedMechanicalWork
                    || !CONFIGURATIONS.equals(configurations)
                    || !AUTHORITATIVE_METRICS.equals(
                        authoritativeMetrics)
                    || !"ENVIRONMENT_QUALIFIED_DIAGNOSTIC_ONLY"
                        .equals(elapsedTimeRole)) {
                throw new IllegalArgumentException(
                    "showcase comparison contract drift");
            }
        }
    }

    public record Acceptance(
        int minimumImprovedCases,
        int minimumDistinctImprovedFamilies,
        int minimumNewlyReachedCases,
        int minimumMedianCanonicalWorkReductionPermille,
        int maximumCorrectnessRegressions,
        int maximumHiddenAssumptionRegressions,
        int maximumTechnicalFailures,
        String positiveRoute,
        String stretchRoute,
        String nullResultPolicy,
        int requiredCleanReproductions,
        boolean requirePinnedContainerReproduction
    ) {
        public Acceptance {
            if (minimumImprovedCases != 4
                    || minimumDistinctImprovedFamilies != 2
                    || minimumNewlyReachedCases != 2
                    || minimumMedianCanonicalWorkReductionPermille != 900
                    || maximumCorrectnessRegressions != 0
                    || maximumHiddenAssumptionRegressions != 0
                    || maximumTechnicalFailures != 0
                    || !"NEW_REACHABILITY_OR_TEN_X_MEDIAN_CANONICAL_WORK_REDUCTION"
                        .equals(positiveRoute)
                    || !"HUNDRED_X_WORK_REDUCTION_OR_TWO_ADDITIONAL_DIFFICULTY_LEVELS"
                        .equals(stretchRoute)
                    || !"COMPLETE_SHOWCASE_NULL_RESULT_WITHOUT_THRESHOLD_OR_CASE_REPAIR"
                        .equals(nullResultPolicy)
                    || requiredCleanReproductions != 2
                    || !requirePinnedContainerReproduction) {
                throw new IllegalArgumentException(
                    "showcase acceptance thresholds drift");
            }
        }
    }

    public record StageStates(
        String candidateFreeze,
        String publicRandomness,
        String generatedFinalTest,
        String execution,
        String resultCard,
        String expertReview,
        String externalNovelty
    ) {
        public StageStates {
            if (!"NOT_CREATED".equals(candidateFreeze)
                    || !"NOT_AVAILABLE".equals(publicRandomness)
                    || !"NOT_CREATED".equals(generatedFinalTest)
                    || !"NOT_RUN".equals(execution)
                    || !"NOT_CREATED".equals(resultCard)
                    || !"DEFERRED".equals(expertReview)
                    || !"NOT_EVALUATED".equals(externalNovelty)) {
                throw new IllegalArgumentException(
                    "pre-execution stage states drift");
            }
        }
    }
}
