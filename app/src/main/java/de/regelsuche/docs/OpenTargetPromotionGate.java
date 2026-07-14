package de.regelsuche.docs;

import de.regelsuche.json.JsonWriter;
import de.regelsuche.mining.HypothesisCandidate;
import de.regelsuche.mining.OpenTargetConjectureEvaluator.EvaluationReport;
import de.regelsuche.mining.OpenTargetConjectureEvaluator.EvaluationStatus;
import de.regelsuche.mining.OpenTargetConjectureMiner.ConvergenceEvidence;
import de.regelsuche.mining.OpenTargetConjectureMiner.OpenTargetConjecture;
import de.regelsuche.mining.OpenTargetConjectureMiner.PathEvidence;
import de.regelsuche.mining.OpenTargetConjectureNoveltyChecker.NoveltyMatch;
import de.regelsuche.mining.OpenTargetConjectureNoveltyChecker.NoveltyReport;
import de.regelsuche.mining.OpenTargetConjectureProofGate.EligibilityStatus;
import de.regelsuche.mining.OpenTargetConjectureProofGate.ProofReport;
import de.regelsuche.mining.OpenTargetConjectureProofGate.ProofStatus;
import de.regelsuche.proof.ProofPolicy;
import de.regelsuche.search.strategy.BestFirstSearchStrategy.GoalStatus;
import de.regelsuche.validation.CandidateProofStatus;
import de.regelsuche.validation.CounterexampleSearchService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.TreeSet;

/**
 * Connects independently produced open-target evidence to the existing promotion
 * and public-evidence gates without merging truth, novelty, proof or interestingness.
 */
final class OpenTargetPromotionGate {
    static final String SCHEMA = "regelsuche.open-target-promotion-gate/v1";

    private final PromotionDecider promotionDecider = new PromotionDecider();
    private final PublicEvidenceGate publicEvidenceGate = new PublicEvidenceGate();

    Decision evaluate(Input input) {
        Objects.requireNonNull(input, "input");
        OpenTargetConjecture conjecture = input.conjecture();
        EvaluationReport evaluation = input.evaluation();
        NoveltyReport novelty = input.novelty();
        ProofReport proof = input.proof();
        HypothesisCandidate hypothesis = input.hypothesis();

        List<String> coreBlockers = coreBlockers(
            conjecture, evaluation, novelty, proof, hypothesis);
        List<String> assumptions = assumptions(conjecture);
        List<String> rulePath = representativeRulePath(conjecture);
        boolean evidenceExists = completeSearchEvidence(conjecture, hypothesis, rulePath);
        boolean curatedPathPresent = curatedPathPresent(conjecture);
        boolean fallbackUsed = fallbackUsed(conjecture);

        PromotionObservation observation = new PromotionObservation(
            conjecture.conjectureId(),
            input.sourceCampaign(),
            input.discoveryDate(),
            family(conjecture),
            conjecture.leftPattern(),
            conjecture.rightPattern(),
            coreBlockers.isEmpty(),
            oracleStatus(proof.proofStatus()),
            proof.backendEvidence(),
            input.ablationEvidence().ablationStatus(),
            evaluation.dynamicRuleId(),
            evaluation.provenanceHash(),
            assumptions,
            rationale(evaluation, novelty, proof),
            rulePath,
            evidenceExists,
            curatedPathPresent,
            fallbackUsed,
            rulePath.size() >= 2 || !conjecture.parameterRelations().isEmpty(),
            input.proofPolicy(),
            input.proverExecutionStatus());

        PromotionRecord baseRecord = promotionDecider.decide(
            observation, input.ablationEvidence());
        List<String> blockers = new ArrayList<>(coreBlockers);
        blockers.addAll(baseRecord.promotionBlockers());
        if (proof.proofStatus() != ProofStatus.SYMBOLICALLY_VERIFIED) {
            blockers.add("symbolic-proof=" + proof.proofStatus().name());
        }
        if (novelty.status()
                != de.regelsuche.mining.OpenTargetConjectureNoveltyChecker.NoveltyStatus
                    .NOVEL_WITHIN_PROJECT) {
            blockers.add("project-novelty=" + novelty.status().name());
        }
        List<String> orderedBlockers = blockers.stream()
            .filter(value -> value != null && !value.isBlank())
            .distinct()
            .sorted()
            .toList();

        PromotionStage stage = finalStage(baseRecord, coreBlockers.isEmpty(), orderedBlockers);
        PromotionRecord finalRecord = copyWithDecision(
            baseRecord, stage, orderedBlockers.isEmpty(), orderedBlockers);
        NoveltyStatus publicNovelty = publicNovelty(novelty);
        PublicEvidenceGate.GateDecision publicDecision =
            publicEvidenceGate.evaluate(finalRecord, publicNovelty);

        String evidenceHash = hash(canonicalMaterial(
            input,
            finalRecord,
            publicNovelty,
            orderedBlockers,
            publicDecision));
        return new Decision(
            SCHEMA,
            conjecture.conjectureId(),
            finalRecord,
            publicNovelty,
            novelty.status().name(),
            novelty.externalNoveltyStatus(),
            proof.proofStatus().name(),
            proof.formalProofStatus(),
            "NOT_EVALUATED",
            input.proverExecutionStatus(),
            orderedBlockers,
            publicDecision,
            evidenceHash);
    }

    private static List<String> coreBlockers(
        OpenTargetConjecture conjecture,
        EvaluationReport evaluation,
        NoveltyReport novelty,
        ProofReport proof,
        HypothesisCandidate hypothesis
    ) {
        List<String> blockers = new ArrayList<>();
        String candidateId = conjecture.conjectureId();
        if (!candidateId.equals(evaluation.conjectureId())) {
            blockers.add("evaluation-provenance-mismatch");
        }
        if (!candidateId.equals(novelty.conjectureId())) {
            blockers.add("novelty-provenance-mismatch");
        }
        if (!candidateId.equals(proof.conjectureId())) {
            blockers.add("proof-provenance-mismatch");
        }
        if (!candidateId.equals(hypothesis.id())) {
            blockers.add("hypothesis-provenance-mismatch");
        }

        if (!"OBSERVED_CONJECTURE".equals(conjecture.candidateStatus())
                || !"EQUIVALENCE_PRESERVING_CONVERGENT_PATHS".equals(
                    conjecture.evidenceStatus())
                || conjecture.supportCount() < 2
                || conjecture.distinctAlphaSupport() < 2
                || conjecture.evidence().size() != conjecture.supportCount()) {
            blockers.add("open-target-support-incomplete");
        }
        if (conjecture.evidence().stream()
                .anyMatch(item -> item.searchStatus() != GoalStatus.UNTARGETED)) {
            blockers.add("targeted-evidence-present");
        }
        TreeSet<String> evidenceIds = conjecture.evidence().stream()
            .map(ConvergenceEvidence::observationId)
            .collect(java.util.stream.Collectors.toCollection(TreeSet::new));
        TreeSet<String> declaredIds = new TreeSet<>(conjecture.supportingObservationIds());
        long alphaSupport = conjecture.evidence().stream()
            .map(ConvergenceEvidence::alphaPairFingerprint)
            .distinct()
            .count();
        if (evidenceIds.size() != conjecture.supportCount()
                || !evidenceIds.equals(declaredIds)
                || alphaSupport != conjecture.distinctAlphaSupport()) {
            blockers.add("open-target-support-metadata-inconsistent");
        }

        if (evaluation.status() != EvaluationStatus.ACCEPTED_FOR_PROOF
                || !evaluation.acceptedForProof()
                || !evaluation.holdoutsComplete()
                || !evaluation.allHoldoutsPassed()
                || evaluation.configuredPositiveHoldouts() < 1
                || evaluation.configuredNegativeHoldouts() < 1
                || !evaluation.blockers().isEmpty()) {
            blockers.add("candidate-evaluation-not-accepted");
        }
        if (!"COMPILED".equals(evaluation.compilationStatus())
                || evaluation.dynamicRuleId().isBlank()
                || evaluation.provenanceHash().isBlank()) {
            blockers.add("compiled-operator-provenance-missing");
        }
        if (!CounterexampleSearchService.Status.NO_COUNTEREXAMPLE_FOUND.name().equals(
                evaluation.counterexample().status())
                || evaluation.counterexample().attemptedSources().isEmpty()
                || !evaluation.counterexample().inferredAssumptions().isEmpty()
                || !evaluation.counterexample().assignments().isEmpty()) {
            blockers.add("counterexample-evidence-not-cleared");
        }

        if (!conjecture.leftPattern().equals(hypothesis.leftPattern())
                || !conjecture.rightPattern().equals(hypothesis.rightPattern())
                || !hypothesis.proofStatus().atLeast(CandidateProofStatus.VALIDATED_BY_EXAMPLES)
                || hypothesis.counterexampleSearchStatus()
                    != CounterexampleSearchService.Status.NO_COUNTEREXAMPLE_FOUND
                || !Boolean.FALSE.equals(hypothesis.counterexampleStatus())
                || hypothesis.supportingPaths().isEmpty()
                || hypothesis.supportingExpressions().size() != conjecture.supportCount()) {
            blockers.add("hypothesis-lifecycle-evidence-incomplete");
        }

        if (proof.eligibility() != EligibilityStatus.ELIGIBLE
                || !proof.proofObligationEmitted()
                || proof.obligation() == null
                || !proof.blockers().isEmpty()) {
            blockers.add("proof-obligation-ineligible");
        } else {
            if (!candidateId.equals(proof.obligation().conjectureId())
                    || proof.obligation().targetProvided()
                    || !conjecture.leftPattern().equals(proof.obligation().leftExpression())
                    || !conjecture.rightPattern().equals(proof.obligation().rightExpression())
                    || !assumptions(conjecture).equals(proof.obligation().assumptions())) {
                blockers.add("proof-obligation-provenance-mismatch");
            }
        }

        if (novelty.status()
                == de.regelsuche.mining.OpenTargetConjectureNoveltyChecker.NoveltyStatus
                    .NOVEL_WITHIN_PROJECT
                && (!novelty.matches().isEmpty()
                    || novelty.exactSignatureHash().isBlank()
                    || novelty.alphaSignatureHash().isBlank())) {
            blockers.add("novelty-evidence-inconsistent");
        }
        return List.copyOf(blockers);
    }

    private static PromotionStage finalStage(
        PromotionRecord baseRecord,
        boolean coreAccepted,
        List<String> blockers
    ) {
        if (!coreAccepted) {
            return baseRecord.stage();
        }
        if (blockers.isEmpty()) {
            return PromotionStage.PROMOTED;
        }
        if ("DISAGREE".equals(baseRecord.oracleStatus())) {
            return PromotionStage.CANDIDATE;
        }
        return PromotionStage.VALIDATED;
    }

    private static PromotionRecord copyWithDecision(
        PromotionRecord source,
        PromotionStage stage,
        boolean promotionEligible,
        List<String> blockers
    ) {
        return new PromotionRecord(
            source.candidateId(),
            source.sourceCampaign(),
            source.discoveryDate(),
            source.family(),
            stage,
            source.originalExpression(),
            source.discoveredStructure(),
            source.oracleStatus(),
            source.oracleEvidence(),
            source.ablationStatus(),
            source.sourceOperator(),
            source.sourcePack(),
            source.assumptions(),
            source.rationale(),
            source.rulePath(),
            promotionEligible,
            blockers,
            source.evidenceExists(),
            source.curatedPathPresent(),
            source.fallbackUsed(),
            source.macroOpportunity(),
            source.generatedMacroId(),
            source.reusedMacroIds(),
            source.measuredImprovement(),
            source.reuseCampaign(),
            source.ablationEvidence(),
            source.proofPolicy(),
            source.proverExecutionStatus());
    }

    private static NoveltyStatus publicNovelty(NoveltyReport novelty) {
        return switch (novelty.status()) {
            case NOVEL_WITHIN_PROJECT -> NoveltyStatus.NEW;
            case EXACT_DUPLICATE -> novelty.matches().stream()
                .map(NoveltyMatch::source)
                .anyMatch("ACTIVE_INVENTORY"::equals)
                    ? NoveltyStatus.KNOWN_RULE
                    : NoveltyStatus.DUPLICATE;
            case ALPHA_EQUIVALENT_DUPLICATE -> NoveltyStatus.ALPHA_EQUIVALENT;
            case INCONCLUSIVE_UNPARSEABLE -> NoveltyStatus.UNKNOWN;
        };
    }

    private static String oracleStatus(ProofStatus status) {
        return switch (status) {
            case SYMBOLICALLY_VERIFIED -> "AGREE";
            case REFUTED -> "DISAGREE";
            case INCONCLUSIVE, NOT_RUN -> "UNAVAILABLE";
        };
    }

    private static List<String> assumptions(OpenTargetConjecture conjecture) {
        return conjecture.evidence().stream()
            .flatMap(evidence -> evidence.paths().stream())
            .flatMap(path -> path.assumptions().stream())
            .filter(value -> value != null && !value.isBlank())
            .map(value -> value.trim().replaceAll("\\s+", " "))
            .distinct()
            .sorted()
            .toList();
    }

    private static List<String> representativeRulePath(OpenTargetConjecture conjecture) {
        return conjecture.evidence().stream()
            .flatMap(evidence -> evidence.paths().stream())
            .filter(path -> !path.ruleIds().isEmpty())
            .sorted(Comparator.comparing((PathEvidence path) ->
                    String.join("\u0001", path.ruleIds()))
                .thenComparing(PathEvidence::pathId))
            .map(PathEvidence::ruleIds)
            .map(List::copyOf)
            .findFirst()
            .orElse(List.of());
    }

    private static boolean completeSearchEvidence(
        OpenTargetConjecture conjecture,
        HypothesisCandidate hypothesis,
        List<String> representativeRulePath
    ) {
        return !representativeRulePath.isEmpty()
            && !hypothesis.supportingPaths().isEmpty()
            && conjecture.evidence().stream().allMatch(evidence ->
                evidence.paths().size() >= 2
                    && evidence.paths().stream().allMatch(path ->
                        !path.expressions().isEmpty()
                            && path.expressions().getLast().equals(evidence.outputExpression())
                            && !path.ruleIds().isEmpty()));
    }

    private static boolean curatedPathPresent(OpenTargetConjecture conjecture) {
        return conjecture.evidence().stream()
            .flatMap(evidence -> evidence.paths().stream())
            .flatMap(path -> java.util.stream.Stream.concat(
                java.util.stream.Stream.of(path.pathId()), path.ruleIds().stream()))
            .filter(Objects::nonNull)
            .map(value -> value.toLowerCase(Locale.ROOT))
            .anyMatch(value -> value.contains("curated")
                || value.contains("hardcoded")
                || value.contains("scenario-exact-path"));
    }

    private static boolean fallbackUsed(OpenTargetConjecture conjecture) {
        return conjecture.evidence().stream()
            .flatMap(evidence -> evidence.paths().stream())
            .flatMap(path -> path.ruleIds().stream())
            .filter(Objects::nonNull)
            .map(value -> value.toLowerCase(Locale.ROOT))
            .anyMatch(value -> value.contains("fallback"));
    }

    private static String family(OpenTargetConjecture conjecture) {
        TreeSet<String> families = new TreeSet<>();
        conjecture.postHocFamilies().stream()
            .filter(value -> value != null && !value.isBlank())
            .forEach(families::add);
        conjecture.evidence().stream()
            .map(ConvergenceEvidence::family)
            .filter(value -> value != null && !value.isBlank())
            .forEach(families::add);
        return families.isEmpty() ? "unclassified" : String.join(",", families);
    }

    private static String rationale(
        EvaluationReport evaluation,
        NoveltyReport novelty,
        ProofReport proof
    ) {
        return "target-free candidate; evaluation=" + evaluation.status().name()
            + "; projectNovelty=" + novelty.status().name()
            + "; symbolicProof=" + proof.proofStatus().name()
            + "; externalNovelty=" + novelty.externalNoveltyStatus();
    }

    private static String canonicalMaterial(
        Input input,
        PromotionRecord record,
        NoveltyStatus publicNovelty,
        List<String> blockers,
        PublicEvidenceGate.GateDecision publicDecision
    ) {
        return SCHEMA
            + "\ncandidate=" + record.candidateId()
            + "\ncampaign=" + record.sourceCampaign()
            + "\ndate=" + record.discoveryDate()
            + "\nstage=" + record.stage().name()
            + "\npromotionEligible=" + record.promotionEligible()
            + "\nprojectNovelty=" + input.novelty().status().name()
            + "\npublicNovelty=" + publicNovelty.name()
            + "\nexternalNovelty=" + input.novelty().externalNoveltyStatus()
            + "\nsymbolicProof=" + input.proof().proofStatus().name()
            + "\nproofEvidence=" + input.proof().evidenceHash()
            + "\nformalProof=" + input.proof().formalProofStatus()
            + "\nproofPolicy=" + input.proofPolicy().name()
            + "\nproverExecution=" + input.proverExecutionStatus()
            + "\nevaluationProvenance=" + input.evaluation().provenanceHash()
            + "\nexactSignature=" + input.novelty().exactSignatureHash()
            + "\nalphaSignature=" + input.novelty().alphaSignatureHash()
            + "\nablation=" + input.ablationEvidence().compactSummary()
            + "\nassumptions=" + String.join("\u0001", record.assumptions())
            + "\nrulePath=" + String.join("\u0001", record.rulePath())
            + "\nblockers=" + String.join("\u0001", blockers)
            + "\npublicEvidence=" + publicDecision.accepted()
            + "\npublicRejections="
            + String.join("\u0001", publicDecision.rejectionReasons());
    }

    private static String hash(String material) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(material.getBytes(StandardCharsets.UTF_8));
            return "sha256:" + java.util.HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    record Input(
        String sourceCampaign,
        String discoveryDate,
        OpenTargetConjecture conjecture,
        EvaluationReport evaluation,
        NoveltyReport novelty,
        ProofReport proof,
        HypothesisCandidate hypothesis,
        AblationEvidence ablationEvidence,
        ProofPolicy proofPolicy,
        String proverExecutionStatus
    ) {
        Input {
            requireText(sourceCampaign, "sourceCampaign");
            requireText(discoveryDate, "discoveryDate");
            try {
                LocalDate.parse(discoveryDate);
            } catch (DateTimeParseException exception) {
                throw new IllegalArgumentException(
                    "discoveryDate must use ISO-8601 YYYY-MM-DD", exception);
            }
            Objects.requireNonNull(conjecture, "conjecture");
            Objects.requireNonNull(evaluation, "evaluation");
            Objects.requireNonNull(novelty, "novelty");
            Objects.requireNonNull(proof, "proof");
            Objects.requireNonNull(hypothesis, "hypothesis");
            Objects.requireNonNull(ablationEvidence, "ablationEvidence");
            Objects.requireNonNull(proofPolicy, "proofPolicy");
            proverExecutionStatus = ProofPolicy.normaliseExecutionStatus(
                proverExecutionStatus);
        }
    }

    record Decision(
        String schema,
        String candidateId,
        PromotionRecord promotionRecord,
        NoveltyStatus publicNoveltyStatus,
        String projectNoveltyStatus,
        String externalNoveltyStatus,
        String symbolicProofStatus,
        String formalProofStatus,
        String interestingnessStatus,
        String proverExecutionStatus,
        List<String> promotionBlockers,
        PublicEvidenceGate.GateDecision publicEvidenceDecision,
        String evidenceHash
    ) {
        Decision {
            if (!SCHEMA.equals(schema)) {
                throw new IllegalArgumentException("unsupported promotion-gate schema");
            }
            requireText(candidateId, "candidateId");
            Objects.requireNonNull(promotionRecord, "promotionRecord");
            Objects.requireNonNull(publicNoveltyStatus, "publicNoveltyStatus");
            promotionBlockers = promotionBlockers == null
                ? List.of()
                : List.copyOf(promotionBlockers);
            Objects.requireNonNull(publicEvidenceDecision, "publicEvidenceDecision");
            requireText(evidenceHash, "evidenceHash");
            if (!evidenceHash.startsWith("sha256:")) {
                throw new IllegalArgumentException("evidenceHash must be SHA-256");
            }
            externalNoveltyStatus = externalNoveltyStatus == null
                ? "NOT_EVALUATED"
                : externalNoveltyStatus;
            interestingnessStatus = interestingnessStatus == null
                ? "NOT_EVALUATED"
                : interestingnessStatus;
            proverExecutionStatus = ProofPolicy.normaliseExecutionStatus(
                proverExecutionStatus);
        }

        boolean promoted() {
            return promotionRecord.stage().atLeast(PromotionStage.PROMOTED)
                && promotionRecord.promotionEligible();
        }

        boolean publicEvidenceAccepted() {
            return publicEvidenceDecision.accepted();
        }

        String toCanonicalJson() {
            AblationEvidence ablation = promotionRecord.ablationEvidence();
            return new JsonWriter().beginObject()
                .property("schema", schema)
                .property("candidateId", candidateId)
                .property("promotionStage", promotionRecord.stage().name())
                .property("promotionEligible", promotionRecord.promotionEligible())
                .property("publicNoveltyStatus", publicNoveltyStatus.name())
                .property("projectNoveltyStatus", projectNoveltyStatus)
                .property("externalNoveltyStatus", externalNoveltyStatus)
                .property("symbolicProofStatus", symbolicProofStatus)
                .property("formalProofStatus", formalProofStatus)
                .property("interestingnessStatus", interestingnessStatus)
                .property("proofPolicy", promotionRecord.proofPolicy().name())
                .property("proverExecutionStatus", proverExecutionStatus)
                .object("ablation", object -> object
                    .property("status", ablation.ablationStatus())
                    .property("improvementRatio", ablation.improvementRatio())
                    .object("withCandidate", run -> writeRun(
                        run, ablation.withCandidate()))
                    .object("withoutCandidate", run -> writeRun(
                        run, ablation.withoutCandidate())))
                .stringArray("promotionBlockers", promotionBlockers)
                .object("publicEvidence", object -> object
                    .property("accepted", publicEvidenceDecision.accepted())
                    .stringArray(
                        "rejectionReasons",
                        publicEvidenceDecision.rejectionReasons()))
                .property("evidenceHash", evidenceHash)
                .endObject()
                .toString();
        }

        private static void writeRun(
            JsonWriter json,
            AblationEvidence.RunEvidence evidence
        ) {
            json.property(
                    "success",
                    evidence.success() == null
                        ? "unknown"
                        : evidence.success().toString())
                .property("pathLength", evidence.pathLength())
                .property("statesExplored", evidence.statesExplored());
        }
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}