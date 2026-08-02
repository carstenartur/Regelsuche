package de.regelsuche.evolution;

import de.regelsuche.evolution.EvolutionRewriteProgramHeldOutCommitment.Split;
import java.util.Objects;

/**
 * Builds the public TRAIN/VALIDATION/FINAL TEST partition for the flagship
 * rewrite-program study from the open TRAIN corpus and two private reveal
 * bundles that have already passed trusted local validation.
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

        EvolutionRewriteProgramTrainSuite train =
            FlagshipRewriteProgramTrainCorpus.create();
        String corpusHash = EvolutionGenome.hash(
            "regelsuche.flagship-rewrite-program-corpus/v1"
                + "\ntrainSuiteHash=" + train.contentHash()
                + "\nvalidationRevealHash=" + validation.contentHash()
                + "\nfinalTestRevealHash=" + finalTest.contentHash());
        EvolutionSplitManifest manifest = EvolutionSplitManifest.create(
            STUDY_ID,
            corpusHash,
            EvolutionGenome.hash(FEATURE_SCHEMA_ID),
            EvolutionRewriteProgramTrainCaseReferences.create(train),
            validation.splitReferences(),
            finalTest.splitReferences());

        validation.commitment().requireMatches(manifest);
        finalTest.commitment().requireMatches(manifest);
        return manifest;
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
