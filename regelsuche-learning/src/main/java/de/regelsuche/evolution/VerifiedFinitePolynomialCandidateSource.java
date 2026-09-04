package de.regelsuche.evolution;

import de.regelsuche.evolution.ExactFinitePolynomialPlanCandidateEvidenceVerifier.EvidenceData;
import de.regelsuche.evolution.ExactFinitePolynomialPlanCandidateEvidenceVerifier.VerifiedCandidateEvidence;
import de.regelsuche.search.program.BudgetedTransformationSource;
import de.regelsuche.search.program.BudgetedTransformationSource.ExactTheoryTransition;
import de.regelsuche.search.program.BudgetedTransformationSource.Result;
import de.regelsuche.search.program.BudgetedTransformationSource.SourceIdentity;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;

/**
 * Exposes exactly one verifier-issued finite-polynomial candidate through the
 * explicit budgeted exact-theory source protocol.
 *
 * <p>No target, candidate ordering or hidden best-of choice is consulted. The
 * adapter is bound to the candidate already selected by its evidence.</p>
 */
public final class VerifiedFinitePolynomialCandidateSource
        implements BudgetedTransformationSource {
    public static final String SOURCE_ID =
        "regelsuche.verified-finite-polynomial-candidate-source/v1";
    public static final String REVISION_HASH = SchematicProofPlan.hash(
        lengthPrefixed(
            SOURCE_ID,
            BudgetedTransformationSource.PROTOCOL_REVISION,
            ExactFinitePolynomialPlanCandidateEvidenceVerifier.VERIFIER_ID,
            ExactFinitePolynomialPlanCandidateEvidenceVerifier.REVISION_HASH,
            "one-evidence-one-transition",
            "exact-source-match-before-budget-admission",
            "no-primitive-rewrite-claim"));

    private static final long SOURCE_MATCH_WORK = 1;
    private static final long BUDGET_CHECK_WORK = 1;
    private static final long CANDIDATE_MATERIALIZATION_WORK = 1;

    private final EvidenceData evidence;
    private final String evidenceHash;
    private final SourceIdentity identity;
    private final String applicationKey;

    public VerifiedFinitePolynomialCandidateSource(
        VerifiedCandidateEvidence verifiedEvidence
    ) {
        VerifiedCandidateEvidence checked = Objects.requireNonNull(
            verifiedEvidence,
            "verifiedEvidence");
        evidence = Objects.requireNonNull(checked.data(), "evidence data");
        evidenceHash = SchematicProofPlan.requireSha256(
            checked.evidenceHash(),
            "evidenceHash");
        if (!ExactFinitePolynomialPlanCandidateEvidenceVerifier.THEORY_STEP_ID
                .equals(evidence.theoryStepId())
                || !evidence.assumptions().isEmpty()
                || evidence.canonicalWork().totalWorkUnits() < 1) {
            throw new IllegalArgumentException(
                "candidate evidence is outside the v1 exact-theory source contract");
        }
        identity = new SourceIdentity(SOURCE_ID, REVISION_HASH, evidenceHash);
        applicationKey = SchematicProofPlan.hash(lengthPrefixed(
            SOURCE_ID,
            REVISION_HASH,
            evidenceHash,
            evidence.candidateHash(),
            evidence.sourceExpression(),
            evidence.transformedExpression()));
    }

    @Override
    public SourceIdentity identity() {
        return identity;
    }

    @Override
    public Result transform(
        String expression,
        long availableMathematicalWorkUnits
    ) {
        String normalized = normalizeExpression(expression);
        if (availableMathematicalWorkUnits < 0) {
            throw new IllegalArgumentException(
                "availableMathematicalWorkUnits must not be negative");
        }
        if (!evidence.sourceExpression().equals(normalized)) {
            return Result.noMatch(
                identity,
                normalized,
                availableMathematicalWorkUnits,
                SOURCE_MATCH_WORK,
                "SOURCE_MISMATCH");
        }

        long required = evidence.canonicalWork().totalWorkUnits();
        long checkedWork = Math.addExact(
            SOURCE_MATCH_WORK,
            BUDGET_CHECK_WORK);
        if (availableMathematicalWorkUnits < required) {
            return Result.budgetInconclusive(
                identity,
                normalized,
                availableMathematicalWorkUnits,
                required,
                checkedWork,
                "INSUFFICIENT_MATHEMATICAL_WORK_AUTHORITY");
        }

        ExactTheoryTransition candidate = ExactTheoryTransition.create(
            evidence.sourceExpression(),
            evidence.transformedExpression(),
            evidence.theoryStepId(),
            evidenceHash,
            evidence.assumptions(),
            required,
            applicationKey);
        return Result.candidates(
            identity,
            normalized,
            availableMathematicalWorkUnits,
            List.of(candidate),
            Math.addExact(checkedWork, CANDIDATE_MATERIALIZATION_WORK),
            "VERIFIED_EXACT_THEORY_CANDIDATE");
    }

    public String evidenceHash() {
        return evidenceHash;
    }

    public String candidateHash() {
        return evidence.candidateHash();
    }

    private static String normalizeExpression(String expression) {
        if (expression == null || expression.isBlank()) {
            throw new IllegalArgumentException("expression must not be blank");
        }
        String normalized = expression.trim().replaceAll("\\s+", " ");
        if (normalized.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(
                "expression contains a control character");
        }
        return normalized;
    }

    private static String lengthPrefixed(String... values) {
        StringBuilder result = new StringBuilder();
        for (String value : values) {
            result.append(value.getBytes(StandardCharsets.UTF_8).length)
                .append(':')
                .append(value);
        }
        return result.toString();
    }
}
