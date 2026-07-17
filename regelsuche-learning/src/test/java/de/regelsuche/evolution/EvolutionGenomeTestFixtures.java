package de.regelsuche.evolution;

import de.regelsuche.evolution.EvolutionGenome.EvidenceObligation;
import de.regelsuche.evolution.EvolutionGenome.FeatureWeight;
import de.regelsuche.evolution.EvolutionGenome.FitnessSignal;
import de.regelsuche.evolution.EvolutionGenome.GuardPolicy;
import de.regelsuche.evolution.EvolutionGenome.Objective;
import de.regelsuche.evolution.EvolutionGenome.ResourceBudget;
import de.regelsuche.evolution.EvolutionGenome.RewriteGene;
import de.regelsuche.evolution.EvolutionGenome.SourceSplit;
import de.regelsuche.evolution.EvolutionGenome.TrainingScope;
import de.regelsuche.transform.RewriteKind;
import java.util.List;

final class EvolutionGenomeTestFixtures {
    private EvolutionGenomeTestFixtures() {
    }

    static EvolutionGenome genome(RewriteGene... genes) {
        return EvolutionGenome.create(
            Objective.OPEN_TARGET_OPERATOR,
            scope('a'),
            List.of(genes),
            List.of(
                new FeatureWeight(FitnessSignal.STRUCTURAL_NOVELTY, 500),
                new FeatureWeight(FitnessSignal.COUNTEREXAMPLE_RISK, -500)),
            GuardPolicy.strictDefault(),
            new ResourceBudget(16, 128, 12, 32, 80),
            List.of("core.ast-rewrite"),
            List.of());
    }

    static RewriteGene addZero(String id, String placeholder) {
        return gene(id, "?" + placeholder + "+0", "?" + placeholder);
    }

    static RewriteGene gene(String id, String source, String target) {
        return new RewriteGene(
            id,
            source,
            target,
            RewriteKind.SIMPLIFY,
            true,
            -2,
            4,
            4,
            List.of(),
            obligations());
    }

    static List<EvidenceObligation> obligations() {
        return List.of(
            EvidenceObligation.SEMANTIC_VALIDATION,
            EvidenceObligation.COUNTEREXAMPLE_SEARCH,
            EvidenceObligation.PROOF_OR_CERTIFICATE,
            EvidenceObligation.NOVELTY_REVIEW,
            EvidenceObligation.HOLDOUT_EVALUATION);
    }

    static TrainingScope scope(char marker) {
        return new TrainingScope(
            SourceSplit.TRAIN,
            hash(marker),
            hash(next(marker, 1)),
            hash(next(marker, 2)),
            hash(next(marker, 3)));
    }

    static String hash(char marker) {
        char normalized = marker;
        if (normalized < 'a' || normalized > 'f') {
            normalized = 'a';
        }
        return "sha256:" + String.valueOf(normalized).repeat(64);
    }

    private static char next(char marker, int offset) {
        int value = Math.floorMod(marker - 'a' + offset, 6);
        return (char) ('a' + value);
    }
}
