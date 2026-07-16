package de.regelsuche.docs;

import de.regelsuche.json.JsonWriter;
import de.regelsuche.mining.HypothesisCandidate;
import de.regelsuche.mining.OpenTargetConjectureEvaluator.EvaluationReport;
import de.regelsuche.mining.OpenTargetConjectureMiner.ConvergenceEvidence;
import de.regelsuche.mining.OpenTargetConjectureMiner.OpenTargetConjecture;
import de.regelsuche.mining.OpenTargetConjectureMiner.PathEvidence;
import de.regelsuche.mining.OpenTargetConjectureNoveltyChecker.NoveltyMatch;
import de.regelsuche.mining.OpenTargetConjectureNoveltyChecker.NoveltyReport;
import de.regelsuche.mining.OpenTargetConjectureProofGate.ProofReport;
import de.regelsuche.mining.OpenTargetConjectureProofGate.ProofStatus;
import de.regelsuche.proof.ProofPolicy;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
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

        List<String> coreBlockers = OpenTargetPromotionEvidenceValidator.validate(
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
            "open-target:" + evaluation.provenanceHash(),
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
        List<String> blockers = collectBlockers(coreBlockers, baseRecord, novelty, proof);
        PromotionStage stage = finalStage(baseRecord, coreBlockers.isEmpty(), blockers);
        PromotionRecord finalRecord = copyWithDecision(
            baseRecord, stage, blockers.isEmpty(), blockers);
        NoveltyStatus publicNovelty = publicNovelty(novelty);
        PublicEvidenceGate.GateDecision publicDecision =
            publicEvidenceGate.evaluate(finalRecord, publicNovelty);
        OpenTargetPromotionProvenance provenance = provenance(input, finalRecord);

        String evidenceHash = hash(canonicalMaterial(
            input,
            finalRecord,
            publicNovelty,
            blockers,
            publicDecision,
            provenance));
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
            blockers,
            publicDecision,
            provenance,
            evidenceHash);
    }

    private static List<String> collectBlockers(
        List<String> coreBlockers,
        PromotionRecord baseRecord,
        NoveltyReport novelty,
        ProofReport proof
    ) {
        List<String> blockers = new ArrayList<>(coreBlockers);
        blockers.addAll(baseRecord.promotionBlockers());
        proof.blockers().stream()
            .filter(value -> value != null && !value.isBlank())
            .map(value -> "proof-report=" + value)
            .forEach(blockers::add);
        if (proof.proofStatus() != ProofStatus.SYMBOLICALLY_VERIFIED) {
            blockers.add("symbolic-proof=" + proof.proofStatus().name());
        }
        if (novelty.status()
                != de.regelsuche.mining.OpenTargetConjectureNoveltyChecker.NoveltyStatus
                    .NOVEL_WITHIN_PROJECT) {
            blockers.add("project-novelty=" + novelty.status().name());
        }
        return blockers.stream()
            .filter(value -> value != null && !value.isBlank())
            .distinct()
            .sorted()
            .toList();
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
            .map(path -> List.copyOf(path.ruleIds()))
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

    private static OpenTargetPromotionProvenance provenance(
        Input input,
        PromotionRecord record
    ) {
        String proofObligationHash = input.proof().obligation() == null
            ? ""
            : input.proof().obligation().contentHash();
        return new OpenTargetPromotionProvenance(
            input.sourceCampaign(),
            input.discoveryDate(),
            input.evaluation().dynamicRuleId(),
            input.evaluation().provenanceHash(),
            input.novelty().exactSignatureHash(),
            input.novelty().alphaSignatureHash(),
            input.proof().evidenceHash(),
            proofObligationHash,
            record.assumptions(),
            record.rulePath(),
            record.evidenceExists(),
            record.curatedPathPresent(),
            record.fallbackUsed());
    }

    private static String canonicalMaterial(
        Input input,
        PromotionRecord record,
        NoveltyStatus publicNovelty,
        List<String> blockers,
        PublicEvidenceGate.GateDecision publicDecision,
        OpenTargetPromotionProvenance provenance
    ) {
        return SCHEMA
            + "\ncandidate=" + record.candidateId()
            + "\nstage=" + record.stage().name()
            + "\npromotionEligible=" + record.promotionEligible()
            + "\nprojectNovelty=" + input.novelty().status().name()
            + "\npublicNovelty=" + publicNovelty.name()
            + "\nexternalNovelty=" + input.novelty().externalNoveltyStatus()
            + "\nsymbolicProof=" + input.proof().proofStatus().name()
            + "\nformalProof=" + input.proof().formalProofStatus()
            + "\nproofPolicy=" + input.proofPolicy().name()
            + "\nproverExecution=" + input.proverExecutionStatus()
            + "\ninterestingness=NOT_EVALUATED"
            + "\nablation=" + input.ablationEvidence().compactSummary()
            + "\nblockers=" + String.join("\u0001", blockers)
            + "\npublicEvidence=" + publicDecision.accepted()
            + "\npublicRejections="
            + String.join("\u0001", publicDecision.rejectionReasons())
            + "\n" + provenance.canonicalMaterial();
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
        OpenTargetPromotionProvenance provenance,
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
            Objects.requireNonNull(provenance, "provenance");
            requireText(evidenceHash, "evidenceHash");
            if (!evidenceHash.matches("sha256:[0-9a-f]{64}")) {
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
                .object("provenance", provenance::writeJson)
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
