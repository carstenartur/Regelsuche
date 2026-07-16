package de.regelsuche.mining;

import de.regelsuche.json.JsonWriter;
import de.regelsuche.mining.OpenTargetConjectureEvaluator.EvaluationReport;
import de.regelsuche.mining.OpenTargetConjectureEvaluator.EvaluationStatus;
import de.regelsuche.mining.OpenTargetConjectureMiner.OpenTargetConjecture;
import de.regelsuche.solver.ir.SolverBackend;
import de.regelsuche.solver.ir.SolverIr;
import de.regelsuche.solver.ir.SolverIr.Obligation;
import de.regelsuche.solver.ir.SolverIr.ResultStatus;
import de.regelsuche.solver.ir.SolverIr.SolverResult;
import de.regelsuche.solver.ir.SolverIr.TranslationStatus;
import de.regelsuche.solver.ir.SolverObligationFactory;
import de.regelsuche.solver.ir.SymPySolverBackend;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

/**
 * Emits and evaluates one solver-neutral proof obligation only after an
 * open-target candidate passed validation and counterexample search.
 */
public final class OpenTargetConjectureProofGate {
    public static final String REPORT_SCHEMA =
        "regelsuche.open-target-conjecture-proof/v2";

    private final SolverBackend backend;
    private final SolverObligationFactory obligations =
        new SolverObligationFactory();

    public OpenTargetConjectureProofGate() {
        this(new SymPySolverBackend());
    }

    /** Creates a gate for one explicit backend; used by solver portfolios. */
    public OpenTargetConjectureProofGate(SolverBackend backend) {
        this.backend = Objects.requireNonNull(backend, "backend");
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
                null,
                blockers,
                "NOT_EVALUATED");
        }

        List<String> assumptions = assumptions(conjecture);
        Obligation obligation = obligations.equality(
            conjecture.conjectureId() + "-proof",
            conjecture.leftPattern(),
            conjecture.rightPattern(),
            assumptions,
            SolverIr.RequestedEvidence.SYMBOLIC_CERTIFICATE,
            new SolverIr.SourceProvenance(
                "open-target-conjecture",
                conjecture.conjectureId(),
                conjectureRevisionHash(conjecture, assumptions)));
        SolverResult result = backend.solve(obligation);
        ProofStatus status = proofStatus(result);
        return report(
            conjecture.conjectureId(),
            EligibilityStatus.ELIGIBLE,
            status,
            obligation,
            result,
            resultBlockers(result),
            "NOT_EVALUATED");
    }

    private static ProofReport report(
        String conjectureId,
        EligibilityStatus eligibility,
        ProofStatus status,
        Obligation obligation,
        SolverResult result,
        List<String> blockers,
        String formalProofStatus
    ) {
        List<String> orderedBlockers = blockers.stream()
            .filter(value -> value != null && !value.isBlank())
            .distinct()
            .sorted()
            .toList();
        String evidenceHash = hash(
            REPORT_SCHEMA
                + "\nconjecture=" + conjectureId
                + "\neligibility=" + eligibility.name()
                + "\nproof=" + status.name()
                + "\nobligation="
                    + (obligation == null ? "" : obligation.contentHash())
                + "\nresult=" + (result == null ? "" : result.contentHash())
                + "\nformal=" + formalProofStatus
                + "\nblockers=" + orderedBlockers);
        return new ProofReport(
            REPORT_SCHEMA,
            conjectureId,
            eligibility,
            status,
            obligation,
            result,
            formalProofStatus,
            orderedBlockers,
            evidenceHash);
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

    private static List<String> assumptions(OpenTargetConjecture conjecture) {
        return conjecture.evidence().stream()
            .flatMap(item -> item.paths().stream())
            .flatMap(path -> path.assumptions().stream())
            .filter(value -> value != null && !value.isBlank())
            .map(value -> value.trim().replaceAll("\\s+", " "))
            .distinct()
            .sorted()
            .toList();
    }

    private static String conjectureRevisionHash(
        OpenTargetConjecture conjecture,
        List<String> assumptions
    ) {
        return hash(
            "conjecture=" + conjecture.conjectureId()
                + "\nleft=" + conjecture.leftPattern()
                + "\nright=" + conjecture.rightPattern()
                + "\nparameters=" + conjecture.parameterRelations()
                + "\nassumptions=" + assumptions
                + "\nsupport=" + conjecture.supportingObservationIds());
    }

    private static ProofStatus proofStatus(SolverResult result) {
        if (result.translationStatus() != TranslationStatus.LOSSLESS) {
            return ProofStatus.INCONCLUSIVE;
        }
        return switch (result.status()) {
            case CONFIRMED -> ProofStatus.SYMBOLICALLY_VERIFIED;
            case REFUTED -> ProofStatus.REFUTED;
            case UNKNOWN, TIMEOUT, UNSUPPORTED, ERROR -> ProofStatus.INCONCLUSIVE;
        };
    }

    private static List<String> resultBlockers(SolverResult result) {
        if (result.translationStatus() != TranslationStatus.LOSSLESS) {
            return List.of(
                "solver translation is not lossless: "
                    + result.translationStatus().name()
                    + (result.translationIssues().isEmpty()
                        ? ""
                        : " (" + String.join(",", result.translationIssues()) + ')'));
        }
        return switch (result.status()) {
            case CONFIRMED -> List.of();
            case REFUTED -> List.of("solver backend refuted the conjecture");
            case UNKNOWN -> List.of("solver backend produced no conclusive result");
            case TIMEOUT -> List.of("solver backend timed out");
            case UNSUPPORTED -> List.of(
                "solver backend does not support obligation: "
                    + String.join(",", result.translationIssues()));
            case ERROR -> List.of("solver backend failed: " + result.message());
        };
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

    public record ProofReport(
        String schema,
        String conjectureId,
        EligibilityStatus eligibility,
        ProofStatus proofStatus,
        Obligation obligation,
        SolverResult result,
        String formalProofStatus,
        List<String> blockers,
        String evidenceHash
    ) {
        public ProofReport {
            if (!REPORT_SCHEMA.equals(schema)) {
                throw new IllegalArgumentException("unsupported proof-report schema");
            }
            if (conjectureId == null || conjectureId.isBlank()) {
                throw new IllegalArgumentException("conjectureId must not be blank");
            }
            Objects.requireNonNull(eligibility, "eligibility");
            Objects.requireNonNull(proofStatus, "proofStatus");
            blockers = blockers == null ? List.of() : blockers.stream()
                .filter(value -> value != null && !value.isBlank())
                .distinct().sorted().toList();
            formalProofStatus = formalProofStatus == null
                ? "NOT_EVALUATED" : formalProofStatus;
            boolean emitted = obligation != null && result != null;
            if ((obligation == null) != (result == null)) {
                throw new IllegalArgumentException(
                    "proof obligation and solver result must be present together");
            }
            if (result != null
                    && !result.obligationHash().equals(obligation.contentHash())) {
                throw new IllegalArgumentException(
                    "solver result belongs to another obligation");
            }
            if (eligibility == EligibilityStatus.NOT_ELIGIBLE
                    && (emitted || proofStatus != ProofStatus.NOT_RUN)) {
                throw new IllegalArgumentException(
                    "ineligible proof cannot emit or execute an obligation");
            }
            if (eligibility == EligibilityStatus.ELIGIBLE && !emitted) {
                throw new IllegalArgumentException(
                    "eligible proof must emit and execute one obligation");
            }
            if (proofStatus == ProofStatus.SYMBOLICALLY_VERIFIED
                    && (result.status() != ResultStatus.CONFIRMED
                        || result.translationStatus() != TranslationStatus.LOSSLESS)) {
                throw new IllegalArgumentException(
                    "symbolic verification requires a lossless confirmed result");
            }
            if (proofStatus == ProofStatus.REFUTED
                    && (result.status() != ResultStatus.REFUTED
                        || result.translationStatus() != TranslationStatus.LOSSLESS)) {
                throw new IllegalArgumentException(
                    "refutation requires a lossless refuted result");
            }
            if (evidenceHash == null
                    || !evidenceHash.matches("sha256:[0-9a-f]{64}")) {
                throw new IllegalArgumentException("evidenceHash must be SHA-256");
            }
        }

        public boolean proofObligationEmitted() {
            return obligation != null;
        }

        public String backendId() {
            return result == null ? "" : result.backendId();
        }

        public String backendEvidence() {
            return result == null ? "" : result.message();
        }

        public String toCanonicalJson() {
            return new JsonWriter().beginObject()
                .property("schema", schema)
                .property("conjectureId", conjectureId)
                .property("eligibility", eligibility.name())
                .property("proofStatus", proofStatus.name())
                .property("proofObligationEmitted", proofObligationEmitted())
                .property("solverObligationHash",
                    obligation == null ? "" : obligation.contentHash())
                .property("solverResultHash",
                    result == null ? "" : result.contentHash())
                .property("backendId", backendId())
                .property("backendVersion",
                    result == null ? "" : result.backendVersion())
                .property("backendStatus",
                    result == null ? "NOT_RUN" : result.status().name())
                .property("translationStatus",
                    result == null ? "NOT_EMITTED"
                        : result.translationStatus().name())
                .property("backendEvidence", backendEvidence())
                .property("formalProofStatus", formalProofStatus)
                .stringArray("blockers", blockers)
                .property("evidenceHash", evidenceHash)
                .endObject()
                .toString();
        }
    }
}
