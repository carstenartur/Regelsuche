package de.regelsuche.evolution;

import de.regelsuche.evolution.EvolutionRewriteProgramHeldOutCommitment.Split;
import de.regelsuche.evolution.EvolutionRewriteProgramHeldOutRevealBundle.DifficultyTier;
import de.regelsuche.evolution.EvolutionRewriteProgramHeldOutRevealBundle.ExpectedTerminalClass;
import de.regelsuche.evolution.EvolutionRewriteProgramHeldOutRevealBundle.RevealCase;
import java.util.List;
import java.util.Objects;

/**
 * Derives the canonical split-manifest identities for a frozen TRAIN suite.
 *
 * <p>The identity algorithm is intentionally reused from the held-out reveal
 * contract so TRAIN, VALIDATION and FINAL TEST are compared with exactly the
 * same normalization, exact-signature and alpha-signature semantics. The
 * synthetic bundle created here is never persisted and does not make TRAIN
 * material held out.</p>
 */
public final class EvolutionRewriteProgramTrainCaseReferences {
    private static final String DERIVATION_STUDY_ID =
        "train_case_reference_derivation";

    private EvolutionRewriteProgramTrainCaseReferences() {
    }

    public static List<EvolutionSplitManifest.CaseReference> create(
        EvolutionRewriteProgramTrainSuite suite
    ) {
        Objects.requireNonNull(suite, "suite");
        List<RevealCase> identityCases = suite.cases().stream()
            .map(item -> RevealCase.create(
                item.caseId(),
                item.familyId(),
                item.inputExpression(),
                item.targetExpression(),
                item.assumptions(),
                DifficultyTier.STANDARD,
                ExpectedTerminalClass.CONFIRMED))
            .toList();
        return EvolutionRewriteProgramHeldOutRevealBundle.create(
            DERIVATION_STUDY_ID,
            Split.VALIDATION,
            identityCases).splitReferences();
    }
}
