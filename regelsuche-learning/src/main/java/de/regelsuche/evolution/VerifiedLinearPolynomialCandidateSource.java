package de.regelsuche.evolution;

import de.regelsuche.evolution.ExactLinearPolynomialPlanEvidenceVerifier.VerifiedCandidateEvidence;
import de.regelsuche.json.JsonWriter;
import de.regelsuche.search.program.BudgetedTransformationSource;
import java.util.List;
import java.util.Objects;

/** Exactly one independently replayed rational coefficient solution, available only through explicit program composition. */
public final class VerifiedLinearPolynomialCandidateSource implements BudgetedTransformationSource {
    public static final String SOURCE_ID = "regelsuche.verified-linear-polynomial-candidate-source/v1";
    public static final String REVISION_HASH = SchematicProofPlan.hash(SOURCE_ID + "|" + PROTOCOL_REVISION + "|"
        + ExactLinearPolynomialPlanEvidenceVerifier.REVISION_HASH + "|one-verified-candidate-cumulative-theory-work");
    private final VerifiedCandidateEvidence evidence;
    private final SourceIdentity identity;
    private final String applicationKey;

    public VerifiedLinearPolynomialCandidateSource(VerifiedCandidateEvidence evidence) {
        this.evidence = Objects.requireNonNull(evidence, "evidence");
        identity = new SourceIdentity(SOURCE_ID, REVISION_HASH, evidence.evidenceHash());
        applicationKey = SchematicProofPlan.hash(new JsonWriter().beginObject().property("sourceId", SOURCE_ID)
            .property("revisionHash", REVISION_HASH).property("evidenceHash", evidence.evidenceHash())
            .property("candidateHash", evidence.candidateHash()).endObject().toString());
    }

    @Override public SourceIdentity identity() { return identity; }

    @Override public Result transform(String expression, long availableMathematicalWorkUnits) {
        if (expression == null || expression.isBlank() || expression.length() > 16_384
                || expression.chars().anyMatch(Character::isISOControl) || availableMathematicalWorkUnits < 0) {
            throw new IllegalArgumentException("bounded expression and nonnegative mathematical budget are required");
        }
        String normalized = expression.trim().replaceAll("\\s+", " ");
        if (!evidence.sourceExpression().equals(normalized)) {
            return Result.noMatch(identity, normalized, availableMathematicalWorkUnits, 1, "SOURCE_MISMATCH");
        }
        long required = evidence.mathematicalWorkUnits();
        if (availableMathematicalWorkUnits < required) {
            return Result.budgetInconclusive(identity, normalized, availableMathematicalWorkUnits, required, 2,
                "INSUFFICIENT_MATHEMATICAL_WORK_AUTHORITY");
        }
        var transition = ExactTheoryTransition.create(evidence.sourceExpression(), evidence.transformedExpression(),
            ExactLinearPolynomialPlanEvidenceVerifier.THEORY_STEP_ID, identity.authorityHash(), List.of(), required, applicationKey);
        return Result.candidates(identity, normalized, availableMathematicalWorkUnits, List.of(transition), 3,
            "VERIFIED_EXACT_THEORY_CANDIDATE");
    }
}
