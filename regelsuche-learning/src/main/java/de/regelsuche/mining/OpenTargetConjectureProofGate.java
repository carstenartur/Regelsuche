package de.regelsuche.mining;

import de.regelsuche.equivalence.EquivalenceService;
import de.regelsuche.equivalence.SymPyEquivalenceService;
import de.regelsuche.json.JsonWriter;
import de.regelsuche.mining.OpenTargetConjectureEvaluator.EvaluationReport;
import de.regelsuche.mining.OpenTargetConjectureEvaluator.EvaluationStatus;
import de.regelsuche.mining.OpenTargetConjectureMiner.OpenTargetConjecture;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Emits and checks a proof obligation only after an open-target candidate passed
 * compilation, fresh holdouts and counterexample search.
 *
 * <p>The oracle receives an already-formed relation. It cannot construct the
 * search path, conjecture or ranking, and its result never flows back into mining.</p>
 */
public final class OpenTargetConjectureProofGate {
    public static final String REPORT_SCHEMA = "regelsuche.open-target-conjecture-proof/v1";
    public static final String OBLIGATION_SCHEMA =
        "regelsuche.open-target-conjecture-proof-obligation/v1";

    private final EquivalenceService oracle;
    private final String backendId;

    public OpenTargetConjectureProofGate() {
        this(new SymPyEquivalenceService(), "sympy-equivalence-v1");
    }

    OpenTargetConjectureProofGate(EquivalenceService oracle, String backendId) {
        this.oracle = Objects.requireNonNull(oracle, "oracle");
        if (backendId == null || backendId.isBlank()) {
            throw new IllegalArgumentException("backendId must not be blank");
        }
        this.backendId = backendId;
    }

    public ProofReport evaluate(
        OpenTargetConjecture conjecture,
        EvaluationReport evaluation
    ) {
        Objects.requireNonNull(conjecture, "conjecture");
        Objects.requireNonNull(evaluation, "evaluation");
        List<String> blockers = eligibilityBlockers(conjecture, evaluation);
        if (!blockers.isEmpty()) {
            return report(
                conjecture.conjectureId(),
                EligibilityStatus.NOT_ELIGIBLE,
                ProofStatus.NOT_RUN,
                null,
                "",
                blockers,
                "NOT_EVALUATED");
        }

        List<String> assumptions = conjecture.evidence().stream()
            .flatMap(item -> item.paths().stream())
            .flatMap(path -> path.assumptions().stream())
            .filter(value -> value != null && !value.isBlank())
            .distinct()
            .sorted()
            .toList();
        ProofObligation obligation = new ProofObligation(
            OBLIGATION_SCHEMA,
            conjecture.conjectureId(),
            false,
            conjecture.leftPattern(),
            conjecture.rightPattern(),
            assumptions,
            obligationHash(conjecture.leftPattern(), conjecture.rightPattern(), assumptions));

        boolean equivalent = oracle.areEquivalent(
            obligation.leftExpression(), obligation.rightExpression());
        String backendEvidence = oracle.evidence(
            obligation.leftExpression(), obligation.rightExpression());
        ProofStatus status = classify(equivalent, backendEvidence);
        List<String> resultBlockers = switch (status) {
            case SYMBOLICALLY_VERIFIED -> List.of();
            case REFUTED -> List.of("oracle refuted the conjecture");
            case INCONCLUSIVE -> List.of("oracle produced no conclusive equivalence result");
            case NOT_RUN -> throw new IllegalStateException("eligible proof must run the oracle");
        };
        return report(
            conjecture.conjectureId(),
            EligibilityStatus.ELIGIBLE,
            status,
            obligation,
            backendEvidence,
            resultBlockers,
            "NOT_EVALUATED");
    }

    private ProofReport report(
        String conjectureId,
        EligibilityStatus eligibility,
        ProofStatus status,
        ProofObligation obligation,
        String backendEvidence,
        List<String> blockers,
        String formalProofStatus
    ) {
        List<String> orderedBlockers = blockers.stream().distinct().sorted().toList();
        String evidenceMaterial = REPORT_SCHEMA
            + "\nconjecture=" + conjectureId
            + "\neligibility=" + eligibility
            + "\nproof=" + status
            + "\nbackend=" + backendId
            + "\nobligation=" + (obligation == null ? "" : obligation.obligationHash())
            + "\nevidence=" + (backendEvidence == null ? "" : backendEvidence)
            + "\nformal=" + formalProofStatus
            + "\nblockers=" + String.join("\u0001", orderedBlockers);
        return new ProofReport(
            REPORT_SCHEMA,
            conjectureId,
            eligibility,
            status,
            obligation,
            backendId,
            backendEvidence == null ? "" : backendEvidence,
            formalProofStatus,
            orderedBlockers,
            hash(evidenceMaterial));
    }

    private static List<String> eligibilityBlockers(
        OpenTargetConjecture conjecture,
        EvaluationReport evaluation
    ) {
        LinkedHashSet<String> blockers = new LinkedHashSet<>();
        if (!conjecture.conjectureId().equals(evaluation.conjectureId())) {
            blockers.add("candidate/evaluation provenance mismatch");
        }
        if (evaluation.status() != EvaluationStatus.ACCEPTED_FOR_PROOF) {
            blockers.add("candidate is not accepted for proof");
        }
        if (!evaluation.holdoutsComplete()) {
            blockers.add("holdout evaluation is incomplete");
        }
        if (!evaluation.allHoldoutsPassed()) {
            blockers.add("one or more holdouts failed");
        }
        if (!"NO_COUNTEREXAMPLE_FOUND".equals(evaluation.counterexample().status())) {
            blockers.add("counterexample search did not clear the candidate");
        }
        if (!evaluation.blockers().isEmpty()) {
            blockers.add("candidate evaluation contains blockers");
        }
        if (!"OBSERVED_CONJECTURE".equals(conjecture.candidateStatus())
                || !"EQUIVALENCE_PRESERVING_CONVERGENT_PATHS".equals(
                    conjecture.evidenceStatus())
                || conjecture.supportCount() < 2
                || conjecture.distinctAlphaSupport() < 2) {
            blockers.add("candidate lacks independent open-target convergence evidence");
        }
        return List.copyOf(blockers);
    }

    private static ProofStatus classify(boolean equivalent, String evidence) {
        if (equivalent) {
            return ProofStatus.SYMBOLICALLY_VERIFIED;
        }
        String normalized = evidence == null ? "" : evidence.toLowerCase(Locale.ROOT);
        if (normalized.contains("not equivalent")
                || normalized.contains("counterexample")
                || normalized.contains("refut")) {
            return ProofStatus.REFUTED;
        }
        return ProofStatus.INCONCLUSIVE;
    }

    private static String obligationHash(
        String leftExpression,
        String rightExpression,
        List<String> assumptions
    ) {
        return hash(OBLIGATION_SCHEMA
            + "\ntargetProvided=false"
            + "\nleft=" + leftExpression.trim().replaceAll("\\s+", " ")
            + "\nright=" + rightExpression.trim().replaceAll("\\s+", " ")
            + "\nassumptions=" + String.join("\u0001", assumptions));
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

    public enum EligibilityStatus {
        ELIGIBLE,
        NOT_ELIGIBLE
    }

    public enum ProofStatus {
        SYMBOLICALLY_VERIFIED,
        REFUTED,
        INCONCLUSIVE,
        NOT_RUN
    }

    public record ProofObligation(
        String schema,
        String conjectureId,
        boolean targetProvided,
        String leftExpression,
        String rightExpression,
        List<String> assumptions,
        String obligationHash
    ) {
        public ProofObligation {
            if (!OBLIGATION_SCHEMA.equals(schema)) {
                throw new IllegalArgumentException("unsupported proof-obligation schema");
            }
            if (targetProvided) {
                throw new IllegalArgumentException("open-target proof obligation must not contain a target");
            }
            if (conjectureId == null || conjectureId.isBlank()
                    || leftExpression == null || leftExpression.isBlank()
                    || rightExpression == null || rightExpression.isBlank()) {
                throw new IllegalArgumentException("proof obligation fields must not be blank");
            }
            assumptions = assumptions == null ? List.of() : assumptions.stream()
                .filter(value -> value != null && !value.isBlank())
                .distinct()
                .sorted()
                .toList();
            if (obligationHash == null || !obligationHash.startsWith("sha256:")) {
                throw new IllegalArgumentException("obligationHash must be SHA-256");
            }
        }
    }

    public record ProofReport(
        String schema,
        String conjectureId,
        EligibilityStatus eligibility,
        ProofStatus proofStatus,
        ProofObligation obligation,
        String backendId,
        String backendEvidence,
        String formalProofStatus,
        List<String> blockers,
        String evidenceHash
    ) {
        public ProofReport {
            if (!REPORT_SCHEMA.equals(schema)) {
                throw new IllegalArgumentException("unsupported proof-report schema");
            }
            Objects.requireNonNull(eligibility, "eligibility");
            Objects.requireNonNull(proofStatus, "proofStatus");
            blockers = blockers == null ? List.of() : List.copyOf(blockers);
            backendEvidence = backendEvidence == null ? "" : backendEvidence;
            formalProofStatus = formalProofStatus == null ? "NOT_EVALUATED" : formalProofStatus;
            if (evidenceHash == null || !evidenceHash.startsWith("sha256:")) {
                throw new IllegalArgumentException("evidenceHash must be SHA-256");
            }
        }

        public boolean proofObligationEmitted() {
            return obligation != null;
        }

        public String toCanonicalJson() {
            JsonWriter json = new JsonWriter().beginObject()
                .property("schema", schema)
                .property("conjectureId", conjectureId)
                .property("eligibility", eligibility.name())
                .property("proofStatus", proofStatus.name())
                .property("proofObligationEmitted", proofObligationEmitted())
                .property("backendId", backendId)
                .property("backendEvidence", backendEvidence)
                .property("formalProofStatus", formalProofStatus)
                .stringArray("blockers", blockers)
                .property("evidenceHash", evidenceHash);
            if (obligation == null) {
                json.nullProperty("obligation");
            } else {
                json.object("obligation", object -> object
                    .property("schema", obligation.schema())
                    .property("conjectureId", obligation.conjectureId())
                    .property("targetProvided", obligation.targetProvided())
                    .property("leftExpression", obligation.leftExpression())
                    .property("rightExpression", obligation.rightExpression())
                    .stringArray("assumptions", obligation.assumptions())
                    .property("obligationHash", obligation.obligationHash()));
            }
            return json.endObject().toString();
        }
    }
}
