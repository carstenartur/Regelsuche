package de.regelsuche.evolution;

import de.regelsuche.evolution.EvolutionRewriteProgramHeldOutRevealBundle.DifficultyTier;
import de.regelsuche.evolution.EvolutionRewriteProgramHeldOutRevealBundle.ExpectedTerminalClass;
import de.regelsuche.evolution.EvolutionRewriteProgramHeldOutRevealBundle.RevealCase;
import java.util.List;
import java.util.Objects;

/**
 * Derives the canonical split-manifest identities for a frozen TRAIN suite.
 *
 * <p>Each case is normalized through the same {@link RevealCase} identity
 * implementation used by held-out material. References are then constructed
 * per case, without applying held-out-bundle uniqueness rules inside TRAIN.
 * Duplicate targets or signatures may be legitimate within one TRAIN split;
 * {@link EvolutionSplitManifest} remains responsible for rejecting collisions
 * between TRAIN, VALIDATION and FINAL TEST.</p>
 */
public final class EvolutionRewriteProgramTrainCaseReferences {
    private EvolutionRewriteProgramTrainCaseReferences() {
    }

    public static List<EvolutionSplitManifest.CaseReference> create(
        EvolutionRewriteProgramTrainSuite suite
    ) {
        Objects.requireNonNull(suite, "suite");
        return suite.cases().stream()
            .map(item -> RevealCase.create(
                item.caseId(),
                item.familyId(),
                item.inputExpression(),
                item.targetExpression(),
                item.assumptions(),
                DifficultyTier.STANDARD,
                ExpectedTerminalClass.CONFIRMED))
            .map(identity -> new EvolutionSplitManifest.CaseReference(
                identity.caseId(),
                identity.familyId(),
                identity.exactSignatureHash(),
                identity.alphaSignatureHash(),
                identity.inputHash(),
                identity.targetHash()))
            .toList();
    }
}
