package de.regelsuche.evolution;

import de.regelsuche.evolution.EvolutionRewriteProgramHeldOutCommitment.Split;
import java.util.Objects;

/**
 * Builds the public TRAIN/VALIDATION/FINAL TEST partition for the flagship
 * rewrite-program study from the open TRAIN corpus and either two validated
 * private reveals or their commitment-only public derivatives.
 */
public final class FlagshipRewriteProgramSplitManifest {
    public static final String STUDY_ID = "flagship_rewrite_program_v1";
    public static final String FEATURE_SCHEMA_ID =
        "regelsuche.flagship-rewrite-program-features/v1";

    private FlagshipRewriteProgramSplitManifest() {
    }

    public static EvolutionSplitManifest create(
        EvolutionRewriteProgramHeldOutRevealBundle validation,
        EvolutionRewriteProgramHeldOutRevealBundle finalTest
    ) {
        requireBundle(validation, Split.VALIDATION, "validation");
        requireBundle(finalTest, Split.FINAL_TEST, "finalTest");
        return create(
            validation.commitment(),
            EvolutionRewriteProgramHeldOutSplitReferences.create(validation),
            finalTest.commitment(),
            EvolutionRewriteProgramHeldOutSplitReferences.create(finalTest));
    }

    /**
     * Reconstructs the exact public split surface without reopening either
     * private reveal bundle. The commitment root and hash-only references must
     * agree before a manifest can be created.
     */
    public static EvolutionSplitManifest create(
        EvolutionRewriteProgramHeldOutCommitment validationCommitment,
        EvolutionRewriteProgramHeldOutSplitReferences validationReferences,
        EvolutionRewriteProgramHeldOutCommitment finalTestCommitment,
        EvolutionRewriteProgramHeldOutSplitReferences finalTestReferences
    ) {
        requirePublicArtifacts(
            validationCommitment,
            validationReferences,
            Split.VALIDATION,
            "validation");
        requirePublicArtifacts(
            finalTestCommitment,
            finalTestReferences,
            Split.FINAL_TEST,
            "finalTest");

        EvolutionRewriteProgramTrainSuite train =
            FlagshipRewriteProgramTrainCorpus.create();
        String corpusHash = EvolutionGenome.hash(
            "regelsuche.flagship-rewrite-program-corpus/v1"
                + "\ntrainSuiteHash=" + train.contentHash()
                + "\nvalidationRevealHash="
                + validationReferences.revealBundleHash()
                + "\nfinalTestRevealHash="
                + finalTestReferences.revealBundleHash());
        EvolutionSplitManifest manifest = EvolutionSplitManifest.create(
            STUDY_ID,
            corpusHash,
            EvolutionGenome.hash(FEATURE_SCHEMA_ID),
            EvolutionRewriteProgramTrainCaseReferences.create(train),
            validationReferences.cases(),
            finalTestReferences.cases());

        validationCommitment.requireMatches(manifest);
        validationReferences.requireMatches(manifest);
        finalTestCommitment.requireMatches(manifest);
        finalTestReferences.requireMatches(manifest);
        return manifest;
    }

    private static void requirePublicArtifacts(
        EvolutionRewriteProgramHeldOutCommitment commitment,
        EvolutionRewriteProgramHeldOutSplitReferences references,
        Split expectedSplit,
        String name
    ) {
        Objects.requireNonNull(commitment, name + "Commitment");
        Objects.requireNonNull(references, name + "References");
        if (!STUDY_ID.equals(commitment.studyId())
                || !STUDY_ID.equals(references.studyId())) {
            throw new IllegalArgumentException(
                name + " public artifacts belong to another study");
        }
        if (commitment.split() != expectedSplit
                || references.split() != expectedSplit) {
            throw new IllegalArgumentException(
                name + " public artifacts are bound to the wrong split");
        }
        if (!commitment.sealedRevealHash().equals(
                references.revealBundleHash())) {
            throw new IllegalArgumentException(
                name
                    + " commitment and split references bind different reveals");
        }
    }

    private static void requireBundle(
        EvolutionRewriteProgramHeldOutRevealBundle bundle,
        Split expectedSplit,
        String name
    ) {
        Objects.requireNonNull(bundle, name);
        if (!STUDY_ID.equals(bundle.studyId())) {
            throw new IllegalArgumentException(
                name + " reveal bundle belongs to another study");
        }
        if (bundle.split() != expectedSplit) {
            throw new IllegalArgumentException(
                name + " reveal bundle is bound to the wrong split");
        }
    }
}
